DROP PROCEDURE IF EXISTS drop_column_if_exists;

DELIMITER $$

CREATE PROCEDURE drop_column_if_exists(
    tname VARCHAR(64),
    cname VARCHAR(64)
)
BEGIN
    IF EXISTS (SELECT *
               FROM information_schema.COLUMNS
               WHERE column_name = cname
                 and table_name = tname
                 and table_schema = DATABASE())
    THEN
        SET @drop_column_if_exists = CONCAT('ALTER TABLE `', tname, '` DROP COLUMN `', cname, '`;');
        PREPARE drop_query FROM @drop_column_if_exists;
        EXECUTE drop_query;
    END IF;
END $$
DELIMITER ;