CREATE TABLE IF NOT EXISTS five_minute_bar
(
    symbol      VARCHAR(20)    NOT NULL,
    bar_start   DATETIME(3)    NOT NULL,
    bar_end     DATETIME(3)    NOT NULL,
    open_price  DECIMAL(18, 6) NOT NULL,
    high_price  DECIMAL(18, 6) NOT NULL,
    low_price   DECIMAL(18, 6) NOT NULL,
    close_price DECIMAL(18, 6) NOT NULL,
    volume      BIGINT         NOT NULL,
    vwap        DECIMAL(18, 6),
    created_at  DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (symbol, bar_start),
    KEY idx_five_min_bar_start (bar_start)

)
    PARTITION BY RANGE (TO_DAYS(bar_start)) (
        PARTITION p202610 VALUES LESS THAN (TO_DAYS('2026-11-01')),
        PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
        PARTITION p202612 VALUES LESS THAN (TO_DAYS('2027-01-01')),
        PARTITION pmax VALUES LESS THAN MAXVALUE
        );

CREATE INDEX idx_five_min_bar_created_at ON five_minute_bar (created_at);