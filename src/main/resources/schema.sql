-- 정산 대사 배치 결과. JPA 엔티티가 아니라 배치(JDBC)가 직접 쓰는 테이블이라 DDL을 명시적으로 둔다.
-- (target_date, payment_id) 유니크: 같은 날짜를 다시 대사하거나 재시작해도 결과가 한 번만 남는다.
CREATE TABLE IF NOT EXISTS reconciliation_mismatch (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    target_date     DATE           NOT NULL,
    payment_id      BIGINT         NOT NULL,
    mismatch_type   VARCHAR(30)    NOT NULL,
    action_required VARCHAR(30)    NOT NULL,
    payment_amount  DECIMAL(19, 2) NOT NULL,
    settled_amount  DECIMAL(19, 2) NOT NULL,
    entry_count     INT            NOT NULL,
    detected_at     DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_recon_date_payment (target_date, payment_id)
);
