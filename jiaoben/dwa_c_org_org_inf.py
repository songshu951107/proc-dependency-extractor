# *****************************************头部注释模块**************************************
#脚本名称：dwa_c_org_org_inf.py
#功能说明：共性层机构信息表CDM脚本
#更新方式：切片表
#更新对象：CDM.C_ORG_ORG_INF
#创 建 人： 
#创建日期：2023-06-26
#运行周期：日
#备    注：
#维护信息： 
# *******************************************************************************************
# -*- coding: utf-8 -*-
import atexit
from datetime import datetime
import sys
import os
import subprocess
import re

# -- 1.验证输入参数是否正确-- #
if len(sys.argv) == 2:
    ETL_TX_DT = sys.argv[1]
else:
    print("usage :python xxx.py YYYYMMDD")
    exit(-1)

ETL_HOME = os.path.abspath(os.environ['ETL_HOME'])
if not ETL_HOME:
    print("Error:you must define a ETL_HOME before import etl module")
    exit(-1)

# -- 2.日期及变量初始化-- #
# -- 自定义模块-- #
from config import db
from log import Log
import etl
MAX_DATE = etl.MAX_DATE
MIN_DATE = etl.MIN_DATE
NULL_DATE = etl.NULL_DATE
LEG_DATE = etl.LEG_DATE

dwa_host = etl.dwa_host
dwa_port = etl.dwa_port
dwa_db_fdm = etl.dwa_db_fdm
dwa_db_cdm = etl.dwa_db_cdm
dwa_username = etl.dwa_usr_cdm
dwa_password = etl.dwa_pwd_cdm
TDM = etl.dwa_db_tdm
ODM = etl.dwa_db_odm
FDM = etl.dwa_db_fdm
CDM = etl.dwa_db_cdm


#-- 3.初始化日志-- #
log = Log('dwa', ETL_TX_DT)
log_filename = log.logname
log.info("BEGIN . ")

"""
  函数名称:execute_gbase_sql
  参数(2个): 管道句柄 运行sql
  功能: 将sql语句发送到gbase去执行
"""
def execute_gbase_sql(bformatsql):
    try:
        prefix_sql = "\n"
        suffix_sql = "\nquit\n"
        sql = prefix_sql + bformatsql + suffix_sql
        gsql_str_p = f"set -o pipefail; gccli -h {dwa_host} -P {dwa_port} -u {dwa_username} -p\"{dwa_password}\" -D {dwa_db_cdm} -v -v |tee -a {log_filename}"
        p = subprocess.Popen(gsql_str_p, shell=True, stdin=subprocess.PIPE, stdout=sys.stdout, stderr=sys.stdout,close_fds=True,universal_newlines=True, bufsize=1)
        output = p.communicate(sql)
        return p.returncode
    except:
        log.exception("Execute [%s] failed.%s" % (gsql_str_p,output.decode('utf-8')))
        return -1

def execute_gbase_sql_with_result(bformatsql):
    """
    执行SQL并返回结果，过滤掉SQL语句本身，只保留查询结果
    """
    try:
        prefix_sql = "\n"
        suffix_sql = "\nquit\n"
        sql = prefix_sql + bformatsql + suffix_sql
        gsql_str_p = f"set -o pipefail; gccli -h {dwa_host} -P {dwa_port} -u {dwa_username} -p\"{dwa_password}\" -D {dwa_db_cdm} -v -v"

        p = subprocess.Popen(gsql_str_p, shell=True, stdin=subprocess.PIPE,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             close_fds=True, universal_newlines=True, bufsize=1)
        stdout, stderr = p.communicate(sql)

        if p.returncode != 0:
            log.error(f"SQL执行失败: {stderr}")
            return None

        # 过滤掉 SQL 语句和注释行，只保留查询结果
        result_lines = []
        for line in stdout.splitlines():
            line = line.strip()
            # 忽略空行、注释行、分隔线、SQL语句行
            if not line:
                continue
            if line.startswith("--") or line.startswith("--------------"):
                continue
            if line.lower().startswith(("select", "insert", "update", "delete", "create", "drop", "replace")):
                continue
            result_lines.append(line)

        # 返回过滤后的结果
        return "\n".join(result_lines).strip()

    except Exception as e:
        log.exception("执行SQL失败: %s", str(e))
        return None

@atexit.register
def __exit__():
    """print a log when exit"""
    log.info("END\n")



# -- 4.执行sql内容-- #
# -- bsql1支持重跑sql及临时表创建-- #
bsql1="""

-- 支持重跑删除当前执行日期
DELETE FROM {CDM}.C_ORG_ORG_INF WHERE data_dt=  DATE '{ETL_TX_DT}' AND crbs_org_flag = '0' ; 

-- 机构信息表中间临时表
CREATE TEMPORARY  TABLE  VT_C_ORG_ORG_INF_TMP(
 biz_org_no	             VARCHAR(100) DEFAULT NULL  COMMENT '营业机构号'
,org_type	             VARCHAR(100) DEFAULT NULL COMMENT '机构类型'
,brch_org_no	         VARCHAR(100) DEFAULT NULL COMMENT '分行机构号'
,org_cn_name	         VARCHAR(100) DEFAULT NULL COMMENT '机构中文名称'
,org_abre	             VARCHAR(100) DEFAULT NULL COMMENT '机构简称'
,acctg_super_org_no	     VARCHAR(100) DEFAULT NULL COMMENT '账务上级机构号'
,report_super_org_no	 VARCHAR(100) DEFAULT NULL COMMENT '报表上级机构号'
,org_biz_lvl_cd	         VARCHAR(100) DEFAULT NULL COMMENT '机构营业级别代码'
,org_mgmt_lvl_cd	     VARCHAR(100) DEFAULT NULL COMMENT '机构管理级别代码'
,bkkp_org_flag	         VARCHAR(100) DEFAULT NULL COMMENT '记账机构标志'
,vault_org_flag	         VARCHAR(100) DEFAULT NULL COMMENT '金库机构标志'
,issued_brch_cd	         VARCHAR(100) DEFAULT NULL COMMENT '发卡分行代码'
,audit_org_flag	         VARCHAR(100) DEFAULT NULL COMMENT '稽核机构标志'
,indep_acctg_flag	     VARCHAR(100) DEFAULT NULL COMMENT '独立核算标志'
,pboc_bank_no	         VARCHAR(100) DEFAULT NULL COMMENT '人行行号'
,out_tube_cd	         VARCHAR(100) DEFAULT NULL COMMENT '外管代码'
,city_cd	             VARCHAR(100) DEFAULT NULL COMMENT '城市代码'
,sourc_sys               VARCHAR(100) DEFAULT NULL COMMENT '来源系统'
);


	 

	 --  插入信贷机构数据
	 INSERT INTO VT_C_ORG_ORG_INF_TMP (
	  biz_org_no            -- 营业机构号
	 ,org_type              -- 机构类型
	 ,brch_org_no           -- 分行机构号
	 ,org_cn_name           -- 机构中文名称
	 ,org_abre              -- 机构简称
	 ,acctg_super_org_no    -- 账务上级机构号
	 ,report_super_org_no   -- 报表上级机构号
	 ,org_biz_lvl_cd        -- 机构营业级别代码
	 ,org_mgmt_lvl_cd       -- 机构管理级别代码
	 ,bkkp_org_flag         -- 记账机构标志
	 ,vault_org_flag        -- 金库机构标志
	 ,issued_brch_cd        -- 发卡分行代码
	 ,audit_org_flag        -- 稽核机构标志
	 ,indep_acctg_flag      -- 独立核算标志
	 ,pboc_bank_no          -- 人行行号
	 ,out_tube_cd           -- 外管代码
	 ,city_cd               -- 城市代码
	 ,sourc_sys             -- 来源系统
	 )
	 SELECT
	   T1.org_no	            --  机构号
	  ,'2'                  --  机构类型
	  ,T1.brch_org_cd	        --  分行代码
	  ,T1.org_name	            --  机构名称
	  ,T1.org_name	            --  机构名称
      ,SUBSTR(T1.org_no,1,2)||'98'     -- 账务上级机构号
	  ,SUBSTR(T1.org_no,1,2)||'99'     -- 报表上级机构号
	  ,CASE WHEN SUBSTR(T1.org_no,1,2)='00' THEN '1' ELSE '2' END     -- 机构营业级别代码
	  ,CASE WHEN SUBSTR(T1.org_no,1,2)='00' or SUBSTR(T1.org_no,3,2)='99' THEN '1' ELSE '2' END  -- 机构管理级别代码
	  ,CASE WHEN SUBSTR(T1.org_no,3,2)='99' THEN '0' ELSE '1' END      -- 记账机构标志
	  , ' '                                   -- 金库机构标志
	  ,brch_org_cd                          	-- 发卡分行代码
	  ,'1'                                    -- 稽核机构标志
	  ,'1'                                    -- 独立核算标志
	  ,	' '                                   -- 人行行号
	  ,	' '                                   -- 外管代码
	  ,	' '                                   -- 城市代码
	  , 'CRMS'                              -- 来源系统
	  from {FDM}.F_CO_CRMS_LOAN_BIZ_ORG  T1
	  LEFT JOIN  {FDM}.F_CO_CRBS_ORG  T2    -- 作为补充
	  ON  T1.org_no = T2.biz_org_no  -- 营业机构号
	  AND    T2.start_dt <= DATE'{ETL_TX_DT}' 
      and    T2.end_dt  > DATE'{ETL_TX_DT}'  
      and    T2.record_sts_cd = '0'
	  where  T1.start_dt <=  DATE'{ETL_TX_DT}'
	  and    T1.end_dt  > DATE'{ETL_TX_DT}' 
	  AND    T2.biz_org_no IS NULL
	  ;

	  -- 机构目标临时信息表
CREATE TEMPORARY  TABLE VT_C_ORG_ORG_INF_TAGTMP(
 data_dt   date  COMMENT '数据日期'
,org_no    VARCHAR(100) DEFAULT NULL  COMMENT '机构号'
,org_name  VARCHAR(100) DEFAULT NULL  COMMENT '机构名称'
,org_abre  VARCHAR(100) DEFAULT NULL  COMMENT '机构简称'
,level_scd_org_no      VARCHAR(100) DEFAULT NULL  COMMENT '二级机构号'
,level_scd_org_name    VARCHAR(100) DEFAULT NULL  COMMENT '二级机构名称'
,level_scd_org_abre    VARCHAR(100) DEFAULT NULL  COMMENT '二级机构简称'
,level_one_org_no      VARCHAR(100) DEFAULT NULL  COMMENT '一级机构号'
,level_one_org_name    VARCHAR(100) DEFAULT NULL  COMMENT '一级机构名称'
,level_one_org_abre    VARCHAR(100) DEFAULT NULL  COMMENT '一级机构简称'
,super_org_no          VARCHAR(100) DEFAULT NULL  COMMENT '上级机构号'
,super_org_name        VARCHAR(100) DEFAULT NULL  COMMENT '上级机构名称'
,super_org_abre        VARCHAR(100) DEFAULT NULL  COMMENT '上级机构简称'
,org_lvl_cd            VARCHAR(100) DEFAULT NULL  COMMENT '机构级别代码'
,city_district_flag    VARCHAR(100) DEFAULT NULL  COMMENT '市区标志'
,area_cd               VARCHAR(100) DEFAULT NULL  COMMENT '区域代码'
,pboc_large_pay_bank_no  VARCHAR(100) DEFAULT NULL  COMMENT '人行大额支付行号'
,org_type_cd           VARCHAR(100) DEFAULT NULL  COMMENT '机构类型代码'
,org_attac_inf                  VARCHAR(100) DEFAULT NULL  COMMENT '机构附属信息'
,cty_scope_sbubrch_flag   VARCHAR(100) DEFAULT NULL  COMMENT '县域支行标志'
,sourc_sys             VARCHAR(100) DEFAULT NULL COMMENT '来源系统'
) ;




-- 按照层级插入机构信息目标表临时表 -- 总行数据
INSERT INTO VT_C_ORG_ORG_INF_TAGTMP
 (
 org_no          --  机构号      
,org_name        --  机构名称    
,org_abre        --  机构简称    
,level_scd_org_no              --  二级机构号      
,level_scd_org_name            --  二级机构名称    
,level_scd_org_abre            --  二级机构简称    
,level_one_org_no            --  一级机构号      
,level_one_org_name          --  一级机构名称    
,level_one_org_abre          --  一级机构简称    
,super_org_no                --  上级机构号      
,super_org_name              --  上级机构名称    
,super_org_abre              --  上级机构简称    
,org_lvl_cd                  --  机构级别代码    
,city_district_flag          --  市区标志        
,area_cd                     --  区域代码
,PBOC_LARGE_PAY_BANK_NO      -- 人行大额支付行号
,ORG_TYPE_CD                 -- 机构类型代码 
,sourc_sys                   -- 来源系统
)
SELECT 
         '0099'                                  --  机构号  
		,'河北银行股份有限公司总行'              --  机构名称
		,'总行'                                  --  机构简称 
        ,'0099'                                  --  二级机构号
		,'河北银行股份有限公司总行'              --  二级机构名称 
		,'总行'                                  --  二级机构简称
        ,'0099'                                  --  一级机构号
		,'河北银行股份有限公司总行'              --  一级机构名称
		,'总行'                                  --  一级机构简称
        ,''                                                  -- 上级机构号
        ,''                                                  -- 上级机构名称
        ,''                                                  -- 上级机构简称
        ,'1'                                                  -- 机构级别代码
        ,CASE WHEN city_cd='1210' THEN '1' ELSE '0' END       -- 市区标志
        ,city_cd                                              -- 区域代码
		,pboc_bank_no                                         -- 人行大额支付行号
		,org_type                                             -- 机构类型代码
		,sourc_sys                   -- 来源系统
FROM   VT_C_ORG_ORG_INF_TMP T 
WHERE  org_mgmt_lvl_cd='0' AND SUBSTR(biz_org_no,1,2)<>'99' ;

-- 按照层级插入机构信息目标表临时表:分行数据
INSERT INTO  VT_C_ORG_ORG_INF_TAGTMP
(
 org_no          --  机构号      
,org_name        --  机构名称    
,org_abre        --  机构简称      
,level_scd_org_no              --  二级机构号      
,level_scd_org_name            --  二级机构名称    
,level_scd_org_abre            --  二级机构简称    
,level_one_org_no            --  一级机构号      
,level_one_org_name          --  一级机构名称    
,level_one_org_abre          --  一级机构简称    
,super_org_no                --  上级机构号      
,super_org_name              --  上级机构名称    
,super_org_abre              --  上级机构简称    
,org_lvl_cd                  --  机构级别代码    
,city_district_flag          --  市区标志        
,area_cd                     --  区域代码 
,pboc_large_pay_bank_no      -- 人行大额支付行号
,org_type_cd                 -- 机构类型代码
,sourc_sys                   -- 来源系统         
)
SELECT 
      T1.biz_org_no                                  --  机构号
		, CASE WHEN  T1.biz_org_no LIKE '%98' AND T1.biz_org_no<>'0198' THEN T1.org_cn_name ||'营业部' 
		  ELSE T1.org_cn_name   END  --  三级机构名称 
		,CASE WHEN 	 T1.biz_org_no LIKE '%98' AND T1.biz_org_no<>'0198' THEN T1.org_abre||'营业部' 
              ELSE  T1.org_abre  	END      	 
        ,T1.biz_org_no                                  --  二级机构号
		, CASE WHEN  T1.biz_org_no LIKE '%98' AND T1.biz_org_no<>'0198' AND T1.biz_org_no<>'0098'  THEN T1.org_cn_name ||'营业部' 
		  ELSE T1.org_cn_name   END  
		,CASE WHEN 	 T1.biz_org_no LIKE '%98' AND T1.biz_org_no<>'0198' AND T1.biz_org_no<>'0098'   THEN T1.org_abre||'营业部' 
              ELSE  T1.org_abre	END      --  二级机构简称	 
        ,'0099'                                        --  一级机构号
		,'河北银行股份有限公司总行'                    --  一级机构名称
		,'总行'                                        --  一级机构简称
        ,' '                                  --  二级机构号
		,' '                                 --  二级机构名称 
        ,' '                                                  -- 上级机构简称
        ,'2'                                                  -- 机构级别代码
        ,CASE WHEN T1.city_cd='1210' THEN '1' ELSE '0' END       -- 市区标志
    ,T1.city_cd                                              -- 区域代码
		,T1.pboc_bank_no                                         -- 人行大额支付行号
		,T1.org_type                                             -- 机构类型代码
		,T1.sourc_sys                   -- 来源系统
FROM     VT_C_ORG_ORG_INF_TMP T1 		  
WHERE    t1.org_mgmt_lvl_cd='1' AND SUBSTR(t1.biz_org_no,1,2)<>'70'      -- 去掉村镇银行
;


-- 按照层级插入机构信息目标表临时表:支行数据
INSERT INTO  VT_C_ORG_ORG_INF_TAGTMP
(
 org_no          --  机构号      
,org_name        --  机构名称    
,org_abre        --  机构简称     
,level_scd_org_no              --  二级机构号      
,level_scd_org_name            --  二级机构名称    
,level_scd_org_abre            --  二级机构简称    
,level_one_org_no            --  一级机构号      
,level_one_org_name          --  一级机构名称    
,level_one_org_abre          --  一级机构简称    
,super_org_no                --  上级机构号      
,super_org_name              --  上级机构名称    
,super_org_abre              --  上级机构简称    
,org_lvl_cd                  --  机构级别代码    
,city_district_flag          --  市区标志        
,area_cd                     --  区域代码
,pboc_large_pay_bank_no      -- 人行大额支付行号
,org_type_cd                 -- 机构类型代码 
,sourc_sys                   -- 来源系统                 
)	  	  
SELECT 
         T1.biz_org_no                                  --  三级机构号
		, CASE WHEN  T1.biz_org_no LIKE '%98' AND T1.biz_org_no<>'0198' THEN T1.org_cn_name ||'营业部' 
		  ELSE T1.org_cn_name   END  --  三级机构名称 
		,CASE WHEN 	 T1.biz_org_no LIKE '%98' AND T1.biz_org_no<>'0198' AND T1.biz_org_no<>'0098' THEN T1.org_abre||'营业部' 
              ELSE  T1.org_abre 	END      --  三级机构简称 	 
        ,T2.biz_org_no                                  --  二级机构号
		,T2.org_cn_name                              --  二级机构名称 
		,T2.org_abre                      --  二级机构简称
        ,'0099'                                         --  一级机构号
		,'河北银行股份有限公司总行'                        --  一级机构名称
		,'总行'                                          --  一级机构简称
        ,''                                                  -- 上级机构号
        ,''                                                 -- 上级机构名称
        ,''                                                  -- 上级机构简称
        ,'3'                                                  -- 机构级别代码
        ,CASE WHEN T1.city_cd='1210' THEN '1' ELSE '0' END       -- 市区标志
        ,T1.city_cd                                              -- 区域代码
		,T1.pboc_bank_no                                         -- 人行大额支付行号
		,T1.org_type                                             -- 机构类型代码
		,T1.sourc_sys                   -- 来源系统         
FROM   VT_C_ORG_ORG_INF_TMP T1
LEFT JOIN (
           SELECT  biz_org_no   -- 机构号
		          ,org_cn_name  -- 机构名称
				      ,org_abre     -- 机构简称
		   from  VT_C_ORG_ORG_INF_TMP
		   where org_mgmt_lvl_cd='1' AND SUBSTR(biz_org_no,1,2)<>'70'		   
          ) T2
		  ON T1.REPORT_SUPER_ORG_NO = T2.BIZ_ORG_NO 		  
WHERE    t1.org_mgmt_lvl_cd='2' AND SUBSTR(t1.biz_org_no,1,2)<>'70'      -- 去掉村镇银行
  AND    ( T1.biz_org_no NOT  LIKE '%93'  AND  org_mgmt_lvl_cd = '2' )   -- 去除卡机构
;

  UPDATE VT_C_ORG_ORG_INF_TAGTMP A SET  A.org_abre = REPLACE(REPLACE(A.org_abre,'管理机构',''),'石家庄报表','石家庄营管部')
                                ,A.level_scd_org_name=REPLACE(A.level_scd_org_name,'管理机构','')
                                ,A.level_scd_org_abre=REPLACE(A.level_scd_org_abre,'管理机构','') ;
--                                
  UPDATE VT_C_ORG_ORG_INF_TAGTMP A SET  A.level_scd_org_name=REPLACE(A.level_scd_org_name,'石家庄报表','石家庄营业管理部')
                                    ,A.level_scd_org_abre=REPLACE(A.level_scd_org_abre,'石家庄报表','石家庄营管部') ;


"""

# -生成mapping映射- #
bsqlmapping="""


"""

if ETL_TX_DT < '20260401':
  # -bsql2算法逻辑主体实现- #

  bsql2="""

  -- 插入目标表
  INSERT INTO  {CDM}.C_ORG_ORG_INF( 
   data_dt	                     -- 日期                
  ,org_no          --  机构号      
  ,org_name        --  机构名称    
  ,org_abre        --  机构简称     
  ,level_scd_org_no                  -- 二级机构号      
  ,level_scd_org_name                -- 二级机构名称    
  ,level_scd_org_abre                -- 二级机构简称    
  ,level_one_org_no                -- 一级机构号      
  ,level_one_org_name              -- 一级机构名称    
  ,level_one_org_abre              -- 一级机构简称    
  ,super_org_no                    -- 上级机构号      
  ,super_org_name                  -- 上级机构名称    
  ,super_org_abre                  -- 上级机构简称    
  ,org_lvl_cd                      -- 机构级别代码    
  ,city_district_flag              -- 市区标志        
  ,area_cd                         -- 区域代码  
  ,pboc_large_pay_bank_no          -- 人行大额支付行号
  ,org_type_cd                     -- 机构类型代码  
  ,org_attac_inf                   -- 机构附属信息
  ,cty_scope_sbubrch_flag          -- 县域支行标志
  ,empha_cty_scope_sbubrch_flag    -- 重点县支行标志
  ,crbs_org_flag                   -- 核心机构标志     
  ,land_scope_lvl_cd               -- 地域级别代码            
  )
  select
   DATE'{ETL_TX_DT}'              -- 日期        
  ,T1.org_no          --  机构号      
  ,T1.org_name        --  机构名称    
  ,T1.org_abre        --  机构简称 
  ,T1.level_scd_org_no                  -- 二级机构号      
  ,T1.level_scd_org_name                -- 二级机构名称    
  ,T1.level_scd_org_abre                -- 二级机构简称 
  ,T1.level_one_org_no                 -- 一级机构号  
  ,T1.level_one_org_name               -- 一级机构名称
  ,T1.level_one_org_abre                -- 一级机构简称
  ,CASE  WHEN T1.org_lvl_cd  = '1' THEN ' '                     -- 总行
         WHEN T1.org_lvl_cd  = '2' THEN T2.level_one_org_no     -- 分行
  	   WHEN T1.org_lvl_cd  = '3' THEN t2.level_scd_org_no       -- 支行
  	END AS super_org_no              -- 上级机构号
  ,CASE  WHEN T1.org_lvl_cd  = '1' THEN ' '                      -- 总行
         WHEN T1.org_lvl_cd  = '2' THEN T2.level_one_org_name     -- 分行
  	   WHEN T1.org_lvl_cd  = '3' THEN t2.level_scd_org_name       -- 支行
  	END AS super_org_name              -- 上级机构名称
   ,CASE  WHEN T1.org_lvl_cd  = '1' THEN ' '                      -- 总行
         WHEN T1.org_lvl_cd  = '2' THEN T2.level_one_org_abre     -- 分行
  	   WHEN T1.org_lvl_cd  = '3' THEN t2.level_scd_org_abre       -- 支行
  	END AS super_org_abre              -- 上级机构简称  
  ,T1.org_lvl_cd                      --	机构级别代码
  ,T1.city_district_flag              -- 市区标志        
  ,T1.area_cd                         -- 区域代码
  ,NVL(T3.pboc_large_pay_bank_no,'')         -- 人行大额支付行号
  ,NVL(T3.org_type_cd,'')                     -- 机构类型代码  
  ,NVL(T3.org_attac_inf,'')                   -- 机构附属信息
  ,NVL(T3.cty_scope_sbubrch_flag,'0')          -- 县域支行标志
  ,NVL(T3.empha_cty_scope_sbubrch_flag,'0')    -- 重点县域支行标志
  ,CASE WHEN T1.sourc_sys  = 'CRBS' THEN '1' else '0' end                  -- 来源系统     
  ,NVL(T4.lvl_cd,'')  as land_scope_lvl_cd             -- 地域级别代码            
  FROM       VT_C_ORG_ORG_INF_TAGTMP T1
  INNER JOIN VT_C_ORG_ORG_INF_TAGTMP T2
  ON T1.org_no = T2.org_no 
  LEFT JOIN  {CDM}.C_ORG_ORG_ORGPARAM_CONFIG   T3
  ON trim(T1.org_no) = trim(T3.org_no) 
  LEFT JOIN {FDM}.F_CO_CRBS_ORG_PARAM_ADDIT_INF T4
  ON trim(T1.org_no) = trim(T4.BIZ_ORG_NO) 
  AND    T4.start_dt <= DATE'{ETL_TX_DT}' 
  and    T4.end_dt  > DATE'{ETL_TX_DT}'  
  ;
  
  """


else:

  bsql2="""

  -- 插入目标表
  INSERT INTO  {CDM}.C_ORG_ORG_INF(
   data_dt	                     -- 日期                
  ,org_no          --  机构号      
  ,org_name        --  机构名称    
  ,org_abre        --  机构简称     
  ,level_scd_org_no                  -- 二级机构号      
  ,level_scd_org_name                -- 二级机构名称    
  ,level_scd_org_abre                -- 二级机构简称    
  ,level_one_org_no                -- 一级机构号      
  ,level_one_org_name              -- 一级机构名称    
  ,level_one_org_abre              -- 一级机构简称    
  ,super_org_no                    -- 上级机构号      
  ,super_org_name                  -- 上级机构名称    
  ,super_org_abre                  -- 上级机构简称    
  ,org_lvl_cd                      -- 机构级别代码    
  ,city_district_flag              -- 市区标志        
  ,area_cd                         -- 区域代码  
  ,pboc_large_pay_bank_no          -- 人行大额支付行号
  ,org_type_cd                     -- 机构类型代码  
  ,org_attac_inf                   -- 机构附属信息
  ,cty_scope_sbubrch_flag          -- 县域支行标志
  ,empha_cty_scope_sbubrch_flag    -- 重点县支行标志
  ,crbs_org_flag                   -- 核心机构标志     
  ,land_scope_lvl_cd               -- 地域级别代码            
  )
  select
   DATE'{ETL_TX_DT}'              -- 日期        
  ,T1.org_no          --  机构号      
  ,T1.org_name        --  机构名称    
  ,T1.org_abre        --  机构简称 
  ,T1.level_scd_org_no                  -- 二级机构号      
  ,T1.level_scd_org_name                -- 二级机构名称    
  ,T1.level_scd_org_abre                -- 二级机构简称 
  ,T1.level_one_org_no                 -- 一级机构号  
  ,T1.level_one_org_name               -- 一级机构名称
  ,T1.level_one_org_abre                -- 一级机构简称
  ,CASE  WHEN T1.org_lvl_cd  = '1' THEN ' '                     -- 总行
         WHEN T1.org_lvl_cd  = '2' THEN T2.level_one_org_no     -- 分行
  	   WHEN T1.org_lvl_cd  = '3' THEN t2.level_scd_org_no       -- 支行
  	END AS super_org_no              -- 上级机构号
  ,CASE  WHEN T1.org_lvl_cd  = '1' THEN ' '                      -- 总行
         WHEN T1.org_lvl_cd  = '2' THEN T2.level_one_org_name     -- 分行
  	   WHEN T1.org_lvl_cd  = '3' THEN t2.level_scd_org_name       -- 支行
  	END AS super_org_name              -- 上级机构名称
   ,CASE  WHEN T1.org_lvl_cd  = '1' THEN ' '                      -- 总行
         WHEN T1.org_lvl_cd  = '2' THEN T2.level_one_org_abre     -- 分行
  	   WHEN T1.org_lvl_cd  = '3' THEN t2.level_scd_org_abre       -- 支行
  	END AS super_org_abre              -- 上级机构简称  
  ,T1.org_lvl_cd                      --	机构级别代码
  ,T1.city_district_flag              -- 市区标志        
  ,T1.area_cd                         -- 区域代码
  ,NVL(T3.pboc_large_pay_bank_no,'')         -- 人行大额支付行号
  ,NVL(T3.org_type_cd,'')                     -- 机构类型代码  
  ,NVL(T3.org_attac_inf,'')                   -- 机构附属信息
  ,NVL(T3.cty_scope_sbubrch_flag,'0')          -- 县域支行标志
  ,NVL(T3.empha_cty_scope_sbubrch_flag,'0')    -- 重点县域支行标志
  ,CASE WHEN T1.sourc_sys  = 'CRBS' THEN '1' else '0' end                  -- 来源系统     
  ,NVL(T4.wdjb,'')  as land_scope_lvl_cd             -- 地域级别代码            
  FROM       VT_C_ORG_ORG_INF_TAGTMP T1
  INNER JOIN VT_C_ORG_ORG_INF_TAGTMP T2
  ON T1.org_no = T2.org_no 
  LEFT JOIN  {CDM}.C_ORG_ORG_ORGPARAM_CONFIG   T3
  ON trim(T1.org_no) = trim(T3.org_no) 
  LEFT JOIN {ODM}.O_SBIM_BANKINST T4
  ON trim(T1.org_no) = trim(T4.jgbh) 
  AND    T4.start_dt <= DATE'{ETL_TX_DT}' 
  AND    T4.end_dt  > DATE'{ETL_TX_DT}'  
  AND    T4.del_ind <> 'D'
  ;
  
  """

# -bsql3算法逻辑主体实现- #
bsql3="""

update  {CDM}.C_ORG_ORG_INF
SET level_scd_org_name = '河北银行股份有限公司石家庄分行'
   ,level_scd_org_abre = '石家庄分行'
   ,super_org_name = '河北银行股份有限公司石家庄分行'
   ,super_org_abre = '石家庄分行'
   where  level_scd_org_no ='0199';

update  {CDM}.C_ORG_ORG_INF
SET org_name = '河北银行股份有限公司石家庄分行营业部'
,org_abre = '石家庄分行营业部'
,super_org_name = '河北银行股份有限公司石家庄分行'
,super_org_abre = '石家庄分行'
where  org_no ='0126';	  




update  {CDM}.C_ORG_ORG_INF
SET   org_abre = '总行营业部'
     ,level_scd_org_abre = '总行营业部'
WHERE  org_no = '0098'
;

update  {CDM}.C_ORG_ORG_INF
SET  org_abre = '石家庄分行'
     ,level_scd_org_abre = '石家庄分行'
WHERE  org_no = '0199'
;


UPDATE {CDM}.C_ORG_ORG_INF
SET super_org_name='河北银行股份有限公司总行',super_org_abre='总行'
WHERE org_no='0199'
;
  
update  {CDM}.C_ORG_ORG_INF set cty_scope_sbubrch_flag  = '否'
where cty_scope_sbubrch_flag IS NULL;

"""

# -- 5.主键唯一性校验SQL -- #
bsql_check_pk = """
-- 检查主键(data_dt, org_no)是否唯一
SELECT data_dt,org_no,COUNT(*) AS cnt
FROM {CDM}.C_ORG_ORG_INF
WHERE data_dt = DATE '{ETL_TX_DT}'
GROUP BY data_dt,org_no
HAVING COUNT(*) > 1;
"""

# # -- 主调程序-- #
# bsql=bsql1+bsqlmapping+bsql2+bsql3
# bformatsql = bsql.format_map(vars())
# ret_code = execute_gbase_sql(bformatsql)
# if ret_code :
    # log.error(" ERROR - ERROR .")
    # exit(-1)
# else:
    # log.info("SUCESS . ") 

# -- 主调程序-- #
def main():
	bsql=bsql1+bsqlmapping+bsql2+bsql3
	bformatsql = bsql.format(**globals())
	log.info("开始执行SQL脚本...")
	ret_code = execute_gbase_sql(bformatsql)
	if ret_code:
		log.error("ERROR - SQL execution failed.")
		exit(-1)
    # -- 6.主键唯一性校验逻辑 -- #
	log.info("SQL脚本执行成功，开始执行主键唯一性校验...")
	bchecksql = bsql_check_pk.format(**globals())
	result = execute_gbase_sql_with_result(bchecksql)
	if result is not None and isinstance(result, str):
       # 1. 清洗result：去除换行、空格、分隔线，统一转小写
		cleaned_result = result.strip().lower().replace('-', '').replace('\n', '').replace(' ', '')
       # 2. 模糊匹配：只要包含"emptyset"就判定为无重复（兼容分隔线、空格、换行）
		if "emptyset" in cleaned_result:
			log.info("主键唯一性校验通过，无重复记录。")
		else:
			log.error("主键(data_dt, org_no)存在重复记录，请检查数据！")
			exit(-1)
	else:
		log.error("主键校验SQL执行失败，请检查数据库连接或SQL语句。")
		exit(-1)
	log.info("SUCCESS . ")
if __name__ == "__main__":
	main()