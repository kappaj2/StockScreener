drop procedure if exists AddColumnUnlessExists;

delimiter ;;

create procedure AddColumnUnlessExists(
    IN dbName tinytext,
    IN tableName tinytext,
    IN fieldName tinytext,
    IN fieldDef text,
    IN afterCol text)
begin
    IF NOT EXISTS (SELECT *
                   FROM information_schema.COLUMNS
                   WHERE column_name = fieldName
                     and table_name = tableName
                     and table_schema = dbName)
    THEN
        set @ddl = CONCAT('ALTER TABLE ', dbName, '.', tableName,
                          ' ADD COLUMN ', fieldName, ' ', fieldDef, ' AFTER ', afterCol);
        prepare stmt from @ddl;
        execute stmt;
    END IF;
end;;

DELIMITER ;