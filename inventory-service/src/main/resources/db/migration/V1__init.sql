-- 판매 가능 재고. 조건부 UPDATE 한 문장으로 차감한다 (docs/design/02)
-- slot_mode 가 SPREAD 나 MERGED 인 상품은 슬롯 합이 판매 가능 재고이고 quantity 는 0으로 두고 쓰지 않는다
CREATE TABLE stock (
    product_id   BIGINT      NOT NULL,
    quantity     INT         NOT NULL,
    slot_mode    VARCHAR(10) NOT NULL DEFAULT 'NONE',   -- NONE / SPREAD / MERGED
    hold_minutes INT         NOT NULL DEFAULT 10,       -- 예약 유지 시간. 특가 상품은 3
    updated_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (product_id),
    CONSTRAINT ck_stock_quantity CHECK (quantity >= 0)
);

-- 슬롯 행은 지우지 않는다. MERGED 뒤에는 슬롯 0 만 쓴다
CREATE TABLE stock_slot (
    product_id BIGINT      NOT NULL,
    slot_no    INT         NOT NULL,
    quantity   INT         NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (product_id, slot_no),
    CONSTRAINT ck_stock_slot_quantity CHECK (quantity >= 0)
);

-- 주문과 상품마다 한 행. 다시 결제해도 새로 만들지 않는다
-- PAYING 에는 결제 회차와 기한을 남긴다. 되돌림과 만료는 그 회차를 조건으로 건다 (docs/design/02)
CREATE TABLE stock_reservation (
    reservation_id VARCHAR(40) NOT NULL,
    order_no       VARCHAR(30) NOT NULL,
    product_id     BIGINT      NOT NULL,
    quantity       INT         NOT NULL,
    status           VARCHAR(20) NOT NULL,   -- HELD / PAYING / CONFIRMED / RELEASED / RESTORED
    payment_round    INT         NULL,
    payment_deadline DATETIME(3) NULL,
    expires_at       DATETIME(3) NOT NULL,
    created_at     DATETIME(3) NOT NULL,
    updated_at     DATETIME(3) NOT NULL,
    PRIMARY KEY (reservation_id),
    UNIQUE KEY uk_reservation_order_product (order_no, product_id),
    KEY idx_reservation_status_expires (status, expires_at)
);

-- 슬롯 상품의 예약이 어느 슬롯에서 몇 개를 뺐는지. 해제와 복원은 이 내역대로 되돌린다
CREATE TABLE reservation_slot (
    reservation_id VARCHAR(40) NOT NULL,
    slot_no        INT         NOT NULL,
    quantity       INT         NOT NULL,
    PRIMARY KEY (reservation_id, slot_no)
);

-- 실사 조정을 0 에서 멈췄을 때 요청한 값과 반영한 값
CREATE TABLE stock_adjustment_log (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    event_id        CHAR(36)    NOT NULL,
    product_id      BIGINT      NOT NULL,
    requested_delta INT         NOT NULL,
    applied_delta   INT         NOT NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id)
);

-- 입고와 실사 이벤트처럼 값을 더하는 소비만 쓴다. 기록과 처리를 같은 트랜잭션에 둔다 (docs/design/03)
CREATE TABLE inbox_event (
    consumer     VARCHAR(50) NOT NULL,
    event_id     CHAR(36)    NOT NULL,
    processed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (consumer, event_id)
);
