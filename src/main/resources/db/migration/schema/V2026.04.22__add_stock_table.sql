CREATE TABLE IF NOT EXISTS minute_bar
(
    symbol             VARCHAR(20)    NOT NULL,
    bar_start          DATETIME(3)    NOT NULL,
    bar_end            DATETIME(3)    NOT NULL,
    open_price         DECIMAL(18, 6) NOT NULL,
    high_price         DECIMAL(18, 6) NOT NULL,
    low_price          DECIMAL(18, 6) NOT NULL,
    close_price        DECIMAL(18, 6) NOT NULL,
    volume             BIGINT         NOT NULL,
    accumulated_volume BIGINT         NOT NULL,
    official_open      DECIMAL(18, 6),
    vwap               DECIMAL(18, 6),
    today_vwap         DECIMAL(18, 6),
    avg_trade_size     INT,
    created_at         DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (symbol, bar_start),
    KEY idx_bar_start (bar_start)

)
    PARTITION BY RANGE (TO_DAYS(bar_start)) (
        PARTITION p202604 VALUES LESS THAN (TO_DAYS('2026-05-01')),
        PARTITION p202605 VALUES LESS THAN (TO_DAYS('2026-06-01')),
        PARTITION p202606 VALUES LESS THAN (TO_DAYS('2026-07-01')),
        PARTITION pmax VALUES LESS THAN MAXVALUE
        );