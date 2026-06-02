CREATE TABLE IF NOT EXISTS daily_bar_score
(
    symbol              VARCHAR(20)    NOT NULL,
    trade_date          DATE           NOT NULL,
    close_price         DECIMAL(18, 6) NOT NULL,
    price_change_pct    DECIMAL(10, 6) NOT NULL,
    volume              BIGINT         NOT NULL,
    vol_ratio           DECIMAL(10, 4)          NULL,
    close_position      DECIMAL(10, 6)          NULL,
    vol_per_bar         DECIMAL(18, 4)          NULL,
    -- individual signal flags (0 or 1; quiet_accumulation can also be 2)
    vol_spike           INT            NOT NULL DEFAULT 0,
    up_day              INT            NOT NULL DEFAULT 0,
    strong_close        INT            NOT NULL DEFAULT 0,
    tight_range         INT            NOT NULL DEFAULT 0,
    quiet_accumulation  INT            NOT NULL DEFAULT 0,
    vol_concentration   INT            NOT NULL DEFAULT 0,
    -- composite scores
    day_score           INT            NOT NULL DEFAULT 0,
    rolling_10d_score   INT            NOT NULL DEFAULT 0,
    computed_at         DATETIME(3)    NOT NULL,

    PRIMARY KEY (symbol, trade_date)
);

CREATE INDEX idx_dbs_trade_date     ON daily_bar_score (trade_date);
CREATE INDEX idx_dbs_rolling_score  ON daily_bar_score (rolling_10d_score DESC);
CREATE INDEX idx_dbs_symbol_date    ON daily_bar_score (symbol, trade_date DESC);