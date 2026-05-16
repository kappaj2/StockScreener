CREATE TABLE IF NOT EXISTS `high_watch_stocks`
(
    `symbol`      VARCHAR(20)  NOT NULL,
    `valid_from`  DATETIME(3)  NOT NULL,
    `valid_to`    DATETIME(3)  NULL,
    `source_list` VARCHAR(255) NULL,
    `created_at`  DATETIME(3)  NULL,
    PRIMARY KEY (`symbol`),
    INDEX `from_indx` (`valid_from` ASC) VISIBLE,
    INDEX `to_indx` (`valid_to` ASC) VISIBLE
)
    ENGINE = InnoDB;