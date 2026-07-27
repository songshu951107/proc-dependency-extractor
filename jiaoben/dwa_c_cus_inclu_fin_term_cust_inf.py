# *****************************************头部注释模块**************************************
#脚本名称：dwa_c_cus_inclu_fin_term_cust_inf.py
#功能说明：普惠金融终端客户信息
#更新方式：每日全量
#更新对象：CDM.C_CUS_INCLU_FIN_TERM_CUST_INF
#创 建 人： 
#创建日期：2024-11-22
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
I_MB_DT = etl.I_MB_DT    #月初日期
I_LME_DT = etl.I_LME_DT    #上月末日期

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

#print(I_MB_DT);
#print(I_LME_DT);

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
        gsql_str_p = f"set -o pipefail; gccli -h {dwa_host} -P {dwa_port} -u {dwa_username} -p\"{dwa_password}\" -D {dwa_db_cdm} -v -v "
        p = subprocess.Popen(gsql_str_p, shell=True, stdin=subprocess.PIPE, stdout=sys.stdout, stderr=sys.stdout,close_fds=True,universal_newlines=True, bufsize=1)
        output = p.communicate(sql)
        return p.returncode
    except:
        log.exception("Execute [%s] failed.%s" % (gsql_str_p,output.decode('utf-8')))
        return -1

@atexit.register
def __exit__():
    """print a log when exit"""
    log.info("END\n")



# -- 4.执行sql内容-- #
# -- bsql1支持重跑sql及临时表创建-- #
bsql1="""

/*Step1:创建目标表之前先清除目标表当天数据*/
DELETE FROM {CDM}.C_CUS_INCLU_FIN_TERM_CUST_INF    -- 普惠金融终端客户信息
 WHERE data_dt = DATE '{ETL_TX_DT}'
 ;
  

/*Step2:向主表插入数据*/
 CREATE TEMPORARY TABLE {CDM}.VT_C_CUS_INCLU_FIN_TERM_CUST_INF_01
(
   card_no             VARCHAR(100)
  ,cust_community      VARCHAR(60)
  ,cust_chan_cd        VARCHAR(60)
  ,devic_imei          VARCHAR(100)
  ,devic_sn            VARCHAR(100)
  ,cust_manag_no       VARCHAR(100) 
  ,sts_cd              VARCHAR(25)
  ,activa_tm           DATETIME
  ,appl_tm             DATETIME
  ,deposit_type_cd     VARCHAR(25)
  ,proc_cmg_card_flag  VARCHAR(10)
  ,cmg_card_mb_no      VARCHAR(60)
  ,cmg_card_pkg        VARCHAR(255)
  ,out_list_tm         DATETIME
  ,devic_no            VARCHAR(60)
  ,identt_card_no      VARCHAR(64)
  ,cust_sourc_cd       VARCHAR(64)
  ,cust_type_cd        VARCHAR(64)
  ,rn                  INTEGER
)
;
-- 临时表2 设备的最初激活时间
CREATE TEMPORARY TABLE {CDM}.VT_C_CUS_INCLU_FIN_TERM_CUST_INF_02
(
devic_imei VARCHAR(100)
,devic_original_activa_tm DATETIME
,RN VARCHAR(10)
);


-- 临时表3 客户最初激活时间
CREATE TEMPORARY TABLE {CDM}.VT_C_CUS_INCLU_FIN_TERM_CUST_INF_03
(
cust_no VARCHAR(100)
,identt_card_no VARCHAR(100)
,cust_original_activa_tm DATETIME
);

-- 临时表4 客户经理机构
CREATE TEMPORARY TABLE {CDM}.VT_C_CUS_INCLU_FIN_TERM_CUST_INF_04
(
  user_name         VARCHAR(60)
  ,user_nickname    VARCHAR(300)
  ,biz_org_id       VARCHAR(100)
  ,biz_org_name     VARCHAR(100)
  ,user_vld_flg     VARCHAR(10)
)
;   
"""

# -生成mapping映射- #
bsqlmapping="""


"""

# -bsql2算法逻辑主体实现- #
bsql2="""

-- 临时表-客户申领设备表，按照证件号取创建时间最新的一条
INSERT INTO {CDM}.VT_C_CUS_INCLU_FIN_TERM_CUST_INF_01
  SELECT
          card_no       
         ,cust_community
         ,cust_chan_cd  
         ,devic_imei    
         ,devic_sn      
         ,cust_manag_no      
         ,sts_cd             
         ,activa_tm          
         ,appl_tm            
	     ,deposit_type_cd    
	     ,proc_cmg_card_flag 
         ,cmg_card_mb_no     
         ,cmg_card_pkg       
		 ,out_list_tm        
		 ,devic_no           
		 ,identt_card_no     
		 ,cust_sourc_cd      
		 ,cust_type_cd       
         , ROW_NUMBER() OVER (PARTITION BY identt_card_no ORDER BY create_tm DESC) RANK
  FROM {FDM}.F_RE_ITCM_APPL_DEVIC_INF
 WHERE start_dt <= DATE '{ETL_TX_DT}' 
   AND end_dt > DATE '{ETL_TX_DT}'
  ;  
  -- 上月末设备最初激活时间
INSERT INTO {CDM}.VT_C_CUS_INCLU_FIN_TERM_CUST_INF_02
SELECT devic_imei
	  ,devic_original_activa_tm
      ,ROW_NUMBER() OVER (PARTITION BY devic_imei ORDER BY activa_tm ASC) rn
 FROM {CDM}.C_CUS_INCLU_FIN_TERM_CUST_INF
WHERE DATA_DT = DATE '{I_LME_DT}'
  AND NVL(devic_imei,'') <>''
  AND NVL(devic_original_activa_tm,'')<>''
  AND vld_flg='1'
  ; 
  -- 月初到统计日的设备最初激活时间
INSERT INTO {CDM}.VT_C_CUS_INCLU_FIN_TERM_CUST_INF_02
SELECT T1.devic_imei,MIN(T1.min_activa_tm) AS devic_original_activa_tm,'1'
  FROM 
      (
       SELECT T1.devic_imei
             ,MIN(T1.activa_tm) AS min_activa_tm
         FROM {FDM}.F_RE_ITCM_APPL_DEVIC_INF T1 
		 LEFT JOIN {FDM}.F_RE_ITDM_DEVIC_INF T4 -- 设备信息
           ON T1.devic_imei = T4.devic_imei_1  
          AND T4.wareh_no = '93' -- 剔除 仓库编号93
          AND T4.start_dt<=DATE '{ETL_TX_DT}' AND T4.end_dt> DATE '{ETL_TX_DT}'
        WHERE T1.start_dt <= DATE '{ETL_TX_DT}' 
          AND T1.end_dt > DATE '{I_MB_DT}'
		  AND NVL(T1.devic_imei,'')<>''
		  AND NVL(T1.activa_tm,'')<>''
		  AND T4.devic_imei_1 IS NULL
		GROUP BY devic_imei
      )T1 
  LEFT JOIN {CDM}.VT_C_CUS_INCLU_FIN_TERM_CUST_INF_02 T2 
    ON T1.devic_imei = T2.devic_imei 
   AND T2.RN=1
 WHERE T2.devic_imei IS NULL
 GROUP BY T1.devic_imei
  ;
  
  -- 上月末客户最初激活时间
INSERT INTO {CDM}.VT_C_CUS_INCLU_FIN_TERM_CUST_INF_03
SELECT cust_no,iden_no,cust_original_activa_tm
  FROM {CDM}.C_CUS_INCLU_FIN_TERM_CUST_INF
 WHERE data_dt=DATE '{I_LME_DT}'
   AND NVL(cust_original_activa_tm,'')<>''
   AND vld_flg='1'   
;
  -- 月初到统计日客户最初激活时间
INSERT INTO {CDM}.VT_C_CUS_INCLU_FIN_TERM_CUST_INF_03
SELECT T1.cust_no,T1.identt_card_no,T1.cust_original_activa_tm
  FROM 
     (
      SELECT cust_no,identt_card_no,MIN(cust_original_activa_tm) cust_original_activa_tm
        FROM 
            (
             SELECT COALESCE(B.cust_no,C.main_cust_no) AS cust_no
			        ,A.identt_card_no
                    ,MAX(COALESCE(B.cn_name,C.cust_acct_name)) AS cust_name
                    ,MIN(min_activa_tm) AS cust_original_activa_tm				   
      	     FROM 
      	         ( 	  
      	          SELECT T1.card_no 
				         ,T1.identt_card_no
                         ,MIN(T1.activa_tm) AS min_activa_tm
                     FROM {FDM}.F_RE_ITCM_APPL_DEVIC_INF T1 
					 LEFT JOIN {FDM}.F_RE_ITDM_DEVIC_INF T4 -- 设备信息
                       ON T1.devic_imei = T4.devic_imei_1  
                      AND T4.wareh_no = '93' -- 剔除 仓库编号93
                      AND T4.start_dt<=DATE '{ETL_TX_DT}' AND T4.end_dt> DATE '{ETL_TX_DT}'
                    WHERE T1.start_dt <= DATE '{ETL_TX_DT}' 
                      AND T1.end_dt > DATE '{I_MB_DT}'
					  AND NVL(T1.card_no,'')<>''
					  AND NVL(T1.activa_tm,'')<>''
					  AND T4.devic_imei_1 IS NULL
					GROUP BY card_no,identt_card_no
      	         )A 
      	     LEFT JOIN {FDM}.F_MD_CRBS_ISSUED_INF B -- 核心已经发卡表
                    ON A.card_no = B.card_no 
                AND B.start_dt <= DATE '{ETL_TX_DT}'
                AND B.end_dt > DATE '{ETL_TX_DT}' 
      	     LEFT JOIN {FDM}.F_AC_CRBS_CUST_ACCT_NO C -- 核心客户账户表
                 ON A.card_no=C.cust_acct_no 
                AND C.start_dt<=DATE '{ETL_TX_DT}' AND C.end_dt> DATE '{ETL_TX_DT}'	
              GROUP BY COALESCE(B.cust_no,C.main_cust_no),A.identt_card_no    
            )T GROUP BY cust_no,identt_card_no
     )T1 
 LEFT JOIN {CDM}.VT_C_CUS_INCLU_FIN_TERM_CUST_INF_03 T2 
   ON T1.cust_no = T2.cust_no AND T1.identt_card_no = T2.identt_card_no 
WHERE T2.cust_no IS NULL  
;
-- 员工部门信息插入临时表
INSERT INTO {CDM}.VT_C_CUS_INCLU_FIN_TERM_CUST_INF_04
SELECT T1.user_name
      ,T1.user_nickname
      ,T2.biz_org_id
      ,T2.biz_org_name 
      ,T1.user_vld_flg	  
 FROM 
     (
	 SELECT user_name,user_nickname,org_id
	    ,CASE WHEN T.lock_flag_cd IN ('0','1','2','3','4') THEN '1' ELSE '2' END AS user_vld_flg 
	    ,ROW_NUMBER()OVER(PARTITION BY T.USER_NAME ORDER BY start_dt desc,create_tm desc ,modif_tm desc) AS RN
	   FROM {FDM}.F_PT_SJMH_USER_INF T
	  WHERE T.start_dt <= DATE '{ETL_TX_DT}' 
        AND T.end_dt > DATE '{ETL_TX_DT}' 
       -- AND T.lock_flag_cd IN ('0','1','2','3','4')
	 )T1
  LEFT JOIN {FDM}.F_CO_SJMH_ORG_INF T2
	ON T1.org_id = T2.org_id 
   AND T2.start_dt <= DATE '{ETL_TX_DT}' 
   AND T2.end_dt > DATE '{ETL_TX_DT}' 
 WHERE T1.rn=1
 ;
   
  -- 结果表插入数据
INSERT INTO {CDM}.C_CUS_INCLU_FIN_TERM_CUST_INF 
SELECT DATE '{ETL_TX_DT}' AS data_dt
      ,COALESCE(T3.cust_no,T10.main_cust_no) AS cust_no
      ,T1.devic_imei -- 设备号
      ,T1.card_no AS cust_acct_no   -- 卡号
	  ,'10100' AS iden_type_cd 
	  ,T1.identt_card_no AS iden_no   
      ,COALESCE(T3.cn_name,T10.cust_acct_name) AS cust_name
      ,T1.cust_manag_no AS cust_manag_wage_no -- 客户经理工资号
      ,COALESCE(T2.user_nickname,T21.user_nickname) AS cust_manag_name  
	  ,COALESCE(T2.biz_org_id,T21.biz_org_id) AS devic_open_mach_org_no
      ,COALESCE(T2.biz_org_name,T21.biz_org_name) AS devic_open_mach_org_name
      ,T1.cust_chan_cd  -- 客户渠道代码
      ,T1.cust_community   AS custgrp_class_cd -- 客群分类
	  ,T1.deposit_type_cd
      ,T1.devic_sn
      ,T1.sts_cd AS devic_sts_cd
	  ,DECODE(T1.proc_cmg_card_flag,'Y','1','N','0',T1.proc_cmg_card_flag) AS proc_cmg_card_flag
      ,T1.cmg_card_mb_no
      ,T1.cmg_card_pkg
      ,T1.appl_tm
      ,T1.activa_tm
      ,T1.out_list_tm
	  ,NVL(T5.cust_original_activa_tm , T1.activa_tm) AS cust_original_activa_tm
      ,NVL(T8.devic_original_activa_tm, T1.activa_tm) AS devic_original_activa_tm
      -- ,'' AS share_prft_cd
      -- ,NULL AS share_prft_begin_tm
      -- ,NULL AS share_prft_end_tm

      ,T1.cust_sourc_cd
      ,T1.cust_type_cd
      ,CASE WHEN T4.devic_imei_1 IS NULL THEN '1' ELSE '0' END AS VLD_FLG 
 FROM {CDM}.VT_C_CUS_INCLU_FIN_TERM_CUST_INF_01 T1 -- 客户申请设备
 LEFT JOIN {CDM}.VT_C_CUS_INCLU_FIN_TERM_CUST_INF_04 T2  -- 用户机构 
   ON T1.cust_manag_no = T2.user_name
  AND T2.user_vld_flg = '1'
 LEFT JOIN {CDM}.VT_C_CUS_INCLU_FIN_TERM_CUST_INF_04 T21  -- 用户机构 
   ON T1.cust_manag_no = T21.user_name
  AND T21.user_vld_flg = '2'
 LEFT JOIN {FDM}.F_MD_CRBS_ISSUED_INF T3 -- 核心已经发卡表
   ON T1.card_no = T3.card_no 
  AND T3.start_dt <= DATE '{ETL_TX_DT}'
  AND T3.end_dt > DATE '{ETL_TX_DT}' 
  AND T3.card_no<>''
 LEFT JOIN {FDM}.F_RE_ITDM_DEVIC_INF T4 -- 设备信息
   ON T1.devic_imei = T4.devic_imei_1  
  AND T4.wareh_no = '93' -- 剔除 仓库编号93
  AND T4.start_dt<=DATE '{ETL_TX_DT}' AND T4.end_dt> DATE '{ETL_TX_DT}'
 LEFT JOIN {CDM}.VT_C_CUS_INCLU_FIN_TERM_CUST_INF_03 T5 -- 客户最早激活
   ON T3.cust_no = T5.cust_no AND T1.identt_card_no = T5.identt_card_no
 LEFT JOIN {CDM}.VT_C_CUS_INCLU_FIN_TERM_CUST_INF_02 T8 -- 设备最早激活
   ON T1.devic_imei = T8.devic_imei  AND T8.devic_imei <> '' AND T8.rn = 1
 LEFT JOIN {FDM}.F_AC_CRBS_CUST_ACCT_NO T10 -- 核心客户账户表
   ON T1.card_no=T10.cust_acct_no 
  AND T10.start_dt<=DATE '{ETL_TX_DT}' AND T10.end_dt> DATE '{ETL_TX_DT}'	  
WHERE T1.rn = 1 
;

"""

# -- 主调程序-- #
bsql=bsql1+bsqlmapping+bsql2
bformatsql = bsql.format_map(vars())
ret_code = execute_gbase_sql(bformatsql)
if ret_code :
    log.error(" ERROR - ERROR .")
    exit(-1)
else:
    log.info("SUCESS . ")
