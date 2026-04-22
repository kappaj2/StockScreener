CREATE TABLE minute_bar (
    id                 BIGINT          NOT NULL AUTO_INCREMENT,
    symbol             VARCHAR(20)     NOT NULL,
    bar_start          DATETIME(3)     NOT NULL,
    bar_end            DATETIME(3)     NOT NULL,
    open_price         DECIMAL(18, 6)  NOT NULL,
    high_price         DECIMAL(18, 6)  NOT NULL,
    low_price          DECIMAL(18, 6)  NOT NULL,
    close_price        DECIMAL(18, 6)  NOT NULL,
    volume             BIGINT          NOT NULL,
    accumulated_volume BIGINT          NOT NULL,
    official_open      DECIMAL(18, 6)  NULL,
    vwap               DECIMAL(18, 6)  NULL,
    today_vwap         DECIMAL(18, 6)  NULL,
    avg_trade_size     INT             NULL,
    created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    INDEX idx_minute_bar_symbol_start (symbol, bar_start),
    INDEX idx_minute_bar_bar_start (bar_start)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
