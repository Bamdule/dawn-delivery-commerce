-- 주문 상태 전이와 보상의 유일한 주인 (docs/design/01)
CREATE TABLE orders (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    order_no        VARCHAR(30)  NOT NULL,
    checkout_id     CHAR(36)     NOT NULL,   -- 주문서 화면에서 발급. 주문 요청 재시도를 한 주문으로 묶는다
    member_id       BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL,   -- PENDING / PAID / FAILED / CANCELLED
    total_amount    BIGINT       NOT NULL,
    refunded_amount BIGINT       NOT NULL DEFAULT 0,
    payment_round   INT          NOT NULL DEFAULT 1,   -- 주문 서비스가 정하는 다음 결제 회차. 결제 서비스가 검사한다
    paid_round      INT          NULL,                 -- 주문을 PAID 로 만든 승인 회차. 다른 회차의 승인은 환불한다
    recipient_name  VARCHAR(50)  NOT NULL,
    address         VARCHAR(200) NOT NULL,
    region_code     VARCHAR(20)  NOT NULL,
    cancel_reason   VARCHAR(30)  NULL,       -- CUSTOMER / UNFILLABLE / ADMIN
    reservation_expires_at DATETIME(3) NULL, -- 예약 응답으로 받은 한 주문의 만료 시각
    created_at      DATETIME(3)  NOT NULL,
    paid_at         DATETIME(3)  NULL,       -- 마감 회차의 기준 시각 (docs/design/05)
    updated_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_order_no (order_no),
    UNIQUE KEY uk_orders_checkout_id (checkout_id),
    KEY idx_orders_status_created (status, created_at),
    KEY idx_orders_status_paid (status, paid_at)
);

CREATE TABLE order_item (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    order_id   BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity   INT    NOT NULL,
    unit_price BIGINT NOT NULL,
    cancelled_quantity INT NOT NULL DEFAULT 0,   -- 취소는 품목 단위. quantity 를 넘지 않는다
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_item_product (order_id, product_id)
);

-- 환불 키는 사유에서 결정적으로 만든다. 다시 계산해도 같은 키라 두 번 환불되지 않는다 (docs/design/04)
CREATE TABLE refund_request (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    order_no        VARCHAR(30) NOT NULL,
    refund_key      VARCHAR(80) NOT NULL,   -- refund-{주문번호}-cancel / -cutoff-{마감일시} / -admin-{예외번호} / -orphan-{회차}
    payment_round   INT         NOT NULL,   -- 환불할 결제 회차. 보통은 paid_round, 쓰이지 않은 승인이면 그 회차
    amount          BIGINT      NOT NULL,
    reason          VARCHAR(30) NOT NULL,   -- CUSTOMER_CANCEL / UNFILLABLE_PARTIAL / UNFILLABLE_CANCEL / ADMIN_CANCEL / ORPHAN_APPROVAL
    status          VARCHAR(20) NOT NULL,   -- REQUESTED / ACCEPTED / COMPLETED / FAILED. FAILED 도 사람이 확정하면 COMPLETED
    attempt_count   INT         NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    created_at      DATETIME(3) NOT NULL,
    updated_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_request_key (refund_key),
    KEY idx_refund_request_status_next (status, next_attempt_at)
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
