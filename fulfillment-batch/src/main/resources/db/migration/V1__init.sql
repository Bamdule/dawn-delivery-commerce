-- 마감 회차마다 한 행. 스텝이 끝날 때마다 상태를 올린다 (docs/design/05)
CREATE TABLE cutoff_run (
    cutoff_at    DATETIME(3) NOT NULL,
    status       VARCHAR(30) NOT NULL,   -- STARTED / TARGET_FIXED / ALLOCATED / UNFILLABLE_PUBLISHED / SHIPMENT_CREATED / VERIFIED / VERIFY_FAILED
    target_count INT         NULL,
    updated_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (cutoff_at)
);

-- PAID 이벤트로 미리 쌓고 마감에 주문 서비스 목록과 대조한다
-- 모르는 주문의 CANCELLED 가 먼저 오면 취소 표시만 남기고, 뒤늦은 PAID 가 되살리지 않는다
CREATE TABLE shipment_target (
    order_no    VARCHAR(30) NOT NULL,
    status             VARCHAR(20) NOT NULL,   -- WAITING / CANCELLED
    region_code        VARCHAR(20) NULL,
    paid_at            DATETIME(3) NULL,
    assigned_cutoff_at DATETIME(3) NULL,       -- 대상 확정이 기록하는 회차. 이월하면 다음 회차로 바꾼다
    deferred           BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (order_no),
    KEY idx_target_status_paid (status, paid_at),
    KEY idx_target_assigned (assigned_cutoff_at)
);

CREATE TABLE shipment_target_line (
    order_no   VARCHAR(30) NOT NULL,
    product_id BIGINT      NOT NULL,
    quantity   INT         NOT NULL,
    PRIMARY KEY (order_no, product_id)
);

-- 센터별 실물 재고는 풀필먼트가 가진다. 출고 지시와 같은 트랜잭션에서 차감한다
CREATE TABLE center_stock (
    center_id  VARCHAR(20) NOT NULL,
    product_id BIGINT      NOT NULL,
    quantity   INT         NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (center_id, product_id),
    CONSTRAINT ck_center_stock_quantity CHECK (quantity >= 0)
);

CREATE TABLE stock_adjustment (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    center_id   VARCHAR(20) NOT NULL,
    product_id  BIGINT      NOT NULL,
    before_qty  INT         NOT NULL,
    after_qty   INT         NOT NULL,
    reason      VARCHAR(30) NOT NULL,   -- INBOUND / STOCKTAKE
    created_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (id)
);

-- 할당을 시작할 때 고정한 센터 재고. 다시 계산해도 같은 입력을 쓴다
CREATE TABLE allocation_stock_snapshot (
    cutoff_at  DATETIME(3) NOT NULL,
    center_id  VARCHAR(20) NOT NULL,
    product_id BIGINT      NOT NULL,
    quantity   INT         NOT NULL,
    PRIMARY KEY (cutoff_at, center_id, product_id)
);

-- 할당 결과. 회차가 ALLOCATED 가 아니면 지우고 같은 스냅샷으로 다시 계산한다
CREATE TABLE allocation_result (
    cutoff_at  DATETIME(3) NOT NULL,
    order_no   VARCHAR(30) NOT NULL,
    product_id BIGINT      NOT NULL,
    center_id  VARCHAR(20) NOT NULL,
    quantity   INT         NOT NULL,
    PRIMARY KEY (cutoff_at, order_no, product_id, center_id),
    KEY idx_allocation_center_order (cutoff_at, center_id, order_no)
);

CREATE TABLE unfillable_decision (
    cutoff_at  DATETIME(3) NOT NULL,
    order_no   VARCHAR(30) NOT NULL,
    decision   VARCHAR(20) NOT NULL,   -- PARTIAL / CANCEL
    reason     VARCHAR(30) NOT NULL,   -- STOCKTAKE_GAP / INBOUND_DELAY / ALLOCATION_FAILED
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (cutoff_at, order_no)
);

-- (마감 시각, 주문, 센터)마다 하나. 이미 있으면 삽입도 재고 차감도 하지 않는다
CREATE TABLE shipment_order (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    cutoff_at  DATETIME(3) NOT NULL,
    order_no   VARCHAR(30) NOT NULL,
    center_id  VARCHAR(20) NOT NULL,
    status     VARCHAR(20) NOT NULL,   -- CREATED / SHIPPED
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipment_order (cutoff_at, order_no, center_id)
);

CREATE TABLE shipment_order_line (
    shipment_order_id BIGINT NOT NULL,
    product_id        BIGINT NOT NULL,
    quantity          INT    NOT NULL,
    PRIMARY KEY (shipment_order_id, product_id)
);

-- 주소 오류처럼 데이터가 잘못된 주문. 사람이 고쳐 다음 회차로 넘기거나 취소한다
CREATE TABLE shipment_exception (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    cutoff_at  DATETIME(3)  NOT NULL,
    order_no   VARCHAR(30)  NOT NULL,
    center_id  VARCHAR(20)  NOT NULL,
    reason     VARCHAR(200) NOT NULL,
    status     VARCHAR(20)  NOT NULL,   -- OPEN / RESOLVED
    created_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipment_exception (cutoff_at, order_no, center_id)
);

-- 상태 변경과 같은 트랜잭션에 기록하고 릴레이가 선점, 전송, 기록 세 단계로 발행한다 (docs/design/03)
CREATE TABLE outbox_event (
    seq             BIGINT       NOT NULL AUTO_INCREMENT,
    event_id        CHAR(36)     NOT NULL,
    topic           VARCHAR(100) NOT NULL,
    partition_key   VARCHAR(100) NOT NULL,
    payload         JSON         NOT NULL,
    status          VARCHAR(20)  NOT NULL,   -- PENDING / SENT. 버리는 상태는 두지 않는다
    attempt_count   INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3)  NOT NULL,
    created_at      DATETIME(3)  NOT NULL,
    sent_at         DATETIME(3)  NULL,
    PRIMARY KEY (seq),
    UNIQUE KEY uk_outbox_event_id (event_id),
    KEY idx_outbox_status_next (status, next_attempt_at),
    KEY idx_outbox_key_status (partition_key, status, seq)
);
