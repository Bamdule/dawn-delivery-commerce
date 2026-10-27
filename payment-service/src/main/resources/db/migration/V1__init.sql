-- 같은 주문의 같은 결제 회차는 한 번만 승인한다. 재시도는 회차를 올리지 않는다 (docs/design/04)
CREATE TABLE payment (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    order_no           VARCHAR(30)  NOT NULL,
    payment_round      INT          NOT NULL,
    idempotency_key    VARCHAR(80)  NOT NULL,
    amount             BIGINT       NOT NULL,
    status             VARCHAR(20)  NOT NULL,   -- REQUESTED / APPROVED / FAILED / UNKNOWN
    pg_transaction_id  VARCHAR(80)  NULL,
    failure_code       VARCHAR(50)  NULL,
    inquiry_count      INT          NOT NULL DEFAULT 0,
    requested_at       DATETIME(3)  NOT NULL,
    updated_at         DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_order_round (order_no, payment_round),
    UNIQUE KEY uk_payment_idempotency_key (idempotency_key),
    KEY idx_payment_status_requested (status, requested_at)
);

-- 환불은 결제를 지우지 않고 반대 방향의 새 결제로 상쇄한다
CREATE TABLE refund (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    payment_id      BIGINT      NOT NULL,
    refund_key      VARCHAR(80) NOT NULL,   -- refund-{주문번호}-{환불시도번호}
    amount          BIGINT      NOT NULL,
    status          VARCHAR(20) NOT NULL,   -- REQUESTED / COMPLETED / FAILED / UNKNOWN
    reason          VARCHAR(50) NOT NULL,
    requested_at    DATETIME(3) NOT NULL,
    updated_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_key (refund_key)
);
