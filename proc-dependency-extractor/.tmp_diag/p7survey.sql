SELECT 'total_create' k, COUNT(*) v FROM sql_block_info WHERE UPPER(TRIM(sql_content)) LIKE 'CREATE TABLE%'
UNION ALL SELECT 'engine', COUNT(*) FROM sql_block_info WHERE UPPER(TRIM(sql_content)) LIKE 'CREATE TABLE%' AND UPPER(sql_content) LIKE '%ENGINE%'
UNION ALL SELECT 'tablespace', COUNT(*) FROM sql_block_info WHERE UPPER(TRIM(sql_content)) LIKE 'CREATE TABLE%' AND UPPER(sql_content) LIKE '%TABLESPACE%'
UNION ALL SELECT 'charset', COUNT(*) FROM sql_block_info WHERE UPPER(TRIM(sql_content)) LIKE 'CREATE TABLE%' AND UPPER(sql_content) LIKE '%CHARSET%'
UNION ALL SELECT 'comment_tbl', COUNT(*) FROM sql_block_info WHERE UPPER(TRIM(sql_content)) LIKE 'CREATE TABLE%' AND UPPER(sql_content) LIKE '%COMMENT%'
UNION ALL SELECT 'distributed', COUNT(*) FROM sql_block_info WHERE UPPER(TRIM(sql_content)) LIKE 'CREATE TABLE%' AND UPPER(sql_content) LIKE '%DISTRIBUTED BY%'
UNION ALL SELECT 'duplicate', COUNT(*) FROM sql_block_info WHERE UPPER(TRIM(sql_content)) LIKE 'CREATE TABLE%' AND UPPER(sql_content) LIKE '%DUPLICATE BY%'
UNION ALL SELECT 'partition_by', COUNT(*) FROM sql_block_info WHERE UPPER(TRIM(sql_content)) LIKE 'CREATE TABLE%' AND UPPER(sql_content) LIKE '%PARTITION BY%'
UNION ALL SELECT 'as_select', COUNT(*) FROM sql_block_info WHERE UPPER(TRIM(sql_content)) LIKE 'CREATE TABLE%' AND UPPER(sql_content) LIKE '% AS %SELECT%';
