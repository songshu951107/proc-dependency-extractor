# *****************************************头部注释模块**************************************
#脚本名称：dwa_c_agt_deposit_acct_inf.py
#功能说明：共性层存款账户信息脚本
#更新方式：每日全量
#更新对象：CDM.C_AGT_DEPOSIT_ACCT_INF
#创 建 人： 
#创建日期：2023-07-03
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
 DELETE FROM {CDM}.C_AGT_DEPOSIT_ACCT_INF    -- 存款账户信息
  WHERE data_dt = DATE '{ETL_TX_DT}';

/*Step2:创建加工过程临时表*/
CREATE TEMPORARY TABLE {CDM}.VT_C_AGT_DEPOSIT_ACCT_INF_01
(acct_no           VARCHAR(100)   COMMENT  '账号'
,int_rate          DECIMAL(12,6)  COMMENT  '利率'
,agr_retn_amt      DECIMAL(24,2)  COMMENT  '协定留存金额'
) DISTRIBUTED BY (
     'acct_no'
)
comment  '存款账户信息加工临时表01协定存款相关信息';


CREATE TEMPORARY TABLE {CDM}.VT_C_AGT_DEPOSIT_ACCT_INF_02
(acct_no             VARCHAR(100)  COMMENT  '账号'
,int_rate_no         VARCHAR(30)   COMMENT  '利率编号'
,int_rate_no_eff_day DATE          COMMENT  '利率编号生效日'
,int_rate            DECIMAL(12,6) COMMENT  '当前执行利率'
,base_int_rate       DECIMAL(12,6) COMMENT  '基准利率'
,int_rate_float_ratio DECIMAL(12,6)   COMMENT '利率浮动比例'
,int_rate_float_mode_cd  VARCHAR(60)  COMMENT '利率浮动方式代码'
,int_rate_float_point DECIMAL(12,6)   COMMENT '利率浮动点数'
,int_rate_adjust_freq  VARCHAR(10)    COMMENT '利率调整频率'
,last_int_rate_adjust_dt  DATE    COMMENT  '上次利率调整日期'
,next_int_rate_adjust_dt  DATE    COMMENT  '下次利率调整日期'
,open_acct_int_rate   DECIMAL(12,6)   COMMENT '开户利率'
) DISTRIBUTED BY (
     'acct_no'
)
comment  '存款账户信息加工临时表02加工存款利率相关信息';

CREATE TEMPORARY TABLE {CDM}.VT_C_AGT_DEPOSIT_ACCT_INF_03
(acct_no   VARCHAR(100) NOT NULL COMMENT '账号'
,currcy_cd VARCHAR(10) COMMENT '币种代码'
,biz_type  VARCHAR(50) COMMENT '业务类型'
,biz_class VARCHAR(50) COMMENT '业务种类'
,acct_name VARCHAR(300) COMMENT '账户名称'
,prod_no   VARCHAR(50) COMMENT  '产品代码'
,prod_type VARCHAR(10) COMMENT  '产品类型'
,cust_acct_no VARCHAR(50) COMMENT '客户账号'
,sub_acct_serial_no   VARCHAR(10) COMMENT '子账户序号'
,cust_acct_no_type_cd VARCHAR(10) COMMENT '客户账号类型代码'
,acct_type_cd         VARCHAR(10) COMMENT '账户类型代码'
,acct_addit_type_cd   VARCHAR(10) COMMENT '账户附加类型代码'
,finan_deposit_acct_type_cd  VARCHAR(60) COMMENT '财政存款账户类型代码'
,deposit_category_cd  VARCHAR(20) COMMENT '存款种类代码'
,acct_sts_cd          VARCHAR(10) COMMENT '账户状态代码'
,cash_remitt_flag VARCHAR(10)     COMMENT '钞汇标志'
,deposit_term_cd  VARCHAR(12) COMMENT '存款期限代码'
,rnewa_mode_cd        VARCHAR(60) COMMENT '转存方式代码'
,frozen_mode_cd    varchar(60) COMMENT '冻结方式代码'
,alw_adv_withd_flag   VARCHAR(10) COMMENT '允许提前支取标志'
,alw_overd_flag       VARCHAR(10) COMMENT '允许透支标志'
,fx_supvs_flag        VARCHAR(10) COMMENT '外汇监管标志'
,stl_acct_flag        VARCHAR(10) COMMENT '结算账户标志'
,cust_no              VARCHAR(30) COMMENT '客户编号'
,agent_cust_no        VARCHAR(60) COMMENT '代理人客户号'
,gover_affairs_dtl_class varchar(600) COMMENT '账号政务明细类别'
,prod_class_cd    VARCHAR(10) COMMENT '产品分类代码1'
,open_acct_org_no VARCHAR(10) COMMENT '开户机构号'
,open_acct_dt     DATE        COMMENT '账户开户日'
,open_acct_teller VARCHAR(16) COMMENT '开户柜员编号'
,acct_cance_org   VARCHAR(10) COMMENT '账户销户机构号'
,acct_cance_dt    DATE        COMMENT '账户销户日期'
,acct_cance_teller_no  VARCHAR(100)  COMMENT '销户柜员编号'
,val_dt           DATE        COMMENT '初始起息日期'
,expir_dt         DATE        COMMENT '账户到期日'
,rgst_dt        DATE        COMMENT '登记日期'
,agr_deposit_begin_save_amt DECIMAL(24,2)  COMMENT '协定存款起存金额'
,open_acct_amt    DECIMAL(24,2) COMMENT '开户金额'
,frozen_amt       DECIMAL(24,2) COMMENT '需冻结金额'
,curr_frozen_amt  DECIMAL(24,2) COMMENT '已冻结金额'
,recen_movg_acct_dt DATE        COMMENT '余额最近更新日期'
,stop_pay_amt     DECIMAL(24,2) COMMENT '止付金额'
,acct_grade_cd    varchar(600) COMMENT '账户分级代码'
,ctrl_measure_cd  VARCHAR(20)  COMMENT '控制措施代码'
) DISTRIBUTED BY (
     'acct_no'
)
comment  '存款账户信息加工临时表03';


CREATE TEMPORARY TABLE {CDM}.VT_C_AGT_DEPOSIT_ACCT_INF_04
(cust_no           VARCHAR(100) COMMENT  '客户账号'
,cust_name         VARCHAR(300) COMMENT  '客户名称'
,govern_structure  VARCHAR(10)  COMMENT  '政务客户标志'
) DISTRIBUTED BY (
     'cust_no'
)
comment  '存款账户信息加工临时表04政务客户标志';

"""


# -生成mapping映射- #
bsqlmapping="""

"""


# -bsql2算法逻辑主体实现- #
bsql2="""
/*Step1:加工协定存款相关信息*/
INSERT INTO {CDM}.VT_C_AGT_DEPOSIT_ACCT_INF_01
SELECT liabi_acct_no
      ,new_acct_exec_int_rate
      ,agr_retn_amt
FROM (SELECT T.liabi_acct_no
            ,T.new_acct_exec_int_rate
            ,T.agr_retn_amt
            ,ROW_NUMBER() OVER (PARTITION BY T.liabi_acct_no ORDER BY trade_dt DESC,txn_tm DESC) AS trn 
      FROM {FDM}.F_EV_CRBS_AGR_DEPOSIT_BOOK T
     WHERE etl_tx_dt <= DATE '{ETL_TX_DT}'
       AND trade_dt <= '{ETL_TX_DT}'
       AND opt_type_cd = '1'
      ) TA
WHERE TA.trn = 1;


/*Step2:加工存款利率相关信息*/
INSERT INTO {CDM}.VT_C_AGT_DEPOSIT_ACCT_INF_02
SELECT TAP.liabi_acct_no                                 -- 账号
      ,TAP.base_accru_int_rate_no                        -- 利率编号
      ,DATE(TAP.acct_int_rate_no_eff_day) AS int_rate_no_eff_day    -- 利率编号生效日
      -- ,CASE WHEN TAP.acct_base_int_rate = 0
                -- THEN COALESCE(TRP.actual_int_rate ,TAP.curr_exec_int_rate)
            -- ELSE TAP.acct_base_int_rate
       -- END AS int_rate                                   -- 利率         -- del 20240329  yangxiang  
      ,CASE WHEN TAP.CURR_EXEC_INT_RATE <>0 THEN TAP.CURR_EXEC_INT_RATE ELSE NVL(TRP.ACTUAL_INT_RATE,0) END AS INT_RATE   -- add 20240329  yangxiang 
	  ,COALESCE(TAP.acct_base_int_rate,0)           AS base_int_rate              -- 基准利率
      ,CASE WHEN TAP.acct_base_int_rate = 0 
                 THEN 0 
            ELSE (TAP.curr_exec_int_rate - TAP.acct_base_int_rate) * 100 / TAP.acct_base_int_rate 
       END                                           AS int_rate_float_ratio       -- 利率浮动比例
	  ,COALESCE(TAP.acct_int_rate_float_type_cd,'')  AS int_rate_float_mode_cd     -- 利率浮动方式代码
	  ,COALESCE(TAP.acct_int_rate_float_val,0)      AS int_rate_float_point       -- 利率浮动点数
	  ,COALESCE(TAP.int_rate_update_freq,'')         AS int_rate_adjust_freq       -- 利率调整频率
	  ,CASE WHEN TAP.int_rate_last_update_day = ''
                 THEN DATE '{MAX_DATE}'
            WHEN DATE(TAP.int_rate_last_update_day) is null
                 THEN DATE '{LEG_DATE}'
            ELSE DATE(TAP.int_rate_last_update_day)
       END AS        last_int_rate_adjust_dt    -- 上次利率调整日期
      ,CASE WHEN TAP.int_rate_next_update_day = ''
                 THEN DATE '{MAX_DATE}'
            WHEN DATE(TAP.int_rate_next_update_day) is null
                 THEN DATE '{LEG_DATE}'
            ELSE DATE(TAP.int_rate_next_update_day)
       END AS        next_int_rate_adjust_dt    -- 下次利率调整日期
      ,COALESCE(TAP.open_acct_int_rate,0)  as  open_acct_int_rate                         -- 开户利率
 FROM {FDM}.F_AC_CRBS_DEPOSIT_ACCT_INT_RATE_DEF  TAP     -- 核心存款账户利率定义
LEFT JOIN (SELECT L.int_rate_no
                 ,L.actual_int_rate
                 ,ROW_NUMBER() OVER(PARTITION BY L.int_rate_no ORDER BY L.eff_dt DESC) tmrn
             FROM {FDM}.F_CO_CRBS_ACTUAL_INT_RATE_PARAM L
            WHERE L.start_dt <= DATE '{ETL_TX_DT}'
              AND L.end_dt > DATE '{ETL_TX_DT}'
              AND L.eff_dt <= '{ETL_TX_DT}'
              -- AND L.base_int_rate_no = 'A06'
              AND L.base_int_rate_no in ('A06','A26')-- 20251124核心系统表业务变更
              AND L.record_sts_cd = '0'
              AND L.del_flag <> 'D'
           ) TRP
   ON TRP.int_rate_no = TAP.base_accru_int_rate_no
  AND TRP.tmrn = 1
WHERE TAP.end_dt > DATE '{ETL_TX_DT}'
  AND TAP.start_dt <= DATE '{ETL_TX_DT}'
  AND TRIM(TAP.int_rate_cd) ='INTRAT'
;

/*Step3:加工存款账户信息*/
INSERT INTO {CDM}.VT_C_AGT_DEPOSIT_ACCT_INF_03
SELECT TXX.liabi_acct_no         AS  acct_no          -- 账号
      ,TXX.currcy_cd             AS  currcy_cd        -- 币种代码
      ,CASE WHEN MOD(CAST(TXX.acctg_type_cd AS INT),10) = 1 AND TXX.acct_sts_cd IN ('I','D') 
                 THEN CASE TXX.acct_sts_cd WHEN 'D' THEN 'XA-DPBDHKM'
                                           WHEN 'I' THEN 'XA-DPYYWKM'
                       END 
            WHEN SUBSTR(TXX.acctg_type_cd,2,1)='1'
                 THEN 'XA-LDGBAL'
            WHEN SUBSTR(TXX.acctg_type_cd,1,1)='2' AND TXX.currcy_cd <> '156'
                 THEN '2' ||CASE SUBSTR(TXX.acctg_type_cd,2,1) 
                                 WHEN '0' THEN TRIM(TXX.acct_class_cd_1)||TRIM(TXX.acct_class_cd_2)
                                 WHEN '2' THEN TRIM(TXX.acct_class_cd_1)
                                 WHEN '3' THEN TRIM(TXX.acct_class_cd_2)
                             END
                      || '-' || 'LDGBAL' 
            ELSE '1' || CASE SUBSTR(TXX.acctg_type_cd,2,1) 
                             WHEN '0' THEN TRIM(TXX.acct_class_cd_1)||TRIM(TXX.acct_class_cd_2) 
                             WHEN '2' THEN TRIM(TXX.acct_class_cd_1) 
                             WHEN '3' THEN TRIM(TXX.acct_class_cd_2) END
                     || '-' || 'LDGBAL' END AS biz_type   -- 业务类型
      ,CASE WHEN TXX.prod_cd IN ('1024','2016','2018','2211','2021') THEN '99Y' ELSE TXX.deposit_term_cd END AS biz_class                -- 业务种类
      ,TXX.acct_name              AS acct_name            -- 户名
      ,TXX.prod_cd                AS prod_no              -- 产品编号
      ,TXX.liabi_prod_type_cd     AS prod_type            -- 产品类型
      ,TXX.open_acct_cust_acct_no AS cust_acct_no         -- 客户账号
      ,COALESCE(TCA.sub_acct_serial_no,'')     AS sub_acct_serial_no   -- 子账户序号
      ,COALESCE(TCU.cust_acct_no_type_cd,'') AS cust_acct_no_type_cd -- 客户账号类型代码
      ,TXX.acct_class_cd_1        AS acct_type_cd         -- 账户类型代码
      ,TXX.acct_class_cd_2        AS acct_addit_type_cd   -- 账户附加类型代码
	  ,COALESCE(TXX.finan_deposit_flag,'')     AS finan_deposit_acct_type_cd   -- 财政存款账户类型代码
      ,TXX.deposit_category_cd    AS deposit_category_cd  -- 存款种类代码
      ,TXX.acct_sts_cd            AS acct_sts_cd          -- 账户状态代码
      ,TXX.cash_remitt_flag       AS cash_remitt_flag     -- 钞汇标志
      ,TXX.deposit_term_cd        AS deposit_term_cd      -- 存款期限代码
	  ,NVL(TXX.rnewa_mode_cd,'')          AS rnewa_mode_cd        -- 转存方式代码
      ,NVL(TFO.limit_mode_cd,'')          AS frozen_mode_cd       -- 冻结方式代码
      -- 1 金额冻结;2 只收不付;3 封闭冻结;4 只付不收
	  ,NVL(TXX.withd_ctrl_mode_cd,'')     AS alw_adv_withd_flag   -- 允许提前支取标志
	  ,NVL(TXX.alw_overd_flag,'')         AS alw_overd_flag       -- 允许透支标志
	  ,NVL(TXX.fx_supvs_flag_aboq,'')     AS fx_supvs_flag        -- 外汇监管标志
	  ,NVL(TXX.stl_acct_flag_flag,'')     AS stl_acct_flag        -- 结算账户标志
      ,TXX.main_cust_no           AS cust_no              -- 客户编号
      ,TXX.agent_cust_no          AS agent_cust_no        -- 代理人客户号
      ,COALESCE(TAS.DATA_VAL,'')  AS gover_affairs_dtl_class         -- 账号政务明细类别
      ,TXX.prod_class_cd_1        AS prod_class_cd        -- 产品分类代码
      ,TXX.open_acct_org_no       AS open_acct_org_no     -- 开户机构号
      ,CASE WHEN TXX.acct_open_acct_day = ''
                 THEN DATE '{MIN_DATE}'
            WHEN DATE(TXX.acct_open_acct_day) is null
                 THEN DATE '{LEG_DATE}'
            ELSE DATE(TXX.acct_open_acct_day) 
       END AS open_acct_dt                                -- 开户日期
      ,TXX.open_acct_teller_no    AS open_acct_teller     -- 开户柜员
      ,TXX.acct_acct_cance_org_no AS acct_cance_org       -- 销户机构
      ,CASE WHEN TXX.acct_acct_cance_dt = ''
                 THEN DATE '{MAX_DATE}'
            WHEN DATE(TXX.acct_acct_cance_dt) is null
                 THEN DATE '{LEG_DATE}'
            ELSE DATE(TXX.acct_acct_cance_dt)
       END AS acct_cance_dt                               -- 销户日期
	  ,TXX.acct_acct_cance_teller_no  AS acct_cance_teller_no   -- 销户柜员编号
      ,CASE WHEN TXX.incep_val_dt = ''
                 THEN DATE '{MIN_DATE}'
            WHEN DATE(TXX.incep_val_dt) is null
                 THEN DATE '{LEG_DATE}'
            ELSE DATE(TXX.incep_val_dt) 
       END AS val_dt                                      -- 起息日期
      ,CASE WHEN TXX.acct_expir_dt = ''
                 THEN DATE '{MAX_DATE}'
            WHEN DATE(TXX.acct_expir_dt) is null
                 THEN DATE '{LEG_DATE}'
            ELSE DATE(TXX.acct_expir_dt)
       END AS expir_dt                                    -- 到期日期
      ,CASE WHEN NVL(TXX.stand_appl_dt_1,'') <> '' 
                THEN CDM.UDF_DTCHK(TXX.stand_appl_dt_1,DATE '{LEG_DATE}')
            ELSE DATE '{NULL_DATE}'
       END AS rgst_dt              -- 登记日期
	  ,CASE WHEN TXX.deposit_category_cd = '09'
	             THEN TXX.begin_save_amt
            ELSE 0
	   END AS agr_deposit_begin_save_amt                  -- 协定存款起存金额
      ,TXX.open_acct_amt          AS open_acct_amt        -- 开户金额
      ,TFO.frozen_amt             AS frozen_amt           -- 需冻结金额
      ,TFO.curr_frozen_amt        AS curr_frozen_amt      -- 已冻结金额
      ,CASE WHEN TXX.bal_recen_update_dt = ''
                 THEN DATE '{MIN_DATE}'
            WHEN DATE(TXX.bal_recen_update_dt) is null
                 THEN DATE '{LEG_DATE}'
            ELSE DATE(TXX.bal_recen_update_dt)
       END AS recen_movg_acct_dt                          -- 最近动账日期
      ,CASE WHEN TXX.acct_collect_but_not_pay_flag = 1 
                 THEN COALESCE(TXX.curr_gl_bal,0)
       END AS stop_pay_amt                                       -- 止付金额
      ,COALESCE(TLV.data_val,'')          AS acct_grade_cd       -- 账户分级代码
      ,COALESCE(TLV.stand_appl_field1,'') AS ctrl_measure_cd     -- 控制措施代码
     FROM {FDM}.F_AC_CRBS_DEPOSIT_ACCT TXX               -- 核心存款账户
LEFT JOIN {FDM}.F_AC_CRBS_CUST_ACCT_NO_COMPAR_RELA TCA   -- 核心客户账号对照关系
       ON TXX.liabi_acct_no = TCA.sys_acct_no
      AND TXX.open_acct_cust_acct_no = TCA.cust_acct_no
      AND TCA.end_dt > DATE '{ETL_TX_DT}'
      AND TCA.start_dt <= DATE '{ETL_TX_DT}'
      AND TCA.del_flag <> 'D'
LEFT JOIN {FDM}.F_AC_CRBS_CUST_ACCT_NO TCU               -- 核心客户账号
       ON TXX.open_acct_cust_acct_no = TCU.cust_acct_no
      AND TCU.end_dt > DATE '{ETL_TX_DT}'
      AND TCU.start_dt <= DATE '{ETL_TX_DT}'
      AND TCU.del_flag <> 'D'
LEFT JOIN {FDM}.F_AC_CRBS_ACCT_NO_ASSIS_INF   TAS         -- 核心账号辅助信息
       ON TAS.acct_no = TXX.open_acct_cust_acct_no        -- 客户账号
      AND TAS.end_dt > DATE '{ETL_TX_DT}'
      AND TAS.start_dt <= DATE '{ETL_TX_DT}'
      AND TAS.del_flag <> 'D'
      AND TAS.data_field ='ZWMSLB'
LEFT JOIN {FDM}.F_AC_CRBS_ACCT_NO_ASSIS_INF   TLV         -- 核心账号辅助信息
       ON TLV.acct_no = TXX.liabi_acct_no                 -- 账号
      AND TLV.end_dt > DATE '{ETL_TX_DT}'
      AND TLV.start_dt <= DATE '{ETL_TX_DT}'
      AND TLV.del_flag <> 'D'
      AND TLV.data_field ='KHZHJB'
LEFT JOIN 
    (select 
         sys_acct_no
         ,limit_mode_cd
         ,SUM(frozen_amt) + SUM(curr_frozen_amt) as frozen_amt
         ,SUM(curr_frozen_amt) as curr_frozen_amt
      from {FDM}.F_EV_CRBS_DEPOSIT_ACCT_FROZEN_BOOK  
       WHERE ETL_TX_DT <= DATE '{ETL_TX_DT}'
       and FROZEN_SOURC_CD = '1'
       and UNFRE_FLAG = '0'
       and RECORD_STS_CD = '0'
       and LIMIT_MODE_CD = '1'    
       group by sys_acct_no ,limit_mode_cd
       )TFO  -- 核心存款账户冻结登记簿
       ON TFO.sys_acct_no = TXX.liabi_acct_no    -- 账号
    WHERE TXX.end_dt > DATE '{ETL_TX_DT}'
      AND TXX.start_dt <= DATE '{ETL_TX_DT}'
      AND TXX.del_flag <> 'D';

/*Step4:从ec系统取客户名称及政务客户标志*/
INSERT INTO {CDM}.VT_C_AGT_DEPOSIT_ACCT_INF_04
SELECT T1.core_cust_no AS cust_no
      ,T1.cust_name
      ,T2.govern_structure
  FROM {FDM}.F_PT_ECIF_WHOLE_BANK_CUST_INF T1
 LEFT JOIN {FDM}.F_PT_ECIF_PUB_CUST_INF    T2
        ON T1.cust_no = t2.cust_no
       AND T2.start_dt <= DATE '{ETL_TX_DT}'
       AND T2.end_dt > DATE '{ETL_TX_DT}'
       AND t2.del_flag <> 'D'
     WHERE T1.start_dt <= DATE '{ETL_TX_DT}'
       AND T1.end_dt > DATE '{ETL_TX_DT}'
       AND t1.del_flag <> 'D'
       AND T1.core_cust_no <>'';


/*Step5:向主表插入存款业务数据*/
INSERT INTO {CDM}.C_AGT_DEPOSIT_ACCT_INF
SELECT DATE '{ETL_TX_DT}'               -- 数据日期
      ,T01.acct_no                      -- 账号
      ,T01.acct_name                    -- 账户名称
      ,T01.prod_no                      -- 产品编号
      ,T01.prod_type                    -- 产品类型
      ,CASE WHEN T01.prod_no = '1027' AND T02.biz_cd_rule IS NULL 
                 THEN '3582' 
            ELSE COALESCE(T02.biz_cd_rule,'') 
       END AS biz_cd                    -- 业务代号
      ,CASE WHEN T02.biz_cd_rule = '3543'
                 THEN '22100101'
            WHEN T02.biz_cd_rule = '3582'
                 THEN '22100102'
            WHEN T01.prod_no = '1027' AND T02.biz_cd_rule IS NULL
                 THEN '22100102'
            ELSE COALESCE(T03.subj_no,'') 
       END AS subj_no                   -- 科目号
      ,T01.cust_acct_no                 -- 客户账号
      ,T01.sub_acct_serial_no           -- 子账户序号
      ,T01.cust_acct_no_type_cd         -- 客户账号类型代码
      ,T01.acct_type_cd                 -- 账户类型代码
      ,T01.acct_addit_type_cd           -- 账户附加类型代码
	  ,T01.finan_deposit_acct_type_cd   -- 财政存款账户类型代码
      ,T01.deposit_category_cd          -- 存款种类代码
      ,T01.acct_sts_cd                  -- 账户状态代码
      ,T01.cash_remitt_flag             -- 钞汇标志
      ,CASE WHEN SUBSTR(T01.deposit_term_cd,-1) IN ('D','M','Y')
                 THEN SUBSTR(T01.deposit_term_cd,-1)
            ELSE 'X' 
       END AS term_cycle_cd             -- 期限周期代码
      ,CASE WHEN SUBSTR(T01.deposit_term_cd,-1) IN ('D','M','Y')
                 THEN SUBSTR(T01.deposit_term_cd,1,LENGTH(T01.deposit_term_cd) - 1)
            ELSE T01.deposit_term_cd
       END AS term_cycle                -- 期限周期
      ,T01.rnewa_mode_cd                -- 转存方式代码
      ,T01.frozen_mode_cd               -- 冻结方式代码
	  ,T01.alw_adv_withd_flag           -- 允许提前支取标志
	  ,T01.alw_overd_flag               -- 允许透支标志
	  ,T01.fx_supvs_flag                -- 外汇监管标志
	  ,T01.stl_acct_flag                -- 结算账户标志
      ,T01.cust_no                      -- 客户编号
      ,TM4.cust_name                    -- 客户名称
      ,CASE WHEN T01.prod_class_cd = '2' AND TM4.GOVERN_STRUCTURE = '1' 
                 THEN '0202'            -- 政务
            WHEN T01.prod_class_cd = '2' AND NVL(TM4.GOVERN_STRUCTURE,'') <> '1' 
                 THEN '0201'            -- 公司
            WHEN T01.prod_class_cd = '1' 
                 THEN '01'              -- 个人
            WHEN T01.prod_class_cd = '3' 
                 THEN '03'              -- 同业
       END AS cust_type_cd              -- 客户类型
      ,T01.agent_cust_no                -- 代理人客户号
      ,T01.gover_affairs_dtl_class      -- 账号政务明细类别
      ,T01.open_acct_org_no             -- 开户机构号
      ,T01.open_acct_dt                 -- 开户日期
      ,T01.open_acct_teller             -- 开户柜员编号
      ,T01.acct_cance_org               -- 销户机构号
      ,T01.acct_cance_dt                -- 销户日期
      ,T01.acct_cance_teller_no         -- 销户柜员编号
      ,T01.val_dt                       -- 起息日期
      ,T01.expir_dt                     -- 账户到期日
      ,T01.rgst_dt                      -- 登记日期
      ,COALESCE(T01.agr_deposit_begin_save_amt,0)   -- 协定存款起存金额
      ,TM1.agr_retn_amt                 -- 协定留存金额
      ,TM2.int_rate_no                  -- 利率编号
      ,TM2.int_rate_no_eff_day          -- 利率编号生效日
      ,TM2.int_rate                     -- 当前执行利率
      ,COALESCE(TM2.base_int_rate,0)                -- 基准利率
      ,TM2.int_rate_float_ratio         -- 利率浮动比例
      /*
	  ,TM2.int_rate_float_mode_cd       -- 利率浮动方式代码
	  ,TM2.int_rate_float_point         -- 利率浮动点数
	  ,TM2.int_rate_adjust_freq         -- 利率调整频率
	  ,TM2.last_int_rate_adjust_dt      -- 上次利率调整日期
	  ,TM2.next_int_rate_adjust_dt      -- 下次利率调整日期
      */
      ,COALESCE(TM2.int_rate_float_mode_cd,'')  AS int_rate_float_mode_cd     -- 利率浮动方式代码
	  ,COALESCE(TM2.int_rate_float_point,0)      AS int_rate_float_point       -- 利率浮动点数
	  ,COALESCE(TM2.int_rate_adjust_freq,'')         AS int_rate_adjust_freq       -- 利率调整频率
	  ,CASE WHEN UDF_DTCHK(TM2.last_int_rate_adjust_dt,'') <> ''
                 THEN DATE(TM2.last_int_rate_adjust_dt)
            ELSE DATE '{NULL_DATE}'
       END AS        last_int_rate_adjust_dt    -- 上次利率调整日期
      ,CASE WHEN UDF_DTCHK(TM2.next_int_rate_adjust_dt,'') <> ''
                 THEN DATE(TM2.next_int_rate_adjust_dt)
            ELSE DATE '{NULL_DATE}'
       END AS        next_int_rate_adjust_dt    -- 下次利率调整日期
      ,COALESCE(TM1.int_rate,TM2.open_acct_int_rate) AS open_acct_int_rate   -- 开户利率
      ,T01.open_acct_amt                -- 开户金额
      ,T01.stop_pay_amt                 -- 止付金额
      ,COALESCE(T01.frozen_amt,0) as frozen_amt -- 需冻结金额
      ,COALESCE(T01.curr_frozen_amt,0) as curr_frozen_amt -- 已冻结金额
      ,0    AS LDR_FROZEN_PRINCIPAL     -- 大额存单冻结金额
      ,T01.acct_grade_cd                -- 账户分级代码
      ,T01.ctrl_measure_cd              -- 控制措施代码
      ,T01.recen_movg_acct_dt           -- 最近动账日期
      ,T01.currcy_cd                    -- 币种代码
      ,TCB.acct_bal                     -- 账户余额
      ,TCB.month_daily_avg              -- 月日均
      ,TCB.quart_daily_avg              -- 季日均
      ,TCB.half_year_daily_avg          -- 半年日均
      ,TCB.year_daily_avg               -- 年日均
      ,TCB.roll_month_daily_avg         -- 滚动月日均
      ,TCB.roll_quart_daily_avg         -- 滚动季日均
      ,TCB.roll_half_year_daily_avg     -- 滚动半年日均
      ,TCB.roll_year_daily_avg          -- 滚动年日均
      ,COALESCE(TAI.today_accru_intst,0) + COALESCE(TAI.today_paid_intst,0)      AS day_intst    -- 日利息
      ,COALESCE(TAI.thmonth_accru_intst,0) + COALESCE(TAI.thmonth_paid_intst,0)  AS month_intst  -- 月利息
      ,COALESCE(TAI.quart_accru_intst,0) + COALESCE(TAI.quart_paid_intst,0)      AS quart_intst  -- 季利息
      ,COALESCE(TAI.thyear_accru_intst,0) + COALESCE(TAI.thyear_paid_intst,0)    AS year_intst   -- 年利息
      ,'F_AC_CRBS_DEPOSIT_ACCT'                         AS data_sourc_tab_name
 FROM {CDM}.VT_C_AGT_DEPOSIT_ACCT_INF_03   T01
LEFT JOIN (SELECT T1.prod_no
                 ,T1.prod_class_cd2
                 ,TRIM(T1.prod_class_cd1)||'-'||TRIM(T1.prod_featu_cd) AS biz_type
                 ,T1.biz_cd_rule 
             FROM {FDM}.F_FI_CRBS_NEW_ACCTG_ENTRY_CONFIG_FILE_BIZ    T1      -- 核心新会计分录配置文件业务信息
            WHERE T1.end_dt > DATE '{ETL_TX_DT}'
              AND T1.start_dt <= DATE '{ETL_TX_DT}' 
              AND T1.belo_modl_cd = 'DP' 
              AND T1.prod_featu_cd IN ('LDGBAL','DPBDHKM','DPYYWKM') 
              AND T1.record_sts_cd = '0' 
           GROUP BY T1.prod_no,T1.prod_class_cd2,TRIM(T1.prod_class_cd1)||'-'||TRIM(T1.prod_featu_cd),T1.biz_cd_rule
           ) T02
      ON T01.biz_type = T02.biz_type
     AND (T02.prod_class_cd2 = 'XA' OR (T01.biz_class = T02.prod_class_cd2 AND T02.prod_class_cd2 <> 'XA'))
     AND (T02.prod_no = 'XA' OR (T01.prod_no = T02.prod_no AND T02.prod_no <> 'XA'))
LEFT JOIN {FDM}.F_CO_CRBS_BIZ_CD T03 
      ON T02.biz_cd_rule = T03.biz_cd 
     AND T03.end_dt > DATE '{ETL_TX_DT}'
     AND T03.start_dt <= DATE '{ETL_TX_DT}'
LEFT JOIN {CDM}.VT_C_AGT_DEPOSIT_ACCT_INF_01   TM1
      ON T01.acct_no = TM1.acct_no
LEFT JOIN {CDM}.VT_C_AGT_DEPOSIT_ACCT_INF_02   TM2
        ON T01.acct_no = TM2.acct_no
LEFT JOIN  {CDM}.VT_C_AGT_DEPOSIT_ACCT_INF_04  TM4
      ON  T01.cust_no = TM4.cust_no
LEFT JOIN {CDM}.C_AGT_DEPOSIT_ACCT_CALCU_BASE  TCB
      ON T01.acct_no = TCB.acct_no
     AND TCB.data_dt = DATE '{ETL_TX_DT}'
LEFT JOIN (SELECT
          	T.ACCT_NO,
          	SUM( TODAY_ACCRU_INTST ) AS TODAY_ACCRU_INTST,
          	SUM( TODAY_PAID_INTST ) AS TODAY_PAID_INTST,
          	SUM( THMONTH_ACCRU_INTST ) AS THMONTH_ACCRU_INTST,
          	SUM( THMONTH_PAID_INTST ) AS THMONTH_PAID_INTST,
          	SUM( QUART_ACCRU_INTST ) AS QUART_ACCRU_INTST,
          	SUM( QUART_PAID_INTST ) AS QUART_PAID_INTST,
          	SUM( THYEAR_ACCRU_INTST ) AS THYEAR_ACCRU_INTST,
          	SUM( THYEAR_PAID_INTST ) AS THYEAR_PAID_INTST
          from {CDM}.C_AGT_DEPOSIT_ACCT_INTST T
          WHERE T.DATA_DT = DATE '{ETL_TX_DT}'
          GROUP BY T.ACCT_NO ) TAI
      ON T01.acct_no = TAI.acct_no
;


/*Step6:向主表插入大额存单业务数据*/
INSERT INTO {CDM}.C_AGT_DEPOSIT_ACCT_INF
SELECT DATE '{ETL_TX_DT}'               -- 数据日期
      ,T01.acct_no                      -- 账号
      ,TM4.cust_name                    -- 账户名称
      ,T01.prod_cd                      -- 产品编号
      ,'1' AS prod_type                 -- 产品类型
      ,''  AS biz_cd                    -- 业务代号
      ,CASE WHEN TPD.term = '1M' AND T01.cust_type_cd = '0'
                 THEN '22020201'        -- 一月期单位大额存单
            WHEN TPD.term = '3M' AND T01.cust_type_cd = '0'
                 THEN '22020202'        -- 三月期单位大额存单
            WHEN TPD.term = '6M' AND T01.cust_type_cd = '0'
                 THEN '22020203'        -- 六月期单位大额存单
            WHEN TPD.term = '9M' AND T01.cust_type_cd = '0'
                 THEN '22020204'        -- 九月期单位大额存单
            WHEN TPD.term = '12M' AND T01.cust_type_cd = '0'
                 THEN '22020205'        -- 一年期单位大额存单
            WHEN TPD.term = '18M' AND T01.cust_type_cd = '0'
                 THEN '22020206'        -- 十八月期单位大额存单
            WHEN TPD.term = '2Y' AND T01.cust_type_cd = '0'
                 THEN '22020207'        -- 两年期单位大额存单
            WHEN TPD.term = '3Y' AND T01.cust_type_cd = '0'
                 THEN '22020208'        -- 三年期单位大额存单
            WHEN TPD.term = '5Y' AND T01.cust_type_cd = '0'
                 THEN '22020209'        -- 五年期单位大额存单
            WHEN TPD.term = '1M' AND T01.cust_type_cd = '1'
                 THEN '22040601'        -- 一月期个人大额存单
            WHEN TPD.term = '3M' AND T01.cust_type_cd = '1'
                 THEN '22040602'        -- 三月期个人大额存单
            WHEN TPD.term = '6M' AND T01.cust_type_cd = '1'
                 THEN '22040603'        -- 六月期个人大额存单
            WHEN TPD.term = '9M' AND T01.cust_type_cd = '1'
                 THEN '22040604'        -- 九月期个人大额存单
            WHEN TPD.term = '12M' AND T01.cust_type_cd = '1'
                 THEN '22040605'        -- 一年期个人大额存单
            WHEN TPD.term = '18M' AND T01.cust_type_cd = '1'
                 THEN '22040606'        -- 十八月期个人大额存单
            WHEN TPD.term = '2Y'  AND T01.cust_type_cd = '1'
                 THEN '22040607'        -- 两年期个人大额存单
            WHEN TPD.term = '3Y'  AND T01.cust_type_cd = '1'
                 THEN '22040608'        -- 三年期个人大额存单
            WHEN TPD.term = '5Y'  AND T01.cust_type_cd = '1'
                 THEN '22040609'        -- 五年期个人大额存单
            WHEN T01.cust_type_cd = '0'
                 THEN '220202'          -- 其他单位大额存单
            ELSE '220406'               -- 其他个人大额存单
       END AS subj_no                   -- 科目号
      ,T01.bank_acct_no AS cust_acct_no -- 客户账号
      ,''   AS sub_acct_serial_no       -- 子账户序号
      ,'0'  AS cust_acct_no_type_cd     -- 客户账号类型代码
      ,''   AS acct_type_cd             -- 账户类型代码
      ,''   AS acct_addit_type_cd       -- 账户附加类型代码
	  ,''   AS finan_deposit_acct_type_cd   -- 财政存款账户类型代码
      ,''   AS deposit_category_cd      -- 存款种类代码
      ,CASE WHEN TCB.acct_bal <> 0
                 THEN 'A'
            ELSE 'C'
       END  AS acct_sts_cd              -- 账户状态代码
      ,'0'  AS cash_remitt_flag         -- 钞汇标志
      ,CASE WHEN SUBSTR(TPD.term,-1) IN ('D','M','Y')
                 THEN SUBSTR(TPD.term,-1)
            ELSE 'X' 
       END AS term_cycle_cd             -- 期限周期代码
      ,CASE WHEN SUBSTR(TPD.term,-1) IN ('D','M','Y')
                 THEN SUBSTR(TPD.term,1,LENGTH(TPD.term) - 1)
            ELSE TPD.term
       END AS term_cycle                -- 期限周期
      ,''   AS rnewa_mode_cd            -- 转存方式代码
      ,''   AS frozen_mode_cd           -- 冻结方式代码
	  ,''   AS alw_adv_withd_flag       -- 允许提前支取标志
	  ,''   AS alw_overd_flag           -- 允许透支标志
	  ,''   AS fx_supvs_flag            -- 外汇监管标志
	  ,''   AS stl_acct_flag            -- 结算账户标志
      ,T01.cust_no                      -- 客户编号
      ,TM4.cust_name                    -- 客户名称
      ,CASE WHEN T01.cust_type_cd = '0' AND TM4.GOVERN_STRUCTURE = '1' 
                 THEN '0202'            -- 政务
            WHEN T01.cust_type_cd = '0' AND NVL(TM4.GOVERN_STRUCTURE,'') <> '1' 
                 THEN '0201'            -- 公司
            WHEN T01.cust_type_cd = '1' 
                 THEN '01'              -- 个人
       END AS cust_type_cd              -- 客户类型
      ,'' as agent_cust_no              -- 代理人客户号
      ,'' AS gover_affairs_dtl_class    -- 账号政务明细类别
      ,T01.belo_org_no                  -- 开户机构号
      ,CASE WHEN T01.trade_dt = 0
                 THEN DATE '{MIN_DATE}'
            WHEN DATE(CAST(T01.trade_dt as VARCHAR)) is null
                 THEN DATE '{LEG_DATE}'
            ELSE DATE(CAST(T01.trade_dt as VARCHAR))
       END AS open_acct_dt              -- 开户日期
      ,T01.txn_teller_no AS open_acct_teller          -- 开户柜员编号
      ,CASE WHEN T01.txn_sts_cd = '5' 
                 THEN COALESCE(TCA.txn_org_no,'')
            ELSE '' 
       END AS acct_cance_org            -- 销户机构号
      ,CASE WHEN T01.txn_sts_cd = '5' 
                 THEN COALESCE(TCA.trade_dt, DATE '{LEG_DATE}')
            ELSE DATE(T01.adjust_after_expir_dt)
       END AS acct_cance_dt             -- 销户日期
      ,CASE WHEN T01.txn_sts_cd = '5' 
                 THEN COALESCE(TCA.txn_teller_no,'')
            ELSE ''
       END AS acct_cance_teller          -- 销户柜员编号
      ,CASE WHEN T01.txn_val_dt = 0
                 THEN DATE '{MIN_DATE}'
            WHEN DATE(CAST(T01.txn_val_dt as VARCHAR)) is null
                 THEN DATE '{LEG_DATE}'
            ELSE DATE(CAST(T01.txn_val_dt as VARCHAR))
       END AS val_dt                    -- 起息日期
      ,CASE WHEN T01.adjust_after_expir_dt = 0
                 THEN DATE '{MAX_DATE}'
            WHEN DATE(CAST(T01.adjust_after_expir_dt as VARCHAR)) is null
                 THEN DATE '{LEG_DATE}'
            ELSE DATE(CAST(T01.adjust_after_expir_dt as VARCHAR))
       END AS expir_dt                     -- 账户到期日
      ,DATE '{NULL_DATE}'  as rgst_dt                    -- 登记日期
      ,0   AS agr_deposit_begin_save_amt   -- 协定存款起存金额
      ,0   AS agr_retn_amt                 -- 协定留存金额
      ,''  AS int_rate_no                  -- 利率编号
      ,CASE WHEN T01.trade_dt = 0
                 THEN DATE '{MIN_DATE}'
            WHEN DATE(CAST(T01.trade_dt as VARCHAR)) is null
                 THEN DATE '{LEG_DATE}'
            ELSE DATE(CAST(T01.trade_dt as VARCHAR))
       END AS int_rate_no_eff_day           -- 利率编号生效日
      ,TPD.face_annua_int_rate              AS int_rate       -- 当前执行利率
      ,COALESCE(TPD.face_annua_int_rate,0)              AS base_int_rate            -- 基准利率
      ,0                                    AS int_rate_float_ratio          -- 利率浮动比例
      ,''                                   AS int_rate_float_mode_cd        -- 利率浮动方式代码
      ,0                                    AS int_rate_float_point          -- 利率浮动点数
      ,''                                   AS int_rate_adjust_freq          -- 利率调整频率
      ,DATE '{NULL_DATE}'  AS last_int_rate_adjust_dt       -- 上次利率调整日期
      ,DATE '{NULL_DATE}'  AS next_int_rate_adjust_dt       -- 下次利率调整日期
      ,TPD.face_annua_int_rate              -- 开户利率
      ,T01.principal                        -- 开户金额
      ,0   AS stop_pay_amt                  -- 止付金额
      ,0   AS frozen_amt                    -- 需冻结金额
      ,0   AS curr_frozen_amt               -- 已冻结金额
      ,COALESCE(T02.FROZEN_PRINCIPAL,0)    AS LDR_FROZEN_PRINCIPAL    -- 大额存单冻结金额
      ,'' AS acct_grade_cd              -- 账户分级代码
      ,'' AS ctrl_measure_cd            -- 控制措施代码
      ,CASE WHEN T01.latest_modif_dt = 0
                 THEN DATE '{MIN_DATE}'
            WHEN DATE(CAST(T01.latest_modif_dt as VARCHAR)) is null
                 THEN DATE '{LEG_DATE}'
            ELSE DATE(CAST(T01.latest_modif_dt as VARCHAR))
       END AS recen_movg_acct_dt        -- 最近动账日期
      ,'156' AS prod_currcy_cd          -- 币种代码  基础层大额存单系统未做码值标准化,暂时直接填写人民币代码
      ,TCB.acct_bal                     -- 账户余额
      ,TCB.month_daily_avg              -- 月日均
      ,TCB.quart_daily_avg              -- 季日均
      ,TCB.half_year_daily_avg          -- 半年日均
      ,TCB.year_daily_avg               -- 年日均
      ,TCB.roll_month_daily_avg         -- 滚动月日均
      ,TCB.roll_quart_daily_avg         -- 滚动季日均
      ,TCB.roll_half_year_daily_avg     -- 滚动半年日均
      ,TCB.roll_year_daily_avg          -- 滚动年日均
      ,COALESCE(TAI.day_intst,0)    AS day_intst    -- 日利息
      ,COALESCE(TAI.month_intst,0)  AS month_intst  -- 月利息
      ,COALESCE(TAI.quart_intst,0)  AS quart_intst  -- 季利息
      ,COALESCE(TAI.year_intst,0)   AS year_intst   -- 年利息
      ,'F_AC_CTDS_LDR_ACCT' AS data_sourc_tab_name
 FROM {FDM}.F_AC_CTDS_LDR_ACCT T01
INNER JOIN {CDM}.C_AGT_LDR_ACCT_CALCU_BASE  TCB
      ON T01.acct_no = TCB.acct_no
     AND TCB.data_dt = DATE '{ETL_TX_DT}'
LEFT JOIN {FDM}.F_PD_CTDS_LDR_PROD_INF TPD 
    ON T01.prod_cd = TPD.prod_no 
   AND TPD.end_dt > DATE(CAST(T01.trade_dt as VARCHAR))
   AND TPD.start_dt <= DATE(CAST(T01.trade_dt as VARCHAR))
LEFT JOIN {CDM}.VT_C_AGT_DEPOSIT_ACCT_INF_04 TM4
    ON T01.cust_no = TM4.cust_no
LEFT JOIN {CDM}.C_AGT_LDR_ACCT_INTST       TAI
    ON T01.acct_no = TAI.acct_no
   AND TAI.data_dt = DATE '{ETL_TX_DT}'
LEFT JOIN (SELECT TC.ori_deal_serial_no
                 ,TC.txn_org_no
                 ,CASE WHEN TC.trade_dt = 0
                            THEN DATE '{MIN_DATE}'
                       WHEN DATE(cast(TC.trade_dt as VARCHAR)) is null
                            THEN DATE '{LEG_DATE}'
                       ELSE DATE(cast(TC.trade_dt as VARCHAR)) 
                  END AS trade_dt
                  ,TC.txn_teller_no 
                 ,ROW_NUMBER()OVER(PARTITION BY TC.ori_deal_serial_no ORDER BY TC.trade_dt DESC, TC.latest_modif_dt DESC) AS tcrn
             FROM {FDM}.F_AC_CTDS_LDR_ACCT TC 
            WHERE TC.end_dt > DATE '{ETL_TX_DT}'
              AND TC.start_dt <= DATE '{ETL_TX_DT}'
              AND TC.txn_cd IN ('1A0002','1A0225','1A0017')
           ) TCA
    ON T01.acct_no = TCA.ori_deal_serial_no
   AND TCA.tcrn = 1
LEFT JOIN (
    SELECT
        SRC_TXN_SEQ_NO       -- 源交易流水号
        ,TXN_SEQ_NO          -- 交易流水号
        ,FROZEN_PRINCIPAL    -- 冻结本金
        ,TXN_STS_CD          -- 交易状态代码
        ,ROW_NUMBER()OVER(PARTITION BY SRC_TXN_SEQ_NO ORDER BY TRADE_DAY_TERM DESC, LATEST_MODIF_DT DESC) AS RN
    FROM {FDM}.F_AC_CTDS_LDR_FROZEN_INF
    WHERE START_DT <= DATE '{ETL_TX_DT}'
    AND END_DT > DATE '{ETL_TX_DT}'
    AND TXN_CD = '1A0400'
    AND VERSI_NO = '0'
    AND FROZEN_PRINCIPAL > 0
) T02
    ON T01.ACCT_NO = T02.SRC_TXN_SEQ_NO
    AND T01.TXN_STS_CD = '6'
    AND T01.FROZEN_PRINCIPAL = 0
    AND T02.RN = 1
    AND T02.TXN_STS_CD = '2'
 WHERE T01.end_dt > DATE '{ETL_TX_DT}'
   AND T01.start_dt <= DATE '{ETL_TX_DT}'
   AND T01.txn_cd = '1A0001';

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
