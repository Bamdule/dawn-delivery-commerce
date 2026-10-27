-- 주문 상태 전이의 유일한 주인. 결제 결과를 받은 뒤 짧은 트랜잭션으로 상태와 아웃박스를 함께 커밋한다 (docs/design/01)
CREATE TABLE orders (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    order_no      VARCHAR(30)  NOT NULL,
    member_id     BIGINT       NOT NULL,
    status        VARCHAR(20)  NOT NULL,   -- PENDING / PAID / FAILED / CANCELLED
    total_amount  BIGINT       NOT NULL,
    payment_round INT          NOT NULL DEFAULT 1,
    created_at    DATETIME(3)  NOT NULL,
    updated_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_order_no (order_no)
);

CREATE TABLE order_item (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    order_id       BIGINT      NOT NULL,
    product_id     BIGINT      NOT NULL,
    quantity       INT         NOT NULL,
    unit_price     BIGINT      NOT NULL,
    reservation_id VARCHAR(40) NULL,
    PRIMARY KEY (id),
    KEY idx_order_item_order (order_id)
);

-- 발행을 같은 트랜잭션의 DB 쓰기로 바꾼다 (docs/design/03)
CREATE TABLE outbox_event (
    event_id      CHAR(36)     NOT NULL,
    topic         VARCHAR(100) NOT NULL,
    partition_key VARCHAR(100) NOT NULL,
    payload       JSON         NOT NULL,
    status        VARCHAR(20)  NOT NULL,   -- PENDING / SENT / FAILED
    retry_count   INT          NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3)  NULL,
    created_at    DATETIME(3)  NOT NULL,
    sent_at       DATETIME(3)  NULL,
    PRIMARY KEY (event_id),
    KEY idx_outbox_status_created (status, created_at),
    KEY idx_outbox_key_status (partition_key, status)
);
