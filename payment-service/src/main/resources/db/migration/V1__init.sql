-- 같은 주문의 같은 결제 회차는 한 번만 승인한다. 새 회차는 가장 최근 회차가 FAILED 일 때만 연다 (docs/design/04)
CREATE TABLE payment (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    order_no          VARCHAR(30) NOT NULL,
    payment_round     INT         NOT NULL,
    idempotency_key   VARCHAR(80) NOT NULL,   -- order-{주문번호}-payment-{결제회차}. 대행사 조회도 이 키로 한다
    amount            BIGINT      NOT NULL,
    status            VARCHAR(20) NOT NULL,   -- REQUESTED / APPROVED / FAILED / UNKNOWN
    pg_transaction_id VARCHAR(80) NULL,
    failure_code      VARCHAR(50) NULL,
    attempt_count     INT         NOT NULL DEFAULT 0,
    last_sent_at      DATETIME(3) NOT NULL,   -- "요청 없음"은 이 시각에서 60초가 지난 뒤에만 믿는다
    next_attempt_at   DATETIME(3) NULL,
    requested_at      DATETIME(3) NOT NULL,
    approved_at       DATETIME(3) NULL,
    updated_at        DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_order_round (order_no, payment_round),
    UNIQUE KEY uk_payment_idempotency_key (idempotency_key),
    KEY idx_payment_status_requested (status, requested_at)
);

-- 환불은 결제를 지우지 않고 반대 방향의 새 거래로 상쇄한다. 키는 주문 서비스가 정하고, 대행사 확정은 결제 서비스 환불 배치가 맡는다
CREATE TABLE refund (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    payment_id   BIGINT      NOT NULL,
    refund_key   VARCHAR(80) NOT NULL,
    amount       BIGINT      NOT NULL,
    status       VARCHAR(20) NOT NULL,   -- REQUESTED / COMPLETED / FAILED / UNKNOWN
    last_sent_at DATETIME(3) NULL,
    requested_at DATETIME(3) NOT NULL,
    updated_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_key (refund_key),
    KEY idx_refund_status_requested (status, requested_at)
);

-- 하루 한 번 대행사 정산 파일과 맞춰 본 결과 중 어긋난 건
CREATE TABLE settlement_mismatch (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    settlement_date   DATE        NOT NULL,
    idempotency_key   VARCHAR(80) NOT NULL,
    pg_status         VARCHAR(20) NOT NULL,
    our_status        VARCHAR(20) NULL,
    resolution        VARCHAR(20) NOT NULL,   -- OPEN / REFUNDED / CORRECTED
    created_at        DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_mismatch (settlement_date, idempotency_key)
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
