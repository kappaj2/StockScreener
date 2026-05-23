ALTER TABLE daily_bar_summary
    DROP INDEX idx_daily_summary_trade_date,
    ADD INDEX idx_daily_summary_trade_date_symbol (trade_date, symbol);