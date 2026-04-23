CREATE TABLE IF NOT EXISTS daily_bar_summary
(
    symbol      VARCHAR(20)    NOT NULL,
    trade_date  DATE           NOT NULL,
    first_ts    DATETIME(3)    NOT NULL,
    last_ts     DATETIME(3)    NOT NULL,
    open_price  DECIMAL(18, 6) NOT NULL,
    high_price  DECIMAL(18, 6) NOT NULL,
    low_price   DECIMAL(18, 6) NOT NULL,
    close_price DECIMAL(18, 6) NOT NULL,
    volume      BIGINT         NOT NULL,
    bar_count   INT            NOT NULL,
    created_at  DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (symbol, trade_date),
    KEY idx_daily_summary_trade_date (trade_date)
);
