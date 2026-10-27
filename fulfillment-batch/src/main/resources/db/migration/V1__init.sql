-- 마감 회차마다 한 행. 스텝이 끝날 때마다 상태를 올린다 (docs/design/05)
CREATE TABLE cutoff_run (
    cutoff_at    DATETIME(3) NOT NULL,
    status       VARCHAR(30) NOT NULL,   -- STARTED / TARGET_FIXED / ALLOCATED / UNFILLABLE_PUBLISHED / SHIPMENT_CREATED / VERIFIED / VERIFY_FAILED
    target_count INT         NULL,
    input_quantity INT       NULL,   -- 할당 입력 표(allocation_input)의 수량 합. 결과 검증이 쓴다
    updated_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (cutoff_at)
);

-- PAID 이벤트로 미리 쌓고 마감에 주문 서비스 목록과 대조한다
-- 모르는 주문의 CANCELLED 가 먼저 오면 취소 표시만 남기고, 뒤늦은 PAID 가 되살리지 않는다
CREATE TABLE shipment_target (
    order_no    VARCHAR(30) NOT NULL,
    status             VARCHAR(20) NOT NULL,   -- WAITING / CANCELLED
    region_code        VARCHAR(20) NULL,
    recipient_name     VARCHAR(50)  NULL,
    address            VARCHAR(200) NULL,       -- PAID 이벤트에서 복사한 배송지. 주소 오류는 관리 API 로 여기서 고친다
    paid_at            DATETIME(3) NULL,
    updated_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (order_no),
    KEY idx_target_status_paid (status, paid_at)
);

-- 품목의 상태는 수량으로 센다. 다섯 수량의 합은 늘 quantity 다. 할당의 입력은 대기 수량뿐이다
CREATE TABLE shipment_target_line (
    order_no           VARCHAR(30) NOT NULL,
    product_id         BIGINT      NOT NULL,
    quantity           INT         NOT NULL,
    waiting_qty        INT         NOT NULL,
    shipped_qty        INT         NOT NULL DEFAULT 0,
    unfilled_qty       INT         NOT NULL DEFAULT 0,
    exception_qty      INT         NOT NULL DEFAULT 0,
    cancelled_qty      INT         NOT NULL DEFAULT 0,
    assigned_cutoff_at DATETIME(3) NULL,       -- 대기 수량이 배정된 회차. 비었으면 목록에 오른 주문만 가져가고, 이월은 값을 그대로 둬 다음 대상 확정이 지난 회차로 가져간다
    PRIMARY KEY (order_no, product_id),
    KEY idx_target_line_cutoff (assigned_cutoff_at),
    CONSTRAINT ck_target_line_sum CHECK (waiting_qty + shipped_qty + unfilled_qty + exception_qty + cancelled_qty = quantity),
    CONSTRAINT ck_target_line_nonneg CHECK (waiting_qty >= 0 AND shipped_qty >= 0 AND unfilled_qty >= 0 AND exception_qty >= 0 AND cancelled_qty >= 0)
);

-- 물류센터와 그 센터가 있는 권역. 할당 1차와 2차는 주문 권역 안의 센터만 쓴다
CREATE TABLE center (
    center_id   VARCHAR(20) NOT NULL,
    region_code VARCHAR(20) NOT NULL,
    PRIMARY KEY (center_id)
);

-- 권역 밖이지만 새벽 도착이 가능한 센터. 할당 3차가 쓴다
CREATE TABLE center_coverage (
    center_id   VARCHAR(20) NOT NULL,
    region_code VARCHAR(20) NOT NULL,
    PRIMARY KEY (center_id, region_code)
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

-- 대상 확정이 세 번 어긋났을 때 남기는 차이 목록. 사람이 보고 출고 대상을 바로잡는다
CREATE TABLE target_fix_diff (
    cutoff_at  DATETIME(3) NOT NULL,
    order_no   VARCHAR(30) NOT NULL,
    kind       VARCHAR(30) NOT NULL,   -- MISSING_IN_TARGET / CANCELLED_MARK_BUT_PAID / EXTRA_IN_TARGET
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (cutoff_at, order_no)
);

-- 대상 확정이 끝날 때 고정한 할당 입력. 할당과 재시작은 이 표만 읽어, 배치 중 이월이 품목 행을 바꿔도 입력이 같다
CREATE TABLE allocation_input (
    cutoff_at   DATETIME(3) NOT NULL,
    order_no    VARCHAR(30) NOT NULL,
    product_id  BIGINT      NOT NULL,
    quantity    INT         NOT NULL,
    region_code VARCHAR(20) NOT NULL,
    paid_at     DATETIME(3) NOT NULL,   -- 할당 순서. 먼저 결제한 주문이 먼저다
    PRIMARY KEY (cutoff_at, order_no, product_id),
    KEY idx_allocation_input_order (cutoff_at, paid_at, order_no)
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

-- 못 채운 수량의 판정. 할당은 여기까지만 쓰고, 품목 수량 이동과 이벤트는 판정 발행 스텝이 한다
CREATE TABLE unfillable_decision (
    cutoff_at  DATETIME(3) NOT NULL,
    order_no   VARCHAR(30) NOT NULL,
    product_id BIGINT      NOT NULL,
    quantity   INT         NOT NULL,
    reason     VARCHAR(30) NOT NULL,   -- STOCKTAKE_GAP / INBOUND_DELAY / REGION_MISMATCH
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (cutoff_at, order_no, product_id)
);

-- (마감 시각, 주문, 센터)마다 하나. 이미 있으면 삽입도 재고 차감도 하지 않는다
CREATE TABLE shipment_order (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    cutoff_at  DATETIME(3) NOT NULL,
    order_no   VARCHAR(30) NOT NULL,
    center_id  VARCHAR(20) NOT NULL,
    status     VARCHAR(20) NOT NULL,   -- CREATED / SHIPPED / CANCELLED (배차 전 관리자 취소. 실물 재고를 되돌리고 출고 예외로)
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

-- 출고 지시를 만들지 못한 (센터, 주문). id 가 예외 번호다. 사람이 풀필먼트 관리 API 로 이월하거나 관리자 취소한다
-- 두 처리 모두 status = 'OPEN' 조건부 갱신이라 한 예외에 한 번만 일어난다
CREATE TABLE shipment_exception (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    cutoff_at  DATETIME(3)  NOT NULL,
    order_no   VARCHAR(30)  NOT NULL,
    center_id  VARCHAR(20)  NOT NULL,
    reason     VARCHAR(200) NOT NULL,
    status     VARCHAR(20)  NOT NULL,   -- OPEN / DEFERRED / CANCELLED
    created_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipment_exception (cutoff_at, order_no, center_id)
);

CREATE TABLE shipment_exception_line (
    shipment_exception_id BIGINT NOT NULL,
    product_id            BIGINT NOT NULL,
    quantity              INT    NOT NULL,
    PRIMARY KEY (shipment_exception_id, product_id)
);

-- 고객 취소는 대기 수량을 취소 수량으로 옮기는 소비라 인박스로 중복을 막는다 (docs/design/03)
CREATE TABLE inbox_event (
    consumer     VARCHAR(50) NOT NULL,
    event_id     CHAR(36)    NOT NULL,
    processed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (consumer, event_id)
);

-- 상태 변경과 같은 트랜잭션에 기록하고 릴레이가 선점, 전송, 기록 세 단계로 발행한다 (docs/design/03)
CREATE TABLE outbox_event (
    seq             BIGINT       NOT NULL AUTO_INCREMENT,
    event_id        CHAR(36)     NOT NULL,
    topic           VARCHAR(100) NOT NULL,
    partition_key   VARCHAR(100) NOT NULL,
    payload         JSON         NOT NULL,
    status          VARCHAR(20)  NOT NULL,   -- PENDING / SENT / QUARANTINED (사람만 격리)
    attempt_count   INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3)  NOT NULL,
    created_at      DATETIME(3)  NOT NULL,
    sent_at         DATETIME(3)  NULL,
    PRIMARY KEY (seq),
    UNIQUE KEY uk_outbox_event_id (event_id),
    KEY idx_outbox_status_next (status, next_attempt_at),
    KEY idx_outbox_key_status (partition_key, status, seq)
);
