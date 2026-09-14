SELECT CONCAT('@@BLOCK ', proc_name, ' | ', block_seq, '@@') AS marker, sql_content
FROM sql_block_info
WHERE UPPER(TRIM(sql_content)) LIKE 'CREATE TABLE%'
ORDER BY proc_name, block_seq;
