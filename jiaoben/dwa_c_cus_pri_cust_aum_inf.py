# *****************************************头部注释模块**************************************
#脚本名称：dwa_c_cus_pri_cust_aum_inf.py
#功能说明：共性层个人客户金融资产信息脚本
#更新方式：每日全量
#更新对象：CDM.C_CUS_PRI_CUST_AUM_INF
#创 建 人： 
#创建日期：2024-02-05
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
DELETE FROM {CDM}.C_CUS_PRI_CUST_AUM_INF    -- 个人客户金融资产信息
 WHERE data_dt = DATE '{ETL_TX_DT}';

"""

# -生成mapping映射- #
bsqlmapping="""

"""


# -bsql2算法逻辑主体实现- #
bsql2="""

/*Step1:向主表插入数据*/
INSERT INTO {CDM}.C_CUS_PRI_CUST_AUM_INF
  SELECT DATE '{ETL_TX_DT}'                         -- 数据日期
        ,TA1.cust_no                                -- 客户编号
        ,MAX(TA1.cust_name)                          -- 客户名称
        ,TA1.currcy_cd                          -- 币种代码
        ,SUM(TA1.aum_bal)                            -- AUM余额
        ,SUM(TA1.aum_month_daily_avg)                -- AUM月日均
        ,SUM(TA1.aum_quart_daily_avg)                -- AUM季日均
        ,SUM(TA1.aum_half_year_daily_avg)            -- AUM半年日均
        ,SUM(TA1.aum_year_daily_avg)                 -- AUM年日均
        ,SUM(TA1.aum_roll_month_daily_avg)           -- AUM滚动月日均
        ,SUM(TA1.aum_roll_quart_daily_avg)           -- AUM滚动季日均
        ,SUM(TA1.aum_roll_half_year_daily_avg)       -- AUM滚动半年日均
        ,SUM(TA1.aum_roll_year_daily_avg)            -- AUM滚动年日均
        ,SUM(TA1.deposit_bal)                        -- 存款余额
        ,SUM(TA1.deposit_month_daily_avg)            -- 存款月日均
        ,SUM(TA1.deposit_quart_daily_avg)            -- 存款季日均
        ,SUM(TA1.deposit_half_year_daily_avg)        -- 存款半年日均
        ,SUM(TA1.deposit_year_daily_avg)             -- 存款年日均
        ,SUM(TA1.deposit_roll_month_daily_avg)       -- 存款滚动月日均
        ,SUM(TA1.deposit_roll_quart_daily_avg)       -- 存款滚动季日均
        ,SUM(TA1.deposit_roll_half_year_daily_avg)   -- 存款滚动半年日均
        ,SUM(TA1.deposit_roll_year_daily_avg)        -- 存款滚动年日均
        ,SUM(TA1.ldr_bal)	                       -- 大额存单余额       
        ,SUM(TA1.ldr_month_daily_avg)	           -- 大额存单月日均      
        ,SUM(TA1.ldr_quart_daily_avg)	           -- 大额存单季日均      
        ,SUM(TA1.ldr_half_year_daily_avg)	       -- 大额存单半年日均     
        ,SUM(TA1.ldr_year_daily_avg)	           -- 大额存单年日均      
        ,SUM(TA1.ldr_roll_month_daily_avg)	     -- 大额存单滚动月日均    
        ,SUM(TA1.ldr_roll_quart_daily_avg)	     -- 大额存单滚动季日均    
        ,SUM(TA1.ldr_roll_half_year_daily_avg)	 -- 大额存单滚动半年日均
        ,SUM(TA1.ldr_roll_year_daily_avg)	       -- 大额存单滚动年日均
        ,SUM(TA1.fin_bal)                            -- 理财余额
        ,SUM(TA1.fin_month_daily_avg)                -- 理财月日均
        ,SUM(TA1.fin_quart_daily_avg)                -- 理财季日均
        ,SUM(TA1.fin_half_year_daily_avg)            -- 理财半年日均
        ,SUM(TA1.fin_year_daily_avg)                 -- 理财年日均
        ,SUM(TA1.fin_roll_month_daily_avg)           -- 理财滚动月日均
        ,SUM(TA1.fin_roll_quart_daily_avg)           -- 理财滚动季日均
        ,SUM(TA1.fin_roll_half_year_daily_avg)       -- 理财滚动半年日均
        ,SUM(TA1.fin_roll_year_daily_avg)            -- 理财滚动年日均
        ,SUM(TA1.fund_bal)                           -- 基金余额
        ,SUM(TA1.fund_month_daily_avg)               -- 基金月日均
        ,SUM(TA1.fund_quart_daily_avg)               -- 基金季日均
        ,SUM(TA1.fund_half_year_daily_avg)           -- 基金半年日均
        ,SUM(TA1.fund_year_daily_avg)                -- 基金年日均
        ,SUM(TA1.fund_roll_month_daily_avg)          -- 基金滚动月日均
        ,SUM(TA1.fund_roll_quart_daily_avg)          -- 基金滚动季日均
        ,SUM(TA1.fund_roll_half_year_daily_avg)      -- 基金滚动半年日均
        ,SUM(TA1.fund_roll_year_daily_avg)           -- 基金滚动年日均
        ,SUM(TA1.debt_bal)                           -- 国债余额
        ,SUM(TA1.debt_month_daily_avg)               -- 国债月日均
        ,SUM(TA1.debt_quart_daily_avg)               -- 国债季日均
        ,SUM(TA1.debt_half_year_daily_avg)           -- 国债半年日均
        ,SUM(TA1.debt_year_daily_avg)                -- 国债年日均
        ,SUM(TA1.debt_roll_month_daily_avg)          -- 国债滚动月日均
        ,SUM(TA1.debt_roll_quart_daily_avg)          -- 国债滚动季日均
        ,SUM(TA1.debt_roll_half_year_daily_avg)      -- 国债滚动半年日均
        ,SUM(TA1.debt_roll_year_daily_avg)           -- 国债滚动年日均
        ,SUM(TA1.insac_bal)                          -- 保险余额
        ,SUM(TA1.insac_month_daily_avg)              -- 保险月日均
        ,SUM(TA1.insac_quart_daily_avg)              -- 保险季日均
        ,SUM(TA1.insac_half_year_daily_avg)          -- 保险半年日均
        ,SUM(TA1.insac_year_daily_avg)               -- 保险年日均
        ,SUM(TA1.insac_roll_month_daily_avg)         -- 保险滚动月日均
        ,SUM(TA1.insac_roll_quart_daily_avg)         -- 保险滚动季日均
        ,SUM(TA1.insac_roll_half_year_daily_avg)     -- 保险滚动半年日均
        ,SUM(TA1.insac_roll_year_daily_avg)          -- 保险滚动年日均
        ,SUM(TA1.over_pay_bal)                       -- 溢缴款余额
        ,SUM(TA1.over_pay_month_daily_avg)           -- 溢缴款月日均
        ,SUM(TA1.over_pay_quart_daily_avg)           -- 溢缴款季日均
        ,SUM(TA1.over_pay_half_year_daily_avg)       -- 溢缴款半年日均
        ,SUM(TA1.over_pay_year_daily_avg)            -- 溢缴款年日均
        ,SUM(TA1.over_pay_roll_month_daily_avg)      -- 溢缴款滚动月日均
        ,SUM(TA1.over_pay_roll_quart_daily_avg)      -- 溢缴款滚动季日均
        ,SUM(TA1.over_pay_roll_half_year_daily_avg)  -- 溢缴款滚动半年日均
        ,SUM(TA1.over_pay_roll_year_daily_avg)       -- 溢缴款滚动年日均
	   ,SUM(TA1.zy_fnc_bal)                        -- 自营理财余额
       ,SUM(TA1.zy_fnc_month_daily_avg)            -- 自营理财月日均
       ,SUM(TA1.zy_fnc_quart_daily_avg)            -- 自营理财季日均
       ,SUM(TA1.zy_fnc_half_year_daily_avg)        -- 自营理财半年日均
       ,SUM(TA1.zy_fnc_year_daily_avg)             -- 自营理财年日均
       ,SUM(TA1.zy_fnc_roll_month_daily_avg)       -- 自营理财滚动月日均
       ,SUM(TA1.zy_fnc_roll_quart_daily_avg)       -- 自营理财滚动季日均
       ,SUM(TA1.zy_fnc_roll_half_year_daily_avg)   -- 自营理财滚动半年日均
       ,SUM(TA1.zy_fnc_roll_year_daily_avg)        -- 自营理财滚动年日均
       ,SUM(TA1.dx_fnc_bal  )                      -- 代销理财余额
       ,SUM(TA1.dx_fnc_month_daily_avg)            -- 代销理财月日均
       ,SUM(TA1.dx_fnc_quart_daily_avg)            -- 代销理财季日均
       ,SUM(TA1.dx_fnc_half_year_daily_avg)        -- 代销理财半年日均
       ,SUM(TA1.dx_fnc_year_daily_avg )            -- 代销理财年日均
       ,SUM(TA1.dx_fnc_roll_month_daily_avg)       -- 代销理财滚动月日均
       ,SUM(TA1.dx_fnc_roll_quart_daily_avg)       -- 代销理财滚动季日均
       ,SUM(TA1.dx_fnc_roll_half_year_daily_avg)   -- 代销理财滚动半年日均
       ,SUM(TA1.dx_fnc_roll_year_daily_avg)        -- 代销理财滚动年日均
       ,SUM(TA1.zgqs_fnc_pay_bal)                        -- 资管券商余额
       ,SUM(TA1.zgqs_fnc_pay_month_daily_avg )           -- 资管券商月日均
       ,SUM(TA1.zgqs_fnc_pay_quart_daily_avg )           -- 资管券商季日均
       ,SUM(TA1.zgqs_fnc_pay_half_year_daily_avg )       -- 资管券商半年日均
       ,SUM(TA1.zgqs_fnc_pay_year_daily_avg  )           -- 资管券商年日均
       ,SUM(TA1.zgqs_fnc_pay_roll_month_daily_avg)       -- 资管券商滚动月日均
       ,SUM(TA1.zgqs_fnc_pay_roll_quart_daily_avg)       -- 资管券商滚动季日均
       ,SUM(TA1.zgqs_fnc_pay_roll_half_year_daily_avg)   -- 资管券商滚动半年日均
       ,SUM(TA1.zgqs_fnc_pay_roll_year_daily_avg)        -- 资管券商滚动年日均
	   ,SUM(TA1.deman_deposit_bal)                        -- 活期存款余额
       ,SUM(TA1.deman_deposit_month_daily_avg)            -- 活期存款月日均
       ,SUM(TA1.deman_deposit_quart_daily_avg)            -- 活期存款季日均
       ,SUM(TA1.deman_deposit_half_year_daily_avg)        -- 活期存款半年日均
       ,SUM(TA1.deman_deposit_year_daily_avg )            -- 活期存款年日均
       ,SUM(TA1.deman_deposit_roll_month_daily_avg)       -- 活期存款滚动月日均
       ,SUM(TA1.deman_deposit_roll_quart_daily_avg)       -- 活期存款滚动季日均
       ,SUM(TA1.deman_deposit_roll_half_year_daily_avg)   -- 活期存款滚动半年日均
       ,SUM(TA1.deman_deposit_roll_year_daily_avg)        -- 活期存款滚动年日均
       ,SUM(TA1.term_deposit_bal                     )   -- 定期存款余额        
       ,SUM(TA1.term_deposit_month_daily_avg         )   -- 定期存款月日均      
       ,SUM(TA1.term_deposit_quart_daily_avg         )   -- 定期存款季日均      
       ,SUM(TA1.term_deposit_half_year_daily_avg     )   -- 定期存款半年日均    
       ,SUM(TA1.term_deposit_year_daily_avg          )   -- 定期存款年日均      
       ,SUM(TA1.term_deposit_roll_month_daily_avg    )   -- 定期存款滚动月日均  
       ,SUM(TA1.term_deposit_roll_quart_daily_avg    )   -- 定期存款滚动季日均  
       ,SUM(TA1.term_deposit_roll_half_year_daily_avg)   -- 定期存款滚动半年日均
       ,SUM(TA1.term_deposit_roll_year_daily_avg     )   -- 定期存款滚动年日均  
FROM {CDM}.C_CUS_PRI_CUST_ORG_AUM TA1
WHERE data_dt = DATE '{ETL_TX_DT}'
GROUP BY cust_no,currcy_cd;

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
