-- 결제 완료 이벤트로 등록되는 출고 대상. 마감 시각을 파라미터로 받아 이 표에서 고른다 (docs/design/05)
CREATE TABLE shipment_target (
    order_no    VARCHAR(30) NOT NULL,
    member_id   BIGINT      NOT NULL,
    region_code VARCHAR(20) NOT NULL,
    paid_at     DATETIME(3) NOT NULL,
    status      VARCHAR(20) NOT NULL,   -- WAITING / ALLOCATED / UNFILLABLE / CANCELLED
    PRIMARY KEY (order_no),
    KEY idx_target_status_paid (status, paid_at)
);

CREATE TABLE shipment_target_line (
    order_no   VARCHAR(30) NOT NULL,
    product_id BIGINT      NOT NULL,
    quantity   INT         NOT NULL,
    PRIMARY KEY (order_no, product_id)
);

-- 할당 스텝이 한 번에 계산한 결과. 같은 마감 회차로 다시 돌리면 덮어쓴다
CREATE TABLE allocation_result (
    cutoff_at  DATETIME(3) NOT NULL,
    order_no   VARCHAR(30) NOT NULL,
    product_id BIGINT      NOT NULL,
    center_id  VARCHAR(20) NOT NULL,
    quantity   INT         NOT NULL,
    PRIMARY KEY (cutoff_at, order_no, product_id, center_id),
    KEY idx_allocation_center (cutoff_at, center_id)
);

-- 주문과 센터마다 하나. 재시작해도 같은 출고 지시가 두 번 생기지 않는다
CREATE TABLE shipment_order (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    cutoff_at  DATETIME(3) NOT NULL,
    order_no   VARCHAR(30) NOT NULL,
    center_id  VARCHAR(20) NOT NULL,
    status     VARCHAR(20) NOT NULL,   -- CREATED / PICKING / SHIPPED
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipment_order_center (order_no, center_id)
);

CREATE TABLE unfillable_decision (
    order_no   VARCHAR(30) NOT NULL,
    cutoff_at  DATETIME(3) NOT NULL,
    decision   VARCHAR(20) NOT NULL,   -- PARTIAL / CANCEL
    reason     VARCHAR(30) NOT NULL,   -- STOCKTAKE_GAP / INBOUND_DELAY / ALLOCATION_FAILED
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (order_no, cutoff_at)
);

CREATE TABLE inbox_event (
    consumer     VARCHAR(50) NOT NULL,
    event_id     CHAR(36)    NOT NULL,
    processed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (consumer, event_id)
);
