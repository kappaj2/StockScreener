CREATE TABLE IF NOT EXISTS `signal_history`
(
    `id`               BIGINT AUTO_INCREMENT NOT NULL,
    `signal_id`        VARCHAR(36)    NOT NULL,
    `symbol`           VARCHAR(20)    NOT NULL,
    `pattern`          VARCHAR(30)    NOT NULL,
    `entry_price`      DECIMAL(18, 6) NOT NULL,
    `stop_price`       DECIMAL(18, 6) NOT NULL,
    `target_price`     DECIMAL(18, 6) NOT NULL,
    `confidence`       INT            NOT NULL,
    `risk`             VARCHAR(255)   NULL,
    `notes`            VARCHAR(1000)  NULL,
    `signal_timestamp` BIGINT         NOT NULL,
    `pre_market_high`  DECIMAL(18, 6) NULL,
    `pre_market_low`   DECIMAL(18, 6) NULL,
    `news`             VARCHAR(1000)  NULL,
    `high_watch`       TINYINT(1)     NOT NULL DEFAULT 0,
    `created_at`       DATETIME(3)    NOT NULL,
    PRIMARY KEY (`id`)
)
    ENGINE = InnoDB;

CREATE INDEX `idx_signal_history_symbol`     ON `signal_history` (`symbol`);
CREATE INDEX `idx_signal_history_signal_id`  ON `signal_history` (`signal_id`);
CREATE INDEX `idx_signal_history_created_at` ON `signal_history` (`created_at`);
