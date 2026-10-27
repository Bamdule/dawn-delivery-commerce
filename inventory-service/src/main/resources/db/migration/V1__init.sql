-- 판매 가능 재고. 조건부 UPDATE 한 문장으로 차감한다 (docs/design/02)
CREATE TABLE stock (
    product_id   BIGINT      NOT NULL,
    quantity     INT         NOT NULL,
    slot_enabled BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (product_id),
    CONSTRAINT ck_stock_quantity CHECK (quantity >= 0)
);

-- 주문이 쏠리는 상품만 슬롯으로 쪼갠다. slot_enabled 인 상품만 사용한다
CREATE TABLE stock_slot (
    product_id BIGINT      NOT NULL,
    slot_no    INT         NOT NULL,
    quantity   INT         NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (product_id, slot_no),
    CONSTRAINT ck_stock_slot_quantity CHECK (quantity >= 0)
);

CREATE TABLE stock_reservation (
    reservation_id VARCHAR(40) NOT NULL,
    order_no       VARCHAR(30) NOT NULL,
    product_id     BIGINT      NOT NULL,
    slot_no        INT         NULL,
    quantity       INT         NOT NULL,
    status         VARCHAR(20) NOT NULL,   -- HELD / CONFIRMED / RELEASED
    expires_at     DATETIME(3) NOT NULL,
    created_at     DATETIME(3) NOT NULL,
    updated_at     DATETIME(3) NOT NULL,
    PRIMARY KEY (reservation_id),
    UNIQUE KEY uk_reservation_order_product (order_no, product_id),
    KEY idx_reservation_status_expires (status, expires_at)
);

-- 출고는 특정 센터에서 나가므로 판매 가능 재고와 나눠 둔다
CREATE TABLE center_stock (
    center_id  VARCHAR(20) NOT NULL,
    product_id BIGINT      NOT NULL,
    quantity   INT         NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (center_id, product_id)
);

-- 값을 더하는 소비만 쓴다. 기록과 처리를 같은 트랜잭션에 둔다 (docs/design/03)
CREATE TABLE inbox_event (
    consumer     VARCHAR(50) NOT NULL,
    event_id     CHAR(36)    NOT NULL,
    processed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (consumer, event_id)
);
