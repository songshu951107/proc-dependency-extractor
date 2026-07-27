# *****************************************头部注释模块**************************************
#脚本名称：dwa_c_cus_pri_cust_loan_sum_crms.py
#功能说明：共性层个人客户贷款汇总
#更新方式：每日全量
#更新对象：CDM.C_CUS_PRI_CUST_LOAN_SUM
#创 建 人：
#创建日期：2023-12-02
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

/*Step1:先清除目标表当天数据*/
DELETE FROM {CDM}.C_CUS_PRI_CUST_LOAN_SUM    -- 个人客户贷款汇总
 WHERE data_dt =  '{ETL_TX_DT}'
 AND data_sourc_tab_name = 'C_AGT_LOAN_CONTRACT_BASIC_INF';

/*Step2:创建加工过程临时表1*/
CREATE TEMPORARY TABLE {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_01
( CUST_NO varchar(100) DEFAULT NULL COMMENT '客户号',
  CUST_NAME varchar(100) DEFAULT NULL COMMENT '客户名称',
  HANDL_TELLER_NO varchar(100) DEFAULT NULL COMMENT '经办柜员编号',
  HANDL_ORG_NO varchar(100) DEFAULT NULL COMMENT '经办机构号',
  PROD_NO varchar(60) DEFAULT NULL COMMENT '产品编号',
  MINOR_ENT_CHARACT_PROD_TYPE_CD varchar(60) DEFAULT NULL COMMENT '小企业特色产品类型代码',
  CURRCY_CD varchar(60) DEFAULT NULL COMMENT '币种代码',
  LOAN_TYPE_CD varchar(60) DEFAULT NULL COMMENT '贷款类型代码',
  LOAN_APPL_NUM_TODAY decimal(20,0) DEFAULT NULL COMMENT '贷款申请笔数_本日',
  LOAN_APPL_NUM_THMONTH decimal(20,0) DEFAULT NULL COMMENT '贷款申请笔数_本月',
  LOAN_APPL_NUM_SEASO decimal(20,0) DEFAULT NULL COMMENT '贷款申请笔数_本季',
  LOAN_APPL_NUM_THYEAR decimal(20,0) DEFAULT NULL COMMENT '贷款申请笔数_本年',
  CREDIT_CONTRACT_NUM_TODAY decimal(20,0) DEFAULT NULL COMMENT '授信合同数_本日',
  CREDIT_CONTRACT_NUM_THMONTH decimal(20,0) DEFAULT NULL COMMENT '授信合同数_本月',
  CREDIT_CONTRACT_NUM_SEASO decimal(20,0) DEFAULT NULL COMMENT '授信合同数_本季',
  CREDIT_CONTRACT_NUM_THYEAR decimal(20,0) DEFAULT NULL COMMENT '授信合同数_本年',  
  DISTR_AMT_TODAY decimal(24,2) DEFAULT NULL COMMENT '投放金额_本日',
  DISTR_AMT_THMONTH decimal(24,2) DEFAULT NULL COMMENT '投放金额_本月',
  DISTR_AMT_SEASO decimal(24,2) DEFAULT NULL COMMENT '投放金额_本季',
  DISTR_AMT_THYEAR decimal(24,2) DEFAULT NULL COMMENT '投放金额_本年',
  DISTR_AMT_HIS_CUMUL decimal(24,2) DEFAULT NULL COMMENT '投放金额_历史累计',
  DISTR_NUM_TODAY decimal(20,0) DEFAULT NULL COMMENT '投放笔数_本日',
  DISTR_NUM_THMONTH decimal(20,0) DEFAULT NULL COMMENT '投放笔数_本月',
  DISTR_NUM_SEASO decimal(20,0) DEFAULT NULL COMMENT '投放笔数_本季',
  DISTR_NUM_THYEAR decimal(20,0) DEFAULT NULL COMMENT '投放笔数_本年',
  DISTR_NUM_HIS_CUMUL decimal(20,0) DEFAULT NULL COMMENT '投放笔数_历史累计',
  RENEWAL_LOAN_CONTRACT_NUM_TODAY  decimal(20,0) DEFAULT NULL COMMENT '续贷合同数_本日',
  RENEWAL_LOAN_CONTRACT_NUM_THMONTH decimal(20,0) DEFAULT NULL COMMENT '续贷合同数_本月',
  RENEWAL_LOAN_CONTRACT_NUM_SEASO decimal(20,0)  DEFAULT NULL COMMENT '续贷合同数_本季',
  RENEWAL_LOAN_CONTRACT_NUM_THYEAR decimal(20,0) DEFAULT NULL COMMENT '续贷合同数_本年',
  UNPAY_OFF_CONTRACT_NUM decimal(20,0) DEFAULT NULL COMMENT '未结清合同数',
  BAL decimal(24,2) DEFAULT NULL COMMENT '余额',
  NP_BAL decimal(24,2) DEFAULT NULL COMMENT '不良余额',
  OVERDUE_BAL decimal(24,2) DEFAULT NULL COMMENT '逾期余额',
  NOR_BAL decimal(24,2) DEFAULT NULL COMMENT '正常余额',
  CONCERN_BAL decimal(24,2) DEFAULT NULL COMMENT '关注余额',
  SCDARY_BAL decimal(24,2) DEFAULT NULL COMMENT '次级余额',
  SUSPI_BAL decimal(24,2) DEFAULT NULL COMMENT '可疑余额',
  LOSS_BAL decimal(24,2) DEFAULT NULL COMMENT '损失余额',
  NOR_NUM decimal(20,0) DEFAULT NULL COMMENT '正常笔数',
  CONCERN_NUM decimal(20,0) DEFAULT NULL COMMENT '关注笔数',
  SCDARY_NUM decimal(20,0) DEFAULT NULL COMMENT '次级笔数',
  SUSPI_NUM decimal(20,0) DEFAULT NULL COMMENT '可疑笔数',
  LOSS_NUM decimal(20,0) DEFAULT NULL COMMENT '损失笔数',
  APPROVE_NUM_TODAY decimal(20,0) DEFAULT NULL COMMENT '审批笔数_本日',
  APPROVE_NUM_THMONTH decimal(20,0) DEFAULT NULL COMMENT '审批笔数_本月',
  APPROVE_NUM_SEASO decimal(20,0) DEFAULT NULL COMMENT '审批笔数_本季',
  APPROVE_NUM_THYEAR decimal(20,0) DEFAULT NULL COMMENT '审批笔数_本年',
  LOAN_CONTRACT_NUM_TODAY decimal(20,0) DEFAULT NULL COMMENT '贷款合同数_本日',
  LOAN_CONTRACT_NUM_THMONTH decimal(20,0) DEFAULT NULL COMMENT '贷款合同数_本月',
  LOAN_CONTRACT_NUM_SEASO decimal(20,0) DEFAULT NULL COMMENT '贷款合同数_本季',
  LOAN_CONTRACT_NUM_THYEAR decimal(20,0) DEFAULT NULL COMMENT '贷款合同数_本年',
  DISTR_CONTRACT_NUM_TODAY decimal(20,0) DEFAULT NULL COMMENT '放款合同数_本日',
  DISTR_CONTRACT_NUM_THMONTH decimal(20,0) DEFAULT NULL COMMENT '放款合同数_本月',
  DISTR_CONTRACT_NUM_SEASO decimal(20,0) DEFAULT NULL COMMENT '放款合同数_本季',
  DISTR_CONTRACT_NUM_THYEAR decimal(20,0) DEFAULT NULL COMMENT '放款合同数_本年',
  CREDIT_AMT_TODAY decimal(24,2) DEFAULT NULL COMMENT '授信金额_本日',
  CREDIT_AMT_THMONTH decimal(24,2) DEFAULT NULL COMMENT '授信金额_本月',
  CREDIT_AMT_SEASO decimal(24,2) DEFAULT NULL COMMENT '授信金额_本季',
  CREDIT_AMT_THYEAR decimal(24,2) DEFAULT NULL COMMENT '授信金额_本年',
  DIST_AMT_TODAY decimal(24,2) DEFAULT NULL COMMENT '放款金额_本日',
  DIST_AMT_THMONTH decimal(24,2) DEFAULT NULL COMMENT '放款金额_本月',
  DIST_AMT_SEASO decimal(24,2) DEFAULT NULL COMMENT '放款金额_本季',
  DIST_AMT_THYEAR decimal(24,2) DEFAULT NULL COMMENT '放款金额_本年',
  RENEWAL_LOAN_PRE_APPROVEVIA_NUM_TODAY decimal(20,0) DEFAULT NULL COMMENT '续贷预审批通过数_本日',
  RENEWAL_LOAN_PRE_APPROVEVIA_NUM_THMONTH decimal(20,0) DEFAULT NULL COMMENT '续贷预审批通过数_本月',
  RENEWAL_LOAN_PRE_APPROVEVIA_NUM_SEASO decimal(20,0) DEFAULT NULL COMMENT '续贷预审批通过数_本季',
  RENEWAL_LOAN_PRE_APPROVEVIA_NUM_THYEAR decimal(20,0) DEFAULT NULL COMMENT '续贷预审批通过数_本年',
  RENEWAL_LOAN_CUST_MANAG_CFM_NUM_TODAY decimal(20,0) DEFAULT NULL COMMENT '续贷客户经理确认数_本日',
  RENEWAL_LOAN_CUST_MANAG_CFM_NUM_THMONTH decimal(20,0) DEFAULT NULL COMMENT '续贷客户经理确认数_本月',
  RENEWAL_LOAN_CUST_MANAG_CFM_NUM_SEASO decimal(20,0) DEFAULT NULL COMMENT '续贷客户经理确认数_本季',
  RENEWAL_LOAN_CUST_MANAG_CFM_NUM_THYEAR decimal(20,0) DEFAULT NULL COMMENT '续贷客户经理确认数_本年',
  RENEWAL_LOAN_CONTRACT_NUM_MODEL_TODAY decimal(20,0) DEFAULT NULL COMMENT '续贷合同数(模型)_本日',
  RENEWAL_LOAN_CONTRACT_NUM_MODEL_THMONTH decimal(20,0) DEFAULT NULL COMMENT '续贷合同数(模型)_本月',
  RENEWAL_LOAN_CONTRACT_NUM_MODEL_SEASO decimal(20,0) DEFAULT NULL COMMENT '续贷合同数(模型)_本季',
  RENEWAL_LOAN_CONTRACT_NUM_MODEL_THYEAR decimal(20,0) DEFAULT NULL COMMENT '续贷合同数(模型)_本年',
  OVERDUE_RETUR_MONEY_AMT_TODAY decimal(24,2) DEFAULT NULL COMMENT '逾期回款金额_本日',
  OVERDUE_RETUR_MONEY_AMT_THMONTH decimal(24,2) DEFAULT NULL COMMENT '逾期回款金额_本月',
  OVERDUE_RETUR_MONEY_AMT_SEASO decimal(24,2) DEFAULT NULL COMMENT '逾期回款金额_本季',
  OVERDUE_RETUR_MONEY_AMT_THYEAR decimal(24,2) DEFAULT NULL COMMENT '逾期回款金额_本年',
  OVERDUE_RETUR_MONEY_CONTRACT_NUM_TODAY decimal(20,0) DEFAULT NULL COMMENT '逾期回款合同数_本日',
  OVERDUE_RETUR_MONEY_CONTRACT_NUM_THMONTH decimal(20,0) DEFAULT NULL COMMENT '逾期回款合同数_本月',
  OVERDUE_RETUR_MONEY_CONTRACT_NUM_SEASO decimal(20,0) DEFAULT NULL COMMENT '逾期回款合同数_本季',
  OVERDUE_RETUR_MONEY_CONTRACT_NUM_THYEAR decimal(20,0) DEFAULT NULL COMMENT '逾期回款合同数_本年',
  OVERDUE_RETUR_MONEY_PRINCIPAL_TODAY decimal(24,2) DEFAULT NULL COMMENT '逾期回款本金_本日',
  OVERDUE_RETUR_MONEY_PRINCIPAL_THMONTH decimal(24,2) DEFAULT NULL COMMENT '逾期回款本金_本月',
  OVERDUE_RETUR_MONEY_PRINCIPAL_SEASO decimal(24,2) DEFAULT NULL COMMENT '逾期回款本金_本季',
  OVERDUE_RETUR_MONEY_PRINCIPAL_THYEAR decimal(24,2) DEFAULT NULL COMMENT '逾期回款本金_本年'
)
comment  '个人客户贷款汇总临时表';

/*Step2:创建加工过程临时表2*/
CREATE TEMPORARY TABLE {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_02
(cust_no varchar(100) defAULT NULL COMMENT '客户号',
 iden_no varchar(100) defAULT NULL COMMENT '证件号',
 cust_name varchar(100) dEFAULT NULL COMMENT '客户名称',
 handl_teller_no varchar(100) DEFAULT NULL COMMENT '经办柜员编号',
 handl_org_no varchar(100) DEFAULT NULL COMMENT '经办机构号',
 prod_no varchar(18) defaULT NULL COMMENT '产品编号',
 MINOR_ENT_CHARACT_PROD_TYPE_CD varchar(60) DEFAULT NULL COMMENT '小企业特色产品类型代码',
 CURRCY_CD varchar(60) DEFAULT NULL COMMENT '币种代码',
 LOAN_TYPE_CD varchar(60) DEFAULT NULL COMMENT '贷款类型代码',
 loan_appl_num_today decimal(20,0) DEFAULT NULL COMMENT '贷款申请笔数_本日',
 loan_appl_num_thmonth decimal(20,0) DEFAULT NULL COMMENT '贷款申请笔数_本月',
 loan_appl_num_seaso decimal(20,0) DEFAULT NULL COMMENT '贷款申请笔数_本季',
 loan_appl_num_thyear decimal(20,0) DEFAULT NULL COMMENT '贷款申请笔数_本年',
 approve_num_today decimal(20,0) DEFAULT NULL COMMENT '审批笔数_本日',
 approve_num_thmonth decimal(20,0) DEFAULT NULL COMMENT '审批笔数_本月',
 approve_num_seaso decimal(20,0) DEFAULT NULL COMMENT '审批笔数_本季',
 approve_num_thyear decimal(20,0) DEFAULT NULL COMMENT '审批笔数_本年'
)
comment  '个人客户贷款汇总临时表-申请明细';

/*Step2:创建加工过程临时表3*/
CREATE TEMPORARY TABLE {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_03
( loan_loan_rcpt_no varchar(32) NOT NULL COMMENT '贷款借据编号', -- varchar(19)
  dist_amt_today decimal(24,2) DEFAULT NULL COMMENT '放款金额_本日',
  dist_amt_thmonth decimal(24,2) DEFAULT NULL COMMENT '放款金额_本月',
  dist_amt_seaso decimal(24,2) DEFAULT NULL COMMENT '放款金额_本季',
  dist_amt_thyear decimal(24,2) DEFAULT NULL COMMENT '放款金额_本年'
)
comment  '个人客户贷款汇总临时表-贷款放款统计';


/*Step2:创建加工过程临时表4*/
CREATE TEMPORARY TABLE {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_04
( loan_loan_rcpt_no varchar(32) not null COMMENT '贷款借据编号',
  overdue_retur_money_amt_today decimal(24,2) DEFAULT NULL COMMENT '逾期回款金额_本日',
  overdue_retur_money_amt_thmonth decimal(24,2) DEFAULT NULL COMMENT '逾期回款金额_本月',
  overdue_retur_money_amt_seaso decimal(24,2) DEFAULT NULL COMMENT '逾期回款金额_本季',
  overdue_retur_money_amt_thyear decimal(24,2) DEFAULT NULL COMMENT '逾期回款金额_本年',
  overdue_retur_money_principal_today decimal(24,2) DEFAULT NULL COMMENT '逾期回款本金_本日',
  overdue_retur_money_principal_thmonth decimal(24,2) DEFAULT NULL COMMENT '逾期回款本金_本月',
  overdue_retur_money_principal_seaso decimal(24,2) DEFAULT NULL COMMENT '逾期回款本金_本季',
  overdue_retur_money_principal_thyear decimal(24,2) DEFAULT NULL COMMENT '逾期回款本金_本年'
)
comment  '个人客户贷款汇总临时表-贷款逾期回款统计';


/*Step2:创建加工过程临时表5*/
CREATE TEMPORARY TABLE {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_05
( loan_loan_rcpt_no varchar(32) not null COMMENT '原借据编号',
  LOAN_CONTRACT_NO varchar(50) DEFAULT NULL COMMENT '原合同编号',
  APPROVE_TM varchar(50) DEFAULT NULL COMMENT '预审批时间',
  APPROVE_STS_CD varchar(50) DEFAULT NULL COMMENT '审批状态',
  APPROVE_RS_EXPIR_DT varchar(50) DEFAULT NULL COMMENT '审批结果到期日',
  HANDL_TELLER_NO varchar(50) DEFAULT NULL COMMENT '原合同经办人',
  HANDL_ORG_NO varchar(50) DEFAULT NULL COMMENT '原合同经办机构',
  CUST_NO varchar(50) DEFAULT NULL COMMENT '原合同信贷客户号',
  CLEAR_DT varchar(50) DEFAULT NULL COMMENT '原借据结清日期',
  IS_NT varchar(50) DEFAULT NULL COMMENT '是否续贷',
  renewal_LOAN_CONTRACT_NO varchar(50) DEFAULT NULL COMMENT '续贷合同流水号',
  renewal_STARTDT varchar(50) DEFAULT NULL COMMENT '续贷合同起始日期',
  renewal_CONTRACT_AMT decimal(24,2) DEFAULT NULL COMMENT '续贷合同金额',
  renewal_EXPIR_DT varchar(50) DEFAULT NULL COMMENT '续贷合同到期日'
)
comment  '个人客户贷款汇总临时表-续贷预审批明细';

CREATE TEMPORARY TABLE {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_06 
( CUST_NO varchar(100) DEFAULT NULL COMMENT '客户号',
  CUST_NAME varchar(100) DEFAULT NULL COMMENT '客户名称',
  HANDL_TELLER_NO varchar(100) DEFAULT NULL COMMENT '经办柜员编号',
  HANDL_ORG_NO varchar(100) DEFAULT NULL COMMENT '经办机构号',
  PROD_NO varchar(60) DEFAULT NULL COMMENT '产品编号',
  MINOR_ENT_CHARACT_PROD_TYPE_CD varchar(60) DEFAULT NULL COMMENT '小企业特色产品类型代码',
  CURRCY_CD varchar(60) DEFAULT NULL COMMENT '币种代码',
  LOAN_TYPE_CD varchar(60) DEFAULT NULL COMMENT '贷款类型代码',
  LOAN_CONTRACT_NUM_TODAY_appl decimal(20,0) DEFAULT NULL COMMENT '贷款合同数_本日申请',
  LOAN_CONTRACT_NUM_THMONTH_appl decimal(20,0) DEFAULT NULL COMMENT '贷款合同数_本月申请',
  LOAN_CONTRACT_NUM_SEASO_appl decimal(20,0) DEFAULT NULL COMMENT '贷款合同数_本季申请',
  LOAN_CONTRACT_NUM_THYEAR_appl decimal(20,0) DEFAULT NULL COMMENT '贷款合同数_本年申请',  
   DISTR_CONTRACT_NUM_TODAY_appl decimal(20,0) DEFAULT NULL COMMENT '放款合同数_本日申请',
   DISTR_CONTRACT_NUM_THMONTH_appl decimal(20,0) DEFAULT NULL COMMENT '放款合同数_本月申请',
   DISTR_CONTRACT_NUM_SEASO_appl decimal(20,0) DEFAULT NULL COMMENT '放款合同数_本季申请',
   DISTR_CONTRACT_NUM_THYEAR_appl decimal(20,0) DEFAULT NULL COMMENT '放款合同数_本年申请',  
  DISTR_CONTRACT_NUM_TODAY_credit decimal(20,0) DEFAULT NULL COMMENT '放款合同数_本日授信',
  DISTR_CONTRACT_NUM_THMONTH_credit decimal(20,0) DEFAULT NULL COMMENT '放款合同数_本月授信',
  DISTR_CONTRACT_NUM_SEASO_credit decimal(20,0) DEFAULT NULL COMMENT '放款合同数_本季授信',
  DISTR_CONTRACT_NUM_THYEAR_credit decimal(20,0) DEFAULT NULL COMMENT '放款合同数_本年授信',  
   DIST_AMT_TODAY_credit decimal(24,2) DEFAULT NULL COMMENT '放款金额_本日授信',
   DIST_AMT_THMONTH_credit decimal(24,2) DEFAULT NULL COMMENT '放款金额_本月授信',
   DIST_AMT_SEASO_credit decimal(24,2) DEFAULT NULL COMMENT '放款金额_本季授信',
   DIST_AMT_THYEAR_credit decimal(24,2) DEFAULT NULL COMMENT '放款金额_本年授信'
)
comment  '个人客户贷款汇总临时表6-申请授信对应的';

"""

# -生成mapping映射- #
bsqlmapping="""


"""

# -bsql2算法逻辑主体实现- #
bsql2="""

/*Step1:向临时表2插入数据-信贷系统申请审批信息*/

INSERT INTO {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_02
SELECT CP.unifi_cust_no AS cust_no,
       CP.iden_no,
       CP.cust_name,
       AP.handl_teller_no,
       AP.handl_org_no,
       AP.prod_no,
	   AP.MINOR_ENT_CHARACT_PROD_TYPE_CD,
       AP.currcy_cd,
	   AP.LOAN_TYPE_CD,
       AP.loan_appl_num_today,
       AP.loan_appl_num_thmonth,
       AP.loan_appl_num_seaso,
       AP.loan_appl_num_thyear,
       AP.approve_num_today,
       AP.approve_num_thmonth,
       AP.approve_num_seaso,
       AP.approve_num_thyear
FROM (SELECT A.cust_no,
             CASE WHEN A.handl_teller_no <> '' THEN A.handl_teller_no ELSE '000000' END handl_teller_no,
             CASE WHEN A.handl_org_no <> '' THEN A.handl_org_no ELSE '0099' END handl_org_no,
             CASE WHEN A.prod_no <> '' THEN A.prod_no ELSE '999999' END prod_no,
			 nvl( A.MINOR_ENT_CHARA_PROD_TYPE_CD ,'') as MINOR_ENT_CHARACT_PROD_TYPE_CD,
             CASE WHEN A.currcy_cd <> '' THEN A.currcy_cd ELSE '156' END currcy_cd,
			 A.BIZ_TYPE_CD as LOAN_TYPE_CD,
             COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','') = '{ETL_TX_DT}' THEN A.appl_no END) AS loan_appl_num_today,  -- 贷款申请笔数_本日
             COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','') BETWEEN '{I_MB_DT}' AND '{ETL_TX_DT}' THEN A.appl_no END) AS loan_appl_num_thmonth,  -- 贷款申请笔数_本月
             COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','') BETWEEN '{I_QB_DT}' AND '{ETL_TX_DT}' THEN A.appl_no END) AS loan_appl_num_seaso,  -- 贷款申请笔数_本季
             COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','') BETWEEN '{I_YB_DT}' AND '{ETL_TX_DT}' THEN A.appl_no END) AS loan_appl_num_thyear,  -- 贷款申请笔数_本年
             COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','') = '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL THEN A.appl_no END) AS approve_num_today, -- 审批笔数_本日
             COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','') BETWEEN '{I_MB_DT}' AND '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL THEN A.appl_no END) AS approve_num_thmonth, -- 审批笔数_本月
             COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','') BETWEEN '{I_QB_DT}' AND '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL THEN A.appl_no END) AS approve_num_seaso, -- 审批笔数_本季
             COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','') BETWEEN '{I_YB_DT}' AND '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL THEN A.appl_no END) AS approve_num_thyear -- 审批笔数_本年
      FROM {FDM}.F_EV_CRMS_LOAN_BIZ_APPL_INF A -- 信贷业务申请信息
      LEFT JOIN {FDM}.F_EV_PLPT_TB_BIZ_APPLY_INFO B -- 信贷前置房抵贷调度明细
           ON A.appl_no = B.chan_biz_appl_seq_no
          AND B.end_dt > DATE '{ETL_TX_DT}'
          AND B.start_dt <= DATE '{ETL_TX_DT}'
      LEFT JOIN {FDM}.F_EV_PLPT_TB_BIZ_DISPATCH_PRO_DETAIL C -- 信贷前置房抵贷调度产品明细
           ON B.FRONT_APPL_NO = C.FRONT_BIZ_APPL_NO
          AND C.end_dt > DATE '{ETL_TX_DT}'
          AND C.start_dt <= DATE '{ETL_TX_DT}'
          AND C.exec_sts_cd in ('TS00','TS06')
      WHERE A.end_dt > DATE '{ETL_TX_DT}'
          AND A.start_dt <= DATE '{ETL_TX_DT}'
          AND REPLACE(A.appl_dt,'/','') >= '{I_YB_DT}' 
          AND REPLACE(A.appl_dt,'/','') <= '{ETL_TX_DT}' 
          AND A.biz_type_cd in ( '0310','0120')   -- 0310个人贷款 0120小企业贷款 
      GROUP BY A.cust_no,
             A.handl_teller_no,
             A.handl_org_no,
             A.prod_no,
			 nvl( A.MINOR_ENT_CHARA_PROD_TYPE_CD ,''),
             A.currcy_cd,
			 A.BIZ_TYPE_CD)  AP
INNER JOIN {CDM}.C_CUS_UNIFI_CUST_INF_PRI CP
   ON AP.cust_no = CP.src_sys_cust_no
  AND CP.data_sourc='CRMS'
  AND CP.data_dt = DATE '{ETL_TX_DT}'
;



/*Step2:向临时表2插入数据-员工APP*/
INSERT INTO {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_02     
SELECT NVL(CP.unifi_cust_no,MD5(AP.iden_no||AP.cust_name)) AS cust_no, 
       AP.iden_no,
       AP.cust_name,
       AP.handl_teller_no,   
       AP.handl_org_no,        
       AP.prod_no,
       AP.MINOR_ENT_CHARACT_PROD_TYPE_CD,
       AP.currcy_cd,
	   AP.BIZ_TYPE_CD,
       AP.loan_appl_num_today,
       AP.loan_appl_num_thmonth,
       AP.loan_appl_num_seaso,
       AP.loan_appl_num_thyear,
       AP.approve_num_today,
       AP.approve_num_thmonth,
       AP.approve_num_seaso,
       AP.approve_num_thyear
FROM (SELECT A.iden_no,
             A.cust_name,
             CASE WHEN A.operr <> '' THEN A.operr ELSE '000000' END AS handl_teller_no,
             CASE WHEN A.handl_org <> '' THEN A.handl_org ELSE '0099' END AS handl_org_no,
             CASE WHEN A.loan_prod_no <> '' THEN A.loan_prod_no ELSE '999999' END AS prod_no,
			 '' as MINOR_ENT_CHARACT_PROD_TYPE_CD,    -- 小企业特色产品类型代码 
             '156' AS currcy_cd,
			 '0310' as BIZ_TYPE_CD,  -- 贷款类型代码 
             COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd') = '{ETL_TX_DT}' THEN A.biz_no END) AS loan_appl_num_today,  -- 贷款申请笔数_本日
             COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd') BETWEEN '{I_MB_DT}' AND '{ETL_TX_DT}' THEN A.biz_no END) AS loan_appl_num_thmonth,  -- 贷款申请笔数_本月
             COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd') BETWEEN '{I_QB_DT}' AND '{ETL_TX_DT}' THEN A.biz_no END) AS loan_appl_num_seaso,  -- 贷款申请笔数_本季
             COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd') BETWEEN '{I_YB_DT}' AND '{ETL_TX_DT}' THEN A.biz_no END) AS loan_appl_num_thyear,  -- 贷款申请笔数_本年
             COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd') = '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL THEN A.biz_no END) AS approve_num_today, -- 审批笔数_本日
             COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd') BETWEEN '{I_MB_DT}' AND '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL THEN A.biz_no END) AS approve_num_thmonth, -- 审批笔数_本月
             COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd') BETWEEN '{I_QB_DT}' AND '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL THEN A.biz_no END) AS approve_num_seaso,   -- 审批笔数_本季
             COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd') BETWEEN '{I_YB_DT}' AND '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL THEN A.biz_no END) AS approve_num_thyear   -- 审批笔数_本年
      FROM {FDM}.F_EV_EAPP_LOAN_BIZ_DATA_DTL A  -- 员工APP贷款业务数据
      LEFT JOIN {FDM}.F_EV_CRMS_LOAN_BIZ_APPL_INF B -- 信贷业务申请信息
          ON A.biz_no = B.appl_no
         AND B.end_dt > DATE '{ETL_TX_DT}'
         AND B.start_dt <= DATE '{ETL_TX_DT}'
      LEFT JOIN {FDM}.F_EV_PLPT_TB_BIZ_APPLY_INFO C -- 信贷前置房抵贷调度明细
           ON A.biz_no = C.chan_biz_appl_seq_no
          AND C.end_dt > DATE '{ETL_TX_DT}'
          AND C.start_dt <= DATE '{ETL_TX_DT}'
      LEFT JOIN {FDM}.F_EV_PLPT_TB_BIZ_DISPATCH_PRO_DETAIL D  -- 信贷前置房抵贷调度产品明细
           ON C.FRONT_APPL_NO = D.FRONT_BIZ_APPL_NO
          AND D.end_dt > DATE '{ETL_TX_DT}'
          AND D.start_dt <= DATE '{ETL_TX_DT}'
          AND D.exec_sts_cd in ('TS00','TS06')
      WHERE A.end_dt > DATE '{ETL_TX_DT}'
          AND A.start_dt <= DATE '{ETL_TX_DT}'
          AND to_char(A.create_tm,'yyyymmdd') >= '{I_YB_DT}'
          AND to_char(A.create_tm,'yyyymmdd') <= '{ETL_TX_DT}'
          AND a.iden_no <> '' 
          AND a.iden_no is not null
          AND B.appl_no IS NULL
      GROUP BY A.iden_no,
             A.cust_name,
             A.operr,
             A.handl_org,
             A.loan_prod_no)  AP
LEFT JOIN {CDM}.C_CUS_UNIFI_CUST_INF_PRI CP
   ON AP.iden_no = CP.iden_no
  AND AP.cust_name = CP.cust_name
  AND CP.data_dt = DATE '{ETL_TX_DT}'
GROUP BY NVL(CP.unifi_cust_no,MD5(AP.iden_no||AP.cust_name)),
       AP.iden_no,
       AP.cust_name,
       AP.handl_teller_no,   
       AP.handl_org_no,        
       AP.prod_no,
	   AP.MINOR_ENT_CHARACT_PROD_TYPE_CD,
       AP.currcy_cd,
	   AP.BIZ_TYPE_CD,
       AP.loan_appl_num_today,
       AP.loan_appl_num_thmonth,
       AP.loan_appl_num_seaso,
       AP.loan_appl_num_thyear,
       AP.approve_num_today,
       AP.approve_num_thmonth,
       AP.approve_num_seaso,
       AP.approve_num_thyear;
        
/*Step3:向临时表插入数据-贷款放款统计20240418*/  
INSERT INTO {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_03
select loan_loan_rcpt_no,
       sum(case when ETL_TX_DT = DATE'{ETL_TX_DT}' then txn_amt else 0 end) dist_amt_today,  -- 放款金额_本日 
       sum(case when ETL_TX_DT between DATE'{I_MB_DT}' and  DATE'{ETL_TX_DT}' then txn_amt else 0 end) dist_amt_thmonth,   -- 放款金额_本月
       sum(case when ETL_TX_DT between DATE'{I_QB_DT}' and  DATE'{ETL_TX_DT}' then txn_amt else 0 end) dist_amt_seaso,     -- 放款金额_本季
       sum(case when ETL_TX_DT between DATE'{I_YB_DT}' and  DATE'{ETL_TX_DT}' then txn_amt else 0 end) dist_amt_thyear     -- 放款金额_本年
from {FDM}.F_EV_CRBS_LOAN_ACCT_TXN_DTL  -- 核心贷款账户交易明细
where ETL_TX_DT between DATE'{I_YB_DT}' and DATE'{ETL_TX_DT}'
and EVENT_DESC like '%放款%'
group by loan_loan_rcpt_no;


/*Step3:向临时表插入数据-逾期回款统计20240419*/  
INSERT INTO {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_04
select loan_loan_rcpt_no,
       sum(case when ETL_TX_DT = DATE'{ETL_TX_DT}' then txn_amt else 0 end) overdue_retur_money_amt_today,  -- 逾期回款金额_本日 
       sum(case when ETL_TX_DT between DATE'{I_MB_DT}' and  DATE'{ETL_TX_DT}' then txn_amt else 0 end) overdue_retur_money_amt_thmonth,   -- 逾期回款金额_本月
       sum(case when ETL_TX_DT between DATE'{I_QB_DT}' and  DATE'{ETL_TX_DT}' then txn_amt else 0 end) overdue_retur_money_amt_seaso,     -- 逾期回款金额_本季
       sum(case when ETL_TX_DT between DATE'{I_YB_DT}' and  DATE'{ETL_TX_DT}' then txn_amt else 0 end) overdue_retur_money_amt_thyear,     -- 逾期回款金额_本年
       sum(case when BAL_FIELD like '%BJ%' and ETL_TX_DT = DATE'{ETL_TX_DT}' then txn_amt else 0 end) overdue_retur_money_principal_today,  -- 逾期回款本金_本日 
       sum(case when BAL_FIELD like '%BJ%' and ETL_TX_DT between DATE'{I_MB_DT}' and  DATE'{ETL_TX_DT}' then txn_amt else 0 end) overdue_retur_money_principal_thmonth,   -- 逾期回款本金_本月
       sum(case when BAL_FIELD like '%BJ%' and ETL_TX_DT between DATE'{I_QB_DT}' and  DATE'{ETL_TX_DT}' then txn_amt else 0 end) overdue_retur_money_principal_seaso,     -- 逾期回款本金_本季
       sum(case when BAL_FIELD like '%BJ%' and ETL_TX_DT between DATE'{I_YB_DT}' and  DATE'{ETL_TX_DT}' then txn_amt else 0 end) overdue_retur_money_principal_thyear     -- 逾期回款本金_本年
from {FDM}.F_EV_CRBS_LOAN_ACCT_TXN_DTL -- 核心贷款账户交易明细
 where etl_tx_dt between DATE'{I_YB_DT}' and DATE'{ETL_TX_DT}'
   and txn_direction_cd = '1' -- 交易方向 ：0增 1减 
   and txn_event ='LN_YQHK'   -- 逾期标准还款
group by loan_loan_rcpt_no;

     
/*Step3:向临时表插入数据-续贷预审批统计20240422-享贷*/  
INSERT INTO {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_05     
select T.LOAN_RCPT_NO,         -- 原借据编号
        T.LOAN_CONTRACT_NO,     -- 原合同编号
        T.APPROVE_TM,           -- 预审批时间
        T.APPROVE_STS_CD,       -- 审批状态
        T.APPROVE_RS_EXPIR_DT,      -- 审批结果到期日
        T.HANDL_TELLER_NO,      -- 原合同经办人
        T.HANDL_ORG_NO,         -- 原合同经办机构
        T.CUST_NO,              -- 原合同信贷客户号
        T.CLEAR_DT,             -- 原借据结清日期
        CASE WHEN THTP.LOAN_CONTRACT_NO IS NOT NULL THEN '1' ELSE '0' END AS IS_NT,	    -- 是否续贷
        THTP.LOAN_CONTRACT_NO,  -- 续贷合同流水号
        THTP.STARTDT,           -- 续贷合同起始日期
	      THTP.CONTRACT_AMT,   	-- 续贷合同金额
	      THTP.EXPIR_DT   		-- 续贷合同到期日
from (select BD.LOAN_RCPT_NO,
             BD.LOAN_CONTRACT_NO,
             T1.APPROVE_TM,
             T1.APPROVE_STS_CD,
             T1.APPROVE_RS_EXPIR_DT,
             BC.HANDL_TELLER_NO,
             BC.HANDL_ORG_NO,
             BD.CUST_NO,
             BD.CLEAR_DT,
             ROW_NUMBER() OVER(PARTITION BY BD.CORE_CUST_NO ORDER BY BD.LOAN_RCPT_NO DESC) AS NEW_INDEX
     from (select *
           from (SELECT ROW_NUMBER() OVER(PARTITION BY BRWER_IDEN_NO ORDER BY ETL_TX_DT DESC,UPDATE_TM DESC) AS NEW_INDEX,
	     		  		A.BRWER_IDEN_NO,
		     	  		A.APPROVE_TM,
			       		A.APPROVE_AMT,
			  		    A.APPROVE_STS_CD,
			  	        A.STS_DESCN_CD,
			       		A.APPROVE_RS_EXPIR_DT,
			  		    A.OPERR
     	   		FROM FDM.F_EV_PLPT_RENEWAL_LOAN_BIZ_APPL_INF A  -- 信贷前置续贷业务申请信息
     			 WHERE A.ETL_TX_DT <= DATE'{ETL_TX_DT}'
     				and APPROVE_STS_CD IN ('2','3','4','6','7') 
					/*
					1	审批中
					2	审批通过待确认
					3	确认通过
					4	确认拒绝
					5	审批拒绝
					6	放款成功
					7	合同签订成功
					8	已认领
					9	失效
					*/
				) B 
     		WHERE NEW_INDEX='1'
			) T1
     LEFT JOIN FDM.F_PT_CRMS_LOAN_BIZ_PRI_CUST_INF T2 -- 信贷业务个人客户信息
          ON T1.BRWER_IDEN_NO = T2.IDEN_NO 
         AND T2.START_DT <= DATE'{ETL_TX_DT}'
       	 AND T2.END_DT > DATE'{ETL_TX_DT}'
     LEFT JOIN CDM.C_AGT_LOAN_RCPT_INF BD  -- 贷款借据信息
          ON T2.CUST_NO = BD.CUST_NO
         AND BD.DATA_DT = DATE'{ETL_TX_DT}'
         AND BD.ACTUAL_EXPIR_DT >= SUBSTR('{ETL_TX_DT}',1,4) || '/01/01' 
     	 AND BD.PROD_NO IN ('1110240','1110250','1110260','1110270') 
		 /*
			1110240	个人快享贷
			1110250	个人金享贷
			1110260	个人薪享贷
			1110270	个人购房专享贷
		 */
     LEFT JOIN CDM.C_AGT_LOAN_CONTRACT_BASIC_INF BC   -- 贷款合同基本信息
          ON BD.LOAN_CONTRACT_NO = BC.LOAN_CONTRACT_NO
         AND BC.DATA_DT = DATE'{ETL_TX_DT}'
     WHERE BD.LOAN_CONTRACT_NO IS NOT NULL
        AND DATE (REPLACE(BD.ACTUAL_EXPIR_DT,'/','-')) - DATE (t1.APPROVE_TM) <= 35)  T
LEFT JOIN CDM.C_AGT_LOAN_CONTRACT_BASIC_INF THTP   -- 贷款合同基本信息
     ON T.CUST_NO = THTP.CUST_NO -- 240430: 另需 LOAN_CONTRACT_NO 关联吗？ 
	AND THTP.DATA_DT = DATE'{ETL_TX_DT}'
	AND T.CLEAR_DT <> DATE'0001-01-01'
    AND THTP.STARTDT >= T.CLEAR_DT 
	AND (THTP.CLEAR_DT > DATE'{ETL_TX_DT}' or THTP.CLEAR_DT = DATE'1899-12-31' or THTP.CLEAR_DT = DATE'1900-01-01')
    AND THTP.PROD_NO IN ('1110240','1110250','1110260','1110270')
WHERE T.NEW_INDEX = '1';
     
/*Step3:向临时表插入数据-续贷预审批统计20240422-房E贷*/  
-- INSERT INTO {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_05     




        
/*Step4:向临时表1插入数据-申请笔数、审批笔数*/
INSERT INTO {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_01
SELECT cust_no,
       cust_name,
       handl_teller_no,
       handl_org_no,
       prod_no,
       MINOR_ENT_CHARACT_PROD_TYPE_CD,
       currcy_cd,
       LOAN_TYPE_CD, 
       sum(loan_appl_num_today) AS loan_appl_num_today,
       SUM(loan_appl_num_thmonth) AS loan_appl_num_thmonth,
       SUM(loan_appl_num_seaso) AS loan_appl_num_seaso,
       SUM(loan_appl_num_thyear) AS loan_appl_num_thyear,
       0 AS credit_contract_num_today,    -- 授信合同数_本日 
       0 AS credit_contract_num_thmonth,  -- 授信合同数_本月
       0 AS credit_contract_num_seaso,    -- 授信合同数_本季
       0 AS credit_contract_num_thyear,   -- 授信合同数_本年    
       0 AS distr_amt_today,                  -- 投放金额_本日 
       0 AS distr_amt_thmonth,                -- 投放金额_本月
       0 AS distr_amt_seaso,                  -- 投放金额_本季
       0 AS distr_amt_thyear,                 -- 投放金额_本年 
	   0 AS distr_amt_his_cumul,              -- 投放金额_历史累计 
       0 AS distr_num_today,                  -- 投放笔数_本日 
       0 AS distr_num_thmonth,                     -- 投放笔数_本月
       0 AS distr_num_seaso,                       -- 投放笔数_本季
       0 AS distr_num_thyear,                      -- 投放笔数_本年
	   0 AS distr_num_his_cumul,                   -- 投放笔数_历史累计
       0 AS renewal_loan_contract_num_today,       -- 续贷合同数_本日
       0 AS renewal_loan_contract_num_thmonth,     -- 续贷合同数_本月   
       0 AS renewal_loan_contract_num_seaso,       -- 续贷合同数_本季   
       0 AS renewal_loan_contract_num_thyear,      -- 续贷合同数_本年   
       0 AS unpay_off_contract_num,                -- 未结清合同数
       0 AS bal,                                   -- 余额
       0 AS np_bal,                                -- 不良余额
       0 AS overdue_bal,                           -- 逾期余额
       0 AS nor_bal,                         -- 正常余额
       0 AS concern_bal,                      -- 关注余额
       0 AS scdary_bal,                     -- 次级余额
       0 AS suspi_bal,                       -- 可疑余额
       0 AS loss_bal,                        -- 损失余额
       0 AS nor_num,                          -- 正常笔数
       0 AS concern_num,                       -- 关注笔数
       0 AS scdary_num,                      -- 次级笔数
       0 AS suspi_num,                        -- 可疑笔数
       0 AS loss_num,                          -- 损失笔数
       SUM(approve_num_today) AS approve_num_today,              -- 审批笔数_本日
       SUM(approve_num_thmonth) AS approve_num_thmonth,            -- 审批笔数_本月
       SUM(approve_num_seaso) AS approve_num_seaso,              -- 审批笔数_本季
       SUM(approve_num_thyear) AS approve_num_thyear,             -- 审批笔数_本年
       0 AS loan_contract_num_today,    -- 贷款合同数_本日 
       0 AS loan_contract_num_thmonth,  -- 贷款合同数_本月
       0 AS loan_contract_num_seaso,    -- 贷款合同数_本季
       0 AS loan_contract_num_thyear,   -- 贷款合同数_本年
       0 AS distr_contract_num_today,    -- 放款合同数_本日 
       0 AS distr_contract_num_thmonth,  -- 放款合同数_本月
       0 AS distr_contract_num_seaso,    -- 放款合同数_本季
       0 AS distr_contract_num_thyear,   -- 放款合同数_本年
       0 AS credit_amt_today,    -- 授信金额_本日 
       0 AS credit_amt_thmonth,  -- 授信金额_本月
       0 AS credit_amt_seaso,    -- 授信金额_本季
       0 AS credit_amt_thyear,   -- 授信金额_本年
       0 AS dist_amt_today,    -- 放款金额_本日 
       0 AS dist_amt_thmonth,  -- 放款金额_本月
       0 AS dist_amt_seaso,    -- 放款金额_本季
       0 AS dist_amt_thyear,   -- 放款金额_本年
       0 as renewal_loan_pre_approvevia_num_today, -- 续贷预审批通过数_本日',
       0 as renewal_loan_pre_approvevia_num_thmonth, -- 续贷预审批通过数_本月',
       0 as renewal_loan_pre_approvevia_num_seaso, -- 续贷预审批通过数_本季',
       0 as renewal_loan_pre_approvevia_num_thyear, -- 续贷预审批通过数_本年',
       0 as renewal_loan_cust_manag_cfm_num_today, -- 续贷客户经理确认数_本日',
       0 as renewal_loan_cust_manag_cfm_num_thmonth, -- 续贷客户经理确认数_本月',
       0 as renewal_loan_cust_manag_cfm_num_seaso, -- 续贷客户经理确认数_本季',
       0 as renewal_loan_cust_manag_cfm_num_thyear, -- 续贷客户经理确认数_本年',
       0 as renewal_loan_contract_num_model_today, -- 续贷合同数(模型)_本日',
       0 as renewal_loan_contract_num_model_thmonth, -- 续贷合同数(模型)_本月',
       0 as renewal_loan_contract_num_model_seaso, -- 续贷合同数(模型)_本季',
       0 as renewal_loan_contract_num_model_thyear, -- 续贷合同数(模型)_本年',
       0 As overdue_retur_money_amt_today,   -- 逾期回款金额_本日
       0 As overdue_retur_money_amt_thmonth,   -- 逾期回款金额_本月
       0 As overdue_retur_money_amt_seaso,   -- 逾期回款金额_本季
       0 As overdue_retur_money_amt_thyear,   -- 逾期回款金额_本年
       0 As overdue_retur_money_contract_num_today,   -- 逾期回款合同数_本日
       0 As overdue_retur_money_contract_num_thmonth,   -- 逾期回款合同数_本月
       0 As overdue_retur_money_contract_num_seaso,   -- 逾期回款合同数_本季
       0 As overdue_retur_money_contract_num_thyear,   -- 逾期回款合同数_本年
       0 As overdue_retur_money_principal_today,   -- 逾期回款本金_本日
       0 As overdue_retur_money_principal_thmontH,   -- 逾期回款本金_本月
       0 As overdue_retur_money_principal_seaso,   -- 逾期回款本金_本季
       0 As overdue_retur_money_principal_thyear   -- 逾期回款本金_本年
FROM {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_02
WHERE loan_appl_num_thyear > 0
GROUP BY cust_no,
       cust_name,
       handl_teller_no,
       handl_org_no,
       prod_no,
       MINOR_ENT_CHARACT_PROD_TYPE_CD,
       currcy_cd,
       LOAN_TYPE_CD;
       
    
/*Step5:向临时表1插入数据-个人传统贷款*/
INSERT INTO {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_01
SELECT CP.unifi_cust_no AS cust_no,                    -- 客户号
       CP.cust_name AS cust_name,                -- 客户姓名
       CASE WHEN LR.prod_no NOT IN ('1110240','1110250','1110260','1110270') AND LR.manager_teller_no <> '' THEN LR.manager_teller_no -- 20240418修改 享贷以合同经办人为准其他优先以借据
            WHEN LC.handl_teller_no <>'' THEN LC.handl_teller_no ELSE '000000' END AS handl_teller_no,    -- 经办柜员编号 
       CASE WHEN LR.prod_no NOT IN ('1110240','1110250','1110260','1110270') AND LR.manager_org_no  <> '' THEN LR.manager_org_no -- 20240418修改 享贷以合同经办人为准其他优先以借据  
            WHEN LC.handl_org_no <>'' THEN LC.handl_org_no ELSE '0099' END AS handl_org_no,          -- 经办机构号
       CASE WHEN LC.prod_no <> '' THEN LC.prod_no ELSE '999999' END AS prod_no,                    -- 产品编号
	   nvl(LR.minor_ent_charact_prod_type_cd,'') as minor_ent_charact_prod_type_cd , -- 小企业特色产品类型代码
       CASE WHEN LC.currcy_cd <> '' THEN LC.currcy_cd ELSE '156' END AS currcy_cd,                -- 币种代码
	   LC.biz_type_cd as LOAN_TYPE_CD,   -- 贷款类型代码
       0 AS loan_appl_num_today,                 -- 贷款申请笔数_本日
       0 AS loan_appl_num_thmonth,                 -- 贷款申请笔数_本月
       0 AS loan_appl_num_seaso,                 -- 贷款申请笔数_本季
       0 AS loan_appl_num_thyear,                 -- 贷款申请笔数_本年
       0 AS credit_contract_num_today,       -- 授信合同数_本日  20240418添加合同状态为有效
       0 AS credit_contract_num_thmonth,  -- 授信合同数_本月
       0 AS credit_contract_num_seaso,    -- 授信合同数_本季
       0 AS credit_contract_num_thyear,   -- 授信合同数_本年 
       SUM(CASE WHEN LR.prod_no NOT IN ('1110240','1110250','1110260','1110270') AND LR.out_acct_dt = DATE '{ETL_TX_DT}' THEN LR.out_acct_amt ELSE 0 END) AS distr_amt_today,                                            -- 投放金额_本日  20240418 享贷特殊处理，以合同金额计算 
       SUM(CASE WHEN LR.prod_no NOT IN ('1110240','1110250','1110260','1110270') AND LR.out_acct_dt BETWEEN DATE'{I_MB_DT}' AND DATE '{ETL_TX_DT}' THEN LR.out_acct_amt ELSE 0 END) AS distr_amt_thmonth,                -- 投放金额_本月  20240418 享贷特殊处理，以合同金额计算
       SUM(CASE WHEN LR.prod_no NOT IN ('1110240','1110250','1110260','1110270') AND LR.out_acct_dt BETWEEN DATE'{I_QB_DT}' AND DATE '{ETL_TX_DT}' THEN LR.out_acct_amt ELSE 0 END) AS distr_amt_seaso,                  -- 投放金额_本季  20240418 享贷特殊处理，以合同金额计算
       SUM(CASE WHEN LR.prod_no NOT IN ('1110240','1110250','1110260','1110270') AND LR.out_acct_dt BETWEEN DATE'{I_YB_DT}' AND DATE '{ETL_TX_DT}' THEN LR.out_acct_amt ELSE 0 END) AS distr_amt_thyear,                 -- 投放金额_本年  20240418 享贷特殊处理，以合同金额计算
       SUM(CASE WHEN LR.prod_no NOT IN ('1110240','1110250','1110260','1110270')  THEN LR.out_acct_amt ELSE 0 END) AS distr_amt_his_cumul,                 -- 投放金额_历史累计  20240523加 享贷特殊处理，以合同金额计算	   
       COUNT(CASE WHEN LR.prod_no NOT IN ('1110240','1110250','1110260','1110270') AND LR.out_acct_dt = DATE '{ETL_TX_DT}' THEN LR.loan_rcpt_no END) AS distr_num_today,                                                 -- 投放笔数_本日  20240418 享贷特殊处理，以合同计算笔数
       COUNT(CASE WHEN LR.prod_no NOT IN ('1110240','1110250','1110260','1110270') AND LR.out_acct_dt BETWEEN DATE'{I_MB_DT}' AND DATE '{ETL_TX_DT}' THEN LR.loan_rcpt_no END) AS distr_num_thmonth,                     -- 投放笔数_本月  20240418 享贷特殊处理，以合同计算笔数
       COUNT(CASE WHEN LR.prod_no NOT IN ('1110240','1110250','1110260','1110270') AND LR.out_acct_dt BETWEEN DATE'{I_QB_DT}' AND DATE '{ETL_TX_DT}' THEN LR.loan_rcpt_no END) AS distr_num_seaso,                       -- 投放笔数_本季  20240418 享贷特殊处理，以合同计算笔数
       COUNT(CASE WHEN LR.prod_no NOT IN ('1110240','1110250','1110260','1110270') AND LR.out_acct_dt BETWEEN DATE'{I_YB_DT}' AND DATE '{ETL_TX_DT}' THEN LR.loan_rcpt_no END) AS distr_num_thyear,                      -- 投放笔数_本年  20240418 享贷特殊处理，以合同计算笔数
       COUNT(CASE WHEN LR.prod_no NOT IN ('1110240','1110250','1110260','1110270')  THEN LR.loan_rcpt_no END) AS distr_num_his_cumul,                      -- 投放笔数_历史累计  20240523加 享贷特殊处理，以合同计算笔数
       COUNT(DISTINCT CASE WHEN LC.startdt = DATE '{ETL_TX_DT}' AND LC.prod_no IN ('1110240','1110250','1110260','1110270') AND LC.startdt > LC_XD_FI.clear_dt THEN LC.loan_contract_no
                           WHEN LC.startdt = DATE '{ETL_TX_DT}' AND LC.prod_no IN ('1110010','1110020','1110080','1110131') AND LC.startdt > LC_FED_FI.clear_dt THEN LC.loan_contract_no 
                           WHEN LC.startdt = DATE '{ETL_TX_DT}' AND LC.startdt > LC_QT_FI.clear_dt THEN LC.loan_contract_no END) AS renewal_loan_contract_num_today,       -- 续贷合同数_本日
       COUNT(DISTINCT CASE WHEN LC.startdt BETWEEN DATE'{I_MB_DT}' AND DATE '{ETL_TX_DT}' AND LC.prod_no IN ('1110240','1110250','1110260','1110270') AND LC.startdt > LC_XD_FI.clear_dt THEN LC.loan_contract_no
                           WHEN LC.startdt BETWEEN DATE'{I_MB_DT}' AND DATE '{ETL_TX_DT}' AND LC.prod_no IN ('1110010','1110020','1110080','1110131') AND LC.startdt > LC_FED_FI.clear_dt THEN LC.loan_contract_no 
                           WHEN LC.startdt BETWEEN DATE'{I_MB_DT}' AND DATE '{ETL_TX_DT}' AND LC.startdt > LC_QT_FI.clear_dt THEN LC.loan_contract_no END) AS renewal_loan_contract_num_thmonth,     -- 续贷合同数_本月   
       COUNT(DISTINCT CASE WHEN LC.startdt BETWEEN DATE'{I_QB_DT}' AND DATE '{ETL_TX_DT}' AND LC.prod_no IN ('1110240','1110250','1110260','1110270') AND LC.startdt > LC_XD_FI.clear_dt THEN LC.loan_contract_no
                           WHEN LC.startdt BETWEEN DATE'{I_QB_DT}' AND DATE '{ETL_TX_DT}' AND LC.prod_no IN ('1110010','1110020','1110080','1110131') AND LC.startdt > LC_FED_FI.clear_dt THEN LC.loan_contract_no 
                           WHEN LC.startdt BETWEEN DATE'{I_QB_DT}' AND DATE '{ETL_TX_DT}' AND LC.startdt > LC_QT_FI.clear_dt THEN LC.loan_contract_no END) AS renewal_loan_contract_num_seaso,       -- 续贷合同数_本季   
       COUNT(DISTINCT CASE WHEN LC.startdt BETWEEN DATE'{I_YB_DT}' AND DATE '{ETL_TX_DT}' AND LC.prod_no IN ('1110240','1110250','1110260','1110270') AND LC.startdt > LC_XD_FI.clear_dt THEN LC.loan_contract_no
                           WHEN LC.startdt BETWEEN DATE'{I_YB_DT}' AND DATE '{ETL_TX_DT}' AND LC.prod_no IN ('1110010','1110020','1110080','1110131') AND LC.startdt > LC_FED_FI.clear_dt THEN LC.loan_contract_no 
                           WHEN LC.startdt BETWEEN DATE'{I_YB_DT}' AND DATE '{ETL_TX_DT}' AND LC.startdt > LC_QT_FI.clear_dt THEN LC.loan_contract_no END) AS renewal_loan_contract_num_thyear,      -- 续贷合同数_本年 
       COUNT(DISTINCT CASE WHEN LC.clear_dt > DATE '{ETL_TX_DT}' THEN LC.loan_contract_no END) AS unpay_off_contract_num,                                -- 未结清合同数
       SUM(CASE WHEN LR.asset_trans_type_cd <> '1' THEN LR.loan_bal ELSE 0 END) AS bal,                                                                             -- 余额 (asset_trans_type_cd: 资产转让类型代码)
       SUM(CASE WHEN LR.asset_trans_type_cd <> '1' AND SUBSTR(LR.fiveclass_class_rs_cd_n,1,2) IN ('03','04','05') THEN LR.loan_bal ELSE 0 END) AS np_bal,             -- 不良余额
       SUM(CASE WHEN LR.asset_trans_type_cd <> '1' AND LR.overdue_days > 0 THEN LR.loan_bal ELSE 0 END) AS overdue_bal,                                             -- 逾期余额
       SUM(CASE WHEN LR.asset_trans_type_cd <> '1' AND SUBSTR(LR.fiveclass_class_rs_cd_n,1,2) = '01' THEN LR.loan_bal ELSE 0 END) AS nor_bal,                         -- 正常余额
       SUM(CASE WHEN LR.asset_trans_type_cd <> '1' AND SUBSTR(LR.fiveclass_class_rs_cd_n,1,2) = '02' THEN LR.loan_bal ELSE 0 END) AS concern_bal,                      -- 关注余额
       SUM(CASE WHEN LR.asset_trans_type_cd <> '1' AND SUBSTR(LR.fiveclass_class_rs_cd_n,1,2) = '03' THEN LR.loan_bal ELSE 0 END) AS scdary_bal,                     -- 次级余额
       SUM(CASE WHEN LR.asset_trans_type_cd <> '1' AND SUBSTR(LR.fiveclass_class_rs_cd_n,1,2) = '04' THEN LR.loan_bal ELSE 0 END) AS suspi_bal,                       -- 可疑余额
       SUM(CASE WHEN LR.asset_trans_type_cd <> '1' AND SUBSTR(LR.fiveclass_class_rs_cd_n,1,2) = '05' THEN LR.loan_bal ELSE 0 END) AS loss_bal,                        -- 损失余额
       COUNT(CASE WHEN LR.asset_trans_type_cd <> '1' AND SUBSTR(LR.fiveclass_class_rs_cd_n,1,2) = '01' AND LR.out_acct_dt <= DATE '{ETL_TX_DT}' AND (LR.CLEAR_DT > DATE '{ETL_TX_DT}' OR LR.CLEAR_DT = DATE '{NULL_DATE}' OR LR.CLEAR_DT IS NULL) THEN LR.loan_rcpt_no END) AS nor_num,                          -- 正常笔数
       COUNT(CASE WHEN LR.asset_trans_type_cd <> '1' AND SUBSTR(LR.fiveclass_class_rs_cd_n,1,2) = '02' AND LR.out_acct_dt <= DATE '{ETL_TX_DT}' AND (LR.CLEAR_DT > DATE '{ETL_TX_DT}' OR LR.CLEAR_DT = DATE '{NULL_DATE}' OR LR.CLEAR_DT IS NULL) THEN LR.loan_rcpt_no END) AS concern_num,                       -- 关注笔数
       COUNT(CASE WHEN LR.asset_trans_type_cd <> '1' AND SUBSTR(LR.fiveclass_class_rs_cd_n,1,2) = '03' AND LR.out_acct_dt <= DATE '{ETL_TX_DT}' AND (LR.CLEAR_DT > DATE '{ETL_TX_DT}' OR LR.CLEAR_DT = DATE '{NULL_DATE}' OR LR.CLEAR_DT IS NULL) THEN LR.loan_rcpt_no END) AS scdary_num,                      -- 次级笔数
       COUNT(CASE WHEN LR.asset_trans_type_cd <> '1' AND SUBSTR(LR.fiveclass_class_rs_cd_n,1,2) = '04' AND LR.out_acct_dt <= DATE '{ETL_TX_DT}' AND (LR.CLEAR_DT > DATE '{ETL_TX_DT}' OR LR.CLEAR_DT = DATE '{NULL_DATE}' OR LR.CLEAR_DT IS NULL) THEN LR.loan_rcpt_no END) AS suspi_num,                        -- 可疑笔数
       COUNT(CASE WHEN LR.asset_trans_type_cd <> '1' AND SUBSTR(LR.fiveclass_class_rs_cd_n,1,2) = '05' AND LR.out_acct_dt <= DATE '{ETL_TX_DT}' AND (LR.CLEAR_DT > DATE '{ETL_TX_DT}' OR LR.CLEAR_DT = DATE '{NULL_DATE}' OR LR.CLEAR_DT IS NULL) THEN LR.loan_rcpt_no END) AS loss_num,                          -- 损失笔数
       0 AS APPROVE_NUM_TODAY,              -- 审批笔数_本日
       0 AS APPROVE_NUM_THMONTH,            -- 审批笔数_本月
       0 AS APPROVE_NUM_SEASO,              -- 审批笔数_本季
       0 AS APPROVE_NUM_THYEAR,             -- 审批笔数_本年
       0 AS loan_contract_num_today,    -- 贷款合同数_本日 
       0 AS loan_contract_num_thmonth,  -- 贷款合同数_本月
       0 AS loan_contract_num_seaso,    -- 贷款合同数_本季
       0 AS loan_contract_num_thyear,   -- 贷款合同数_本年
       COUNT(DISTINCT CASE WHEN LR_DISTR.dist_amt_today > 0 THEN LC.loan_contract_no END) AS distr_contract_num_today,    -- 放款合同数_本日 
       COUNT(DISTINCT CASE WHEN LR_DISTR.dist_amt_thmonth > 0 THEN LC.loan_contract_no END) AS distr_contract_num_thmonth,  -- 放款合同数_本月
       COUNT(DISTINCT CASE WHEN LR_DISTR.dist_amt_seaso > 0 THEN LC.loan_contract_no END) AS distr_contract_num_seaso,    -- 放款合同数_本季
       COUNT(DISTINCT CASE WHEN LR_DISTR.dist_amt_thyear > 0 THEN LC.loan_contract_no END) AS distr_contract_num_thyear,   -- 放款合同数_本年
       0 AS credit_amt_today,    -- 授信金额_本日 
       0 AS credit_amt_thmonth,  -- 授信金额_本月
       0 AS credit_amt_seaso,    -- 授信金额_本季
       0 AS credit_amt_thyear,   -- 授信金额_本年
       SUM(LR_DISTR.dist_amt_today) AS dist_amt_today,    -- 放款金额_本日 
       SUM(LR_DISTR.dist_amt_thmonth) AS dist_amt_thmonth,  -- 放款金额_本月
       SUM(LR_DISTR.dist_amt_seaso) AS dist_amt_seaso,    -- 放款金额_本季
       SUM(LR_DISTR.dist_amt_thyear) AS dist_amt_thyear,   -- 放款金额_本年
        
       /*续贷模型*/
       COUNT(DISTINCT CASE WHEN REPLACE(XD.APPROVE_TM, '-','') = '{ETL_TX_DT}' THEN XD.LOAN_CONTRACT_NO END) as renewal_loan_pre_approvevia_num_today, -- 续贷预审批通过数_本日',
       COUNT(DISTINCT CASE WHEN REPLACE(XD.APPROVE_TM, '-','') BETWEEN '{I_MB_DT}' AND '{ETL_TX_DT}' THEN XD.LOAN_CONTRACT_NO END) as renewal_loan_pre_approvevia_num_thmonth, -- 续贷预审批通过数_本月',
       COUNT(DISTINCT CASE WHEN REPLACE(XD.APPROVE_TM, '-','') BETWEEN '{I_QB_DT}' AND '{ETL_TX_DT}' THEN XD.LOAN_CONTRACT_NO END) as renewal_loan_pre_approvevia_num_seaso, -- 续贷预审批通过数_本季',
       COUNT(DISTINCT CASE WHEN REPLACE(XD.APPROVE_TM, '-','') BETWEEN '{I_YB_DT}' AND '{ETL_TX_DT}' THEN XD.LOAN_CONTRACT_NO END) as renewal_loan_pre_approvevia_num_thyear, -- 续贷预审批通过数_本年',
       COUNT(DISTINCT CASE WHEN XD.APPROVE_STS_CD IN ('3','6','7') AND REPLACE(XD.APPROVE_TM, '-','') = '{ETL_TX_DT}' THEN XD.LOAN_CONTRACT_NO END) as renewal_loan_cust_manag_cfm_num_today, -- 续贷客户经理确认数_本日',
       COUNT(DISTINCT CASE WHEN XD.APPROVE_STS_CD IN ('3','6','7') AND REPLACE(XD.APPROVE_TM, '-','') BETWEEN '{I_MB_DT}' AND '{ETL_TX_DT}' THEN XD.LOAN_CONTRACT_NO END) as renewal_loan_cust_manag_cfm_num_thmonth, -- 续贷客户经理确认数_本月',
       COUNT(DISTINCT CASE WHEN XD.APPROVE_STS_CD IN ('3','6','7') AND REPLACE(XD.APPROVE_TM, '-','') BETWEEN '{I_QB_DT}' AND '{ETL_TX_DT}' THEN XD.LOAN_CONTRACT_NO END) as renewal_loan_cust_manag_cfm_num_seaso, -- 续贷客户经理确认数_本季',
       COUNT(DISTINCT CASE WHEN XD.APPROVE_STS_CD IN ('3','6','7') AND REPLACE(XD.APPROVE_TM, '-','') BETWEEN '{I_YB_DT}' AND '{ETL_TX_DT}' THEN XD.LOAN_CONTRACT_NO END) as renewal_loan_cust_manag_cfm_num_thyear, -- 续贷客户经理确认数_本年',
       COUNT(DISTINCT CASE WHEN XD.APPROVE_STS_CD IN ('6','7') AND REPLACE(XD.APPROVE_TM, '-','') = '{ETL_TX_DT}' THEN XD.LOAN_CONTRACT_NO END) as renewal_loan_contract_num_model_today, -- 续贷合同数(模型)_本日',
       COUNT(DISTINCT CASE WHEN XD.APPROVE_STS_CD IN ('6','7') AND REPLACE(XD.APPROVE_TM, '-','') BETWEEN '{I_MB_DT}' AND '{ETL_TX_DT}' THEN XD.LOAN_CONTRACT_NO END) as renewal_loan_contract_num_model_thmonth, -- 续贷合同数(模型)_本月',
       COUNT(DISTINCT CASE WHEN XD.APPROVE_STS_CD IN ('6','7') AND REPLACE(XD.APPROVE_TM, '-','') BETWEEN '{I_QB_DT}' AND '{ETL_TX_DT}' THEN XD.LOAN_CONTRACT_NO END) as renewal_loan_contract_num_model_seaso, -- 续贷合同数(模型)_本季',
       COUNT(DISTINCT CASE WHEN XD.APPROVE_STS_CD IN ('6','7') AND REPLACE(XD.APPROVE_TM, '-','') BETWEEN '{I_YB_DT}' AND '{ETL_TX_DT}' THEN XD.LOAN_CONTRACT_NO END) as renewal_loan_contract_num_model_thyear, -- 续贷合同数(模型)_本年',
       
       /*逾期回款*/
       SUM(nvl(LR_RETUR.overdue_retur_money_amt_today,0)) AS overdue_retur_money_amt_today,   -- 逾期回款金额_本日
       SUM(nvl(LR_RETUR.overdue_retur_money_amt_thmonth,0)) AS overdue_retur_money_amt_thmonth,   -- 逾期回款金额_本月
       SUM(nvl(LR_RETUR.overdue_retur_money_amt_seaso,0)) AS overdue_retur_money_amt_seaso,   -- 逾期回款金额_本季
       SUM(nvl(LR_RETUR.overdue_retur_money_amt_thyear,0)) AS overdue_retur_money_amt_thyear,   -- 逾期回款金额_本年
       COUNT(DISTINCT case when LR_RETUR.overdue_retur_money_amt_today > 0 then LC.LOAN_CONTRACT_NO END) AS overdue_retur_money_contract_num_today,   -- 逾期回款合同数_本日
       COUNT(DISTINCT case when LR_RETUR.overdue_retur_money_amt_thmonth > 0 then LC.LOAN_CONTRACT_NO END) AS overdue_retur_money_contract_num_thmonth,   -- 逾期回款合同数_本月
       COUNT(DISTINCT case when LR_RETUR.overdue_retur_money_amt_seaso > 0 then LC.LOAN_CONTRACT_NO END) AS overdue_retur_money_contract_num_seaso,   -- 逾期回款合同数_本季
       COUNT(DISTINCT case when LR_RETUR.overdue_retur_money_amt_thyear > 0 then LC.LOAN_CONTRACT_NO END) AS overdue_retur_money_contract_num_thyear,   -- 逾期回款合同数_本年
       SUM(nvl(LR_RETUR.overdue_retur_money_principal_today,0)) AS overdue_retur_money_principal_today,   -- 逾期回款本金_本日
       SUM(nvl(LR_RETUR.overdue_retur_money_principal_thmonth,0)) AS overdue_retur_money_principal_thmonth,   -- 逾期回款本金_本月
       SUM(nvl(LR_RETUR.overdue_retur_money_principal_seaso,0)) AS overdue_retur_money_principal_seaso,   -- 逾期回款本金_本季
       SUM(nvl(LR_RETUR.overdue_retur_money_principal_thyear,0)) AS overdue_retur_money_principal_thyear   -- 逾期回款本金_本年

FROM {CDM}.C_AGT_LOAN_CONTRACT_BASIC_INF LC    -- 贷款合同基本信息
LEFT JOIN (SELECT A.*, 
                  CASE WHEN '{ETL_TX_DT}' = '{I_ME_DT}' THEN fiveclass_class_rs_cd
                       WHEN overdue_days = 0 THEN '01'
                       WHEN overdue_days > 0 AND overdue_days <= 60 AND biz_org_no LIKE '08%' THEN '02'       -- 青岛分行0-60天为关注
                       WHEN overdue_days > 0 AND overdue_days <= 90 AND biz_org_no NOT LIKE '08%' THEN '02'   -- 其他分行0-90天为关注
                       WHEN overdue_days > 60 AND overdue_days <= 270 AND biz_org_no LIKE '08%' THEN '03'     -- 青岛分行60-180天为次级  20231225 修改为60-270
                       WHEN overdue_days > 90 AND overdue_days <= 270 AND biz_org_no NOT LIKE '08%' THEN '03' -- 其他分行90-180天为次级  20231225 修改为60-270
                       WHEN overdue_days > 270 AND overdue_days <= 360 THEN '04'                              -- 20231225 修改为270-360
                   ELSE '05' END fiveclass_class_rs_cd_n
           FROM {CDM}.C_AGT_LOAN_RCPT_INF A 
           WHERE data_dt = DATE '{ETL_TX_DT}' 
           AND loan_type_cd in ( '0310','0120')   -- 0310个人贷款 0120小企业贷款 
		   ) LR
     ON LC.loan_contract_no = LR.loan_contract_no
INNER JOIN {CDM}.C_CUS_UNIFI_CUST_INF_PRI CP   -- 统一客户信息对私
     ON LC.cust_no = CP.src_sys_cust_no
    AND CP.data_dt = DATE '{ETL_TX_DT}'
LEFT JOIN (
SELECT cust_no,biz_type_cd,MIN(clear_dt) AS clear_dt
           FROM {CDM}.C_AGT_LOAN_CONTRACT_BASIC_INF  -- 贷款合同基本信息
           WHERE data_dt = DATE '{ETL_TX_DT}'
             AND biz_type_cd  in ( '0310')   -- 0310个人贷款 0120小企业贷款
             AND prod_no in ('1110240','1110250','1110260','1110270')
             AND clear_dt < DATE '{ETL_TX_DT}'
             AND clear_dt <> DATE '{NULL_DATE}'
           GROUP BY cust_no,biz_type_cd	
union all		   
SELECT cust_no,biz_type_cd,MIN(clear_dt) AS clear_dt
           FROM {CDM}.C_AGT_LOAN_CONTRACT_BASIC_INF  -- 贷款合同基本信息
           WHERE data_dt = DATE '{ETL_TX_DT}'
             AND biz_type_cd  in ( '0120')   -- 0310个人贷款 0120小企业贷款
             AND prod_no in ('1110240','1110250','1110260','1110270')
             AND clear_dt < DATE '{ETL_TX_DT}'
             AND clear_dt <> DATE '{NULL_DATE}'
           GROUP BY cust_no	,biz_type_cd	   
		   		  		   ) LC_XD_FI
     ON LC.cust_no = LC_XD_FI.cust_no
	 and LC.biz_type_cd=LC_XD_FI.biz_type_cd
	 
LEFT JOIN (
SELECT cust_no,biz_type_cd,MIN(clear_dt) AS clear_dt
           FROM {CDM}.C_AGT_LOAN_CONTRACT_BASIC_INF  -- 贷款合同基本信息
           WHERE data_dt = DATE '{ETL_TX_DT}'
             AND biz_type_cd  in ( '0310')   -- 0310个人贷款 0120小企业贷款
             AND prod_no in ('1110010','1110020','1110080','1110131') -- 个人一手住房贷款、个人二手住房贷款、个人循环贷款、个人综合消费贷款
             AND clear_dt < DATE '{ETL_TX_DT}'
             AND clear_dt <> DATE '{NULL_DATE}'
           GROUP BY cust_no,biz_type_cd
		   
union all	
SELECT cust_no,biz_type_cd,MIN(clear_dt) AS clear_dt
           FROM {CDM}.C_AGT_LOAN_CONTRACT_BASIC_INF  -- 贷款合同基本信息
           WHERE data_dt = DATE '{ETL_TX_DT}'
             AND biz_type_cd  in ( '0120')   -- 0310个人贷款 0120小企业贷款
             AND prod_no in ('1110010','1110020','1110080','1110131') -- 个人一手住房贷款、个人二手住房贷款、个人循环贷款、个人综合消费贷款
             AND clear_dt < DATE '{ETL_TX_DT}'
             AND clear_dt <> DATE '{NULL_DATE}'
           GROUP BY cust_no,biz_type_cd	   

		   ) LC_FED_FI  -- 房E贷 ？
     ON LC.cust_no = LC_FED_FI.cust_no
	 and LC.biz_type_cd= LC_FED_FI.biz_type_cd
LEFT JOIN (SELECT cust_no,biz_type_cd,prod_no,MIN(clear_dt) AS clear_dt
           FROM {CDM}.C_AGT_LOAN_CONTRACT_BASIC_INF   -- 贷款合同基本信息
           WHERE data_dt = DATE '{ETL_TX_DT}'
             AND biz_type_cd in ( '0310')   -- 0310个人贷款 0120小企业贷款
             AND prod_no not in ('1110240','1110250','1110260','1110270','1110010','1110020','1110080','1110131')
             AND clear_dt < DATE '{ETL_TX_DT}'
             AND clear_dt <> DATE '{NULL_DATE}'
           GROUP BY cust_no,prod_no,biz_type_cd

union all
SELECT cust_no,biz_type_cd,prod_no,MIN(clear_dt) AS clear_dt
           FROM {CDM}.C_AGT_LOAN_CONTRACT_BASIC_INF   -- 贷款合同基本信息
           WHERE data_dt = DATE '{ETL_TX_DT}'
             AND biz_type_cd in ('0120')   -- 0310个人贷款 0120小企业贷款
             AND prod_no not in ('1110240','1110250','1110260','1110270','1110010','1110020','1110080','1110131')
             AND clear_dt < DATE '{ETL_TX_DT}'
             AND clear_dt <> DATE '{NULL_DATE}'
           GROUP BY cust_no,prod_no,biz_type_cd		   
		   
		   ) LC_QT_FI
     ON LC.cust_no = LC_QT_FI.cust_no
    AND LC.prod_no = LC_QT_FI.prod_no
LEFT JOIN {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_03 LR_DISTR
     ON LR.loan_rcpt_no = LR_DISTR.loan_loan_rcpt_no
LEFT JOIN {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_04 LR_RETUR
     ON  LR.loan_rcpt_no = LR_RETUR.loan_loan_rcpt_no
LEFT JOIN {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_05 XD
     ON  LR.loan_rcpt_no = XD.loan_loan_rcpt_no
WHERE LC.data_dt = DATE '{ETL_TX_DT}'
AND LC.biz_type_cd in ( '0310','0120')   -- 0310个人贷款 0120小企业贷款
GROUP BY CP.unifi_cust_no,
       CP.cust_name,
       CASE WHEN LR.prod_no NOT IN ('1110240','1110250','1110260','1110270') AND LR.manager_teller_no <> '' THEN LR.manager_teller_no -- 20240418修改 享贷以合同经办人为准其他优先以借据
            WHEN LC.handl_teller_no <>'' THEN LC.handl_teller_no ELSE '000000' END,    -- 经办柜员编号
       CASE WHEN LR.prod_no NOT IN ('1110240','1110250','1110260','1110270') AND LR.manager_org_no  <> '' THEN LR.manager_org_no
            WHEN LC.handl_org_no <>'' THEN LC.handl_org_no ELSE '0099' END,          -- 经办机构号
       CASE WHEN LC.prod_no <> '' THEN LC.prod_no ELSE '999999' END,                    -- 产品编号
	   nvl(LR.minor_ent_charact_prod_type_cd,'') , -- 小企业特色产品类型代码
       CASE WHEN LC.currcy_cd <> '' THEN LC.currcy_cd ELSE '156' END,             -- 币种代码
	   LC.biz_type_cd   -- 贷款类型代码
     
	   
UNION ALL
SELECT CP.unifi_cust_no AS cust_no,                    -- 客户号
       CP.cust_name AS cust_name,                -- 客户姓名
       CASE WHEN LC.handl_teller_no <>'' THEN LC.handl_teller_no ELSE '000000' END AS handl_teller_no,    -- 经办柜员编号 
       CASE WHEN LC.handl_org_no <>'' THEN LC.handl_org_no ELSE '0099' END AS handl_org_no,          -- 经办机构号
       CASE WHEN LC.prod_no <> '' THEN LC.prod_no ELSE '999999' END AS prod_no,                    -- 产品编号
	   '' as minor_ent_charact_prod_type_cd, -- 小企业特色产品类型代码
       CASE WHEN LC.currcy_cd <> '' THEN LC.currcy_cd ELSE '156' END AS currcy_cd,                -- 币种代码
	   LC.biz_type_cd as LOAN_TYPE_CD,   -- 贷款类型代码	 	   
       0 AS loan_appl_num_today,                 -- 贷款申请笔数_本日
       0 AS loan_appl_num_thmonth,                 -- 贷款申请笔数_本月
       0 AS loan_appl_num_seaso,                 -- 贷款申请笔数_本季
       0 AS loan_appl_num_thyear,                 -- 贷款申请笔数_本年
       COUNT(DISTINCT CASE WHEN LC.startdt = DATE '{ETL_TX_DT}' AND LC.contract_sts_cd <> '2' THEN LC.loan_contract_no END) AS credit_contract_num_today,       -- 授信合同数_本日  20240418添加合同状态为有效
       COUNT(DISTINCT CASE WHEN LC.startdt BETWEEN DATE'{I_MB_DT}' AND DATE '{ETL_TX_DT}' AND LC.contract_sts_cd <> '2' THEN LC.loan_contract_no END) AS credit_contract_num_thmonth,  -- 授信合同数_本月
       COUNT(DISTINCT CASE WHEN LC.startdt BETWEEN DATE'{I_QB_DT}' AND DATE '{ETL_TX_DT}' AND LC.contract_sts_cd <> '2' THEN LC.loan_contract_no END) AS credit_contract_num_seaso,    -- 授信合同数_本季
       COUNT(DISTINCT CASE WHEN LC.startdt BETWEEN DATE'{I_YB_DT}' AND DATE '{ETL_TX_DT}' AND LC.contract_sts_cd <> '2' THEN LC.loan_contract_no END) AS credit_contract_num_thyear,   -- 授信合同数_本年 
       SUM(CASE WHEN LC.prod_no IN ('1110240','1110250','1110260','1110270') AND LC.startdt = DATE '{ETL_TX_DT}' AND LC.contract_sts_cd <> '2' THEN LC.out_acct_amt ELSE 0 END) AS distr_amt_today,                                            -- 投放金额_本日  20240418 享贷特殊处理，以合同金额计算 
       SUM(CASE WHEN LC.prod_no IN ('1110240','1110250','1110260','1110270') AND LC.startdt BETWEEN DATE'{I_MB_DT}' AND DATE '{ETL_TX_DT}' AND LC.contract_sts_cd <> '2' THEN LC.out_acct_amt ELSE 0 END) AS distr_amt_thmonth,                -- 投放金额_本月  20240418 享贷特殊处理，以合同金额计算
       SUM(CASE WHEN LC.prod_no IN ('1110240','1110250','1110260','1110270') AND LC.startdt BETWEEN DATE'{I_QB_DT}' AND DATE '{ETL_TX_DT}' AND LC.contract_sts_cd <> '2' THEN LC.out_acct_amt ELSE 0 END) AS distr_amt_seaso,                  -- 投放金额_本季  20240418 享贷特殊处理，以合同金额计算
       SUM(CASE WHEN LC.prod_no IN ('1110240','1110250','1110260','1110270') AND LC.startdt BETWEEN DATE'{I_YB_DT}' AND DATE '{ETL_TX_DT}' AND LC.contract_sts_cd <> '2' THEN LC.out_acct_amt ELSE 0 END) AS distr_amt_thyear,                 -- 投放金额_本年  20240418 享贷特殊处理，以合同金额计算
       SUM(CASE WHEN LC.prod_no IN ('1110240','1110250','1110260','1110270')   AND LC.contract_sts_cd <> '2' THEN LC.out_acct_amt ELSE 0 END) AS distr_amt_his_cumul,                 -- 投放金额_历史累计  20240523加 享贷特殊处理，以合同金额计算
	   
       COUNT(CASE WHEN LC.prod_no IN ('1110240','1110250','1110260','1110270') AND LC.startdt = DATE '{ETL_TX_DT}' AND LC.contract_sts_cd <> '2' THEN LC.loan_contract_no END) AS distr_num_today,                                                 -- 投放笔数_本日  20240418 享贷特殊处理，以合同计算笔数
       COUNT(CASE WHEN LC.prod_no IN ('1110240','1110250','1110260','1110270') AND LC.startdt BETWEEN DATE'{I_MB_DT}' AND DATE '{ETL_TX_DT}' AND LC.contract_sts_cd <> '2' THEN LC.loan_contract_no END) AS distr_num_thmonth,                     -- 投放笔数_本月  20240418 享贷特殊处理，以合同计算笔数
       COUNT(CASE WHEN LC.prod_no IN ('1110240','1110250','1110260','1110270') AND LC.startdt BETWEEN DATE'{I_QB_DT}' AND DATE '{ETL_TX_DT}' AND LC.contract_sts_cd <> '2' THEN LC.loan_contract_no END) AS distr_num_seaso,                       -- 投放笔数_本季  20240418 享贷特殊处理，以合同计算笔数
       COUNT(CASE WHEN LC.prod_no IN ('1110240','1110250','1110260','1110270') AND LC.startdt BETWEEN DATE'{I_YB_DT}' AND DATE '{ETL_TX_DT}' AND LC.contract_sts_cd <> '2' THEN LC.loan_contract_no END) AS distr_num_thyear,                      -- 投放笔数_本年  20240418 享贷特殊处理，以合同计算笔数
	   COUNT(CASE WHEN LC.prod_no IN ('1110240','1110250','1110260','1110270') AND LC.contract_sts_cd <> '2' THEN LC.loan_contract_no END) AS distr_num_his_cumul,                      -- 投放笔数_历史累计  20240523加 享贷特殊处理，以合同计算笔数

       0 AS renewal_loan_contract_num_today,       -- 续贷合同数_本日
       0 AS renewal_loan_contract_num_thmonth,     -- 续贷合同数_本月   
       0 AS renewal_loan_contract_num_seaso,       -- 续贷合同数_本季   
       0 AS renewal_loan_contract_num_thyear,      -- 续贷合同数_本年 
       0 AS unpay_off_contract_num,                                -- 未结清合同数
       0 AS bal,                                                                             -- 余额
       0 AS np_bal,             -- 不良余额
       0 AS overdue_bal,                                             -- 逾期余额
       0 AS nor_bal,                         -- 正常余额
       0 AS concern_bal,                      -- 关注余额
       0 AS scdary_bal,                     -- 次级余额
       0 AS suspi_bal,                       -- 可疑余额
       0 AS loss_bal,                        -- 损失余额
       0 AS nor_num,                          -- 正常笔数
       0 AS concern_num,                       -- 关注笔数
       0 AS scdary_num,                      -- 次级笔数
       0 AS suspi_num,                        -- 可疑笔数
       0 AS loss_num,                          -- 损失笔数
       0 AS approve_num_today,              -- 审批笔数_本日
       0 AS approve_num_thmonth,            -- 审批笔数_本月
       0 AS approve_num_seaso,              -- 审批笔数_本季
       0 AS approve_num_thyear,             -- 审批笔数_本年
       COUNT(DISTINCT CASE WHEN LC.startdt = DATE '{ETL_TX_DT}' THEN LC.loan_contract_no END) AS loan_contract_num_today,    -- 贷款合同数_本日 
       COUNT(DISTINCT CASE WHEN LC.startdt BETWEEN DATE'{I_MB_DT}' AND DATE '{ETL_TX_DT}' THEN LC.loan_contract_no END) AS loan_contract_num_thmonth,  -- 贷款合同数_本月
       COUNT(DISTINCT CASE WHEN LC.startdt BETWEEN DATE'{I_QB_DT}' AND DATE '{ETL_TX_DT}' THEN LC.loan_contract_no END) AS loan_contract_num_seaso,    -- 贷款合同数_本季
       COUNT(DISTINCT CASE WHEN LC.startdt BETWEEN DATE'{I_YB_DT}' AND DATE '{ETL_TX_DT}' THEN LC.loan_contract_no END) AS loan_contract_num_thyear,   -- 贷款合同数_本年
       0 AS distr_contract_num_today,    -- 放款合同数_本日 
       0 AS distr_contract_num_thmonth,  -- 放款合同数_本月
       0 AS distr_contract_num_seaso,    -- 放款合同数_本季
       0 AS distr_contract_num_thyear,   -- 放款合同数_本年
       SUM(CASE WHEN LC.startdt = DATE '{ETL_TX_DT}' AND LC.contract_sts_cd <> '2' THEN LC.out_acct_amt ELSE 0 END) AS credit_amt_today,    -- 授信金额_本日 
       SUM(CASE WHEN LC.startdt BETWEEN DATE'{I_MB_DT}' AND DATE '{ETL_TX_DT}' AND LC.contract_sts_cd <> '2' THEN LC.out_acct_amt ELSE 0 END) AS credit_amt_thmonth,  -- 授信金额_本月
       SUM(CASE WHEN LC.startdt BETWEEN DATE'{I_QB_DT}' AND DATE '{ETL_TX_DT}' AND LC.contract_sts_cd <> '2' THEN LC.out_acct_amt ELSE 0 END) AS credit_amt_seaso,    -- 授信金额_本季
       SUM(CASE WHEN LC.startdt BETWEEN DATE'{I_YB_DT}' AND DATE '{ETL_TX_DT}' AND LC.contract_sts_cd <> '2' THEN LC.out_acct_amt ELSE 0 END) AS credit_amt_thyear,   -- 授信金额_本年
       0 AS dist_amt_today,    -- 放款金额_本日 
       0 AS dist_amt_thmonth,  -- 放款金额_本月
       0 AS dist_amt_seaso,    -- 放款金额_本季
       0 AS dist_amt_thyear,   -- 放款金额_本年
       0 as renewal_loan_pre_approvevia_num_today, -- 续贷预审批通过数_本日',
       0 as renewal_loan_pre_approvevia_num_thmonth, -- 续贷预审批通过数_本月',
       0 as renewal_loan_pre_approvevia_num_seaso, -- 续贷预审批通过数_本季',
       0 as renewal_loan_pre_approvevia_num_thyear, -- 续贷预审批通过数_本年',
       0 as renewal_loan_cust_manag_cfm_num_today, -- 续贷客户经理确认数_本日',
       0 as renewal_loan_cust_manag_cfm_num_thmonth, -- 续贷客户经理确认数_本月',
       0 as renewal_loan_cust_manag_cfm_num_seaso, -- 续贷客户经理确认数_本季',
       0 as renewal_loan_cust_manag_cfm_num_thyear, -- 续贷客户经理确认数_本年',
       0 as renewal_loan_contract_num_model_today, -- 续贷合同数(模型)_本日',
       0 as renewal_loan_contract_num_model_thmonth, -- 续贷合同数(模型)_本月',
       0 as renewal_loan_contract_num_model_seaso, -- 续贷合同数(模型)_本季',
       0 as renewal_loan_contract_num_model_thyear, -- 续贷合同数(模型)_本年',
       0 AS overdue_retur_money_amt_today,   -- 逾期回款金额_本日
       0 AS overdue_retur_money_amt_thmonth,   -- 逾期回款金额_本月
       0 AS overdue_retur_money_amt_seaso,   -- 逾期回款金额_本季
       0 AS overdue_retur_money_amt_thyear,   -- 逾期回款金额_本年
       0 AS overdue_retur_money_contract_num_today,   -- 逾期回款合同数_本日
       0 AS overdue_retur_money_contract_num_thmonth,   -- 逾期回款合同数_本月
       0 AS overdue_retur_money_contract_num_seaso,   -- 逾期回款合同数_本季
       0 AS overdue_retur_money_contract_num_thyear,   -- 逾期回款合同数_本年
       0 AS overdue_retur_money_principal_today,   -- 逾期回款本金_本日
       0 AS overdue_retur_money_principal_thmonth,   -- 逾期回款本金_本月
       0 AS overdue_retur_money_principal_seaso,   -- 逾期回款本金_本季
       0 AS overdue_retur_money_principal_thyear   -- 逾期回款本金_本年
FROM {CDM}.C_AGT_LOAN_CONTRACT_BASIC_INF LC    -- 贷款合同基本信息
INNER JOIN {CDM}.C_CUS_UNIFI_CUST_INF_PRI CP   -- 统一客户信息对私
     ON LC.cust_no = CP.src_sys_cust_no
    AND CP.data_dt = DATE '{ETL_TX_DT}'
	 
WHERE LC.data_dt = DATE '{ETL_TX_DT}'
AND LC.biz_type_cd in ( '0310','0120')   -- 0310个人贷款 0120小企业贷款 
GROUP BY CP.unifi_cust_no,                    -- 客户号
       CP.cust_name,                -- 客户姓名
       CASE WHEN LC.handl_teller_no <>'' THEN LC.handl_teller_no ELSE '000000' END,    -- 经办柜员编号 
       CASE WHEN LC.handl_org_no <>'' THEN LC.handl_org_no ELSE '0099' END,          -- 经办机构号
       CASE WHEN LC.prod_no <> '' THEN LC.prod_no ELSE '999999' END,                    -- 产品编号
	   '', -- 小企业特色产品类型代码
       CASE WHEN LC.currcy_cd <> '' THEN LC.currcy_cd ELSE '156' END,
	   LC.biz_type_cd   -- 贷款类型代码
	   ;	   	   	
	   
	   
 -- 临时表6，第1部分	   
 insert into {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_06 
 select CP.unifi_cust_no,                    -- 客户号
       CP.cust_name,                -- 客户姓名
       CASE WHEN LC.handl_teller_no <>'' THEN LC.handl_teller_no ELSE '000000' END,    -- 经办柜员编号 
       CASE WHEN LC.handl_org_no <>'' THEN LC.handl_org_no ELSE '0099' END,          -- 经办机构号
       CASE WHEN LC.prod_no <> '' THEN LC.prod_no ELSE '999999' END,                    -- 产品编号
	   nvl(LR.minor_ent_charact_prod_type_cd,'') as minor_ent_charact_prod_type_cd, -- 小企业特色产品类型代码
       CASE WHEN LC.currcy_cd <> '' THEN LC.currcy_cd ELSE '156' END,
	   LC.biz_type_cd as LOAN_TYPE_CD,   -- 贷款类型代码
	 -- 贷款合同数-本X申请  
	 COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','')  = DATE '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL THEN LC.loan_contract_no END) AS loan_contract_num_today_APPL,    -- 贷款合同数_本日申请 
     COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','')  BETWEEN DATE'{I_MB_DT}' AND DATE '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL THEN LC.loan_contract_no END) AS loan_contract_num_thmonth_APPL,  -- 贷款合同数_本月申请
     COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','')  BETWEEN DATE'{I_QB_DT}' AND DATE '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL THEN LC.loan_contract_no END) AS loan_contract_num_seaso_APPL,    -- 贷款合同数_本季申请
     COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','')  BETWEEN DATE'{I_YB_DT}' AND DATE '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL THEN LC.loan_contract_no END) AS loan_contract_num_thyear_APPL,   -- 贷款合同数_本年申请
      
     -- 放款合同数-本X申请 	   
     COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','')  = DATE '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_today > 0 THEN LC.loan_contract_no END) AS distr_contract_num_today_APPL,    -- 放款合同数_本日申请 
     COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','')  BETWEEN DATE'{I_MB_DT}' AND DATE '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_thmonth > 0 THEN LC.loan_contract_no END) AS distr_contract_num_thmonth_APPL,  -- 放款合同数_本月申请
     COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','')  BETWEEN DATE'{I_QB_DT}' AND DATE '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_seaso > 0 THEN LC.loan_contract_no END) AS distr_contract_num_seaso_APPL,    -- 放款合同数_本季申请
     COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','')  BETWEEN DATE'{I_YB_DT}' AND DATE '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_thyear > 0 THEN LC.loan_contract_no END) AS distr_contract_num_thyear_APPL,   -- 放款合同数_本年申请
     
	 -- 放款合同数-本X授信 
	 COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','')  = DATE '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_today > 0 AND LC.contract_sts_cd <> '2' THEN LC.loan_contract_no END) AS distr_contract_num_today_CREDIT,    -- 放款合同数_本日授信 
     COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','')  BETWEEN DATE'{I_MB_DT}' AND DATE '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_thmonth > 0 AND LC.contract_sts_cd <> '2'  THEN LC.loan_contract_no END) AS distr_contract_num_thmonth_CREDIT,  -- 放款合同数_本月授信
     COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','')  BETWEEN DATE'{I_QB_DT}' AND DATE '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_seaso > 0   AND LC.contract_sts_cd <> '2'  THEN LC.loan_contract_no END) AS distr_contract_num_seaso_CREDIT,    -- 放款合同数_本季授信
     COUNT(DISTINCT CASE WHEN REPLACE(A.appl_dt,'/','')  BETWEEN DATE'{I_YB_DT}' AND DATE '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_thyear > 0  AND LC.contract_sts_cd <> '2'  THEN LC.loan_contract_no END) AS distr_contract_num_thyear_ACREDIT,   -- 放款合同数_本年授信
     
	 -- 放款金额-本X授信
	 SUM( CASE WHEN REPLACE(A.appl_dt,'/','')  = DATE '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_today > 0 AND LC.contract_sts_cd <> '2' THEN LR_DISTR.dist_amt_today END) AS DIST_AMT_TODAY_CREDIT,    -- 放款金额_本日授信 
     SUM( CASE WHEN REPLACE(A.appl_dt,'/','')  BETWEEN DATE'{I_MB_DT}' AND DATE '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_thmonth > 0 AND LC.contract_sts_cd <> '2'  THEN LR_DISTR.dist_amt_thmonth END) AS DIST_AMT_THMONTH_CREDIT,  -- 放款金额_本月授信
     SUM( CASE WHEN REPLACE(A.appl_dt,'/','')  BETWEEN DATE'{I_QB_DT}' AND DATE '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_seaso > 0   AND LC.contract_sts_cd <> '2'  THEN LR_DISTR.dist_amt_seaso   END) AS DIST_AMT_SEASO_CREDIT,    -- 放款金额_本季授信
     SUM( CASE WHEN REPLACE(A.appl_dt,'/','')  BETWEEN DATE'{I_YB_DT}' AND DATE '{ETL_TX_DT}' AND C.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_thyear > 0  AND LC.contract_sts_cd <> '2'  THEN LR_DISTR.dist_amt_thyear  END) AS DIST_AMT_THYEAR_CREDIT   -- 放款金额_本年授信
 FROM {CDM}.C_AGT_LOAN_CONTRACT_BASIC_INF LC
 left join {CDM}.C_AGT_LOAN_RCPT_INF LR 
     ON LC.loan_contract_no = LR.loan_contract_no
	 and LR.data_dt = DATE '{ETL_TX_DT}' 
     AND LR.loan_type_cd in ( '0310','0120')   -- 0310个人贷款 0120小企业贷款 
 LEFT JOIN {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_03 LR_DISTR
     ON LR.loan_rcpt_no = LR_DISTR.loan_loan_rcpt_no
 INNER JOIN {CDM}.C_CUS_UNIFI_CUST_INF_PRI CP
     ON LC.cust_no = CP.src_sys_cust_no
    AND CP.data_dt = DATE '{ETL_TX_DT}'
  inner JOIN {FDM}.F_EV_CRMS_LOAN_BIZ_APPL_INF A -- 信贷业务申请信息
     ON LC.biz_appl_no = A.APPL_NO 
     AND A.end_dt > DATE '{ETL_TX_DT}'
     AND A.start_dt <= DATE '{ETL_TX_DT}'
     AND REPLACE(A.appl_dt,'/','') >= '{I_YB_DT}' 
     AND REPLACE(A.appl_dt,'/','') <= '{ETL_TX_DT}' 
     AND A.biz_type_cd in ( '0310','0120')   -- 0310个人贷款 0120小企业贷款 
 -- 20240520：增以下B/C表关联， 用以取 C.FRONT_BIZ_APPL_NO IS NOT NULL 的条件 
 LEFT JOIN {FDM}.F_EV_PLPT_TB_BIZ_APPLY_INFO B -- 信贷前置房抵贷调度明细
           ON A.appl_no = B.chan_biz_appl_seq_no
          AND B.end_dt > DATE '{ETL_TX_DT}'
          AND B.start_dt <= DATE '{ETL_TX_DT}'
      LEFT JOIN {FDM}.F_EV_PLPT_TB_BIZ_DISPATCH_PRO_DETAIL C -- 信贷前置房抵贷调度产品明细
           ON B.FRONT_APPL_NO = C.FRONT_BIZ_APPL_NO
          AND C.end_dt > DATE '{ETL_TX_DT}'
          AND C.start_dt <= DATE '{ETL_TX_DT}'
          AND C.exec_sts_cd in ('TS00','TS06')	 
WHERE LC.data_dt = DATE '{ETL_TX_DT}'
AND LC.biz_type_cd in ( '0310','0120')   -- 0310个人贷款 0120小企业贷款  
GROUP BY CP.unifi_cust_no,                    -- 客户号
       CP.cust_name,                -- 客户姓名
       CASE WHEN LC.handl_teller_no <>'' THEN LC.handl_teller_no ELSE '000000' END,    -- 经办柜员编号 
       CASE WHEN LC.handl_org_no <>'' THEN LC.handl_org_no ELSE '0099' END,          -- 经办机构号
       CASE WHEN LC.prod_no <> '' THEN LC.prod_no ELSE '999999' END,                    -- 产品编号
	   nvl(LR.minor_ent_charact_prod_type_cd,'') , -- 小企业特色产品类型代码
       CASE WHEN LC.currcy_cd <> '' THEN LC.currcy_cd ELSE '156' END,
	   LC.biz_type_cd   -- 贷款类型代码	
	   ;

 -- 临时表6，第2部分	   
 insert into {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_06
 select CP.unifi_cust_no,                    -- 客户号
       CP.cust_name,                -- 客户姓名
       CASE WHEN LC.handl_teller_no <>'' THEN LC.handl_teller_no ELSE '000000' END,    -- 经办柜员编号 
       CASE WHEN LC.handl_org_no <>'' THEN LC.handl_org_no ELSE '0099' END,          -- 经办机构号
       CASE WHEN LC.prod_no <> '' THEN LC.prod_no ELSE '999999' END,                    -- 产品编号
	   nvl(LR.minor_ent_charact_prod_type_cd,'') as minor_ent_charact_prod_type_cd, -- 小企业特色产品类型代码
       CASE WHEN LC.currcy_cd <> '' THEN LC.currcy_cd ELSE '156' END,
	   LC.biz_type_cd as LOAN_TYPE_CD,   -- 贷款类型代码
	 -- 贷款合同数-本X申请   
	 COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd')  = DATE '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL THEN LC.loan_contract_no END) AS loan_contract_num_today_APPL,    -- 贷款合同数_本日申请
     COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd')  BETWEEN DATE'{I_MB_DT}' AND DATE '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL THEN LC.loan_contract_no END) AS loan_contract_num_thmonth_APPL,  -- 贷款合同数_本月申请
     COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd')  BETWEEN DATE'{I_QB_DT}' AND DATE '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL THEN LC.loan_contract_no END) AS loan_contract_num_seaso_APPL,    -- 贷款合同数_本季申请
     COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd')  BETWEEN DATE'{I_YB_DT}' AND DATE '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL THEN LC.loan_contract_no END) AS loan_contract_num_thyear_APPL,   -- 贷款合同数_本年申请

     -- 放款合同数-本X申请 
     COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd')  = DATE '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_today > 0 THEN LC.loan_contract_no END) AS distr_contract_num_today_APPL,    -- 放款合同数_本日申请 
     COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd')  BETWEEN DATE'{I_MB_DT}'  AND DATE '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_thmonth > 0 THEN LC.loan_contract_no END) AS distr_contract_num_thmonth_APPL,  -- 放款合同数_本月申请
     COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd')  BETWEEN DATE'{I_QB_DT}'  AND DATE '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_seaso > 0 THEN LC.loan_contract_no END) AS distr_contract_num_seaso_APPL,    -- 放款合同数_本季申请
     COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd')  BETWEEN DATE'{I_YB_DT}'  AND DATE '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_thyear > 0 THEN LC.loan_contract_no END) AS distr_contract_num_thyear_APPL,   -- 放款合同数_本年申请
     
	 -- 放款合同数-本X授信 
     COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd')  = DATE '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_today > 0 AND LC.contract_sts_cd <> '2' THEN LC.loan_contract_no END) AS distr_contract_num_today_CREDIT,    -- 放款合同数_本日授信 
     COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd')  BETWEEN DATE'{I_MB_DT}'  AND DATE '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_thmonth > 0 AND LC.contract_sts_cd <> '2' THEN LC.loan_contract_no END) AS distr_contract_num_thmonth_CREDIT,  -- 放款合同数_本月授信
     COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd')  BETWEEN DATE'{I_QB_DT}'  AND DATE '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_seaso > 0 AND LC.contract_sts_cd <> '2'   THEN LC.loan_contract_no END) AS distr_contract_num_seaso_CREDIT,    -- 放款合同数_本季授信
     COUNT(DISTINCT CASE WHEN to_char(A.create_tm,'yyyymmdd')  BETWEEN DATE'{I_YB_DT}'  AND DATE '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_thyear > 0 AND LC.contract_sts_cd <> '2'  THEN LC.loan_contract_no END) AS distr_contract_num_thyear_CREDIT,   -- 放款合同数_本年授信

     -- 放款金额-本X授信 
     SUM( CASE WHEN to_char(A.create_tm,'yyyymmdd')  = DATE '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_today > 0 AND LC.contract_sts_cd <> '2' THEN LR_DISTR.dist_amt_today END) AS DIST_AMT_TODAY_CREDIT,    -- 放款金额_本日授信 
     SUM( CASE WHEN to_char(A.create_tm,'yyyymmdd')  BETWEEN DATE'{I_MB_DT}'  AND DATE '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_thmonth > 0 AND LC.contract_sts_cd <> '2' THEN LR_DISTR.dist_amt_thmonth END) AS DIST_AMT_THMONTH_CREDIT,  -- 放款金额_本月授信
     SUM( CASE WHEN to_char(A.create_tm,'yyyymmdd')  BETWEEN DATE'{I_QB_DT}'  AND DATE '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_seaso > 0 AND LC.contract_sts_cd <> '2'   THEN LR_DISTR.dist_amt_seaso END) AS  DIST_AMT_SEASO_CREDIT,    -- 放款合同数_本季申请
     SUM( CASE WHEN to_char(A.create_tm,'yyyymmdd')  BETWEEN DATE'{I_YB_DT}'  AND DATE '{ETL_TX_DT}' AND D.FRONT_BIZ_APPL_NO IS NOT NULL and LR_DISTR.dist_amt_thyear > 0 AND LC.contract_sts_cd <> '2'  THEN LR_DISTR.dist_amt_thyear END) AS DIST_AMT_THYEAR_CREDIT   -- 放款合同数_本年申请
	 
FROM {CDM}.C_AGT_LOAN_CONTRACT_BASIC_INF LC
left join {CDM}.C_AGT_LOAN_RCPT_INF LR 
     ON LC.loan_contract_no = LR.loan_contract_no
	 and LR.data_dt = DATE '{ETL_TX_DT}' 
     AND LR.loan_type_cd in ( '0310','0120')   -- 0310个人贷款 0120小企业贷款 
 LEFT JOIN {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_03 LR_DISTR
     ON LR.loan_rcpt_no = LR_DISTR.loan_loan_rcpt_no
INNER JOIN {CDM}.C_CUS_UNIFI_CUST_INF_PRI CP
     ON LC.cust_no = CP.src_sys_cust_no
    AND CP.data_dt = DATE '{ETL_TX_DT}'
 LEFT JOIN {FDM}.F_EV_EAPP_LOAN_BIZ_DATA_DTL A  -- 员工APP贷款业务数据
   ON LC.biz_appl_no = A.biz_no  
 LEFT JOIN {FDM}.F_EV_CRMS_LOAN_BIZ_APPL_INF B -- 信贷业务申请信息
          ON A.biz_no = B.appl_no
         AND B.end_dt > DATE '{ETL_TX_DT}'
         AND B.start_dt <= DATE '{ETL_TX_DT}' 
 LEFT JOIN {FDM}.F_EV_PLPT_TB_BIZ_APPLY_INFO C -- 信贷前置房抵贷调度明细
       ON A.biz_no = C.chan_biz_appl_seq_no
      AND C.end_dt > DATE '{ETL_TX_DT}'
      AND C.start_dt <= DATE '{ETL_TX_DT}'
 LEFT JOIN {FDM}.F_EV_PLPT_TB_BIZ_DISPATCH_PRO_DETAIL D  -- 信贷前置房抵贷调度产品明细
       ON C.FRONT_APPL_NO = D.FRONT_BIZ_APPL_NO
      AND D.end_dt > DATE '{ETL_TX_DT}'
      AND D.start_dt <= DATE '{ETL_TX_DT}'
      AND D.exec_sts_cd in ('TS00','TS06')
WHERE LC.data_dt = DATE '{ETL_TX_DT}'
AND LC.biz_type_cd in ( '0310','0120')   -- 0310个人贷款 0120小企业贷款   
AND A.end_dt > DATE '{ETL_TX_DT}'
          AND A.start_dt <= DATE '{ETL_TX_DT}'
          AND to_char(A.create_tm,'yyyymmdd') >= '{I_YB_DT}'
          AND to_char(A.create_tm,'yyyymmdd') <= '{ETL_TX_DT}'
          AND a.iden_no <> '' 
          AND a.iden_no is not null
          AND B.appl_no IS NULL

GROUP BY CP.unifi_cust_no,                    -- 客户号
       CP.cust_name,                -- 客户姓名
       CASE WHEN LC.handl_teller_no <>'' THEN LC.handl_teller_no ELSE '000000' END,    -- 经办柜员编号 
       CASE WHEN LC.handl_org_no <>'' THEN LC.handl_org_no ELSE '0099' END,          -- 经办机构号
       CASE WHEN LC.prod_no <> '' THEN LC.prod_no ELSE '999999' END,                    -- 产品编号
	   nvl(LR.minor_ent_charact_prod_type_cd,''), -- 小企业特色产品类型代码
       CASE WHEN LC.currcy_cd <> '' THEN LC.currcy_cd ELSE '156' END,
	   LC.biz_type_cd   -- 贷款类型代码
	   ;


/*Step5:向主表插入数据-汇总*/
INSERT INTO {CDM}.C_CUS_PRI_CUST_LOAN_SUM
SELECT '{ETL_TX_DT}',
       t1.cust_no,
       t1.cust_name,
       t1.handl_teller_no,
       t1.handl_org_no,
       t1.prod_no,
	   t1.minor_ent_charact_prod_type_cd,
       t1.currcy_cd,
	   t1.LOAN_TYPE_CD,
       SUM(t1.loan_appl_num_today) AS loan_appl_num_today,          -- 贷款申请笔数_本日
       SUM(t1.loan_appl_num_thmonth) AS loan_appl_num_thmonth,      -- 贷款申请笔数_本月
       SUM(t1.loan_appl_num_seaso) AS loan_appl_num_seaso,          -- 贷款申请笔数_本季
       SUM(t1.loan_appl_num_thyear) AS loan_appl_num_thyear,        -- 贷款申请笔数_本年
       SUM(t1.credit_contract_num_today) AS credit_contract_num_today,    -- 授信合同数_本日 
       SUM(t1.credit_contract_num_thmonth) AS credit_contract_num_thmonth,  -- 授信合同数_本月
       SUM(t1.credit_contract_num_seaso) AS credit_contract_num_seaso,    -- 授信合同数_本季
       SUM(t1.credit_contract_num_thyear) AS credit_contract_num_thyear,   -- 授信合同数_本年    
       SUM(t1.distr_amt_today) AS distr_amt_today,                  -- 投放金额_本日 
       SUM(t1.distr_amt_thmonth) AS distr_amt_thmonth,                -- 投放金额_本月
       SUM(t1.distr_amt_seaso) AS distr_amt_seaso,                  -- 投放金额_本季
       SUM(t1.distr_amt_thyear) AS distr_amt_thyear,                 -- 投放金额_本年 
	   SUM(t1.distr_amt_his_cumul) AS distr_amt_his_cumul,           -- 投放金额_历史累计 
       SUM(t1.distr_num_today) AS distr_num_today,                  -- 投放笔数_本日 
       SUM(t1.distr_num_thmonth) AS distr_num_thmonth,                     -- 投放笔数_本月
       SUM(t1.distr_num_seaso) AS distr_num_seaso,                       -- 投放笔数_本季
       SUM(t1.distr_num_thyear) AS distr_num_thyear,                      -- 投放笔数_本年
	   SUM(t1.distr_num_his_cumul) AS distr_num_his_cumul,                 -- 投放笔数_历史累计
       SUM(t1.renewal_loan_contract_num_today) AS renewal_loan_contract_num_today,       -- 续贷合同数_本日
       SUM(t1.renewal_loan_contract_num_thmonth) AS renewal_loan_contract_num_thmonth,     -- 续贷合同数_本月   
       SUM(t1.renewal_loan_contract_num_seaso) AS renewal_loan_contract_num_seaso,       -- 续贷合同数_本季   
       SUM(t1.renewal_loan_contract_num_thyear) AS renewal_loan_contract_num_thyear,      -- 续贷合同数_本年   
       SUM(t1.unpay_off_contract_num) AS unpay_off_contract_num,                -- 未结清合同数
       SUM(t1.bal) AS bal,                                   -- 余额
       SUM(t1.np_bal) AS np_bal,                                -- 不良余额
       SUM(t1.overdue_bal) AS overdue_bal,                           -- 逾期余额
       SUM(t1.nor_bal) AS nor_bal,                         -- 正常余额
       SUM(t1.concern_bal) AS concern_bal,                      -- 关注余额
       SUM(t1.scdary_bal) AS scdary_bal,                     -- 次级余额
       SUM(t1.suspi_bal) AS suspi_bal,                       -- 可疑余额
       SUM(t1.loss_bal) AS loss_bal,                        -- 损失余额
       SUM(t1.nor_num) AS nor_num,                          -- 正常笔数
       SUM(t1.concern_num) AS concern_num,                       -- 关注笔数
       SUM(t1.scdary_num) AS scdary_num,                      -- 次级笔数
       SUM(t1.suspi_num) AS suspi_num,                        -- 可疑笔数
       SUM(t1.loss_num) AS loss_num,                          -- 损失笔数

       SUM(t1.approve_num_today) AS approve_num_today_APPL,              -- 审批笔数_本日
       SUM(t1.approve_num_thmonth) AS approve_num_thmonth_APPL,            -- 审批笔数_本月
       SUM(t1.approve_num_seaso) AS approve_num_seaso_APPL,              -- 审批笔数_本季
       SUM(t1.approve_num_thyear) AS approve_num_thyear_APPL,             -- 审批笔数_本年
       SUM(t1.loan_contract_num_today) AS loan_contract_num_today,    -- 贷款合同数_本日 
       SUM(t1.loan_contract_num_thmonth) AS loan_contract_num_thmonth,  -- 贷款合同数_本月
       SUM(t1.loan_contract_num_seaso) AS loan_contract_num_seaso,    -- 贷款合同数_本季
       SUM(t1.loan_contract_num_thyear) AS loan_contract_num_thyear,   -- 贷款合同数_本年
       SUM(t1.distr_contract_num_today) AS distr_contract_num_today,    -- 放款合同数_本日 
       SUM(t1.distr_contract_num_thmonth) AS distr_contract_num_thmonth,  -- 放款合同数_本月
       SUM(t1.distr_contract_num_seaso) AS distr_contract_num_seaso,    -- 放款合同数_本季
       SUM(t1.distr_contract_num_thyear) AS distr_contract_num_thyear,   -- 放款合同数_本年
       SUM(t1.credit_amt_today) AS credit_amt_today,    -- 授信金额_本日 
       SUM(t1.credit_amt_thmonth) AS credit_amt_thmonth,  -- 授信金额_本月
       SUM(t1.credit_amt_seaso) AS credit_amt_seaso,    -- 授信金额_本季
       SUM(t1.credit_amt_thyear) AS credit_amt_thyear,   -- 授信金额_本年
       SUM(t1.dist_amt_today) AS dist_amt_today,    -- 放款金额_本日 
       SUM(t1.dist_amt_thmonth) AS dist_amt_thmonth,  -- 放款金额_本月
       SUM(t1.dist_amt_seaso) AS dist_amt_seaso,    -- 放款金额_本季
       SUM(t1.dist_amt_thyear) AS dist_amt_thyear,   -- 放款金额_本年
       SUM(t1.renewal_loan_pre_approvevia_num_today) as renewal_loan_pre_approvevia_num_today, -- 续贷预审批通过数_本日',
       SUM(t1.renewal_loan_pre_approvevia_num_thmonth) as renewal_loan_pre_approvevia_num_thmonth, -- 续贷预审批通过数_本月',
       SUM(t1.renewal_loan_pre_approvevia_num_seaso) as renewal_loan_pre_approvevia_num_seaso, -- 续贷预审批通过数_本季',
       SUM(t1.renewal_loan_pre_approvevia_num_thyear) as renewal_loan_pre_approvevia_num_thyear, -- 续贷预审批通过数_本年',
       SUM(t1.renewal_loan_cust_manag_cfm_num_today) as renewal_loan_cust_manag_cfm_num_today, -- 续贷客户经理确认数_本日',
       SUM(t1.renewal_loan_cust_manag_cfm_num_thmonth) as renewal_loan_cust_manag_cfm_num_thmonth, -- 续贷客户经理确认数_本月',
       SUM(t1.renewal_loan_cust_manag_cfm_num_seaso) as renewal_loan_cust_manag_cfm_num_seaso, -- 续贷客户经理确认数_本季',
       SUM(t1.renewal_loan_cust_manag_cfm_num_thyear) as renewal_loan_cust_manag_cfm_num_thyear, -- 续贷客户经理确认数_本年',
       SUM(t1.renewal_loan_contract_num_model_today) as renewal_loan_contract_num_model_today, -- 续贷合同数(模型)_本日',
       SUM(t1.renewal_loan_contract_num_model_thmonth) as renewal_loan_contract_num_model_thmonth, -- 续贷合同数(模型)_本月',
       SUM(t1.renewal_loan_contract_num_model_seaso) as renewal_loan_contract_num_model_seaso, -- 续贷合同数(模型)_本季',
       SUM(t1.renewal_loan_contract_num_model_thyear) as renewal_loan_contract_num_model_thyear, -- 续贷合同数(模型)_本年',
       SUM(t1.overdue_retur_money_amt_today) AS OVERDUE_RETUR_MONEY_AMT_TODAY,   -- 逾期回款金额_本日
       SUM(t1.overdue_retur_money_amt_thmonth) AS OVERDUE_RETUR_MONEY_AMT_THMONTH,   -- 逾期回款金额_本月
       SUM(t1.overdue_retur_money_amt_seaso) AS OVERDUE_RETUR_MONEY_AMT_SEASO,   -- 逾期回款金额_本季
       SUM(t1.overdue_retur_money_amt_thyear) AS OVERDUE_RETUR_MONEY_AMT_THYEAR,   -- 逾期回款金额_本年
       SUM(t1.overdue_retur_money_contract_num_today) AS OVERDUE_RETUR_MONEY_CONTRACT_NUM_TODAY,   -- 逾期回款合同数_本日
       SUM(t1.overdue_retur_money_contract_num_thmonth) AS OVERDUE_RETUR_MONEY_CONTRACT_NUM_THMONTH,   -- 逾期回款合同数_本月
       SUM(t1.overdue_retur_money_contract_num_seaso) AS OVERDUE_RETUR_MONEY_CONTRACT_NUM_SEASO,   -- 逾期回款合同数_本季
       SUM(t1.overdue_retur_money_contract_num_thyear) AS OVERDUE_RETUR_MONEY_CONTRACT_NUM_THYEAR,   -- 逾期回款合同数_本年
       SUM(t1.overdue_retur_money_principal_today) AS OVERDUE_RETUR_MONEY_PRINCIPAL_TODAY,   -- 逾期回款本金_本日
       SUM(t1.overdue_retur_money_principal_thmonth) AS OVERDUE_RETUR_MONEY_PRINCIPAL_THMONTH,   -- 逾期回款本金_本月
       SUM(t1.overdue_retur_money_principal_seaso) AS OVERDUE_RETUR_MONEY_PRINCIPAL_SEASO,   -- 逾期回款本金_本季
       SUM(t1.overdue_retur_money_principal_thyear) AS OVERDUE_RETUR_MONEY_PRINCIPAL_THYEAR   -- 逾期回款本金_本年
	   ,(t6.LOAN_CONTRACT_NUM_TODAY_APPL)
       ,(t6.LOAN_CONTRACT_NUM_THMONTH_APPL)
       ,(t6.LOAN_CONTRACT_NUM_SEASO_APPL)
       ,(t6.LOAN_CONTRACT_NUM_THYEAR_APPL)
       ,(t6.DISTR_CONTRACT_NUM_TODAY_APPL)
       ,(t6.DISTR_CONTRACT_NUM_THMONTH_APPL)
       ,(t6.DISTR_CONTRACT_NUM_SEASO_APPL)
       ,(t6.DISTR_CONTRACT_NUM_THYEAR_APPL)
       ,(t6.DISTR_CONTRACT_NUM_TODAY_CREDIT)
       ,(t6.DISTR_CONTRACT_NUM_THMONTH_CREDIT)
       ,(t6.DISTR_CONTRACT_NUM_SEASO_CREDIT)
       ,(t6.DISTR_CONTRACT_NUM_THYEAR_CREDIT)
       ,(t6.DIST_AMT_TODAY_CREDIT)
       ,(t6.DIST_AMT_THMONTH_CREDIT)
       ,(t6.DIST_AMT_SEASO_CREDIT)
       ,(t6.DIST_AMT_THYEAR_CREDIT) 
	   , 'C_AGT_LOAN_CONTRACT_BASIC_INF' AS data_sourc_tab_name  -- 数据来源表
FROM {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_01 t1 
left join {CDM}.VT_C_CUS_PRI_CUST_LOAN_SUM_06 t6 
  on  t1.cust_no = t6.cust_no 
  and t1.HANDL_TELLER_NO = t6.HANDL_TELLER_NO 
  and t1.HANDL_ORG_NO = t6.HANDL_ORG_NO
  and t1.PROD_NO = t6.PROD_NO 
  and t1.minor_ent_charact_prod_type_cd=t6.minor_ent_charact_prod_type_cd
  and t1.CURRCY_CD = t6.CURRCY_CD 
  and t1.LOAN_TYPE_CD=t6.LOAN_TYPE_CD
GROUP BY t1.cust_no,
       t1.cust_name,
       t1.handl_teller_no,
       t1.handl_org_no,
       t1.prod_no,
	   t1.minor_ent_charact_prod_type_cd,
       t1.currcy_cd,
	   t1.LOAN_TYPE_CD
       ,(t6.LOAN_CONTRACT_NUM_TODAY_APPL)
       ,(t6.LOAN_CONTRACT_NUM_THMONTH_APPL)
       ,(t6.LOAN_CONTRACT_NUM_SEASO_APPL)
       ,(t6.LOAN_CONTRACT_NUM_THYEAR_APPL)
       ,(t6.DISTR_CONTRACT_NUM_TODAY_APPL)
       ,(t6.DISTR_CONTRACT_NUM_THMONTH_APPL)
       ,(t6.DISTR_CONTRACT_NUM_SEASO_APPL)
       ,(t6.DISTR_CONTRACT_NUM_THYEAR_APPL)
       ,(t6.DISTR_CONTRACT_NUM_TODAY_CREDIT)
       ,(t6.DISTR_CONTRACT_NUM_THMONTH_CREDIT)
       ,(t6.DISTR_CONTRACT_NUM_SEASO_CREDIT)
       ,(t6.DISTR_CONTRACT_NUM_THYEAR_CREDIT)
       ,(t6.DIST_AMT_TODAY_CREDIT)
       ,(t6.DIST_AMT_THMONTH_CREDIT)
       ,(t6.DIST_AMT_SEASO_CREDIT)
       ,(t6.DIST_AMT_THYEAR_CREDIT); 	   

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


