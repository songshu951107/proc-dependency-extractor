# **************************************************头部注释模块********************************************
#脚本名称：dwa_pid_a_gfin_mect_dtl_tab.py
#功能说明：收单商户明细表
#更新方式： 
#更新对象：PID.A_GFIN_MECT_DTL_TAB
#创 建 人： 
#创建日期：2026-03-29
#运行周期：日
#备    注：收单商户明细表
#维护信息：
# *******************************************************************************************
# -*- coding: utf-8 -*-
import atexit
from datetime import datetime
import sys
import os
import subprocess
import re

# --  1.验证输入参数是否正确--  #
if len(sys.argv) == 2:
    ETL_TX_DT = sys.argv[1]
else:
    print("usage :python xxx.py YYYYMMDD")
    exit(-1)

ETL_HOME = os.path.abspath(os.environ['ETL_HOME'])
if not ETL_HOME:
    print("Error:you must define a ETL_HOME before import etl module")
    exit(-1)

# --  2.日期及变量初始化--  #
# --  自定义模块--  #
from config import db
from log import Log
import etl
MAX_DATE = etl.MAX_DATE
MIN_DATE = etl.MIN_DATE
NULL_DATE = etl.NULL_DATE
LEG_DATE = etl.LEG_DATE
I_MB_DT = etl.I_MB_DT    #月初日期
I_ME_DT = etl.I_ME_DT    #月末日期
I_QB_DT = etl.I_QB_DT    #季初日期
I_QE_DT = etl.I_QE_DT    #季末日期
I_HB_DT = etl.I_HB_DT    #半年初日期
I_HE_DT = etl.I_HE_DT    #半年末日期
I_YB_DT = etl.I_YB_DT    #年初日期
I_YE_DT = etl.I_YE_DT    #年末日期
I_LAST_DT = etl.I_LAST_DT    #上日日期
I_NEXT_DT = etl.I_NEXT_DT    #下日日期
I_LMB_DT = etl.I_LMB_DT    #上月初日期
I_LME_DT = etl.I_LME_DT    #上月末日期
I_LQB_DT = etl.I_LQB_DT    #上季初日期
I_LQE_DT = etl.I_LQE_DT    #上季末日期
I_NMOM_DT = etl.I_NMOM_DT    #下月同期日期
I_NYOY_DT = etl.I_NYOY_DT    #明年同期日期
I_LYOY_DT = etl.I_LYOY_DT    #去年同期日期
I_LMOM_DT = etl.I_LMOM_DT    #上月同期日期
I_LQOQ_DT = etl.I_LQOQ_DT    #上季同期日期
I_MATTY_DT = etl.I_MATTY_DT    #滚动年开始累积日期
I_MATTH_DT = etl.I_MATTH_DT    #滚动半年年开始累积日期
I_MQTTQ_DT = etl.I_MQTTQ_DT    #滚动季开始累积日期
I_MMTTM_DT = etl.I_MMTTM_DT    #滚动月开始累积日期
MMTTM_DAYS = etl.MMTTM_DAYS    #滚动月天数
MQTTQ_DAYS = etl.MQTTQ_DAYS    #滚动季天数
MATTY_DAYS = etl.MATTY_DAYS    #滚动年天数
MATTH_DAYS = etl.MATTH_DAYS    #滚动半年天数
V_PRE_YB_DT = etl.V_PRE_YB_DT    #上年末日期

V_PRE_3M = etl.THREE_MONTH_AGO  #三个月前
V_12M_BEGIN = etl.LAST_YEAR  #去年同期

dwa_host = etl.dwa_host
dwa_port = etl.dwa_port
dwa_db_fdm = etl.dwa_db_fdm
dwa_db_odm = etl.dwa_db_odm
dwa_db_cdm = etl.dwa_db_cdm
dwa_db_pid = etl.dwa_db_pid
dwa_username = etl.dwa_usr_pid
dwa_password = etl.dwa_pwd_pid

FDM = etl.dwa_db_fdm
ODM = etl.dwa_db_odm
CDM = etl.dwa_db_cdm
PID = etl.dwa_db_pid


#--  3.初始化日志--  #
cur_path = os.path.dirname(os.path.realpath(__file__))
subdir = cur_path.split('/')[-1]                 #根据脚本绝对路径中的最后一级目录作为日志的子目录
log = Log('dlk_etl', ETL_TX_DT, subdir)


"""
  函数名称:execute_gbase_sql
  参数(2个): 管道句柄 运行sql
  功能: 将sql语句发送到gbase去执行
"""
def execute_gbase_sql(sql):
    try:
        prefix_sql = "\n"
        suffix_sql = "\nquit\n"
        sql = prefix_sql + sql + suffix_sql
        gsql_str_p = "gccli -h %s -P %s -u %s -p\'%s\' -D %s -v -v"%(dwa_host, dwa_port, dwa_username, dwa_password, dwa_db_pid)
        p = subprocess.Popen(gsql_str_p, shell=True, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        sql_byte = sql.encode(encoding="utf-8")
        output = p.communicate(sql_byte)[0]
        log.info(output.decode('utf-8'))
        return p.returncode
    except:
        log.exception("Execute [%s] failed.%s" % (gsql_str_p,output.decode('utf-8')))
        return -1

@atexit.register
def __exit__():
    """print a log when exit"""
    log.info("END\n")


# -- 4.执行sql内容 -- #
bsql="""
/*Step0:创建目标表之前先清除目标表当天数据*/
DELETE FROM {PID}.A_GFIN_MECT_DTL_TAB
WHERE DATA_DT = DATE '{ETL_TX_DT}';


 /*Step1:创建加工过程临时表1pos交易数据*/
CREATE TEMPORARY TABLE  {PID}.VT_A_GFIN_MECT_DTL_TAB_01
( MERCH_ID          varchar(40)         
			,JY_COUNT_N   INT 	  
			,JY_AMT_N     DECIMAL(18,2)   	  
			,JY_COUNT_3M   INT  	  
			,JY_AMT_3M 	   DECIMAL(18,2)
            ) DISTRIBUTED BY (
     'MERCH_ID'
)
comment  'pos交易数据'; 

/*Step2:创建加工过程临时表2聚合交易数据*/
CREATE TEMPORARY TABLE  {PID}.VT_A_GFIN_MECT_DTL_TAB_02
( MERCH_ID          varchar(40)         
			,JY_COUNT_N   INT 	  
			,JY_AMT_N     DECIMAL(18,2)   	  
			,JY_COUNT_3M   INT  	  
			,JY_AMT_3M 	   DECIMAL(18,2)
            ) DISTRIBUTED BY (
     'MERCH_ID'
     )
comment  '聚合交易数据'; 

/*Step1_1:创建加工过程临时表年月汇总pos交易汇总数据*/
CREATE TEMPORARY TABLE  {PID}.VT_A_GFIN_MECT_DTL_TAB_SUM_01
( MERCH_ID          varchar(40)         
			,JY_COUNT_Y   INT 	  
			,JY_AMT_Y     DECIMAL(18,2)   	  
			,JY_COUNT_M   INT  	  
			,JY_AMT_M 	   DECIMAL(18,2)
            ,jy_count_3m_pos  INT
            ,jy_amt_3m_pos   DECIMAL(18,2)
            ) DISTRIBUTED BY (
     'MERCH_ID'
)
comment  'pos交易数据'; 

/*Step2_1:创建加工过程临时表月汇总聚合交易数据*/
CREATE TEMPORARY TABLE  {PID}.VT_A_GFIN_MECT_DTL_TAB_SUM_02
( MERCH_ID          varchar(40)         
			,JY_COUNT_Y   INT 	  
			,JY_AMT_Y     DECIMAL(18,2)   	  
			,JY_COUNT_M   INT  	  
			,JY_AMT_M 	   DECIMAL(18,2)
            ) DISTRIBUTED BY (
     'MERCH_ID'
     )
comment  '聚合交易数据'; 

/*Step3:创建加工过程临时表3商户信息*/
CREATE TEMPORARY TABLE  {PID}.VT_A_GFIN_MECT_DTL_TAB_03
(
             MERCH_ID varchar(40)
            ,MERCH_NAME varchar(100)
			,IND_COM_LNS  varchar(100)
            ,MERCH_XZ varchar(100)
            ,MERCH_LX varchar(100)
            ,MOBLIE_MERCH_ID varchar(100)
            ,MOBILE_ACCOUNT varchar(100)
            ,MOBILE_ACCOUNT_ZT varchar(100)
            ,START_DT varchar(100)
            ,BRCH_ID varchar(30)
            ,BRCH_SPL_NM varchar(100)
            ,ORG_ID varchar(30)
            ,ORG_SPL_NM varchar(100)
            ,MGR_NO varchar(50)
            ,MGR_NAME varchar(100)
            ,CUST_ACCT_NO varchar(100)
            ,CUST_ACCT_NO_ZT varchar(100)
            ,CUST_NO varchar(80)
            ,CUST_FLG varchar(10)
            ,TERM_COUNT varchar(100)
            ,c_eb_flg varchar(10)
			,mect_vld_flg  varchar(10)
            ,new_mect_flag  varchar(10)
            ) DISTRIBUTED BY (
     'MERCH_ID'
)
comment  '商户信息'; 


/*Step4:创建加工过程临时表4结算账号信息*/
CREATE TEMPORARY TABLE  {PID}.VT_A_GFIN_MECT_DTL_TAB_04
(
             CUST_ACCT_NO varchar(100)
            ,ORG_ID varchar(100)
            ,ORG_SPL_NM varchar(100)
            ,M_AVG DECIMAL(18,2)
			      ,Y_AVG DECIMAL(18,2)  
            ) DISTRIBUTED BY (
     'CUST_ACCT_NO'
)
comment  '结算账号信息'; 

/*Step5:创建加工过程临时表5客户日均数据(对私)*/
CREATE TEMPORARY TABLE  {PID}.VT_A_GFIN_MECT_DTL_TAB_05
(
             CUST_NO varchar(80)
            ,BAL DECIMAL(18,2)
			,M_AVG DECIMAL(18,2)
            ,Y_AVG DECIMAL(18,2)
			,kh_hq_bal  DECIMAL(18,2)
            ,HQ_M_AVG DECIMAL(18,2)
            ,HQ_Y_AVG DECIMAL(18,2)
			      ,YEAY_Y_AVG1 DECIMAL(18,2)
			      ,YEAY_M_AVG1 DECIMAL(18,2)    
			      ,kh_rm_avg   DECIMAL(18,2)  
            ) DISTRIBUTED BY (
     'CUST_NO'
)
comment  '客户日均数据(对私)'; 

/*Step6:创建加工过程临时表6客户日均数据(对公)*/
CREATE TEMPORARY TABLE  {PID}.VT_A_GFIN_MECT_DTL_TAB_06
(
             CUST_ACCT_NO varchar(80)
            ,BAL DECIMAL(18,2)
			,M_AVG DECIMAL(18,2)
            ,Y_AVG DECIMAL(18,2)
			,hq_bal  DECIMAL(18,2)
            ,HQ_M_AVG DECIMAL(18,2)
            ,HQ_Y_AVG DECIMAL(18,2)
			      ,YEAY_Y_AVG1 DECIMAL(18,2)
			      ,YEAY_M_AVG1 DECIMAL(18,2)  
			      ,rm_avg     DECIMAL(18,2)
            ) DISTRIBUTED BY (
     'CUST_ACCT_NO'
)
comment  '客户日均数据(对公)'; 

/*Step7:创建加工过程临时表7交易数据*/
CREATE TEMPORARY TABLE  {PID}.VT_A_GFIN_MECT_DTL_TAB_07
( MERCH_ID          varchar(40)         
			,JY_COUNT_N   INT 	  
			,JY_AMT_N     DECIMAL(18,2)   	  
			,JY_COUNT_3M   INT  	  
			,JY_AMT_3M 	   DECIMAL(18,2)
            ) DISTRIBUTED BY (
     'MERCH_ID'
)
comment  '交易数据'; 

/*Step8:创建加工过程临时表8交易达标数据*/
CREATE TEMPORARY TABLE  {PID}.VT_A_GFIN_MECT_DTL_TAB_08
( MERCH_ID          varchar(40)         
			,JY_COUNT_N   INT 	  
			,JY_AMT_N     DECIMAL(18,2)   	  
			,JY_COUNT_3M   INT  	  
			,JY_AMT_3M 	   DECIMAL(18,2)
			,RCH_FLG varchar(40)
            ) DISTRIBUTED BY (
     'MERCH_ID'
)
comment  '交易达标数据'; 


/*Step1:插入pos交易数据*/ 
INSERT INTO  {PID}.VT_A_GFIN_MECT_DTL_TAB_01
	  SELECT MERCH_ID
	        ,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_YB_DT}' THEN TRAN_AMT END)
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_YB_DT}'  THEN TRAN_AMT END)/100
			,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date(TO_CHAR(TRUNC(ADD_MONTHS( DATE('{I_MB_DT}'),-2),'MM'),'YYYYMMDD')) THEN TRAN_AMT END)
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date(TO_CHAR(TRUNC(ADD_MONTHS( DATE('{I_MB_DT}'),-2),'MM'),'YYYYMMDD')) THEN TRAN_AMT END)/100 
	  FROM odm.O_POSP_T_CAP_SERIAL
	  WHERE etl_tx_dt <= '{ETL_TX_DT}'
	  AND to_date (CLEAR_DATE,'YYYYMMDD') >= date'{V_12M_BEGIN}'
	  GROUP BY MERCH_ID
    union all
	  SELECT MERCH_ID
	        ,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_YB_DT}' THEN TRAN_AMT END)
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_YB_DT}'  THEN TRAN_AMT END)/100
			,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date(TO_CHAR(TRUNC(ADD_MONTHS( DATE('{I_MB_DT}'),-2),'MM'),'YYYYMMDD')) THEN TRAN_AMT END)
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date(TO_CHAR(TRUNC(ADD_MONTHS( DATE('{I_MB_DT}'),-2),'MM'),'YYYYMMDD')) THEN TRAN_AMT END)/100 
	  FROM odm.O_POSP_T_CAP_SERIAL_T0
	  WHERE etl_tx_dt <='{ETL_TX_DT}'
	  AND to_date (CLEAR_DATE,'YYYYMMDD') >= date'{V_12M_BEGIN}'
	  GROUP BY MERCH_ID
    union all
	  SELECT MERCH_ID
	        ,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_YB_DT}' THEN TRAN_AMT END)
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_YB_DT}'  THEN TRAN_AMT END)/100
			,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date(TO_CHAR(TRUNC(ADD_MONTHS( DATE('{I_MB_DT}'),-2),'MM'),'YYYYMMDD')) THEN TRAN_AMT END)
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date(TO_CHAR(TRUNC(ADD_MONTHS( DATE('{I_MB_DT}'),-2),'MM'),'YYYYMMDD')) THEN TRAN_AMT END)/100  
	  FROM odm.O_POSP_T_15K_SERIAL
	  WHERE etl_tx_dt <='{ETL_TX_DT}'
	  AND to_date (CLEAR_DATE,'YYYYMMDD') >= date'{V_12M_BEGIN}'
	  GROUP BY MERCH_ID  
    union all
	  SELECT MERCH_ID
	        ,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_YB_DT}' THEN TRAN_AMT END)
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_YB_DT}'  THEN TRAN_AMT END)/100
			,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date(TO_CHAR(TRUNC(ADD_MONTHS( DATE('{I_MB_DT}'),-2),'MM'),'YYYYMMDD')) THEN TRAN_AMT END)
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date(TO_CHAR(TRUNC(ADD_MONTHS( DATE('{I_MB_DT}'),-2),'MM'),'YYYYMMDD')) THEN TRAN_AMT END)/100 
	  FROM odm.O_POSP_T_15K_SERIAL_T0
	  WHERE etl_tx_dt <='{ETL_TX_DT}'
	  AND to_date (CLEAR_DATE,'YYYYMMDD') >= date'{V_12M_BEGIN}'
	  GROUP BY MERCH_ID  
;
/*Step2:插入聚合支付交易数据*/  
INSERT INTO  {PID}.VT_A_GFIN_MECT_DTL_TAB_02
	  SELECT MERCH_ID
			    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD') >= date'{I_YB_DT}' THEN ALIPAY_SUCCESS_COUNT+WEIXIN_SUCCESS_COUNT END) 
			    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD') >= date'{I_YB_DT}' THEN ALIPAY_SUCCESS_FEE+WEIXIN_SUCCESS_FEE END)/100
				,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD') >= date(TO_CHAR(TRUNC(ADD_MONTHS( DATE('{I_MB_DT}'),-2),'MM'),'YYYYMMDD')) THEN ALIPAY_SUCCESS_COUNT+WEIXIN_SUCCESS_COUNT END) 
			    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD') >= date(TO_CHAR(TRUNC(ADD_MONTHS( DATE('{I_MB_DT}'),-2),'MM'),'YYYYMMDD')) THEN ALIPAY_SUCCESS_FEE+WEIXIN_SUCCESS_FEE END)/100
	  FROM odm.o_posp_t_merch_mobileday
	  WHERE etl_tx_dt <='{ETL_TX_DT}'
	  AND to_date (CLEAR_DATE,'YYYYMMDD') >= date'{V_12M_BEGIN}'
	  AND (ALIPAY_SUCCESS_COUNT <> 0 OR WEIXIN_SUCCESS_COUNT <> 0)
	  GROUP BY MERCH_ID
;

/*Step1_1:插入年月汇总pos交易数据*/
INSERT INTO  {PID}.VT_A_GFIN_MECT_DTL_TAB_SUM_01  
	select MERCH_ID,sum(jy_count_y_pos),sum(jy_amt_y_pos),sum(jy_count_m_pos),sum(jy_amt_m_pos),sum(jy_count_3m_pos),sum(jy_amt_3m_pos)
from (
	  SELECT MERCH_ID
	        ,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_YB_DT}' THEN TRAN_AMT END)  as jy_count_y_pos
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_YB_DT}'  THEN TRAN_AMT END)/100 as jy_amt_y_pos
			,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_MB_DT}' THEN TRAN_AMT END)      as jy_count_m_pos
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_MB_DT}' THEN TRAN_AMT END)/100  as jy_amt_m_pos
            ,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>= date(TO_CHAR(TRUNC(ADD_MONTHS( DATE('{I_MB_DT}'),-2),'MM'),'YYYYMMDD')) THEN TRAN_AMT END)      as jy_count_3m_pos
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>= date(TO_CHAR(TRUNC(ADD_MONTHS( DATE('{I_MB_DT}'),-2),'MM'),'YYYYMMDD')) THEN TRAN_AMT END)/100  as jy_amt_3m_pos
	  FROM odm.O_POSP_T_CAP_SERIAL   
	  WHERE etl_tx_dt <= '{ETL_TX_DT}'
	  AND to_date (CLEAR_DATE,'YYYYMMDD') >= date'{V_12M_BEGIN}'
	  GROUP BY MERCH_ID
    union all
	  SELECT MERCH_ID
	        ,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_YB_DT}' THEN TRAN_AMT END)  as jy_count_y_pos
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_YB_DT}'  THEN TRAN_AMT END)/100 as jy_amt_y_pos
			,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_MB_DT}' THEN TRAN_AMT END)      as jy_count_m_pos
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_MB_DT}' THEN TRAN_AMT END)/100  as jy_amt_m_pos
            ,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>= date(TO_CHAR(TRUNC(ADD_MONTHS( DATE('{I_MB_DT}'),-2),'MM'),'YYYYMMDD')) THEN TRAN_AMT END)      as jy_count_3m_pos
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>= date(TO_CHAR(TRUNC(ADD_MONTHS( DATE('{I_MB_DT}'),-2),'MM'),'YYYYMMDD')) THEN TRAN_AMT END)/100  as jy_amt_3m_pos
	  FROM odm.O_POSP_T_CAP_SERIAL_T0
	  WHERE etl_tx_dt <='{ETL_TX_DT}'
	  AND to_date (CLEAR_DATE,'YYYYMMDD') >= date'{V_12M_BEGIN}'
	  GROUP BY MERCH_ID
    union all
	  SELECT MERCH_ID
	        ,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_YB_DT}' THEN TRAN_AMT END)  as jy_count_y_pos
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_YB_DT}'  THEN TRAN_AMT END)/100 as jy_amt_y_pos
			,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_MB_DT}' THEN TRAN_AMT END)      as jy_count_m_pos
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_MB_DT}' THEN TRAN_AMT END)/100  as jy_amt_m_pos 
            ,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>= date(TO_CHAR(TRUNC(ADD_MONTHS( DATE('{I_MB_DT}'),-2),'MM'),'YYYYMMDD')) THEN TRAN_AMT END)      as jy_count_3m_pos
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>= date(TO_CHAR(TRUNC(ADD_MONTHS( DATE('{I_MB_DT}'),-2),'MM'),'YYYYMMDD')) THEN TRAN_AMT END)/100  as jy_amt_3m_pos
	  FROM odm.O_POSP_T_15K_SERIAL
	  WHERE etl_tx_dt <='{ETL_TX_DT}'
	  AND to_date (CLEAR_DATE,'YYYYMMDD') >= date'{V_12M_BEGIN}'
	  GROUP BY MERCH_ID  
    union all
	  SELECT MERCH_ID
	        ,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_YB_DT}' THEN TRAN_AMT END)  as jy_count_y_pos
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_YB_DT}'  THEN TRAN_AMT END)/100 as jy_amt_y_pos
			,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_MB_DT}' THEN TRAN_AMT END)      as jy_count_m_pos
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>=date'{I_MB_DT}' THEN TRAN_AMT END)/100  as jy_amt_m_pos
            ,COUNT(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>= date(TO_CHAR(TRUNC(ADD_MONTHS( DATE('{I_MB_DT}'),-2),'MM'),'YYYYMMDD')) THEN TRAN_AMT END)      as jy_count_3m_pos
		    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD')>= date(TO_CHAR(TRUNC(ADD_MONTHS( DATE('{I_MB_DT}'),-2),'MM'),'YYYYMMDD')) THEN TRAN_AMT END)/100  as jy_amt_3m_pos
	  FROM odm.O_POSP_T_15K_SERIAL_T0
	  WHERE etl_tx_dt <='{ETL_TX_DT}'
	  AND to_date (CLEAR_DATE,'YYYYMMDD') >= date'{V_12M_BEGIN}'
	  GROUP BY MERCH_ID  )t
	  group by 1
;
/*Step2_1:插入月度聚合支付交易数据*/  
INSERT INTO  {PID}.VT_A_GFIN_MECT_DTL_TAB_SUM_02
	  SELECT MERCH_ID
			    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD') >= date'{I_YB_DT}' THEN ALIPAY_SUCCESS_COUNT+WEIXIN_SUCCESS_COUNT END) 
			    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD') >= date'{I_YB_DT}' THEN ALIPAY_SUCCESS_FEE+WEIXIN_SUCCESS_FEE END)/100
				,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD') >= date'{I_MB_DT}' THEN ALIPAY_SUCCESS_COUNT+WEIXIN_SUCCESS_COUNT END) 
			    ,SUM(CASE WHEN to_date (CLEAR_DATE,'YYYYMMDD') >= date'{I_MB_DT}' THEN ALIPAY_SUCCESS_FEE+WEIXIN_SUCCESS_FEE END)/100
	  FROM odm.o_posp_t_merch_mobileday
	  WHERE etl_tx_dt <='{ETL_TX_DT}'
	  AND to_date (CLEAR_DATE,'YYYYMMDD') >= date'{V_12M_BEGIN}'
	  AND (ALIPAY_SUCCESS_COUNT <> 0 OR WEIXIN_SUCCESS_COUNT <> 0)
	  GROUP BY MERCH_ID
;




/*Step3:插入商户数据*/
INSERT INTO {PID}.VT_A_GFIN_MECT_DTL_TAB_03
	  SELECT T1.mect_no
            ,T1.mect_name
			,MK.ind_com_lns  -- 营业执照号
            ,T1.mect_nature_cd
            ,T1.mect_type_cd
            ,T1.move_pay_mect_no
            ,T1.move_pay_stl_acct_no
            ,T5.record_sts_cd
            ,T1.create_dt
            ,T2.super_org_no
            ,T2.super_org_abre
            ,T2.org_no
            ,T2.org_abre
            ,T1.cust_manag_wage_no
            ,T1.cust_manag_name
            ,T1.stl_acct_no
            ,T6.record_sts_cd
            ,T1.core_cust_no
            ,CASE WHEN  (length(T3.open_acct_dt)<>0 OR length(T4.open_acct_dt)<>0)  then   
              case when DATEDIFF(DATE(T1.create_dt),DATE(NVL(T3.open_acct_dt,T4.open_acct_dt)))<=30 THEN '1'
              ELSE '0' end
              else '0' end as CUST_FLG
            ,T1.vld_term_qty
            ,t1.e_bank_cust_flag
			,t1.mect_vld_flg
            ,CASE WHEN SUBSTR(T1.start_use_dt,1,4)=SUBSTR('{ETL_TX_DT}',1,4) THEN 1 else 0 END  new_mect_flag
	  FROM cdm.C_CUS_POS_MECT_INF T1
	  LEFT JOIN cdm.C_ORG_ORG_INF T2
	  ON T1.mect_belo_org_no=T2.ORG_NO
	  and t2.data_dt='{ETL_TX_DT}'
	  LEFT JOIN fdm.f_pt_crbs_pri_cust_inf T3
	  ON T1.core_cust_no=T3.cust_no
	 			and T3.start_dt <= DATE '{ETL_TX_DT}' 
      AND T3.end_dt > DATE '{ETL_TX_DT}'
      and T3.del_flag<>'D'
	  LEFT JOIN fdm.f_pt_crbs_pub_cust_inf T4
	  ON T1.core_cust_no=T4.cust_no
	 			and T4.start_dt <= DATE '{ETL_TX_DT}' 
      AND T4.end_dt > DATE '{ETL_TX_DT}'
      and T4.del_flag<>'D'
	  LEFT JOIN fdm.f_ac_crbs_cust_acct_no T5
	  ON T1.move_pay_stl_acct_no=T5.cust_acct_no
	 			and T5.start_dt <= DATE '{ETL_TX_DT}' 
      AND T5.end_dt > DATE '{ETL_TX_DT}'
      and T5.del_flag<>'D'
	  LEFT JOIN fdm.f_ac_crbs_cust_acct_no T6
	  ON T1.stl_acct_no=T6.cust_acct_no
	 	and T6.start_dt <= DATE '{ETL_TX_DT}' 
        AND T6.end_dt > DATE '{ETL_TX_DT}'
        and T6.del_flag<>'D'
	  LEFT JOIN odm.o_posp_t_merch AS MK 
      ON  MK.merch_id =  T1.mect_no 
       and MK.start_dt <= DATE '{ETL_TX_DT}'  
       and MK.end_dt > DATE '{ETL_TX_DT}' 
	  WHERE T1.data_dt = '{ETL_TX_DT}'
	--  and t1.mect_vld_flg='1'  -- 商户状态正常
;
/*Step4:插入结算账号信息*/
INSERT INTO {PID}.VT_A_GFIN_MECT_DTL_TAB_04
	  SELECT T1.CUST_ACCT_NO
	        ,T2.open_acct_org_no
	        ,T3.org_abre
	        ,T4.month_daily_avg
	        ,T4.year_daily_avg
	  FROM (SELECT CUST_ACCT_NO
	        FROM {PID}.VT_A_GFIN_MECT_DTL_TAB_03
	    		GROUP BY 1)T1 
	  LEFT JOIN fdm.f_ac_crbs_cust_acct_no T2
	  ON T1.CUST_ACCT_NO=T2.cust_acct_no
	 			and T2.start_dt <= DATE '{ETL_TX_DT}' 
      AND T2.end_dt > DATE '{ETL_TX_DT}'
      AND T2.del_flag<>'D'
	  LEFT JOIN cdm.C_ORG_ORG_INF T3
	  ON T2.open_acct_org_no=T3.org_no
	  	and t3.data_dt='{ETL_TX_DT}'
	  LEFT JOIN (select CUST_ACCT_NO
	  ,sum(month_daily_avg) as month_daily_avg
	  ,sum(year_daily_avg) as year_daily_avg
	  from cdm.C_AGT_DEPOSIT_ACCT_INF
	  where data_dt='{ETL_TX_DT}'
	  group by 1
	  ) T4
      ON T1.CUST_ACCT_NO=T4.CUST_ACCT_NO
;
/*Step5:插入客户日均数据(对私)*/
INSERT INTO {PID}.VT_A_GFIN_MECT_DTL_TAB_05
    	  SELECT T1.CUST_NO
    	        ,T2.dep_bal
				,T2.m_avg
    	        ,T2.y_avg
				,T2.kh_hq_bal
                ,T2.sap_month_avg
    	        ,T2.sap_year_avg
    	        ,T2.tdp_avg_1y
    	        ,T2.tdp_avg_1m
    	        ,kh_rm_avg 
    	  FROM (SELECT CUST_NO
    	        FROM {PID}.VT_A_GFIN_MECT_DTL_TAB_03 WHERE CUST_NO LIKE '01%'
    	    		GROUP BY 1)T1 
    	  LEFT JOIN (select t.cust_no as cust_no	 
          ,sum(nvl(t.acct_bal,0)) as dep_bal	 
          ,sum(nvl(t.month_daily_avg,0)) as m_avg
		  ,sum(nvl(t.year_daily_avg,0)) as y_avg
		  ,SUM(case when (SUBSTR(SUBJ_NO,1,4) in ('2203')
                          or SUBSTR(SUBJ_NO,1,6) in ('220702')
                          or SUBSTR(SUBJ_NO,1,8) in ('22050201'))
                then nvl(t.acct_bal,0) 
				else 0 end) kh_hq_bal		    --  客户号下活期余额
          -- ,sum(case when t.prod_type = '0' then nvl(t.month_daily_avg,0) else 0 end) as sap_month_avg 
		  ,SUM(case when (SUBSTR(SUBJ_NO,1,4) in ('2203')
                          or SUBSTR(SUBJ_NO,1,6) in ('220702')
                          or SUBSTR(SUBJ_NO,1,8) in ('22050201'))
                then nvl(t.month_daily_avg,0) 
				else 0 end) sap_month_avg
		  ,SUM(case when (SUBSTR(SUBJ_NO,1,4) in ('2203')
                          or SUBSTR(SUBJ_NO,1,6) in ('220702')
                          or SUBSTR(SUBJ_NO,1,8) in ('22050201'))
                then nvl(t.year_daily_avg,0) 
				else 0 end) sap_year_avg
         --  ,sum(case when t.prod_type = '0' then nvl(t.year_daily_avg,0) else 0 end) as sap_year_avg   	
          ,sum(case when ((t.term_cycle_cd like '%D%' and t.term_cycle <= 366) or
                    (t.term_cycle_cd like '%M%' and t.term_cycle <= 12) or
                    (t.term_cycle_cd like '%Y%' and t.term_cycle <= 1) or t.term_cycle = '99')  
                    then nvl(t.year_daily_avg,0) else 0 end) as tdp_avg_1y	
          ,sum(case when ((t.term_cycle_cd like '%D%' and t.term_cycle <= 366) or
                    (t.term_cycle_cd like '%M%' and t.term_cycle <= 12) or
                    (t.term_cycle_cd like '%Y%' and t.term_cycle <= 1) or t.term_cycle = '99')  
                    then nvl(t.month_daily_avg,0) else 0 end) as tdp_avg_1m	
          ,sum(roll_month_daily_avg) as kh_rm_avg 
    from cdm.C_AGT_DEPOSIT_ACCT_INF t	
    where t.data_dt = '{ETL_TX_DT}'
    AND (SUBSTR(T.SUBJ_NO,1,4) in ('2204','2203')
                      or SUBSTR(T.SUBJ_NO,1,6) in ('220602','220702')
                      or SUBSTR(T.SUBJ_NO,1,8) in ('22100102','22050202','22100202','22050201')
                      or SUBSTR(T.SUBJ_NO,1,10) = '2725010102')
    group by t.cust_no)T2 ON T1.CUST_NO=T2.cust_no
                ;
/*Step6:插入客户日均数据(对公)*/
INSERT INTO {PID}.VT_A_GFIN_MECT_DTL_TAB_06
SELECT T1.CUST_ACCT_NO
    	        ,T2.dep_bal
				,T2.m_avg
    	        ,T2.y_avg
				,T2.hq_bal
                ,T2.sap_month_avg
    	        ,T2.sap_year_avg
    	        ,T2.tdp_avg_1y
    	        ,T2.tdp_avg_1m
    	        ,rm_avg 
    	  FROM (SELECT CUST_ACCT_NO
    	        FROM {PID}.VT_A_GFIN_MECT_DTL_TAB_03 WHERE CUST_NO LIKE '02%'
    	    		GROUP BY 1)T1 
    	  LEFT JOIN (select t.CUST_ACCT_NO as CUST_ACCT_NO	 
          ,sum(nvl(t.acct_bal,0)) as dep_bal	 
		  ,sum(nvl(t.month_daily_avg,0)) as m_avg
          ,sum(nvl(t.year_daily_avg,0)) as y_avg	
		  ,sum(case when (SUBSTR(T.SUBJ_NO,1,4) in ('2201','2208','2220','2701')
						or SUBSTR(T.SUBJ_NO,1,8) in ('22050101'))
                    then nvl(t.acct_bal,0) else 0 end) hq_bal
          -- ,sum(case when t.prod_type = '0' then nvl(t.month_daily_avg,0) else 0 end) as sap_month_avg 
		  ,sum(case when (SUBSTR(T.SUBJ_NO,1,4) in ('2201','2208','2220','2701')
						or SUBSTR(T.SUBJ_NO,1,8) in ('22050101'))
                    then nvl(t.month_daily_avg,0) else 0 end) sap_month_avg
		   ,sum(case when (SUBSTR(T.SUBJ_NO,1,4) in ('2201','2208','2220','2701')
						or SUBSTR(T.SUBJ_NO,1,8) in ('22050101'))
                    then nvl(t.year_daily_avg,0) else 0 end) sap_year_avg
          -- ,sum(case when t.prod_type = '0' then nvl(t.year_daily_avg,0) else 0 end) as sap_year_avg   	
          ,sum(case when ((t.term_cycle_cd like '%D%' and t.term_cycle <= 366) or
                    (t.term_cycle_cd like '%M%' and t.term_cycle <= 12) or
                    (t.term_cycle_cd like '%Y%' and t.term_cycle <= 1) or t.term_cycle = '99')  
                    then nvl(t.year_daily_avg,0) else 0 end) as tdp_avg_1y	
          ,sum(case when ((t.term_cycle_cd like '%D%' and t.term_cycle <= 366) or
                    (t.term_cycle_cd like '%M%' and t.term_cycle <= 12) or
                    (t.term_cycle_cd like '%Y%' and t.term_cycle <= 1) or t.term_cycle = '99')  
                    then nvl(t.month_daily_avg,0) else 0 end) as tdp_avg_1m	
           ,sum(roll_month_daily_avg) as rm_avg 
    from cdm.C_AGT_DEPOSIT_ACCT_INF t	
    where t.data_dt = '{ETL_TX_DT}'
    AND (SUBSTR(T.SUBJ_NO,1,4) in ('2201' , '2202', '2208', '2214' , '2701','2220') 
         or SUBSTR(T.SUBJ_NO,1,6) in ('220501' , '220601' , '220701' )
         or SUBSTR(T.SUBJ_NO,1,8) in ('22100101' , '22100201')
         or SUBSTR(T.SUBJ_NO,1,10) = '2725010101')
    group by t.CUST_ACCT_NO)T2 ON T1.CUST_ACCT_NO=T2.CUST_ACCT_NO
;
/*Step7:交易数据*/
INSERT INTO {PID}.VT_A_GFIN_MECT_DTL_TAB_07
	 select MERCH_ID
			,sum(JY_COUNT_N)
			,sum(JY_AMT_N)
			,sum(JY_COUNT_3M)
			,sum(JY_AMT_3M)
			from (
	  SELECT MERCH_ID
			,JY_COUNT_N
			,JY_AMT_N
			,JY_COUNT_3M
			,JY_AMT_3M
	  FROM {PID}.VT_A_GFIN_MECT_DTL_TAB_01 
	  union all
	  SELECT MERCH_ID
			,JY_COUNT_N
			,JY_AMT_N
			,JY_COUNT_3M
			,JY_AMT_3M
	  FROM {PID}.VT_A_GFIN_MECT_DTL_TAB_02
	  ) a
	  GROUP BY MERCH_ID
;
/*Step8:交易达标数据*/
INSERT INTO {PID}.VT_A_GFIN_MECT_DTL_TAB_08
	  SELECT MERCH_ID
			,JY_COUNT_N
			,JY_AMT_N
			,JY_COUNT_3M
			,JY_AMT_3M
			,CASE WHEN JY_COUNT_3M/3>=5 AND JY_AMT_3M/3>=1000 THEN 1 ELSE 0 END
	  FROM {PID}.VT_A_GFIN_MECT_DTL_TAB_07

;
/*Step9:插入主数据*/
INSERT INTO {PID}.A_GFIN_MECT_DTL_TAB
SELECT 
          DATE '{ETL_TX_DT}'
          ,T1.MERCH_ID 
          ,T1.MERCH_NAME 
          ,T1.MERCH_XZ
		  ,T1.mect_vld_flg    -- 商户有效标志 1有效0无效
		  ,T1.IND_COM_LNS	  -- 营业执照号	  
          ,T1.MERCH_LX 
          ,T1.MOBLIE_MERCH_ID
          ,T1.MOBILE_ACCOUNT
          ,T1.MOBILE_ACCOUNT_ZT
          ,T1.CUST_ACCT_NO
          ,T1.CUST_ACCT_NO_ZT
          ,T1.START_DT 
          ,T1.BRCH_ID 
          ,T1.BRCH_SPL_NM 
          ,T1.ORG_ID 
          ,T1.ORG_SPL_NM 
          ,T1.MGR_NO 
          ,T1.MGR_NAME 
          ,T1.CUST_NO
          ,case when T1.CUST_NO like '01%' then '01' 
                when T1.CUST_NO like '02%' then '02'  end as department_id
		  ,T3.ORG_SPL_NM
		  ,T3.ORG_ID
		  ,T1.CUST_FLG     -- 是否新增客户
          ,T2.RCH_FLG   -- 是否达标
          ,CASE WHEN T2.RCH_FLG='1' THEN '1'
		            WHEN T2.RCH_FLG='0' THEN CASE WHEN T5.RCH_FLG='1' THEN '1'
				                                      WHEN T5.RCH_FLG='0' AND T5.RCH_FLG2='1' THEN '1'
				                                      ELSE '0' END
		            ELSE '0' END  -- 曾经是否达标
		  ,CASE WHEN T2.JY_AMT_N>0 THEN '1' ELSE '0' END  -- 是否活跃商户
		  ,T4.aum_bal
		  ,DECODE(T7.BAL,NULL,T8.BAL,T7.BAL)  -- 客户号下存款时点
		  ,DECODE(T7.M_AVG,NULL,T8.M_AVG,T7.M_AVG)         -- 客户月日均
		  ,DECODE(T7.kh_rm_avg,NULL,T8.rm_avg,T7.kh_rm_avg)    -- 客户号下滚动月日均
		  ,DECODE(T7.Y_AVG,NULL,T8.Y_AVG,T7.Y_AVG)   -- 客户号下年日均
		  ,DECODE(T7.kh_hq_bal,NULL,T8.hq_bal,T7.kh_hq_bal) -- 客户号下活期余额
		  ,DECODE(T7.HQ_M_AVG,NULL,T8.HQ_M_AVG,T7.HQ_M_AVG)   -- 客户号下活期存款月日均		  
		  ,DECODE(T7.HQ_Y_AVG,NULL,T8.HQ_Y_AVG,T7.HQ_Y_AVG)   -- 客户号下活期存款年日均
		  ,DECODE(T7.YEAY_Y_AVG1,NULL,T8.YEAY_Y_AVG1,T7.YEAY_Y_AVG1)  -- 客户号下一年期及以下年日均
		  ,DECODE(T7.YEAY_M_AVG1,NULL,T8.YEAY_M_AVG1,T7.YEAY_M_AVG1)  -- 客户号下一年期及以下月日均 
		  ,T11.rm_avg   -- 账户滚动月日均
		  ,t3.M_AVG   -- 账户月日均
		  ,t3.Y_AVG   -- 账户年日均
		  ,CASE WHEN T10.CUST_NO IS NULL THEN '0' ELSE '1' END
          ,T1.new_mect_flag          -- 是否新增商户    
		  ,T1.c_eb_flg
		  ,CASE WHEN T9.CUST_NO IS NULL THEN '0' ELSE '1' END  
		  ,T1.TERM_COUNT   -- 终端数量
		  ,CASE WHEN T4.aum_roll_quart_daily_avg>=500 THEN '1' ELSE '0' END
		  ,CASE WHEN T4.aum_roll_quart_daily_avg>=100000 THEN '1' ELSE '0' END
		  /*,POSP.subsidy_total_amt   -- 当月手续费优惠额度
          ,POSP.subsidy_use_amt  --  当月手续费优惠使用额度
		  ,CASE 
						WHEN POSP.subsidy_mode_cd = 0 THEN '未开启' 
						WHEN POSP.subsidy_mode_cd = 1 THEN '减免手续费每月模式'
						WHEN POSP.subsidy_mode_cd = 2 THEN '减免交易金额每月模式'
						ELSE '  '
						END AS subsidy_mode --   优惠模式
          ,POSP.subsidy_use_fee  --  减免金额 		 
          */
		  ,NVL(T12.JY_COUNT_M,0)   -- 当月POS交易笔数
		  ,NVL(T12.JY_AMT_M,0)     -- 当月POS交易金额 
		  ,NVL(T12.jy_count_3m_pos,0)   --  近三个月POS交易笔数
		  ,NVL(T12.JY_AMT_3M_pos,0)     --  近三个月POS交易金额 
          ,NVL(T12.JY_COUNT_Y,0)     -- 当年POS交易笔数
          ,NVL(T12.JY_AMT_Y,0)       --  当年POS交易金额
		  /*,POSP.subsidy_total_amt+nvl(T16.mob_toamt,0) -- 当年手续费累计优惠额度
              -- POS
          ,POSP.pos_subsidy_debit_total_amt + POSP.credit_card_swiping_card_prefr_tot_limit --  当月手续费优惠额度  15
          ,POSP.swiping_card_prefr_use_limit -- 当月手续费优惠使用额度 
          ,CASE 
          	WHEN POSP.swiping_card_txn_prefr_mode_cd = 0 THEN '未开启' 
          	WHEN POSP.swiping_card_txn_prefr_mode_cd = 1 THEN '减免手续费每日模式'
          	WHEN POSP.swiping_card_txn_prefr_mode_cd = 2 THEN '减免手续费每月模式'
          	WHEN POSP.swiping_card_txn_prefr_mode_cd = 3 THEN '减免交易金额每月模式'
          	ELSE '  '
          	END AS pos_subsidy_mode -- 优惠模式  
		 ,POSP.swiping_card_prefr_use_limit_related_servi_fee -- 减免金额  
         */
		  ,NVL(T14.JY_COUNT_M,0)   -- 当月聚合交易笔数
		  ,NVL(T14.JY_AMT_M,0)     -- 当月聚合交易金额 
		  ,NVL(T15.JY_COUNT_3M,0)   --  近三个月聚合交易笔数
		  ,NVL(T15.JY_AMT_3M,0)     --  近三个月聚合交易金额
		  ,NVL(T14.JY_COUNT_Y,0)   -- 当年聚合交易笔数
		  ,NVL(T14.JY_AMT_Y,0)     -- 当年聚合交易金额  		  
		  /*,POSP.pos_subsidy_debit_total_amt + POSP.credit_card_swiping_card_prefr_tot_limit + nvl(T16.pos_toamt,0) -- 当年手续费累计优惠额度
          ,POSP.subsidy_use_amt+nvl(T16.mob_ua,0)       -- 当年手续费优惠累计使用额  
          */
	FROM {PID}.VT_A_GFIN_MECT_DTL_TAB_03 T1 -- 商户数据
	LEFT JOIN {PID}.VT_A_GFIN_MECT_DTL_TAB_08 T2  -- 交易达标数据
	ON T1.MERCH_ID=T2.MERCH_ID
	LEFT JOIN {PID}.VT_A_GFIN_MECT_DTL_TAB_04 T3  -- 存款数据 
	ON T1.CUST_ACCT_NO=T3.CUST_ACCT_NO
    LEFT JOIN {CDM}.C_CUS_PRI_CUST_AUM_INF T4  -- AUM
	ON T1.CUST_NO=T4.CUST_NO
	AND T4.data_dt = '{ETL_TX_DT}'
	LEFT JOIN {PID}.A_GFIN_MECT_DTL_TAB T5
	ON T1.MERCH_ID=T5.MERCH_ID
	AND T5.data_dt = date'{ETL_TX_DT}'-1
	LEFT JOIN {PID}.VT_A_GFIN_MECT_DTL_TAB_05 T7 
	ON T1.CUST_NO=T7.CUST_NO
	LEFT JOIN {PID}.VT_A_GFIN_MECT_DTL_TAB_06 T8 
	ON T1.CUST_ACCT_NO=T8.CUST_ACCT_NO
	LEFT JOIN {CDM}.C_CUS_INCLU_FIN_TERM_CUST_INF T9
	ON T1.CUST_NO=T9.CUST_NO
	and T9.data_dt = '{ETL_TX_DT}'
	and T9.devic_sts_cd>='3'
	left join  (select distinct cust_no from CDM.C_CUS_PRI_CUST_LOAN_SUM
				where data_dt = '{ETL_TX_DT}'
				) T10
	on T1.CUST_NO=T10.cust_no
	left join (select CUST_ACCT_NO
			,sum(roll_month_daily_avg)  as rm_avg
			from CDM.C_AGT_DEPOSIT_ACCT_INF
		    where data_dt = '{ETL_TX_DT}'
			group by CUST_ACCT_NO
			) T11
	ON T1.CUST_ACCT_NO=T11.CUST_ACCT_NO
	left join {PID}.VT_A_GFIN_MECT_DTL_TAB_SUM_01 T12
		ON T1.MERCH_ID=T12.MERCH_ID
	left join  {PID}.VT_A_GFIN_MECT_DTL_TAB_SUM_02 T14
		on T1.MERCH_ID = T14.MERCH_ID
	left join {PID}.VT_A_GFIN_MECT_DTL_TAB_02 T15
		ON T1.MERCH_ID = T15.MERCH_ID
	/* LEFT JOIN fdm.f_ev_posp_mect_month_degree_prefr_inf AS POSP 
     ON POSP.mect_no =  T1.merch_id 
     and POSP.start_dt <= DATE '{ETL_TX_DT}'  
     and POSP.end_dt > DATE '{ETL_TX_DT}' 
    LEFT JOIN {PID}.A_GFIN_MECT_DTL_TAB T16
     on  T16.merch_id=T1.merch_id
     and T16.data_dt=last_day(ADD_MONTHS(DATE('{I_LME_DT}'),-1))
     and T16.data_dt > STR_TO_DATE(CONCAT(YEAR('{I_LME_DT}'), '-01-01'), '%Y-%m-%d')
     */




;



"""

#  --  主调程序--   #
bformatsql = bsql.format_map(vars())
ret_code = execute_gbase_sql(bformatsql)
if ret_code :
    log.error(" ERROR - ERROR .")
    exit(-1)
else:
    log.info("SUCESS . ")
