# *****************************************头部注释模块**************************************
#脚本名称：dwa_c_cus_pos_mect_inf.py
#功能说明：共性层pos商户信息
#更新方式：每日全量
#更新对象：CDM.C_CUS_POS_MECT_INF
#创 建 人： 
#创建日期：2024-09-01
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
-- Step1:创建目标表之前先清除目标表当天数据
DELETE FROM {CDM}.C_CUS_POS_MECT_INF    -- 商户信息表
WHERE DATA_DT = DATE '{ETL_TX_DT}'
;
-- step2 加工商户的pos标志、移动标志
DROP TABLE IF EXISTS {CDM}.VT_C_CUS_POS_MECT_INFO_01;
CREATE TEMPORARY TABLE {CDM}.VT_C_CUS_POS_MECT_INFO_01 
(
 MECT_NO VARCHAR(100)
,POS_FLG VARCHAR(10)
,MOBILE_FLG VARCHAR(10)
,MECT_VLD_FLG VARCHAR(10)
,ASMS_MECT_FLAG VARCHAR(10)
,VLD_TERM_QTY DECIMAL(24,0)
)DISTRIBUTED BY ('MECT_NO')
COMMENT  '商户信息临时表-处理有效商户'
;

-- step3 合并商户信息
CREATE TEMPORARY TABLE {CDM}.VT_C_CUS_POS_MECT_INFO_02(
 MECT_NO                   VARCHAR(100)
,MECT_NAME                 VARCHAR(100)
,CORE_CUST_NO              VARCHAR(100)
,MECT_LP_NAME              VARCHAR(100)   -- 商户法人姓名   
,LP_IDENTT_CARD_NO         VARCHAR(100)   -- 法人身份证号
,LP_IDEN_TYPE_CD           VARCHAR(60)    -- 法人证件类型代码
,MECT_CONTACT_PERSON_NAME  VARCHAR(100)   -- 商户联系人
,MECT_CONTACT_PERSON_TEL   VARCHAR(100)   -- 联系电话
,MECT_ADDR                 VARCHAR(200)   -- 商户地址
,MECT_VLD_FLG               VARCHAR(60)    -- 商户状态
,BIZ_LICEN_NO              VARCHAR(100)   -- 营业执照号
,STL_ACCT_NO               VARCHAR(100)   -- 结算账号
,MOVE_PAY_MECT_NO          VARCHAR(100)   -- 移动支付商户编号
,MOVE_PAY_STL_ACCT_NO      VARCHAR(100)   -- 移动支付结算账号
,MECT_BELO_ORG_NO          VARCHAR(100)   -- 商户所属机构号
,CUST_TYPE_CD              VARCHAR(100)   -- 客户类型
,POS_FLG                   VARCHAR(10)    -- pos标志
,MOBILE_FLG                VARCHAR(10)    -- 移动端标志
,CREATE_DT                 DATE           -- 创建日期
,APPL_DT                   DATE           -- 申请日期  
,START_USE_DT              DATE           -- 起用日期
,LOGOUT_DT                 DATE           -- 注销日期
,CUST_MANAG_WAGE_NO        VARCHAR(100)   -- 客户经理工资号
,CUST_MANAG_NAME           VARCHAR(100)   -- 客户经理姓名
,MECT_LOGOUT_FLAG          VARCHAR(10)    -- 商户注销标志
,MICRO_SECRET_FREE_FLAG    VARCHAR(10)    -- 小额免密标志
,MOVE_PAY_FLAG             VARCHAR(10)    -- 移动支付标志
,MICRO_MECT_FLAG           VARCHAR(10)    -- 小微商户标志
,ACTIVA_FLG                VARCHAR(10)
,ASMS_MECT_FLAG            VARCHAR(10)
,VLD_TERM_QTY              DECIMAL(24,0)
)DISTRIBUTED BY ('MECT_NO')
COMMENT  '商户信息临时表'
;
-- Step4:加工商户机构
CREATE TEMPORARY TABLE {CDM}.VT_C_CUS_POS_MECT_INFO_03(
MECT_NO                   VARCHAR(100)
,MECT_NAME                VARCHAR(100)
,CORE_CUST_NO             VARCHAR(100)
,MECT_LP_NAME             VARCHAR(100) -- 商户法人姓名   
,LP_IDENTT_CARD_NO        VARCHAR(100) -- 法人身份证号
,LP_IDEN_TYPE_CD          VARCHAR(60)  -- 法人证件类型代码
,MECT_CONTACT_PERSON_NAME VARCHAR(100) -- 商户联系人
,MECT_CONTACT_PERSON_TEL  VARCHAR(100) -- 联系电话
,MECT_ADDR                VARCHAR(200) -- 商户地址
,MECT_VLD_FLG              VARCHAR(60)  -- 商户状态
,BIZ_LICEN_NO             VARCHAR(100) -- 营业执照号
,STL_ACCT_NO              VARCHAR(100) -- 结算账号
,STL_ACCT_NO_STS_CD       VARCHAR(60)  -- 结算账号状态
,MOVE_PAY_MECT_NO         VARCHAR(100) -- 移动支付商户编号
,MOVE_PAY_STL_ACCT_NO     VARCHAR(100) -- 移动支付结算账号
,MOVE_STL_ACCT_NO_STS_CD  VARCHAR(60)  -- 移动结算账号状态代码
,MECT_NATURE_CD           VARCHAR(60)  -- 商户性质
,MECT_TYPE_CD             VARCHAR(60)  -- 商户类型
,MECT_BELO_ORG_NO         VARCHAR(100) -- 所属机构
,MECT_BELO_ORG_NAME       VARCHAR(100) -- 所属机构名称
,BELO_BRCH_ORG_NO         VARCHAR(100) -- 所属分行
,BELO_BRCH_ORG_NAME       VARCHAR(100) -- 所属分行名称 
,CREATE_DT                DATE
,APPL_DT                  DATE         -- 申请日期  
,START_USE_DT             DATE         -- 起用日期
,LOGOUT_DT                DATE         -- 注销日期
,CUST_MANAG_WAGE_NO       VARCHAR(100) -- 客户经理工资号
,CUST_MANAG_NAME          VARCHAR(100) 
,MECT_LOGOUT_FLAG         VARCHAR(10)  -- 商户注销标志
,MICRO_SECRET_FREE_FLAG   VARCHAR(10)  -- 小额免密标志
,MOVE_PAY_FLAG            VARCHAR(10)  -- 移动支付标志
,MICRO_MECT_FLAG          VARCHAR(10)  -- 小微商户标志
,ACTIVA_FLG               VARCHAR(10)
,ASMS_MECT_FLAG           VARCHAR(10)
,VLD_TERM_QTY             DECIMAL(24,0)
)DISTRIBUTED BY ('MECT_NO')
COMMENT  '商户信息临时表'
;
-- Step5:加工账户开户机构
CREATE TEMPORARY TABLE {CDM}.VT_C_CUS_POS_MECT_INFO_04(
STL_ACCT_NO VARCHAR(100)
,OPEN_ACCT_ORG_NO VARCHAR(100) 
,ORG_SPL_NM VARCHAR(100)
)
;
"""

# -生成mapping映射- #
bsqlmapping="""

"""

# -bsql2算法逻辑主体实现- #
bsql2="""
-- Step1:商户临时表-关联终端表,给商户打标签
INSERT INTO {CDM}.VT_C_CUS_POS_MECT_INFO_01 
SELECT mect_cd 
      ,MAX(CASE WHEN mect_vld_flg ='1' AND flg1 = '1' THEN '1' ELSE '0' END) AS pos_flg
      ,MAX(CASE WHEN mect_vld_flg ='1' AND flg2 = '1' THEN '1' ELSE '0' END) AS mobile_flg
	  ,MAX(CASE WHEN mect_vld_flg ='1' THEN '1' ELSE '0' END) AS mect_vld_flg -- 商户有效标志，1正常
	  ,MAX(CASE WHEN term_vld_flg='1' AND SUBSTR(mect_cd,8,4)='5441'  THEN '1' ELSE '0' END) AS asms_mect_flag -- 助农商户标志 
	  ,COUNT(DISTINCT CASE WHEN term_vld_flg='1' THEN mect_term END) AS vld_term_qty -- 终端数量 
 FROM (
       SELECT T1.mect_cd
             ,CASE WHEN (T2.term_no <> '' AND T2.term_sts_cd <> '2' AND T2.term_lock_flag = '0' AND T1.activa_flag = '1')
                   THEN '1' 
				   ELSE '0' 
			   END AS flg1 -- POS有效标志 TERM_STS_CD：2冻结  ACTIVA_FLAG 0无效 1本行跨行有效
             ,CASE WHEN (T1.txn_autho_mode_cd ='2' AND COALESCE(T1.move_pay_mect_no,'') <> '' 
                         AND T1.move_pay_flag = '1' AND T2.term_lock_flag = '0'  AND T2.term_no <> '' AND T2.term_sts_cd <> '2'
						 ) OR (T1.txn_autho_mode_cd ='0' AND COALESCE(T1.move_pay_mect_no,'') <> '' AND T1.move_pay_flag = '1')
                   THEN '1' 
				   ELSE '0' 
			   END AS flg2    -- 移动端标志 TXN_AUTHO_MODE_CD ：0手机端 2POS端
              
			 ,CASE WHEN ((T2.term_no <> '' AND T2.term_sts_cd <> '2' AND T2.term_lock_flag = '0' AND T1.activa_flag = '1')
                          OR (T1.mect_cd <> '' AND T1.move_pay_flag = '1' AND T1.txn_autho_mode_cd ='0') 
                          OR (T1.mect_cd <> '' AND T1.move_pay_flag = '1' AND T1.txn_autho_mode_cd ='2' 
                              AND T2.term_no <> '' AND T2.term_sts_cd <> '2' AND T1.activa_flag = '0' AND T2.term_lock_flag = '0'
			              	 ) 
			             ) THEN '1' 
				   ELSE '0' 
			   END AS mect_vld_flg -- 商户有效标志
             ,CASE WHEN T2.mect_cd IS NOT NULL AND T2.term_sts_cd IN ('0','1')  -- 终端状态 0签退 1签到
                        AND T2.term_lock_flag ='0' -- 终端锁定状态	
                   THEN '1' ELSE '0' 
			   END AS term_vld_flg -- 终端有效标志	
             ,T1.mect_cd||T2.term_no as mect_term				   
       FROM {FDM}.F_PT_POSP_MECT_INF T1       -- POSP商户信息
       LEFT JOIN {FDM}.F_CO_POSP_TERM_INF T2  -- POSP终端信息  
         ON T1.mect_cd = T2.mect_cd
        AND DATE '{ETL_TX_DT}' BETWEEN T2.start_dt AND T2.end_dt -1
      WHERE DATE '{ETL_TX_DT}' BETWEEN T1.start_dt AND T1.end_dt -1
       )P
GROUP BY P.mect_cd
;

-- Step2:商户临时表-合并商户信息
INSERT INTO {CDM}.VT_C_CUS_POS_MECT_INFO_02 
SELECT T1.mect_cd                                       -- 商户号
       ,T1.mect_name AS merch_name_cn                   -- 商户名称
	   ,CASE WHEN T1.move_pay_flag = '1' AND T4.main_cust_no IS NOT NULL THEN T4.main_cust_no
             ELSE T3.main_cust_no
        END AS cust_no                                  -- 客户号
	   ,T1.mect_lp_name                                 -- 商户法人姓名
       ,T1.lp_identt_card_no                            -- 法人身份证号
       ,T1.lp_iden_type_cd                              -- 法人证件类型代码
	   ,T1.mect_contact_person AS sa_per_name           -- 商户联系人
       ,T1.contact_phone_no AS phone                    -- 支行联系人号码
       ,T1.mect_addr AS sa_addr                         -- 商户地址
	   ,NVL(T2.mect_vld_flg,'0') AS mect_vld_flg        -- 商户有效标志
	   ,T1.biz_licen_no                                 -- 营业执照号
	   ,CASE WHEN T1.move_pay_flag = '1' AND T4.main_cust_no IS NOT NULL THEN T1.move_pay_stl_acct_no
             ELSE T1.stl_acct_no
        END  AS cust_acct_no
       ,T1.move_pay_mect_no AS mch_no                   -- 移动支付商户编号
       ,T1.move_pay_stl_acct_no AS mobile_account       -- 移动支付结算账号
	   ,SUBSTR(T1.mect_belo_org_no,3,2)||SUBSTR(T1.mect_belo_org_no,6,2) AS org_id     -- 商户所属机构号 开户机构
       ,CASE WHEN T1.move_pay_flag = '1' AND T4.main_cust_no IS NOT NULL THEN SUBSTR(T4.main_cust_no,1,2)
             ELSE SUBSTR(T3.main_cust_no,1,2)
        END AS cust_type     
       ,NVL(T2.pos_flg,'0') AS pos_flg
       ,NVL(T2.mobile_flg,'0') AS mobile_flg
       ,DECODE(NVL(T1.create_tm,''),'',NULL,CDM.UDF_DTCHK(T1.create_tm,'{LEG_DATE}')) AS create_tm  -- 创建日期	
	   ,DECODE(NVL(T1.appl_dt,''),'',NULL,CDM.UDF_DTCHK(T1.APPL_DT,'{LEG_DATE}')) AS appl_dt        -- 申请日期	  
       ,DECODE(NVL(T1.start_use_dt,''),'',NULL,CDM.UDF_DTCHK(T1.START_USE_DT,'{LEG_DATE}')) AS start_use_dt -- 起用日期
       ,DECODE(NVL(T1.logout_dt,''),'',NULL,CDM.UDF_DTCHK(T1.LOGOUT_DT,'{LEG_DATE}')) AS logout_dt  -- 注销日期
	   ,SUBSTR(T1.sbubrch_contact_person,1,INSTR(T1.sbubrch_contact_person,'/',1,1)-1) AS cust_manag_wage_no -- 客户经理工资号
	   ,substr(T1.sbubrch_contact_person,instr(T1.sbubrch_contact_person,'/',1,1)+1,length(T1.sbubrch_contact_person)) AS mgr_name		  
	   ,T1.mect_logout_flag                             -- 商户注销标志
       ,T1.micro_secret_free_flag                       -- 小额免密标志
       ,T1.move_pay_flag                                -- 移动支付标志
       ,T1.micro_mect_flag                              -- 小微商户标志     
       ,T1.activa_flag as activa_flg 
       ,T2.asms_mect_flag
       ,NVL(T2.vld_term_qty,0) AS vld_term_qty
 FROM {FDM}.F_PT_POSP_MECT_INF T1  -- POS商户信息
 LEFT JOIN {CDM}.VT_C_CUS_POS_MECT_INFO_01 T2
    ON T1.mect_cd = T2.mect_no
 LEFT JOIN {FDM}.F_AC_CRBS_CUST_ACCT_NO T3
   ON T1.stl_acct_no = T3.cust_acct_no -- 结算账号
  AND DATE '{ETL_TX_DT}' BETWEEN T3.start_dt AND T3.end_dt -1
 LEFT JOIN {FDM}.F_AC_CRBS_CUST_ACCT_NO T4
   ON T1.move_pay_stl_acct_no = T4.cust_acct_no -- 移动支付结算账号
  AND DATE '{ETL_TX_DT}' BETWEEN T4.start_dt AND T4.end_dt -1
 WHERE DATE '{ETL_TX_DT}' BETWEEN T1.start_dt AND T1.end_dt -1
;

-- Step3:商户临时表-加工商户机构信息
INSERT INTO {CDM}.VT_C_CUS_POS_MECT_INFO_03 
SELECT T1.mect_no               
      ,T1.mect_name 
      ,T1.core_cust_no
      ,T1.mect_lp_name             -- 商户法人姓名
      ,T1.lp_identt_card_no        -- 法人身份证号
      ,T1.lp_iden_type_cd          -- 法人证件类型代码	
      ,T1.mect_contact_person_name -- 商户联系人姓名
      ,T1.mect_contact_person_tel  -- 商户联系人电话
      ,T1.mect_addr                -- 商户地址
      ,T1.mect_vld_flg             -- 商户有效标志
      ,T1.biz_licen_no             -- 营业执照号
      ,T1.stl_acct_no  
	  ,T6.record_sts_cd AS stl_acct_no_sts_cd -- 结算账号状态
      ,T1.move_pay_mect_no
      ,T1.move_pay_stl_acct_no
	  ,T5.record_sts_cd AS move_stl_acct_no_sts_cd -- 记录状态
      ,CASE WHEN T1.cust_type_cd='01' THEN '个人户' WHEN T1.cust_type_cd='02' THEN '对公户' END AS mect_nature_cd
      ,CASE WHEN T1.pos_flg='1' AND T1.mobile_flg='1' THEN 'POS+聚合'
            WHEN T1.pos_flg='1' THEN 'POS'
            WHEN T1.mobile_flg='1' THEN '聚合'
            ELSE '' END AS MECT_TYPE_CD
	  ,T2.ORG_NO AS org_id
      ,T2.org_abre AS org_spl_nm
      ,T2.level_scd_org_no AS brch_id
      ,T2.level_scd_org_abre AS brch_spl_nm
	  ,T1.create_dt                 -- 创建日期	  
	  ,T1.appl_dt                   -- 申请日期
      ,T1.start_use_dt              -- 起用日期
      ,T1.logout_dt                 -- 注销日期
	  ,T1.cust_manag_wage_no
      ,T1.cust_manag_name
      ,T1.mect_logout_flag          -- 商户注销标志
      ,T1.micro_secret_free_flag    -- 小额免密标志
      ,T1.move_pay_flag             -- 移动支付标志
      ,T1.micro_mect_flag           -- 小微商户标志
	  ,T1.activa_flg
      ,T1.asms_mect_flag
      ,T1.vld_term_qty
  FROM {CDM}.VT_C_CUS_POS_MECT_INFO_02 T1
  LEFT JOIN {CDM}.C_ORG_ORG_INF T2 
    ON T1.mect_belo_org_no=T2.org_no
   AND T2.data_dt=DATE '{ETL_TX_DT}'
  LEFT JOIN {FDM}.F_AC_CRBS_CUST_ACCT_NO T5
    ON T1.move_pay_stl_acct_no=T5.cust_acct_no
   AND DATE '{ETL_TX_DT}' BETWEEN T5.start_dt AND T5.end_dt -1
  LEFT JOIN {FDM}.F_AC_CRBS_CUST_ACCT_NO T6
    ON T1.stl_acct_no=T6.cust_acct_no
   AND DATE '{ETL_TX_DT}' BETWEEN T6.start_dt AND T6.end_dt -1
;

-- Step4:商户临时表-账户开户机构
INSERT INTO {CDM}.VT_C_CUS_POS_MECT_INFO_04 
SELECT T1.stl_acct_no
      ,T2.open_acct_org_no
      ,T3.org_abre AS org_spl_nm
  FROM (SELECT stl_acct_no FROM {CDM}.VT_C_CUS_POS_MECT_INFO_03 GROUP BY 1)T1 
  LEFT JOIN {FDM}.F_AC_CRBS_CUST_ACCT_NO T2
    ON T1.stl_acct_no=T2.cust_acct_no
   AND DATE '{ETL_TX_DT}' BETWEEN T2.start_dt AND T2.end_dt -1
  LEFT JOIN {CDM}.C_ORG_ORG_INF T3
    ON T2.open_acct_org_no=T3.org_no -- 账户开户机构
   AND T3.data_dt=DATE '{ETL_TX_DT}'
;

-- 插入结果表
INSERT INTO {CDM}.C_CUS_POS_MECT_INF
SELECT DATE '{ETL_TX_DT}' AS data_dt 
	  ,T1.mect_no                             -- 商户编号
      ,T1.mect_name                           -- 商户名称
      ,T1.core_cust_no                        -- 客户号
      ,T1.mect_lp_name                        -- 商户法人姓名
      ,T1.lp_identt_card_no                   -- 法人身份证号
      ,T1.lp_iden_type_cd                     -- 法人证件类型代码
      ,T1.mect_contact_person_name            -- 商户联系人姓名
      ,T1.mect_contact_person_tel             -- 商户联系人电话
      ,T1.mect_addr                           -- 商户地址
      ,T1.mect_vld_flg                        -- 商户有效标志
      ,T1.biz_licen_no                        -- 营业执照号
      ,T1.stl_acct_no                         -- 结算账号
      ,T1.stl_acct_no_sts_cd                  -- 结算账号状态代码
      ,T1.move_pay_mect_no                    -- 移动商户号
      ,T1.move_pay_stl_acct_no                -- 移动支付结算账号
      ,T1.move_stl_acct_no_sts_cd             -- 移动支付结算账号状态代码
      ,T1.mect_nature_cd                      -- 商户性质
      ,T1.mect_type_cd                        -- 商户类型
      ,T1.mect_belo_org_no                    -- 所属机构
      ,T1.mect_belo_org_name                  -- 所属机构名称
      ,T3.open_acct_org_no                    -- 客户号开户机构
      ,'' AS 清算机构号                       
      ,T1.belo_brch_org_no                    -- 所属分行
      ,T1.belo_brch_org_name                  -- 所属分行名称 
      ,T1.create_dt                           -- 创建日期	  
      ,T1.appl_dt                             -- 商户表申请日期  
      ,T1.start_use_dt                        -- 商户表起用日期
      ,T1.logout_dt                           -- 商户表注销日期
	  ,T1.activa_flg
      ,T1.cust_manag_wage_no                  -- 客户经理工资号
      ,T1.cust_manag_name                     -- 客户经理姓名		  
      ,NVL(T1.vld_term_qty,0) as vld_term_qty -- 有效终端数量
      ,T1.mect_logout_flag                    -- 商户注销标志
      ,T1.micro_secret_free_flag              -- 小额免密标志
      ,T1.move_pay_flag                       -- 移动支付标志
      ,T1.micro_mect_flag                     -- 小微商户标志
      ,CASE WHEN T4.aum_roll_quart_daily_avg>=500 THEN '1' ELSE '0' END AS base_cust_flag  -- 是否基础客户
      ,CASE WHEN T4.aum_roll_quart_daily_avg>=5000 THEN '1' ELSE '0' END AS medi_high_side_cust_flag -- 是否中高端客户
      ,NVL((CASE WHEN T9.wechat_signe_flag='1' OR T9.wbank_signe_flag='1' OR T9.mb_bank_signe_flag='1' THEN '1' ELSE '0' END),'0') AS e_bank_cust_flag  -- 是否电子银行客户
      ,NVL(T1.asms_mect_flag,'0')  AS asms_mect_flag  -- 助农商户标志
	FROM {CDM}.VT_C_CUS_POS_MECT_INFO_03 T1
	LEFT JOIN {CDM}.VT_C_CUS_POS_MECT_INFO_04 T3
	  ON T1.stl_acct_no=T3.stl_acct_no
    LEFT JOIN {CDM}.C_CUS_PRI_CUST_AUM_INF T4
	  ON T1.core_cust_no=T4.cust_no
	 AND T4.data_dt=DATE '{ETL_TX_DT}'
	LEFT JOIN {CDM}.C_CUS_CHAN_SIGNE_IND T9
	ON T1.core_cust_no=T9.cust_no AND T9.data_dt= DATE '{ETL_TX_DT}'
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
