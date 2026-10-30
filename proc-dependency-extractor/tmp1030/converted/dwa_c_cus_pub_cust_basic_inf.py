# *****************************************头部注释模块**************************************
#脚本名称：dwa_c_cus_pub_cust_basic_inf.py
#功能说明：对公客户信息
#更新方式：每日全量
#更新对象：{CDM}.C_CUS_PUB_CUST_BASIC_INF
#创 建 人：cq
#创建日期：2024-10-15
#运行周期：日
#备    注：整合各个系统的对公客户信息
#维护信息：20250103 cq  增加 我行认定小企业标志
#          20250416 wjj 增加 员工人数
#          20250512 hc  增加 行业类别代码、企业规模代码新增新票据部分
#          20250617 wjj 增加 国籍代码、法人代表电话号码、境内居民标志、注册地址、办公地址、办公电话
#          20250623 hc  增加 登记注册号、注册登记日期、农村城市标志    
#          202604   WXL 增加 单位级别代码、经营地址    
#          202606   WXL 增加 科创企业标志、科创企业类型代码、科创企业类型
# *******************************************************************************************
# -*- coding: utf-8 -*-
import atexit
from datetime import datetime
import sys
import os
import subprocess
import re
from Date import Date

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
dt = Date();
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
YTD_DAYS = etl.YTD_DAYS           #截止到当天年天数
MTD = (dt.to_date(ETL_TX_DT) - dt.to_date(I_MB_DT)).days + 1  #截止到当天月天数
QMD = (dt.to_date(ETL_TX_DT) - dt.to_date(I_QB_DT)).days + 1  #截止到当天季天数
HMD = (dt.to_date(ETL_TX_DT) - dt.to_date(I_HB_DT)).days + 1  #截止到当天半年天数
I_LAST_TX_DT = dt.day_add(I_LYOY_DT,+1)   #截止到去年同期下一天
Q_DAYS = etl.Q_DAYS           #季天数
I_TDB_DT = etl.I_TDB_DT       #旬初
TEN_DAYS  = etl.TEN_DAYS      #当前旬天数
HFYEAR_DAYS = etl.HFYEAR_DAYS  #当前半年天数
V_PRE_SB_DT = dt.day_add(I_TDB_DT,-1)  #上旬末
V_PRE_HB_DT = dt.day_add(I_HB_DT,-1)   #上半年末
V_PRE_YB_DT = dt.day_add(I_YB_DT,-1)   #上年末


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

@atexit.register
def __exit__():
    """print a log when exit"""
    log.info("END\n")



# -- 4.执行sql内容-- #
# -- bsql1支持重跑sql及临时表创建-- #
bsql1="""

-- 删除该表当天数据，支持数据重跑
DELETE FROM  {CDM}.C_CUS_PUB_CUST_BASIC_INF  WHERE  DATA_DT = DATE '{ETL_TX_DT}' ;

DROP TABLE IF EXISTS {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF ;
CREATE TEMPORARY TABLE {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF (
  DATA_DT                               DATE           NOT NULL     COMMENT '数据日期'         
,UNIFI_CUST_NO                          VARCHAR(100)   NOT NULL     COMMENT '统一客户号'
,CUST_NAME                              VARCHAR(300)   DEFAULT NULL COMMENT '客户名称'
,CUST_CLASS                             VARCHAR(100)   DEFAULT NULL COMMENT '客户分类'
,IDEN_TYPE_CD                           VARCHAR(60)    DEFAULT NULL COMMENT '证件类型代码'
,IDEN_NO                                VARCHAR(100)   DEFAULT NULL COMMENT '证件号码'
,USCC                                   VARCHAR(100)   DEFAULT NULL COMMENT '统一社会信用代码'
,ORGAN_CD                               VARCHAR(60)    DEFAULT NULL COMMENT '组织机构代码'
,RGST_REGIS_NO                          VARCHAR(100)   DEFAULT NULL COMMENT '登记注册号'
,RGST_RGST_DT                           DATE           DEFAULT NULL COMMENT '注册登记日期'
,INDUS_AND_COMME_BIZ_LICEN_NO           VARCHAR(32)    DEFAULT NULL COMMENT '工商营业执照号'
,INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT     DATE           DEFAULT NULL COMMENT '工商营业执照到期日' -- 有长期 无
,SHAREHD_FLAG                           VARCHAR(60)    DEFAULT NULL COMMENT '股东标志'
,RURAL_CITY_FLAG                        VARCHAR(10)    DEFAULT NULL COMMENT '农村城市标志'
,INDUS_CLASS_CD                         VARCHAR(60)    DEFAULT NULL COMMENT '行业类别代码'
,nation_cd                              VARCHAR(60)    DEFAULT NULL COMMENT '国籍代码'
,STATU_REP_CUST_NAME                    VARCHAR(100)   DEFAULT NULL COMMENT '法定代表人客户姓名'
,STATU_REP_IDEN_TYPE_CD                 VARCHAR(60)    DEFAULT NULL COMMENT '法定代表人证件类型代码'
,STATU_REP_IDEN_NO                      VARCHAR(100)   DEFAULT NULL COMMENT '法定代表人证件号码'
,statu_rep_tel                          VARCHAR(100)   DEFAULT NULL COMMENT '法定代表人电话号码'
,SET_UP_DT                              DATE           DEFAULT NULL COMMENT '成立日期'
,ENT_SCALE_CD                           VARCHAR(60)    DEFAULT NULL COMMENT '企业规模代码'
,MYBANK_AFFIR_MINOR_ENT_FLAG            VARCHAR(10)    DEFAULT NULL COMMENT '我行认定小企业标志'
,LISTE_COMPY_FLAG                       VARCHAR(10)    DEFAULT NULL COMMENT '上市公司标志'
,dom_resdnt_flag                        VARCHAR(10)    DEFAULT NULL COMMENT '境内居民标志'
,RELATED_GRP_CUST_NO                    VARCHAR(100)   DEFAULT NULL COMMENT '关联集团客户号'
,CREATE_ORG_NO                          VARCHAR(100)   DEFAULT NULL COMMENT '创建机构号'
,UPDATE_ORG_NO                          VARCHAR(100)   DEFAULT NULL COMMENT '更新机构号'
,ADMIN_AREA_CD                          VARCHAR(60)    DEFAULT NULL COMMENT '行政区划代码'
,OPERA_BIZ_SCOPE                        VARCHAR(4000)  DEFAULT NULL COMMENT '经营业务范围'
,HOLD_TYPE_CD                           VARCHAR(60)    DEFAULT NULL COMMENT '控股类型代码'
,CREDIT_RATIN_CD                        VARCHAR(60)    DEFAULT NULL COMMENT '信用评级代码'
,CUST_TYPE_CD                           VARCHAR(60)    DEFAULT NULL COMMENT '客户类型代码'
,FIN_ORG_CD                             VARCHAR(60)    DEFAULT NULL COMMENT '金融机构代码'
,unit_lvl_cd                            VARCHAR(60)    DEFAULT NULL COMMENT '单位级别代码'
,BASIC_DEPOSIT_ACCT_NO                  VARCHAR(100)   DEFAULT NULL COMMENT '基本存款账号'
,BASIC_ACCT_OPENBANK_NAME               VARCHAR(100)   DEFAULT NULL COMMENT '基本账户开户行名称'
,OPERA_STS_CD                           VARCHAR(60)    DEFAULT NULL COMMENT '经营状态代码'
,opera_addr                             VARCHAR(500)   DEFAULT NULL COMMENT '经营地址'
,RGST_CY_CD                             VARCHAR(60)    DEFAULT NULL COMMENT '注册国家代码'
,RGST_CAP                               DECIMAL(24,2)  DEFAULT NULL COMMENT '注册资金'
,RGST_CAP_CURRCY_CD                     VARCHAR(60)    DEFAULT NULL COMMENT '注册资金币种代码'
,RGST_ADDR                              VARCHAR(500)   DEFAULT NULL COMMENT '注册地址'
,offic_addr                             VARCHAR(500)   DEFAULT NULL COMMENT '办公地址'
,offic_tel                              VARCHAR(100)   DEFAULT NULL COMMENT '办公电话'
,RCVD_CAP                               DECIMAL(24,2)  DEFAULT NULL COMMENT '实收资本'
,RCVD_CAP_CURRCY_CD                     VARCHAR(60)    DEFAULT NULL COMMENT '实收资本币种代码'
,YEAR_BIZ_INCOME_CURRCY_CD              VARCHAR(60)    DEFAULT NULL COMMENT '年营业收入币种代码'
,YEAR_BIZ_INCOME_UNIT_CD                VARCHAR(20)    DEFAULT NULL COMMENT '年营业收入单位代码'
,YEAR_BIZ_INCOME                        DECIMAL(24,2)  DEFAULT NULL COMMENT '年营业收入'
,LAST_YEAR_BIZ_INCOME_CURRCY_CD         VARCHAR(60)    DEFAULT NULL COMMENT '上年营业收入币种代码'
,LAST_YEAR_BIZ_INCOME_UNIT_CD           VARCHAR(20)    DEFAULT NULL COMMENT '上年营业收入单位代码'
,LAST_YEAR_BIZ_INCOME                   DECIMAL(24,2)  DEFAULT NULL COMMENT '上年营业收入'
,EMP_PERSON_CNT                         DECIMAL(10,0)  DEFAULT NULL COMMENT '员工人数'
,DATA_SOURC                             VARCHAR(100)   DEFAULT NULL COMMENT '数据来源'

) DISTRIBUTED BY('UNIFI_CUST_NO');

drop table if exists {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_ECIF ; 
drop table if exists {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_CRBS ; 
drop table if exists {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_CRMS;  
drop table if exists {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_NBMS ; 
drop table if exists {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SILN ; 
drop table if exists {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_FNDS_BB ;
drop table if exists {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_FNDS_WB ;
drop table if exists {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SFLN  ;
drop table if exists {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SCLN  ;
drop table if exists {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SCLN_ZZ  ;

CREATE TABLE {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_ECIF  DISTRIBUTED BY('UNIFI_CUST_NO') as  (SELECT * FROM {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF LIMIT 0) ;
CREATE TABLE {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_CRBS  DISTRIBUTED BY('UNIFI_CUST_NO') as  (SELECT * FROM {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF LIMIT 0) ;
CREATE TABLE {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_CRMS  DISTRIBUTED BY('UNIFI_CUST_NO') as  (SELECT * FROM {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF LIMIT 0) ;
CREATE TABLE {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_NBMS  DISTRIBUTED BY('UNIFI_CUST_NO') as  (SELECT * FROM {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF LIMIT 0) ;  -- 新票据 20221220
CREATE TABLE {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SILN  DISTRIBUTED BY('UNIFI_CUST_NO') as  (SELECT * FROM {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF LIMIT 0) ;
CREATE TABLE {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_FNDS_BB  DISTRIBUTED BY('UNIFI_CUST_NO') as  (SELECT * FROM {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF LIMIT 0) ;
CREATE TABLE {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_FNDS_WB  DISTRIBUTED BY('UNIFI_CUST_NO') as  (SELECT * FROM {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF LIMIT 0) ;
CREATE TABLE {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SFLN  DISTRIBUTED BY('UNIFI_CUST_NO') as  (SELECT * FROM {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF LIMIT 0) ;
-- 众商贷
CREATE TABLE {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SCLN  DISTRIBUTED BY('UNIFI_CUST_NO')  AS (SELECT *  FROM  {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF LIMIT 0)  ;
-- 众商贷债转
CREATE TABLE {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SCLN_ZZ  DISTRIBUTED BY('UNIFI_CUST_NO')  AS (SELECT *  FROM  {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF LIMIT 0)  ;

 		   
                  
"""

# -生成mapping映射- #
bsqlmapping="""


"""

# -bsql2算法逻辑主体实现- #
bsql2="""

CREATE TEMPORARY  TABLE {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP1 AS (
SELECT DISTINCT 
        RR.obj_no  -- 客户号 
        ,RR.report_cali_cd  -- 报表口径代码 : 01-本部，02-合并
        ,RR.report_dt   --  报表日期
        ,RD.num_val_item2  -- 主营业务收入 
        ,CF.report_currcy_cd -- 报表币种代码的值示例：156 人民币
        ,CF.report_unit_cd -- 报表单位代码： 01 元，02 千元，03 万元
  FROM 
	(  -- 上年、上上年的年报获取逻辑
       SELECT 
			  ROW_NUMBER() OVER(PARTITION BY RR.report_dt,RR.obj_no -- 报表日期 :监管报送数据日
			  	ORDER BY RR.report_cali_cd DESC  -- 报表口径代码 : 01-本部，02-合并 合并：可能是集团客户，会取合并后的数据
			  	,RR.update_tm DESC      -- 更新时间 :最新记录
			  	,RR.start_dt DESC 
			  	) AS RN 
			  ,RR.obj_no
			  ,RR.report_cali_cd  -- 报表口径代码 : 01-本部，02-合并 合并应该是集团客户
			  ,RR.report_dt   --  报表日期
			  ,RR.report_no
		 FROM {FDM}.F_FI_CRMS_LOAN_BIZ_CUST_FINAN_REPORT_DIR RR -- 信贷业务客户财务报表目录 REPORT_NO报表编号
		WHERE lower(RR.obj_type_cd) = 'customerfs'
		  AND RR.report_model_no IN ('0002','0202','0302','0992','0702')
		  AND REPLACE(RR.report_dt,'/','') IN ((LEFT('{ETL_TX_DT}' ,4)-1) ||'12' , (LEFT('{ETL_TX_DT}' ,4)-2) ||'12')  -- 注意： 需要根据跑数时点更新
		  AND RR.report_sourc_cd = '01'  -- 01 客户提供,02 A角调查,03 B角调查
		  AND RR.del_flag <> 'D'
		  AND RR.start_dt <=DATE '{ETL_TX_DT}'
	) RR
 INNER JOIN 
	( -- '501','d016'不会都有，这个就是定位报表栏位的，只会有一个有营收 
	  SELECT 
		      RD.num_val_item2
		     ,RD.report_no
		FROM {FDM}.F_FI_CRMS_LOAN_BIZ_FINAN_REPORT_DTL RD  -- 信贷业务财务报表明细 科目 report_no
	   WHERE RD.subj_no IN ('501','d016')  --  科目号(报表栏位)
		 AND RD.num_val_item2 IS NOT NULL  
		 AND ETL_TX_DT<=DATE '{ETL_TX_DT}'
	) RD
	ON RD.report_no = RR.report_no

 INNER JOIN 
	( 
	  SELECT 
			   CF.report_currcy_cd -- 报表币种代码的值示例：156 -人民币
			  ,CF.report_unit_cd -- 报表单位代码： 01 元，02 千元，03 万元
			  ,CF.cust_no
			  ,ROW_NUMBER()OVER(PARTITION BY CF.report_dt,CF.cust_no ORDER BY CF.report_currcy_cd DESC ,CF.report_unit_cd DESC ) AS RN
		      ,CF.report_dt
		FROM {FDM}.F_FI_CRMS_LOAN_BIZ_FINAN_REPORT_DIR CF  -- 财务报表记录表 CUST_NO RECORD_NO
	   WHERE REPLACE(CF.report_dt,'/','')   IN ((LEFT('{ETL_TX_DT}' ,4)-1) ||'12' ,(LEFT('{ETL_TX_DT}' ,4)-2) ||'12' )     -- 注意： 需要根据跑数时点更新
		 AND CF.data_sourc_cd = '01'  -- 01 客户提供,02 A角调查,03 B角调查
		 AND CF.report_cycle_cd = '04'  -- 报表周期 : 01 月报,02 季报,03 半年报,04 年报,05 其他
		 AND CF.del_flag <> 'D'
		 AND CF.start_dt <= TO_DATE('{ETL_TX_DT}','YYYYMMDD') -- 开始日期
		 AND CF.end_dt > TO_DATE('{ETL_TX_DT}','YYYYMMDD') -- 结束日期 
	) CF
	ON CF.cust_no = RR.obj_no AND CF.report_dt = RR.report_dt AND CF.RN=1	
 WHERE RR.RN=1  
 );
 
 --  1.2.7 创建预处理临时表
--  V_STEP_ID = '1.2.7';
--  V_STEP_DESC := '创建预处理临时表7';
CREATE TEMPORARY  TABLE {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP2 AS (
 SELECT  OCA.main_cust_no  --  客户号
        ,OCA.basic_acct_acct_no  AS BASE_ACCT --  基本存款账号 
        ,CASE WHEN OCB.basic_acct_openbank_bank_name <>'' THEN  OCB.basic_acct_openbank_bank_name --  基本户开户行行名 
              ELSE NULL END AS base_acct_op_name --  基本账户开户行名称
   FROM 
       (SELECT  main_cust_no  --  客户号
                ,basic_acct_acct_no --  基本户账号
                ,liabi_acct_no --  负债账号
                ,basic_acct_openbank_cd -- 基本户开户行代码
                ,ROW_NUMBER() OVER(PARTITION BY  main_cust_no ORDER BY  acct_open_acct_day /*账户开户日*/ DESC) rn
        FROM {FDM}.F_AC_CRBS_DEPOSIT_ACCT  --  负债账户信息表 
       WHERE start_dt <= DATE '{ETL_TX_DT}' AND  end_dt > DATE '{ETL_TX_DT}' 
         AND basic_acct_acct_no IS NOT NULL  AND basic_acct_acct_no<>''
       ) OCA
  LEFT JOIN {FDM}.F_AC_CRBS_DEPOSIT_ACCT_SUPPL_MATRL_RGST_INF OCB --  负债账户补充资料登记表 
    ON TRIM(OCA.liabi_acct_no) = TRIM(OCB.liabi_acct_no)  AND OCB.start_dt <= DATE '{ETL_TX_DT}' AND OCB.end_dt > DATE '{ETL_TX_DT}'
 WHERE OCA.RN=1 
 )
;   
 
-- ECIF客户去重_对公
CREATE TEMPORARY  TABLE {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP3 AS (
 SELECT  T.*  
   FROM ( 
        SELECT ROW_NUMBER() OVER(PARTITION BY A1.unifi_cust_no  ORDER BY  A.last_update_tm DESC ,A.cust_create_dt DESC )  RN 
		      ,A.cust_no
              ,A.cust_type_cd
              ,A.start_dt
              ,A.end_dt
              ,A.iden_type_cd
              ,A.iden_no
              ,A.del_flag			  
          FROM  {CDM}.C_CUS_UNIFI_CUST_INF_PUB A1 
         INNER JOIN {FDM}.F_PT_ECIF_WHOLE_BANK_CUST_INF A -- ECIF全行客户信息
            ON A1.src_sys_cust_no = A.cust_no AND A.start_dt <= DATE '{ETL_TX_DT}'  AND A.end_dt > DATE '{ETL_TX_DT}'  AND A.CUST_TYPE_CD IN ('0', '2') 
           AND A.core_cust_no<>'' AND A.core_cust_no IS NOT NULL AND A.del_flag <> 'D' 
         WHERE A1.data_sourc='ECIF' AND A1.data_dt=DATE '{ETL_TX_DT}' 
        ) T WHERE  T.RN=1
)
;


--  V_STEP_DESC := '机构干系人信息去重';
CREATE TEMPORARY  TABLE {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP4 AS (
SELECT  cust_no  
        ,stem_person_type_cd 
        ,iden_type_cd        
        ,name 
        ,iden_no
  FROM (SELECT  T1.cust_no 
               ,T1.stem_person_type_cd  --  干系人类别
               ,T1.iden_type_cd         --  证件类型
               ,T1.name                 --  姓名
               ,T1.iden_no              --  证件号
               ,T1.OFFIC_TEL            -- 办公电话
               ,ROW_NUMBER()
                OVER(PARTITION BY T1.cust_no,T1.stem_person_type_cd ORDER BY T1.last_update_tm DESC,T1.org_stem_person_inf DESC) RN
          FROM {FDM}.F_PT_ECIF_PUB_CUST_STEM_PERSON_INF T1 --  机构干系人信息
         WHERE T1.start_dt <= DATE '{ETL_TX_DT}'
           AND T1.end_dt > DATE '{ETL_TX_DT}'
           AND T1.del_flag <> 'D'
           AND T1.stem_person_type_cd IN ('21','23')--  21 法人 --  23 财务主管
        ) T2 WHERE T2.RN =1
)
; 
 
-- 信贷对公客户去重
CREATE TEMPORARY TABLE {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP5 AS (
SELECT * FROM 
(
SELECT A1.unifi_cust_no
      ,ROW_NUMBER() OVER(PARTITION BY A1.unifi_cust_no ORDER BY A.update_dt DESC, A.cust_no DESC ) RN
	  ,A.cust_no
      ,A.lp_organ_cd
      ,A.org_type_cd
      ,A.fin_org_type_cd
      ,A.indus_type_cd
      ,A.mybank_sharehd_flag
      ,A.indus_and_comme_licen_rgst_no
      ,A.indus_and_comme_licen_expir_dt
      ,A.lp_rep
      ,A.lp_rep_identt_card
	  ,A.phone_num
      ,A.compy_set_up_dt
      ,A.ent_scale_cd
      ,A.liste_ent_flag
      ,A.update_org_no
      ,A.opera_scope
      ,A.economy_type_cd
      ,A.mybank_evalu_sight_credit_lvl
      ,A.basic_acct_no
      ,A.basic_acct_bank
      ,A.cust_sts_cd
      ,A.local_cy_zone_cd
      ,A.rgst_cap
      ,A.rgst_cap_currcy_cd
      ,A.regis_addr
      ,A.rcvd_cap
      ,A.rcvd_cap_currcy_cd
	  ,A.uscc_cd
	  ,A.local_admin_regio_scope_cd
	  ,A.office_addr
	  ,A.cust_domic_belo_to_cd                                   -- 农村城市标志
	  ,REPLACE(A.compy_set_up_dt,'/','') AS rgst_rgst_dt         -- 登记注册日期
	  ,CASE WHEN A.mybank_affir_minor_ent_flag='10' THEN '1' -- 是
            WHEN A.mybank_affir_minor_ent_flag='20' THEN '0' -- 否
		ELSE A.mybank_affir_minor_ent_flag END AS mybank_affir_minor_ent_flag -- 我行认定小企业标志
      ,A.EMP_PERSON_CNT
	  ,A.company_level_cd         -- 单位级别代码
	  ,A.actual_opera_addr        -- 经营地址
  FROM {CDM}.C_CUS_UNIFI_CUST_INF_PUB A1 
 INNER JOIN {FDM}.F_PT_CRMS_LOAN_BIZ_BIZCUST_INF A -- 对公客户基本信息表
  ON A1.src_sys_cust_no = A.cust_no AND A.start_dt <= DATE '{ETL_TX_DT}' AND A.end_dt > DATE '{ETL_TX_DT}' AND A.del_flag<>'D'
 WHERE A1.data_dt=DATE '{ETL_TX_DT}' AND A1.data_sourc='CRMS'
 )T 
 WHERE T.RN=1
 );

-- V_STEP_DESC := '新资金FN本币客户去重';
CREATE  TEMPORARY TABLE  {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP6 AS (
select vs_no
       ,prnt_compy_no
       ,unifi_cust_no
       ,cust_name 
       ,create_org
       ,iden_no 
       ,iden_type_cd
       ,data_sourc
 from (
      select T1.vs_no
            ,T1.prnt_compy_no
            ,A1.unifi_cust_no
      	    ,A1.cust_name 
      	    ,A1.create_org
      	    ,A1.iden_no 
      	    ,A1.iden_type_cd
      	    ,A1.data_sourc
            ,ROW_NUMBER() OVER(PARTITION BY T1.vs_no ORDER BY T1.last_modif_tm DESC) RN
        FROM {CDM}.C_CUS_UNIFI_CUST_INF_PUB A1
       INNER JOIN {FDM}.F_PT_FNDS_TBS_VS T1 -- 新资金交易对手
          ON A1.src_sys_cust_no = T1.vs_no
      	 AND T1.start_dt <= DATE '{ETL_TX_DT}'
         AND T1.end_dt > DATE '{ETL_TX_DT}' 
      	 AND T1.del_flag <> 'D'
       WHERE A1.data_sourc = 'FNDS'
         AND A1.data_dt =DATE '{ETL_TX_DT}'
   )t2  WHERE t2.rn=1
 ) 
;

-- V_STEP_DESC := '新资金FN外币客户去重';
CREATE  TEMPORARY TABLE  {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP7 AS (
select vs_no
       ,unifi_cust_no
       ,cust_name 
       ,create_org
       ,iden_no 
       ,iden_type_cd
       ,data_sourc
 from (
select T1.vs_no
      ,A1.unifi_cust_no
	  ,A1.cust_name 
	  ,A1.create_org
	  ,A1.iden_no 
	  ,A1.iden_type_cd
	  ,A1.data_sourc
      ,ROW_NUMBER() OVER(PARTITION BY T1.vs_no ORDER BY T1.update_tm DESC) RN
  FROM {CDM}.C_CUS_UNIFI_CUST_INF_PUB A1
 INNER JOIN {FDM}.F_PT_FNDS_VS_INF T1 -- 交易对手视图
    ON A1.src_sys_cust_no = T1.vs_no
	AND T1.start_dt <= DATE '{ETL_TX_DT}' 
    AND T1.end_dt > DATE '{ETL_TX_DT}' 
	AND T1.del_flag <> 'D'
 where A1.data_sourc = 'FNDS'
   AND A1.data_dt =DATE '{ETL_TX_DT}'
   )t2  where t2.rn=1
 ) 
; 

-- V_STEP_DESC := '新票据客户去重';
CREATE TEMPORARY TABLE {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP8 AS (
SELECT * FROM 
(
SELECT A1.unifi_cust_no
      ,ROW_NUMBER() OVER(PARTITION BY A1.unifi_cust_no ORDER BY SUBSTR(A.modif_tm,1,8) DESC, SUBSTR(A.create_tm,1,8) DESC ) RN
	  ,A.cust_no
      ,A.cust_name
      ,A.uscc
      ,A.indus_class_cd
	  ,A.ent_scale_cd
  FROM {CDM}.C_CUS_UNIFI_CUST_INF_PUB A1 
 INNER JOIN {FDM}.F_PT_NBMS_PUB_CUST_INF A 
  ON A1.src_sys_cust_no = A.cust_no AND A.start_dt <= DATE '{ETL_TX_DT}' AND A.end_dt > DATE '{ETL_TX_DT}'
 WHERE A1.data_dt=DATE '{ETL_TX_DT}' AND A1.data_sourc='NBMS'
 )T 
 WHERE T.RN=1
 );



-- 1.ECIF系统客户属性加工
INSERT INTO {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_ECIF
(
  DATA_DT                            
,UNIFI_CUST_NO                       
,CUST_NAME                           
,CUST_CLASS                          
,IDEN_TYPE_CD                        
,IDEN_NO                             
,USCC                                
,ORGAN_CD                            
,RGST_REGIS_NO
,RGST_RGST_DT
,INDUS_AND_COMME_BIZ_LICEN_NO        
,INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT  
,SHAREHD_FLAG                        
,RURAL_CITY_FLAG
,INDUS_CLASS_CD 
,nation_cd                     
,STATU_REP_CUST_NAME                 
,STATU_REP_IDEN_TYPE_CD              
,STATU_REP_IDEN_NO                   
,statu_rep_tel
,SET_UP_DT                           
,ENT_SCALE_CD                        
-- ,MYBANK_AFFIR_MINOR_ENT_FLAG         
,LISTE_COMPY_FLAG                    
,dom_resdnt_flag
,RELATED_GRP_CUST_NO                 
,CREATE_ORG_NO                       
,UPDATE_ORG_NO                       
,ADMIN_AREA_CD                       
,OPERA_BIZ_SCOPE                     
,HOLD_TYPE_CD                        
,CREDIT_RATIN_CD                     
,CUST_TYPE_CD                        
,FIN_ORG_CD                          
,BASIC_DEPOSIT_ACCT_NO               
,BASIC_ACCT_OPENBANK_NAME            
,OPERA_STS_CD                        
,RGST_CY_CD                          
,RGST_CAP                            
,RGST_CAP_CURRCY_CD                  
,RGST_ADDR                           
,offic_addr
,offic_tel
,RCVD_CAP                            
,RCVD_CAP_CURRCY_CD                  
,YEAR_BIZ_INCOME_CURRCY_CD           
,YEAR_BIZ_INCOME_UNIT_CD             
,YEAR_BIZ_INCOME                     
,LAST_YEAR_BIZ_INCOME_CURRCY_CD      
,LAST_YEAR_BIZ_INCOME_UNIT_CD        
,LAST_YEAR_BIZ_INCOME                
,DATA_SOURC                          
)
SELECT DATE '{ETL_TX_DT}'                                 AS data_dt                            -- 数据日期
       ,A1.unifi_cust_no                                  AS unifi_cust_no                      -- 客户号
       ,REPLACE(A1.cust_name, ' ', '')                    AS cust_name                          -- 客户中文名称
       ,CASE WHEN A2.cust_type_cd = '0' THEN '同业'
	         WHEN A2.cust_type_cd = '2' AND A.govern_structure='1' THEN '政务' ELSE '对公' END AS cust_class
       ,A1.iden_type_cd                                   AS iden_type_cd                       -- 证件类型
       ,A1.iden_no                                        AS iden_no                            -- 证件号码
       ,CASE WHEN (LENGTH(A2.iden_no)='18' AND A2.iden_no LIKE '9%') THEN A2.iden_no 
             WHEN (LENGTH(A.busi_lic_no)='18' AND A.busi_lic_no LIKE '9%') THEN A.busi_lic_no 
             ELSE NULL 			 
         END AS uscc                                                                                    -- 统一社会信用代码
	   ,CASE WHEN A.org_code<>'' THEN A.org_code                                                            
	         WHEN A2.iden_type_cd = '13700' THEN A2.iden_no                                             
	         ELSE NULL END AS organ_cd                                                                   -- 组织机构代码
	  ,NVL(C.regis_no,'')                                 AS rgst_regis_no	                             -- 登记注册号
	  ,TO_CHAR(F.iden_eff_dt,'YYYYMMDD')                   AS rgst_rgst_dt                               -- 注册登记日期
	  ,CASE WHEN NVL(A.busi_lic_no,'')<>'' THEN A.busi_lic_no ELSE NULL END AS indus_and_comme_biz_licen_no      -- 工商营业执照号
      ,NULL                                                AS indus_and_comme_biz_licen_expir_dt          -- 工商营业执照到期日
	  ,NULL                                                AS sharehd_flag                                -- 股东标志
	  ,''                                                AS rural_city_flag                             -- 农村城市标志
	  ,A.industry_sub_type                   AS indus_class_cd                              -- 行业类别代码 信贷细分行业类型   INDUSTRYTYPE--  信贷表 
	  ,A.nation_code AS nation_cd -- 国籍代码
	  ,CASE WHEN D.name <> '' THEN D.name                                                               
            ELSE NULL END                                AS statu_rep_cust_name                         -- 法定代表人客户姓名
	  ,CASE WHEN D.iden_type_cd<>'' THEN D.iden_type_cd                                                       
            ELSE NULL END                                AS statu_rep_iden_type_cd                      -- 法定代表人身份证件类型
      ,CASE WHEN D.iden_no <> '' AND D.iden_no IS NOT NULL THEN D.iden_no --  21:法人                
            ELSE J.lp_rep_iden_no  END                   AS statu_rep_iden_no                           -- 法定代表人证件号码
      ,NVL(A.legal_repr_tel,J.LP_REP_TEL_NO) as statu_rep_tel -- 法定代表人电话号码
	  ,C.set_up_dt AS set_up_dt     -- 成立日期
	  ,CASE WHEN A.ent_scale_rh in ('1','2','3','4') THEN A.ent_scale_rh ELSE NULL END AS ent_scale_cd -- 企业规模代码
	  ,CASE WHEN NVL(E.liste_ent_flag,'') IN ( '1','0') THEN E.liste_ent_flag ELSE NULL END AS liste_compy_flag -- 上市公司标志
	  ,'' as dom_resdnt_flag -- 境内居民标志
	  ,NULL                                               AS related_grp_cust_no                        -- 关联集团客户号
	  ,A1.create_org                                      AS create_org_no                              -- 创建机构号
	  ,CASE WHEN NVL(J.safeg_org_no,'')<>'' THEN J.safeg_org_no ELSE NULL END AS update_org_no          -- 更新机构号
	  ,CASE WHEN NVL(A.area_code,'')<>'' THEN A.area_code ELSE NULL END AS admin_area_cd        -- 行政区划代码
	  ,CASE WHEN C.opera_scope<>'' AND C.opera_scope IS NOT NULL THEN SUBSTR(TRIM(C.opera_scope),1,300)
          ELSE NULL END                                 AS opera_biz_scope                            -- 经营（业务）范围
	  ,NULL                                               AS hold_type_cd                               -- 控股类型代码
	  ,NULL                                               AS credit_ratin_cd                            -- 信用评级代码
	  ,CASE WHEN NVL(J.pub_cust_type_cd,'')<>'' THEN J.pub_cust_type_cd ELSE NULL END AS cust_type_cd   -- 客户类型代码
	  ,NULL                                               AS fin_org_cd                                 -- 金融机构代码
	  ,CASE WHEN NVL(H.base_acct,'') <>'' THEN H.base_acct ELSE NULL END AS basic_deposit_acct_no       -- 基本存款账号
	  ,CASE WHEN NVL(H.base_acct_op_name,'') <>'' THEN H.BASE_ACCT_OP_NAME ELSE NULL END AS basic_acct_openbank_name -- 基本账户开户行名称
	  ,CASE WHEN S.opera_sts_cd = '100' THEN '01'            --  正常经营
            WHEN S.opera_sts_cd = '200' THEN '99'            --  其他
            WHEN S.opera_sts_cd = '300' THEN '07'            --  当年吊销
            WHEN S.opera_sts_cd = '400' THEN '04'            --  当年关闭
            WHEN S.opera_sts_cd IN ('500', '600') THEN '05'  --  当年破产
            ELSE NULL END                                 AS opera_sts_cd                              -- 经营状态代码
	  ,CASE WHEN NVL(A.nation_code,'')<>'' THEN A.nation_code ELSE NULL END AS rgst_cy_cd          -- 注册国家代码
	  ,C.rgst_cap AS rgst_cap                                                                          -- 注册资金
	  ,C.rgst_cap_currcy_cd                                                                            -- 注册资金币种代码 
	  ,CASE WHEN NVL(C.register_addr,'')<>'' THEN C.register_addr ELSE NULL END AS rgst_addr           -- 注册地址
      ,J.OFFIC_ADDR -- 办公地址
      ,J.OFFIC_TEL -- 办公电话
	  ,CASE WHEN NVL(C.rcvd_cap,'')<>'' THEN C.rcvd_cap ELSE NULL END AS rcvd_cap                      -- 实收资本
	  ,CASE WHEN NVL(C.rcvd_cap_currcy_cd,'')<>'' THEN C.rcvd_cap_currcy_cd ELSE NULL END AS rcvd_cap_currcy_cd -- 实收资本币种代码
	  ,'' AS year_biz_income_currcy_cd                                                                 -- 年营业收入币种
	  ,'' AS year_biz_income_unit_cd                                                                   --  年营业收入单位代码 01 元，02 千元，03 万元 
	  ,NULL AS year_biz_income                                                                         -- 年营业收入
	  ,'' AS last_year_biz_income_currcy_cd                                                            -- 上年营业收入币种代码
	  ,'' AS last_year_biz_income_unit_cd                                                              --  上年营业收入单位代码 01 元，02 千元，03 万元
	  ,NULL AS last_year_biz_income                                                                    -- 上年营业收入
      ,A1.data_sourc  AS date_sourcesd                                                                 --   数据来源
 FROM {CDM}.C_CUS_UNIFI_CUST_INF_PUB A1
 INNER JOIN {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP3 A2 --   客户
   ON A1.src_sys_cust_no = A2.CUST_NO AND A2.start_dt <= DATE '{ETL_TX_DT}' AND A2.end_dt > DATE '{ETL_TX_DT}' 
  AND A2.del_flag <> 'D'
 INNER JOIN {ODM}.O_ECIF_M_CI_ORG A -- ECIF对公客户信息
   ON A1.src_sys_cust_no = A.cust_id AND A.start_dt <= DATE '{ETL_TX_DT}' AND A.end_dt > DATE '{ETL_TX_DT}'
 LEFT JOIN {FDM}.F_PT_ECIF_PUB_CUST_RGST_INF C -- 注册信息
   ON A1.src_sys_cust_no = C.cust_no
  AND C.start_dt <= date '{ETL_TX_DT}'
  AND C.end_dt > date '{ETL_TX_DT}'
 LEFT JOIN {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP4  D -- 机构干系人信息
   ON A1.src_sys_cust_no = D.cust_no
  AND D.stem_person_type_cd = '21' --  法人
 LEFT JOIN {FDM}.F_PT_ECIF_PUB_CUST_IMPORT_FLAG E -- 机构客户重要标志
   ON A.cust_id = E.cust_no
  AND E.start_dt <= date '{ETL_TX_DT}'
  AND E.end_dt > date '{ETL_TX_DT}'
 LEFT JOIN (
           SELECT cust_no
                 ,iden_rgst_dt
                 ,iden_eff_dt
             FROM (SELECT cust_no
                         ,iden_rgst_dt
                         ,iden_eff_dt
                         ,ROW_NUMBER () OVER (PARTITION BY cust_no ORDER BY last_update_tm DESC) AS RN
                     FROM {FDM}.F_PT_ECIF_PUB_CUST_IDEN_INF  /*ECIF对公客户证件信息*/
                    WHERE START_DT <= DATE '{ETL_TX_DT}' 
                      AND END_DT > DATE '{ETL_TX_DT}' 
                      AND DEL_FLAG <> 'D' 
                      AND IDEN_TYPE_CD = '@020100' /*营业执照*/ 
                   ) F1
            WHERE F1.RN=1
           ) F
   ON A1.src_sys_cust_no = F.cust_no           
 LEFT JOIN {FDM}.F_PT_ECIF_PUB_CUST_OPERA_INF S -- 机构经营信息  --  CUST_ID 唯一
   ON A1.src_sys_cust_no = S.cust_no
  AND S.start_dt <= DATE '{ETL_TX_DT}'
  AND S.end_dt > DATE '{ETL_TX_DT}'
 LEFT JOIN  {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP2  H  
   ON A1.unifi_cust_no=H.main_cust_no
  LEFT JOIN ( SELECT cust_no,'dg'||pub_cust_type_cd AS pub_cust_type_cd,safeg_org_no,lp_rep_iden_no,LP_REP_TEL_NO,OFFIC_ADDR,OFFIC_TEL
 	            FROM {FDM}.F_PT_CRBS_PUB_CUST_INF D1      -- 核心对公客户信息 
               WHERE D1.start_dt <= date '{ETL_TX_DT}' AND  D1.end_dt > date '{ETL_TX_DT}' AND  D1.del_flag <> 'D'  
           
 		      UNION ALL
			  
 			  SELECT cust_no,'ty'||ibank_cust_type_cd AS pub_cust_type_cd,safeg_org_no,lp_rep_iden_no,LP_REP_TEL_NO,OFFIC_ADDR,OFFIC_TEL
 	            FROM {FDM}.F_PT_CRBS_IBANK_CUST_INF D2    -- 核心同业客户信息
               WHERE D2.start_dt <= date '{ETL_TX_DT}' AND  D2.end_dt > date '{ETL_TX_DT}' AND  D2.del_flag <> 'D'
            ) J 
     ON A1.unifi_cust_no=J.cust_no   
WHERE A1.data_dt=DATE '{ETL_TX_DT}' AND A1.data_sourc='ECIF' 
;

-- 2.核心系统客户属性加工
INSERT INTO {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_CRBS
(
  DATA_DT                            
,UNIFI_CUST_NO                       
,CUST_NAME                           
,CUST_CLASS                          
,IDEN_TYPE_CD                        
,IDEN_NO                             
,USCC                                
,ORGAN_CD                            
,RGST_REGIS_NO
,RGST_RGST_DT
,INDUS_AND_COMME_BIZ_LICEN_NO        
,INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT  
,SHAREHD_FLAG                        
,RURAL_CITY_FLAG
,INDUS_CLASS_CD                      
,NATION_CD
,STATU_REP_CUST_NAME                 
,STATU_REP_IDEN_TYPE_CD              
,STATU_REP_IDEN_NO                   
,STATU_REP_TEL
,SET_UP_DT                           
,ENT_SCALE_CD                        
-- ,MYBANK_AFFIR_MINOR_ENT_FLAG         
,LISTE_COMPY_FLAG                    
,DOM_RESDNT_FLAG
,RELATED_GRP_CUST_NO                 
,CREATE_ORG_NO                       
,UPDATE_ORG_NO                       
,ADMIN_AREA_CD                       
,OPERA_BIZ_SCOPE                     
,HOLD_TYPE_CD                        
,CREDIT_RATIN_CD                     
,CUST_TYPE_CD                        
,FIN_ORG_CD                          
,BASIC_DEPOSIT_ACCT_NO               
,BASIC_ACCT_OPENBANK_NAME            
,OPERA_STS_CD                        
,RGST_CY_CD                          
,RGST_CAP                            
,RGST_CAP_CURRCY_CD                  
,RGST_ADDR                          
,OFFIC_ADDR
,OFFIC_TEL
,RCVD_CAP                            
,RCVD_CAP_CURRCY_CD                  
,YEAR_BIZ_INCOME_CURRCY_CD           
,YEAR_BIZ_INCOME_UNIT_CD             
,YEAR_BIZ_INCOME                     
,LAST_YEAR_BIZ_INCOME_CURRCY_CD      
,LAST_YEAR_BIZ_INCOME_UNIT_CD        
,LAST_YEAR_BIZ_INCOME                
,DATA_SOURC                          
)
SELECT DATE '{ETL_TX_DT}'             AS data_dt                                                 --  数据日期
      ,A1.unifi_cust_no               AS unifi_cust_no                                           --  客户号
      ,REPLACE(A1.cust_name, ' ', '') AS cust_name                                               --  客户中文名称
	  ,CASE WHEN B.cust_class='同业' THEN '同业'                                       
	        WHEN C.govern_structure='1' THEN '政务' ELSE '对公'                           
	    END                           AS cust_class                                              --  同业客户		
      ,A1.iden_type_cd                AS iden_type_cd                                            --  证件类型
      ,A1.iden_no                     AS iden_no                                                 --  证件号码
	  ,NULL                           AS uscc                                                    --  统一社会信用代码
      ,NULL                           AS organ_cd                                                --  组织机构代码
	  ,NULL                           AS rgst_regis_no                                           --  登记注册号
      ,NULL                           AS rgst_rgst_dt                                            --  注册登记日期
	  ,NULL                           AS indus_and_comme_biz_licen_no                            --  工商营业执照号
      ,NULL                           AS indus_and_comme_biz_licen_expir_dt                      --  工商营业执照到期日 
      ,NULL                           AS sharehd_flag                                            --  股东标志 
	  ,''                           AS rural_city_flag                                         --  农村城市标志 
	  ,B.indus_cd                     AS indus_class_cd	             --  行业类别代码	 
	  ,B.nation_cd AS nation_cd -- 国籍代码
      ,CASE WHEN B.lp_rep_name<>'' THEN B.lp_rep_name ELSE NULL END AS statu_rep_cust_name       -- 对公客户法定代表人客户姓名
      ,CASE WHEN B.lp_rep_iden_type_cd<>'' THEN B.lp_rep_iden_type_cd ELSE NULL END AS statu_rep_iden_type_cd    -- 对公客户法定代表人身份证件类型 居民身份证
      ,CASE WHEN B.lp_rep_iden_no<>'' THEN B.lp_rep_iden_no ELSE NULL END AS statu_rep_iden_no   -- 对公客户法定代表人身份证件号码
      ,CASE WHEN B.LP_REP_TEL_NO<>'' THEN B.LP_REP_TEL_NO ELSE NULL END AS statu_rep_tel -- 对公客户法定代表人电话号码
	  ,DECODE(NVL(B.set_up_dt,''),'',NULL,CDM.UDF_DTCHK(B.set_up_dt,'{LEG_DATE}')) AS set_up_dt    -- 成立日期
	  ,CASE WHEN B.ent_scale_cd='2' THEN '1' 	-- 大型企业      
            WHEN B.ent_scale_cd='5' THEN '2' 	-- 中型企业
	        WHEN B.ent_scale_cd='1' THEN '3' 	-- 小型企业    
            WHEN B.ent_scale_cd='4' THEN '4' 	-- 微型企业
            WHEN B.ent_scale_cd='3' THEN '8' 	-- 非企业
       ELSE NULL END	 AS ent_scale_cd                                                    -- 企业规模代码
	  ,DECODE(B.liste_compy_flag,'',NULL,B.liste_compy_flag) AS liste_compy_flag            --  上市公司标志	
      ,B.SUPER_LP_TYPE_CD as dom_resdnt_flag                                                -- 境内居民标志:0非居民\1居民
	  ,NULL AS related_grp_cust_no                                                          --  关联集团客户号
	  ,A1.create_org AS create_org_no                                                       --  创建机构号
	  ,B.safeg_org_no AS update_org_no                                                      --  更新机构号
	  ,CASE WHEN B.belo_zone_cd<>'' THEN B.belo_zone_cd ELSE NULL END AS admin_area_cd      --  行政区划代码
	  ,CASE WHEN B.opera_scope <> '' AND B.opera_scope IS NOT NULL THEN SUBSTR(B.opera_scope,1,300) 
            ELSE NULL 
		END AS opera_biz_scope                                                                --  经营业务范围
	  ,NULL AS hold_type_cd                                                                 --  控股类型代码
	  ,NULL AS credit_ratin_cd                                                              --  信用评级代码
	  ,B.pub_cust_type_cd     AS cust_type_cd                                               --  客户类型代码
	  ,NULL                   AS fin_org_cd                                                 --  金融机构代码
      ,CASE WHEN NVL(H.base_acct,'') <>'' THEN H.base_acct ELSE NULL END AS basic_deposit_acct_no       -- 基本存款账号
	  ,CASE WHEN NVL(H.base_acct_op_name,'') <>'' THEN H.BASE_ACCT_OP_NAME ELSE NULL END AS basic_acct_openbank_name -- 基本账户开户行名称
	  ,NULL                 AS opera_sts_cd                                 --  经营状态代码
	  ,CASE WHEN NVL(C.nation_code,'')<>'' THEN C.nation_code ELSE NULL END AS rgst_cy_cd --  注册国家代码
	  ,B.rgst_cap             AS rgst_cap                                     --  注册资金
	  ,B.rgst_cap_currcy_cd   AS rgst_cap_currcy_cd                           --  注册资金币种代码
	  ,NULL                   AS rgst_addr                                    --  注册地址
	  ,B.OFFIC_ADDR -- 办公地址
      ,B.OFFIC_TEL -- 办公电话
	  ,B.rcvd_cap             AS rcvd_cap                                     --  实收资本
	  ,'156'                  AS rcvd_cap_currcy_cd                           --  实收资本币种代码
      ,''                     AS year_biz_income_currcy_cd                    --  年营业收入币种代码
	  ,''                     AS year_biz_income_unit_cd                      --  年营业收入单位代码 01 元，02 千元，03 万元 
	  ,NULL                   AS year_biz_income                              --  年营业收入
	  ,''                     AS last_year_biz_income_currcy_cd               --  上年营业收入币种代码
	  ,''                     AS last_year_biz_income_unit_cd                 --  上年营业收入单位代码 01 元，02 千元，03 万元
	  ,NULL                   AS last_year_biz_income                         --  上年营业收入	  
      ,A1.data_sourc          AS date_sourcesd                                --  数据来源
 FROM {CDM}.C_CUS_UNIFI_CUST_INF_PUB A1  
INNER JOIN 
      (
      SELECT cust_no,'对公' AS CUST_CLASS,'dg'||pub_cust_type_cd AS pub_cust_type_cd
	         ,safeg_org_no     -- 维护机构
			 ,lp_rep_iden_no   -- 法人代表证件号码
	         ,rcvd_cap         -- 实收资本
			 ,rgst_cap_currcy_cd -- 注册资金币种
             ,rgst_cap -- 注册资金
             ,lp_rep_name -- 法人代表名称
             ,LP_REP_TEL_NO -- 法人代表电话号码
	         ,lp_rep_iden_type_cd -- 法人代表证件种类
             ,SUPER_LP_TYPE_CD -- 上级法人属性
			 ,liste_compy_flag -- 上市公司标志 
			 ,ent_scale_cd  -- 企业规模代码 
			 ,indus_class_cd -- 行业类别
             ,indus_cd -- 行业代码
             ,set_up_dt -- 成立日期
             ,certi_file_category_cd -- 证书文件种类
             ,certi_file_no -- 证书文件编号
			 ,belo_zone_cd -- 所属地区
			 ,opera_scope -- 经营业务范围
             ,NATION_CD as nation_cd -- 国别代码
			 ,cust_sts_cd 
             ,OFFIC_ADDR -- 办公地址
             ,OFFIC_TEL -- 办公电话
       	FROM {FDM}.F_PT_CRBS_PUB_CUST_INF D1      -- 核心对公客户信息 
       WHERE D1.start_dt <= date '{ETL_TX_DT}' AND  D1.end_dt > date '{ETL_TX_DT}' AND  D1.del_flag <> 'D'  
                 
       UNION ALL
      			  
      SELECT cust_no,'同业' AS CUST_CLASS,'ty'||ibank_cust_type_cd AS pub_cust_type_cd
	         ,safeg_org_no
			 ,lp_rep_iden_no
			 ,rcvd_cap -- 实收资本
			 ,rgst_cap_currcy_cd -- 注册资金币种
             ,rgst_cap -- 注册资金
             ,lp_rep_name -- 法人代表名称
             ,LP_REP_TEL_NO -- 法人代表电话号码
	         ,lp_rep_iden_category_cd -- 法人代表证件种类
             ,SUPER_LP_PROP_CD -- 上级法人属性
			 ,liste_compy_flag -- 上市公司标志 
			 ,NULL AS ent_scale_cd  -- 企业规模代码
			 ,indus_class_cd -- 行业类别
             ,indus_cd -- 行业代码
             ,set_up_dt -- 成立日期
             ,certi_file_category_cd -- 证书文件种类
             ,certi_file_no -- 证书文件编号
			 ,belo_zone_cd -- 所属地区
			 ,opera_scope -- 经营业务范围
             ,nation_cd as nation_cd  -- 国别代码
			 ,cust_sts_cd
             ,OFFIC_ADDR -- 办公地址
             ,OFFIC_TEL -- 办公电话
        FROM {FDM}.F_PT_CRBS_IBANK_CUST_INF D2    -- 核心同业客户信息
         WHERE D2.start_dt <= date '{ETL_TX_DT}' AND  D2.end_dt > date '{ETL_TX_DT}' AND  D2.del_flag <> 'D'           
      )B
  ON A1.unifi_cust_no = B.cust_no
 LEFT JOIN {ODM}.O_ECIF_M_CI_ORG C -- ECIF对公客户基本信息
   ON A1.unifi_cust_no = C.cust_id AND C.start_dt <= DATE '{ETL_TX_DT}' AND C.end_dt > DATE '{ETL_TX_DT}'
 LEFT JOIN  {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP2  H  
   ON A1.unifi_cust_no=H.main_cust_no
WHERE A1.data_dt=DATE '{ETL_TX_DT}' AND A1.data_sourc='CRBS'
;


-- 3.信贷系统客户属性加工

INSERT INTO {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_CRMS
(
  DATA_DT                            
,UNIFI_CUST_NO                       
,CUST_NAME                           
,CUST_CLASS                          
,IDEN_TYPE_CD                        
,IDEN_NO                             
,USCC                                
,ORGAN_CD                            
,RGST_REGIS_NO                       
,RGST_RGST_DT                        
,INDUS_AND_COMME_BIZ_LICEN_NO        
,INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT  
,SHAREHD_FLAG                        
,RURAL_CITY_FLAG                     
,INDUS_CLASS_CD                                            
,nation_cd
,STATU_REP_CUST_NAME                 
,STATU_REP_IDEN_TYPE_CD              
,STATU_REP_IDEN_NO                   
,statu_rep_tel
,SET_UP_DT                           
,ENT_SCALE_CD                        
,MYBANK_AFFIR_MINOR_ENT_FLAG         
,LISTE_COMPY_FLAG                    
,dom_resdnt_flag
,RELATED_GRP_CUST_NO                 
,CREATE_ORG_NO                       
,UPDATE_ORG_NO                       
,ADMIN_AREA_CD                       
,OPERA_BIZ_SCOPE                     
,HOLD_TYPE_CD                        
,CREDIT_RATIN_CD                     
,CUST_TYPE_CD                        
,FIN_ORG_CD     
,unit_lvl_cd                     
,BASIC_DEPOSIT_ACCT_NO               
,BASIC_ACCT_OPENBANK_NAME            
,OPERA_STS_CD        
,opera_addr                
,RGST_CY_CD                          
,RGST_CAP                            
,RGST_CAP_CURRCY_CD                  
,RGST_ADDR                           
,offic_addr
,offic_tel
,RCVD_CAP                            
,RCVD_CAP_CURRCY_CD                  
,YEAR_BIZ_INCOME_CURRCY_CD           
,YEAR_BIZ_INCOME_UNIT_CD             
,YEAR_BIZ_INCOME                     
,LAST_YEAR_BIZ_INCOME_CURRCY_CD      
,LAST_YEAR_BIZ_INCOME_UNIT_CD        
,LAST_YEAR_BIZ_INCOME                
,EMP_PERSON_CNT
,DATA_SOURC                          
)
SELECT DATE '{ETL_TX_DT}'             AS data_dt                                                 --  数据日期
      ,A1.unifi_cust_no               AS unifi_cust_no                                           --  客户号
      ,REPLACE(A1.cust_name, ' ', '') AS cust_name                                               --  客户中文名称
	  ,CASE WHEN B.org_type_cd = '0106' THEN '同业'                                       
	        WHEN C.govern_structure='1' THEN '政务' ELSE '对公'                           
	    END                           AS cust_class                                              --  同业客户		
      ,A1.iden_type_cd                AS iden_type_cd                                            --  证件类型
      ,A1.iden_no                     AS iden_no                                                 --  证件号码
	  ,CASE WHEN LENGTH(B.uscc_cd)='18' AND B.uscc_cd LIKE '9%' THEN B.uscc_cd                   
	        ELSE NULL                                                                            
		END                           AS uscc                                                    -- 统一社会信用代码
      ,CASE WHEN NVL(B.lp_organ_cd,'')<>'' THEN B.lp_organ_cd ELSE NULL END AS organ_cd          --  组织机构代码
	  ,''  AS rgst_regis_no                     -- 登记注册号                
      ,CASE WHEN NVL(B.rgst_rgst_dt,'') <> '' 
           THEN CDM.UDF_DTCHK(B.rgst_rgst_dt,DATE '{LEG_DATE}')
       ELSE DATE '{NULL_DATE}'
       END AS rgst_rgst_dt  -- 注册登记日期
	  ,B.indus_and_comme_licen_rgst_no                    AS indus_and_comme_biz_licen_no        --  工商营业执照号
      ,DECODE(NVL(B.indus_and_comme_licen_expir_dt,''),'',NULL
	        ,UDF_DTCHK(B.indus_and_comme_licen_expir_dt,'{LEG_DATE}'))  AS indus_and_comme_biz_licen_expir_dt     --  工商营业执照到期日 
      ,CASE WHEN NVL(B.mybank_sharehd_flag,'') IN ('1','0') 
	        THEN B.mybank_sharehd_flag 
			ELSE NULL END AS sharehd_flag                                                        --  股东标志
      ,NVL(B.cust_domic_belo_to_cd,'') AS rural_city_flag                                                -- 农村城市标志		
	  ,B.indus_type_cd  AS indus_class_cd	 --  行业类别代码
      ,CASE WHEN B.local_cy_zone_cd ='CHN' THEN '156'
      WHEN B.local_cy_zone_cd ='HKG' THEN '344'
      WHEN B.local_cy_zone_cd ='USA' THEN '840'
      WHEN B.local_cy_zone_cd ='GBR' THEN '826' -- 826 大不列颠及北爱尔兰联合王国
      WHEN B.local_cy_zone_cd ='KOR' THEN '410' -- 410 大韩民国
      ELSE '156' END AS nation_cd -- 国籍代码	  
      ,CASE WHEN B.lp_rep <> '' AND B.lp_rep IS NOT NULL THEN B.lp_rep 
            ELSE NULL 
		END AS statu_rep_cust_name                                                               -- 对公客户法定代表人客户姓名
      ,'10100' AS statu_rep_iden_type_cd                                                         -- 对公客户法定代表人身份证件类型 居民身份证
      ,CASE WHEN B.lp_rep_identt_card  <> '' AND B.lp_rep_identt_card  IS NOT NULL THEN B.lp_rep_identt_card 
            ELSE NULL 
		END AS statu_rep_iden_no                                                                 -- 对公客户法定代表人身份证件号码
	  ,B.PHONE_NUM as statu_rep_tel -- 法定代表人电话号码
	  ,DECODE(NVL(B.compy_set_up_dt,''),'',NULL,CDM.UDF_DTCHK(B.compy_set_up_dt,'{LEG_DATE}')) AS set_up_dt  --  成立日期	  
	  ,CASE WHEN B.ent_scale_cd='2' THEN '1'
	        WHEN B.ent_scale_cd='3' THEN '2' 
	        WHEN B.ent_scale_cd='4' THEN '3' 
	        WHEN B.ent_scale_cd='5' THEN '4' 
		    ELSE NULL END AS ent_scale_cd 			-- 企业规模代码
	  ,B.mybank_affir_minor_ent_flag AS mybank_affir_minor_ent_flag
	  ,CASE WHEN B.liste_ent_flag IN ('A','B','H','F') THEN '1'
            WHEN B.liste_ent_flag ='2' THEN '0'
            ELSE NULL
        END AS liste_compy_flag                                                    --  上市公司标志
	  ,'' as dom_resdnt_flag -- 境内居民标志
	  ,CASE WHEN N.related_grp_cust_no <> '' AND N.related_grp_cust_no IS NOT NULL THEN N.related_grp_cust_no
            ELSE NULL 
		END AS related_grp_cust_no                                                 --  关联集团客户号
	  ,A1.create_org AS create_org_no                                              --  创建机构号
	  ,B.update_org_no AS update_org_no                                            --  更新机构号
	  ,CASE WHEN B.local_admin_regio_scope_cd<>'' THEN B.local_admin_regio_scope_cd ELSE NULL END AS admin_area_cd  --  行政区划代码
	  ,CASE WHEN B.opera_scope <> '' AND B.opera_scope IS NOT NULL THEN SUBSTR(B.opera_scope,1,300) 
            ELSE NULL 
		END AS opera_biz_scope                                                                 --  经营业务范围
	  ,CASE WHEN  B.economy_type_cd in ('101010') THEN 'A02' -- 国有相对控股
            WHEN  B.economy_type_cd in ('101020') THEN 'A01' -- 国有绝对控股
            WHEN  B.economy_type_cd in ('102010' ) THEN 'B02' -- 集体相对控股
            WHEN  B.economy_type_cd in ('102020' ) THEN 'B01' -- 集体绝对控股 
            WHEN  B.economy_type_cd in ('201010' ) THEN 'C02' -- 私人相对控股
            WHEN  B.economy_type_cd in ('201020' ) THEN 'C01' -- 私人绝对控股  0013
            WHEN  B.economy_type_cd in ('202010' ) THEN 'D02' -- 港澳台相对控股
            WHEN  B.economy_type_cd in ('202020' ) THEN 'D01' -- 港澳台绝对控股
            WHEN  B.economy_type_cd in ('203010' ) THEN 'E02' -- 外商相对控股
            WHEN  B.economy_type_cd in ('203020' ) THEN 'E01' -- 外商绝对控股  
            ELSE NULL 
		END AS hold_type_cd                                                                  --  控股类型代码
	  ,CASE WHEN B.mybank_evalu_sight_credit_lvl <> '' AND B.mybank_evalu_sight_credit_lvl IS NOT NULL THEN B.mybank_evalu_sight_credit_lvl
            ELSE NULL 
		END AS credit_ratin_cd                                                               --  信用评级代码
	  ,J.pub_cust_type_cd AS cust_type_cd                                                    --  客户类型代码
	  ,NULL AS fin_org_cd                                                                    --  金融机构代码
	  ,NVL(B.company_level_cd,'')                                                            --  单位级别代码
	  ,CASE WHEN B.basic_acct_no <> '' AND B.basic_acct_no IS NOT NULL THEN B.basic_acct_no
            ELSE NULL 
		END AS basic_deposit_acct_no                                                         --  基本存款账号
	  ,CASE WHEN B.basic_acct_bank   <> '' AND B.basic_acct_bank   IS NOT NULL THEN B.basic_acct_bank  
            ELSE NULL 
		END AS basic_acct_openbank_name                                                      --  基本账户开户行名称
	  ,CASE WHEN  B.cust_sts_cd in ('010' ) THEN '01'  -- 正常运营
	        WHEN  B.cust_sts_cd in ('020','030','040') THEN '02' -- 停业（歇业） 
            ELSE '99' 
		END AS opera_sts_cd                                                                   --  经营状态代码
	  ,NVL(B.actual_opera_addr,'')                                                            --  经营地址
	  ,CASE WHEN B.local_cy_zone_cd ='CHN' THEN '156' 
            WHEN B.local_cy_zone_cd ='HKG' THEN '344' 
            WHEN B.local_cy_zone_cd ='USA' THEN '840' 
	        WHEN B.local_cy_zone_cd ='GBR' THEN '826'  -- 826	大不列颠及北爱尔兰联合王国
	        WHEN B.local_cy_zone_cd ='KOR' THEN '410'  -- 410	大韩民国
            ELSE '156' END AS rgst_cy_cd                                                      --  注册国家代码
	  ,B.rgst_cap AS rgst_cap                                                                 --  注册资金
	  ,B.rgst_cap_currcy_cd      AS rgst_cap_currcy_cd                                        --  注册资金币种代码
	  ,CASE WHEN NVL(B.regis_addr,'')<>'' THEN B.regis_addr ELSE NULL END AS rgst_addr        --  注册地址
	  ,CASE WHEN NVL(OFFICE_ADDR,'')<>'' THEN B.OFFICE_ADDR ELSE NULL END AS offic_addr -- 办公地址
      ,CASE WHEN NVL(J.OFFIC_TEL,'')<>'' THEN J.OFFIC_TEL ELSE NULL END AS OFFIC_TEL -- 办公电话
	  ,B.rcvd_cap  AS rcvd_cap                                                                --  实收资本
	  ,CASE WHEN B.rcvd_cap_currcy_cd IS NOT NULL THEN B.rcvd_cap_currcy_cd ELSE NULL END AS rcvd_cap_currcy_cd    --  实收资本币种代码
	  ,NVL(B1.report_currcy_cd,'') AS year_biz_income_currcy_cd                               --  年营业收入币种代码
	  ,NVL(B1.report_unit_cd,'') AS year_biz_income_unit_cd                                   --  年营业收入单位代码 01 元，02 千元，03 万元 
	  ,NVL(B1.num_val_item2,0)     AS year_biz_income                                         --  年营业收入
	  ,NVL(B2.report_currcy_cd,'') AS last_year_biz_income_currcy_cd                          --  上年营业收入币种代码
	  ,NVL(B2.report_unit_cd,'') AS last_year_biz_income_unit_cd                              --  上年营业收入单位代码 01 元，02 千元，03 万元
	  ,NVL(B2.num_val_item2,0) AS last_year_biz_income                                        --  上年营业收入	
      ,NVL(B.EMP_PERSON_CNT,0) AS emp_person_cnt                                              --  员工人数      
      ,A1.data_sourc AS DATE_SOURCESD                                                         --  数据来源
 FROM {CDM}.C_CUS_UNIFI_CUST_INF_PUB A1  
INNER JOIN {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP5 B -- 对公客户基本信息表
  ON A1.src_sys_cust_no = B.cust_no
 LEFT JOIN {ODM}.O_ECIF_M_CI_ORG C -- ECIF对公客户基本信息
   ON A1.src_sys_cust_no = C.cust_id AND C.start_dt <= DATE '{ETL_TX_DT}' AND C.end_dt > DATE '{ETL_TX_DT}'
 LEFT JOIN {FDM}.F_PT_CRMS_LOAN_GROUP_CUST_RELATED_RELA N-- 集团客户关联关系表  
   ON B.cust_no=N.cust_no
  AND N.start_dt <= DATE '{ETL_TX_DT}'
  AND N.end_dt > DATE '{ETL_TX_DT}'
  AND N.del_flag <> 'D'  
 LEFT JOIN {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP1 B1 -- 年营业收入
   ON B.cust_no = B1.obj_no 
  AND REPLACE(B1.report_dt,'/','') = (LEFT('{ETL_TX_DT}' ,4)-1) ||'12' 
 LEFT JOIN {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP1 B2 -- 上年营业收入
   ON B.cust_no = B2.obj_no 
  AND REPLACE(B2.report_dt,'/','') = (LEFT('{ETL_TX_DT}' ,4)-2) ||'12' 
 LEFT JOIN ( SELECT cust_no,'dg'||pub_cust_type_cd AS pub_cust_type_cd,safeg_org_no,lp_rep_iden_no,OFFIC_TEL
 	            FROM {FDM}.F_PT_CRBS_PUB_CUST_INF D1      -- 核心对公客户信息 
               WHERE D1.start_dt <= date '{ETL_TX_DT}' AND  D1.end_dt > date '{ETL_TX_DT}' AND  D1.del_flag <> 'D'  
           
 		      UNION ALL
			  
 			  SELECT cust_no,'ty'||ibank_cust_type_cd,safeg_org_no,lp_rep_iden_no,OFFIC_TEL
 	            FROM {FDM}.F_PT_CRBS_IBANK_CUST_INF D2    -- 核心同业客户信息
               WHERE D2.start_dt <= date '{ETL_TX_DT}' AND  D2.end_dt > date '{ETL_TX_DT}' AND  D2.del_flag <> 'D'
            ) J
   ON A1.unifi_cust_no=J.cust_no
WHERE A1.data_dt=DATE '{ETL_TX_DT}' AND A1.data_sourc='CRMS'
;

-- 微业贷:
INSERT INTO {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SILN
(
  DATA_DT                            
,UNIFI_CUST_NO                       
,CUST_NAME                           
,CUST_CLASS                          
,IDEN_TYPE_CD                        
,IDEN_NO                             
,USCC                                
,ORGAN_CD                            
,RGST_REGIS_NO                       
,RGST_RGST_DT                        
,INDUS_AND_COMME_BIZ_LICEN_NO        
,INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT  
,SHAREHD_FLAG                        
,RURAL_CITY_FLAG                     
,INDUS_CLASS_CD                      
,nation_cd
,STATU_REP_CUST_NAME                 
,STATU_REP_IDEN_TYPE_CD              
,STATU_REP_IDEN_NO                   
,statu_rep_tel
,SET_UP_DT                           
,ENT_SCALE_CD                        
-- ,MYBANK_AFFIR_MINOR_ENT_FLAG         
,LISTE_COMPY_FLAG                    
,dom_resdnt_flag
,RELATED_GRP_CUST_NO                 
,CREATE_ORG_NO                       
,UPDATE_ORG_NO                       
,ADMIN_AREA_CD                       
,OPERA_BIZ_SCOPE                     
,HOLD_TYPE_CD                        
,CREDIT_RATIN_CD                     
,CUST_TYPE_CD                        
,FIN_ORG_CD                          
,BASIC_DEPOSIT_ACCT_NO               
,BASIC_ACCT_OPENBANK_NAME            
,OPERA_STS_CD                        
,RGST_CY_CD                          
,RGST_CAP                            
,RGST_CAP_CURRCY_CD                  
,RGST_ADDR                           
,offic_addr
,offic_tel
,RCVD_CAP                            
,RCVD_CAP_CURRCY_CD                  
,YEAR_BIZ_INCOME_CURRCY_CD           
,YEAR_BIZ_INCOME_UNIT_CD             
,YEAR_BIZ_INCOME                     
,LAST_YEAR_BIZ_INCOME_CURRCY_CD      
,LAST_YEAR_BIZ_INCOME_UNIT_CD        
,LAST_YEAR_BIZ_INCOME                
,DATA_SOURC                          
)
SELECT DATE '{ETL_TX_DT}'                                         AS data_dt              --  数据日期
      ,A1.unifi_cust_no                                           AS unifi_cust_no        --  客户号
      ,REPLACE(A1.cust_name, ' ', '')                             AS cust_name            --  客户中文名称
	  ,'对公'                                                     AS cust_class           --  同业客户		                      
      ,A1.iden_type_cd                                            AS iden_type_cd         --  证件类型
      ,A1.iden_no                                                 AS iden_no              --  证件号码
      ,CASE WHEN NVL(B.org_credit_cd,'')<>'' THEN B.org_credit_cd ELSE NULL END AS uscc   --  统一社会信用代码      
	  ,NULL AS organ_cd                                                                   --  组织机构代码
	  ,'' AS rgst_regis_no                                                              --  登记注册号
	  ,DECODE(NVL(B.set_up_dt,''),'',NULL,CDM.UDF_DTCHK(B.set_up_dt,'{LEG_DATE}')) AS rgst_rgst_dt    --  注册登记日期
	  ,CASE 
	      WHEN NVL(B.biz_licen_no,'')<>'' THEN B.biz_licen_no 
	      ELSE NULL 
		END AS indus_and_comme_biz_licen_no                                               --  工商营业执照号
	  ,CASE 
	      WHEN NVL(B.biz_licen_vld_end_dt,'')='' THEN NULL
          WHEN UDF_DTCHK(SUBSTR(TO_CHAR(B.biz_licen_vld_end_dt),1,10),'0') = '0' THEN  '{LEG_DATE}'  -- 非法日期默认值V
          ELSE B.biz_licen_vld_end_dt 
		END AS indus_and_comme_biz_licen_expir_dt                                         --  工商营业执照到期日 
      ,NULL AS sharehd_flag                                                               --  股东标志	
	  ,'' AS rural_city_flag                                                            --  农村城市标志
	  ,B.indus_direction_cd AS indus_class_cd --  行业类别代码	
      ,B.nation_cd as nation_cd -- 国籍代码
      ,CASE
          WHEN C.senior_execu_name <> '' AND C.senior_execu_name IS NOT NULL THEN C.senior_execu_name   
          ELSE NULL  
        END AS statu_rep_cust_name                 -- 对公客户法定代表人客户姓名
      ,CASE 
	      WHEN C.iden_type_cd<> '' AND C.iden_type_cd IS NOT NULL THEN C.iden_type_cd
          ELSE NULL 
        END AS statu_rep_iden_type_cd              -- 对公客户法定代表人身份证件类型 居民身份证
      ,CASE
          WHEN B.iden_no  <> '' AND B.iden_no  IS NOT NULL THEN B.iden_no 
          ELSE NULL  
        END AS statu_rep_iden_no                   -- 对公客户法定代表人身份证件号码
      ,'' as statu_rep_tel -- 法定代表人电话号码
	  ,DECODE(NVL(B.set_up_dt,''),'',NULL,CDM.UDF_DTCHK(B.set_up_dt,'{LEG_DATE}')) AS set_up_dt    --  成立日期	  
	  ,CASE WHEN NVL(B.ent_scale_cd,'') in ('1','2','3','4') THEN B.ent_scale_cd ELSE NULL END AS ent_scale_cd -- 企业规模代码
	  ,CASE WHEN B.liste_compy_flag='Y' THEN '1' 
	        WHEN B.liste_compy_flag='N' THEN '0' ELSE NULL END AS liste_compy_flag       --  上市公司标志
      ,'' as dom_resdnt_flag -- 境内居民标志
	  ,NULL           AS related_grp_cust_no                                             --  关联集团客户号
	  ,A1.create_org  AS create_org_no                                                   --  创建机构号
	  ,NULL           AS update_org_no                                                   --  更新机构号
	  ,CASE WHEN B.admin_area_cd<>'' THEN B.admin_area_cd ELSE NULL END AS admin_area_cd --  行政区划代码
	  ,CASE WHEN B.opera_scope <> '' AND B.opera_scope IS NOT NULL THEN SUBSTR(B.opera_scope,1,300) 
          ELSE NULL  
        END                               AS opera_biz_scope                           --  经营（业务）范围
	  ,'C02'                                AS hold_type_cd                              --  控股类型代码 私人相对控股
	  ,NULL                                 AS credit_ratin_cd                           --  信用评级代码
	  ,NULL                                 AS cust_type_cd                              --  客户类型代码
	  ,NULL                                 AS fin_org_cd                                --  金融机构代码
	  ,NULL                                 AS basic_deposit_acct_no                     --  基本存款账号
	  ,NULL                                 AS basic_acct_openbank_name                  --  基本账户开户行名称  
	  ,CASE WHEN  B.open_close_flag in ('01') THEN '02' -- 关停
            ELSE '01' 
		END AS opera_sts_cd                                                              --  经营状态代码
	  ,'156' AS rgst_cy_cd                                                               --  注册国家代码
	  ,CASE WHEN NVL(B.rgst_cap,'')<>'' THEN B.rgst_cap ELSE NULL END AS rgst_cap        --  注册资金
	  ,CASE WHEN NVL(B.rgst_cap_currcy_cd,'')<>'' THEN B.rgst_cap_currcy_cd ELSE NULL END AS rgst_cap_currcy_cd      --  注册资金币种代码
	  ,CASE WHEN NVL(B.register_addr,'')<>'' THEN B.register_addr ELSE NULL END AS rgst_addr   --  注册地址
	  ,'' as offic_addr -- 办公地址
      ,'' as offic_tel -- 办公电话
	  ,0 AS rcvd_cap                                                                     --  实收资本
	  ,'156' AS rcvd_cap_currcy_cd                                                       --  实收资本币种代码
	  ,'' AS year_biz_income_currcy_cd                                                   --  年营业收入币种代码
      ,'' AS year_biz_income_unit_cd                                                     --  年营业收入单位代码
	  ,NULL AS year_biz_income                                                           --  年营业收入
	  ,'' AS last_year_biz_income_currcy_cd                                              --  上年营业收入币种代码
      ,'' AS last_year_biz_income_unit_cd                                                --  上年营业收入单位代码
	  ,NULL AS last_year_biz_income                                                      --  上年营业收入
      ,A1.data_sourc AS date_sourcesd                                                    --  数据来源
   
  FROM {CDM}.C_CUS_UNIFI_CUST_INF_PUB A1
 INNER JOIN {FDM}.F_PT_SILN_ENT_BASIC_INF B -- 企业基本信息
    ON A1.src_sys_cust_no = B.cust_no
   AND B.start_dt <= DATE '{I_LAST_DT}'
   AND B.end_dt > DATE '{I_LAST_DT}'
  LEFT JOIN {FDM}.F_PT_SILN_ENT_CONTACT_PERSON_BASIC_INF C -- 企业联系人基本信息
    ON B.cust_no = C.pub_cust_no
   AND C.start_dt <= DATE '{I_LAST_DT}'
   AND C.end_dt > DATE '{I_LAST_DT}'
   AND C.senior_execu_type_cd = '0100' -- 法人代表 
   AND C.del_flag<>'D'  --  删除数据未闭链数据不取否则导致数据重复 
WHERE A1.data_dt=DATE '{ETL_TX_DT}' AND A1.data_sourc='SILN'
;

-- 新票据：
INSERT INTO {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_NBMS
    (
    DATA_DT                                   -- 数据日期
    ,UNIFI_CUST_NO                             -- 统一客户号
    ,CUST_NAME                                 -- 客户名称
    ,CUST_CLASS                                -- 客户分类 政务、对公、同业
    ,IDEN_TYPE_CD                              -- 证件类型代码
    ,IDEN_NO                                   -- 证件号码
	,USCC                                      -- 统一社会信用代码
    ,ORGAN_CD                                  -- 组织机构代码
    ,nation_cd                                 -- 国籍代码
    ,INDUS_AND_COMME_BIZ_LICEN_NO              -- 工商营业执照号
	,INDUS_CLASS_CD                            -- 行业类别代码
    ,ENT_SCALE_CD                              -- 企业规模代码
    ,CREATE_ORG_NO                             -- 创建机构号
	,CREDIT_RATIN_CD                           -- 信用评级代码
    ,RGST_CY_CD                                -- 注册国家代码
    ,DATA_SOURC                                -- 数据来源
    )
SELECT DATE '{ETL_TX_DT}'              AS data_dt          --  数据日期
      ,A1.unifi_cust_no                AS unifi_cust_no    --  客户号
      ,REPLACE(A1.cust_name, ' ', '')  AS cust_name        --  客户中文名称
	  ,'对公'                          AS cust_class       --  同业客户		                      
      ,A1.iden_type_cd                 AS iden_type_cd     --  证件类型
      ,A1.iden_no                      AS iden_no          --  证件号码
	  ,NULL                            AS uscc             --  统一社会信用代码
      ,NULL                            AS organ_cd         --  组织机构代码
      ,'156'                           as nation_cd        -- 国籍代码
	  ,NULL                            AS indus_and_comme_biz_licen_no -- 工商营业执照号
	  ,SUBSTR(B.indus_class_cd,1,1) AS indus_class_cd      -- 行业类别代码
      ,CASE WHEN B.ent_scale_cd='SC00' THEN '1'
            WHEN B.ent_scale_cd='SC01' THEN '2'
            WHEN B.ent_scale_cd='SC02' THEN '3'
            WHEN B.ent_scale_cd='SC03' THEN '4'
            WHEN B.ent_scale_cd='SC04' THEN '9'
      ELSE NULL END AS ent_scale_cd                        -- 企业规模代码
	  ,A1.create_org                   AS create_org_no    --  创建机构号
	  ,NULL                            AS credit_ratin_cd  --  信用评级代码
	  ,'156'                           AS rgst_cy_cd       --  注册国家代码
      ,A1.data_sourc AS DATE_SOURCESD    --    数据来源
 FROM {CDM}.C_CUS_UNIFI_CUST_INF_PUB A1
INNER JOIN {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP8 B
ON A1.src_sys_cust_no = B.cust_no
WHERE A1.data_sourc = 'NBMS'
AND A1.data_dt =DATE '{ETL_TX_DT}'
 ;
   
-- 新资金本币:
INSERT INTO {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_FNDS_BB
(
  DATA_DT                            
,UNIFI_CUST_NO                       
,CUST_NAME                           
,CUST_CLASS                          
,IDEN_TYPE_CD                        
,IDEN_NO                             
,USCC                                
,ORGAN_CD                            
,RGST_REGIS_NO                       
,RGST_RGST_DT                        
,INDUS_AND_COMME_BIZ_LICEN_NO        
,INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT  
,SHAREHD_FLAG                        
,RURAL_CITY_FLAG                     
,INDUS_CLASS_CD                      
,nation_cd
,STATU_REP_CUST_NAME                 
,STATU_REP_IDEN_TYPE_CD              
,STATU_REP_IDEN_NO    
,statu_rep_tel               
,SET_UP_DT                           
,ENT_SCALE_CD                        
-- ,MYBANK_AFFIR_MINOR_ENT_FLAG         
,LISTE_COMPY_FLAG                    
,dom_resdnt_flag
,RELATED_GRP_CUST_NO                 
,CREATE_ORG_NO                       
,UPDATE_ORG_NO                       
,ADMIN_AREA_CD                       
,OPERA_BIZ_SCOPE                     
,HOLD_TYPE_CD                        
,CREDIT_RATIN_CD                     
,CUST_TYPE_CD                        
,FIN_ORG_CD                          
,BASIC_DEPOSIT_ACCT_NO               
,BASIC_ACCT_OPENBANK_NAME            
,OPERA_STS_CD                        
,RGST_CY_CD                          
,RGST_CAP                            
,RGST_CAP_CURRCY_CD                  
,RGST_ADDR 
,offic_addr
,offic_tel                          
,RCVD_CAP                            
,RCVD_CAP_CURRCY_CD                  
,YEAR_BIZ_INCOME_CURRCY_CD           
,YEAR_BIZ_INCOME_UNIT_CD             
,YEAR_BIZ_INCOME                     
,LAST_YEAR_BIZ_INCOME_CURRCY_CD      
,LAST_YEAR_BIZ_INCOME_UNIT_CD        
,LAST_YEAR_BIZ_INCOME                
,DATA_SOURC                          
)
SELECT DATE '{ETL_TX_DT}'                                         AS data_dt                            --  数据日期
      ,A1.unifi_cust_no                                           AS unifi_cust_no                      --  客户号
      ,REPLACE(A1.cust_name, ' ', '')                             AS cust_name                          --  客户中文名称
	  ,DECODE(T15.field_val,'02','对公','01','同业')              AS cust_class                         --  同业客户		                         
	  ,A1.iden_type_cd                                            AS iden_type_cd                       --  证件类型
      ,A1.iden_no                                                 AS iden_no                            --  证件号码
	  ,CASE WHEN NVL(T13.field_val,'')<>'' THEN T13.field_val ELSE NULL END AS uscc                     --  统一社会信用代码
      ,CASE WHEN A1.iden_type_cd IN ('13700','20600') THEN A1.iden_no ELSE NULL END AS organ_cd         --  组织机构代码
	  ,NULL AS RGST_REGIS_NO -- 登记注册号
	  ,NULL AS RGST_RGST_DT -- 注册登记日期
	  ,CASE WHEN A1.iden_type_cd='20500' THEN A1.iden_no ELSE NULL END AS indus_and_comme_biz_licen_no  -- 工商营业执照号
	  ,NULL AS indus_and_comme_biz_licen_expir_dt
      ,NULL AS sharehd_flag -- 股东标志 
	  ,'' AS rural_city_flag -- 农村城市标志 
      ,NULL AS indus_class_cd	--  行业类别代码
      ,'156' as nation_cd -- 国籍代码
      ,T2.field_val    AS statu_rep_cust_name            -- 对公客户法定代表人客户姓名
      ,CASE WHEN T3.field_val <> '' THEN '102' 
          ELSE NULL END AS statu_rep_iden_type_cd        -- 对公客户法定代表人身份证件类型 居民身份证
      ,T3.field_val  AS statu_rep_iden_no                -- 对公客户法定代表人身份证件号码
      ,'' as statu_rep_tel -- 法定代表人电话号码
	  ,DECODE(NVL(T1.field_val,''),'',NULL,UDF_DTCHK(T1.field_val,'{LEG_DATE}')) AS set_up_dt  --  成立日期	  
	  ,CASE WHEN t12.field_val='DX'  then '1'
          WHEN t12.field_val='ZX'  then '2'
          WHEN t12.field_val='WX'  then '4'
          WHEN t12.field_val='XX'  then '3'  
          ELSE NULL
		  END                                                AS ent_scale_cd                    --  企业规模代码
	  ,NULL                                                AS liste_compy_flag                --  上市公司标志
	  ,'' as dom_resdnt_flag -- 境内居民标志
	  ,NULL                                                AS related_grp_cust_no             --  关联集团客户号
	  ,A1.create_org                                       AS create_org_no                   --  创建机构号
	  ,NULL                                                AS update_org_no                   --  更新机构号
	  ,CASE WHEN NVL(T7.field_val,'')<>'' THEN T7.field_val ELSE NULL END AS admin_area_cd    --  行政区划代码
	  ,NULL                                                AS opera_biz_scope                 --  经营（业务）范围
	  ,CASE WHEN T11.field_val='A0101' THEN 'A02' -- 国有控股企业-相对控股
            WHEN T11.field_val='A0102' THEN 'A01' -- 国有控股企业-绝对控股
            WHEN T11.field_val='A0201' THEN 'B02' -- 集体控股企业-相对控股
            WHEN T11.field_val='A0202' THEN 'B01' -- 集体控股企业-绝对控股
            WHEN T11.field_val='B0101' THEN 'C02' -- 私人控股企业-相对控股
            WHEN T11.field_val='B0102' THEN 'C01' -- 私人控股企业-绝对控股
            WHEN T11.field_val='B0201' THEN 'D02' -- 港澳台商控股企业-相对控股
            WHEN T11.field_val='B0202' THEN 'D01' -- 港澳台商控股企业-绝对控股
            WHEN T11.field_val='B0301' THEN 'E02' -- 外商控股企业-相对控股
            WHEN T11.field_val='B0302' THEN 'E01' -- 外商控股企业-绝对控股
        END                                                AS hold_type_cd                    --  控股类型代码 
	  ,T4.val_related_desc                                 AS credit_ratin_cd                 --  信用评级代码
	  ,NULL                                                AS cust_type_cd                    --  客户类型代码
	  ,T8.field_val                                        AS fin_org_cd                      --  金融机构代码
	  ,T5.field_val                                        AS basic_deposit_acct_no           --  基本存款账号
	  ,T6.field_val                                        AS basic_acct_openbank_name        --  基本账户开户行名称
	  ,NULL                                                AS opera_sts_cd                    --  经营状态代码
	  ,CASE WHEN NVL(T16.field_val,'')<>'' THEN T16.field_val ELSE '156' END AS rgst_cy_cd    --  注册国家代码
	  ,NULL                                                AS rgst_cap                        --  注册资金
	  ,NULL                                                AS rgst_cap_currcy_cd              --  注册资金币种代码
	  ,T14.field_val                                       AS rgst_addr                       --  注册地址
	  ,''                                                  as offic_addr                      -- 办公地址
      ,''                                                  as offic_tel                       -- 办公电话
	  ,NULL                                                AS rcvd_cap                        --  实收资本
	  ,NULL                                                AS rcvd_cap_currcy_cd              --  实收资本币种代码
	  ,''                                                  AS year_biz_income_currcy_cd       -- 年营业收入币种代码
	  ,''                                                  AS year_biz_income_unit_cd         --  年营业收入单位代码 01 元，02 千元，03 万元 
	  ,NULL                                                AS year_biz_income                 -- 年营业收入
      ,''                                                  AS last_year_biz_income_currcy_cd  -- 上年营业收入币种
      ,''                                                  AS last_year_biz_income_unit_cd    --  上年营业收入单位代码 01 元，02 千元，03 万元	  
	  ,NULL                                                AS last_year_biz_income            -- 上年营业收入
      ,A1.data_sourc AS date_sourcesd    --  数据来源
FROM {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP6  A1
 LEFT JOIN {FDM}.F_PT_FNDS_TBS_VS_BASE_INF T1 -- 交易对手扩展字段 
     ON A1.vs_no=T1.vs_no
	 AND T1.start_dt <= DATE '{ETL_TX_DT}' 
     AND T1.end_dt > DATE '{ETL_TX_DT}' 
	 AND T1.del_flag <> 'D'
     AND T1.field_no='CLRQ' -- 成立日期
 LEFT JOIN {FDM}.F_PT_FNDS_TBS_VS_BASE_INF T2 -- 交易对手扩展字段 
     ON A1.vs_no=T2.vs_no
	 AND T2.start_dt <= DATE '{ETL_TX_DT}' 
     AND T2.end_dt > DATE '{ETL_TX_DT}' 
	 AND T2.del_flag <> 'D'
     AND T2.field_no='FRDB' -- 法人代表
 LEFT JOIN {FDM}.F_PT_FNDS_TBS_VS_BASE_INF T3 -- 交易对手扩展字段 
     ON A1.vs_no=T3.vs_no
	 AND T3.start_dt <= DATE '{ETL_TX_DT}' 
     AND T3.end_dt > DATE '{ETL_TX_DT}' 
	 AND T3.del_flag <> 'D'
     AND T3.field_no='FRSFZH' -- 法人身份证号
 LEFT JOIN {FDM}.F_PT_FNDS_TBS_VS_BASE_INF T4 -- 交易对手扩展字段 
     ON A1.vs_no=T4.vs_no
	 AND T4.start_dt <= DATE '{ETL_TX_DT}' 
     AND T4.end_dt > DATE '{ETL_TX_DT}' 
	 AND T4.del_flag <> 'D'
     AND T4.field_no='KHPJ' -- 客户评级
 LEFT JOIN {FDM}.F_PT_FNDS_TBS_VS_BASE_INF T5 -- 交易对手扩展字段 
     ON A1.vs_no=T5.vs_no
	 AND T5.start_dt <= DATE '{ETL_TX_DT}' 
     AND T5.end_dt > DATE '{ETL_TX_DT}' 
	 AND T5.del_flag <> 'D'
     AND T5.field_no='JBCKZH' -- 基本存款账号
 LEFT JOIN {FDM}.F_PT_FNDS_TBS_VS_BASE_INF T6 -- 交易对手扩展字段 
     ON A1.vs_no=T6.vs_no
	 AND T6.start_dt <= DATE '{ETL_TX_DT}' 
     AND T6.end_dt > DATE '{ETL_TX_DT}'
     AND T6.del_flag <> 'D'	 
     AND T6.field_no='JBZHKHHMC' -- 基本账号开户行名称
 LEFT JOIN {FDM}.F_PT_FNDS_TBS_VS_BASE_INF T7 -- 交易对手扩展字段 
     ON A1.vs_no=T7.vs_no
     AND T7.start_dt <= DATE '{ETL_TX_DT}' 
     AND T7.end_dt > DATE '{ETL_TX_DT}'
     AND T7.del_flag <> 'D'	 
     AND T7.field_no='DQDM' -- 客户所属地区	 
 LEFT JOIN {FDM}.F_PT_FNDS_TBS_VS_BASE_INF T8 -- 交易对手扩展字段 
     ON A1.vs_no=T8.vs_no
     AND T8.start_dt <= DATE '{ETL_TX_DT}' 
     AND T8.end_dt > DATE '{ETL_TX_DT}' 
     AND T8.del_flag <> 'D'
     AND T8.field_no='JRJGBM' -- 金融机构编码
LEFT JOIN {FDM}.F_PT_FNDS_TBS_VS_BASE_INF A -- 交易对手扩展字段 	
     ON A1.vs_no=A.vs_no
     AND A.start_dt <= DATE '{ETL_TX_DT}' 
     AND A.end_dt > DATE '{ETL_TX_DT}'
     AND A.del_flag <> 'D' 
     AND A.field_no='TBSCOUNTERPARTY' -- 二级分类

 LEFT JOIN {FDM}.F_PT_FNDS_TBS_VS_BASE_INF T11 -- 交易对手扩展字段 	
     ON A1.vs_no=T11.vs_no
     AND T11.start_dt <= DATE '{ETL_TX_DT}' 
     AND T11.end_dt > DATE '{ETL_TX_DT}'
     AND T11.del_flag <> 'D' 
     AND T11.field_no='JJCF' -- 经济成分
   LEFT JOIN {FDM}.F_PT_FNDS_TBS_VS_BASE_INF T12 -- 交易对手扩展字段 	
     ON A1.vs_no=T12.vs_no
     AND T12.start_dt <= DATE '{ETL_TX_DT}' 
     AND T12.end_dt > DATE '{ETL_TX_DT}'
     AND T12.del_flag <> 'D' 
     AND T12.field_no='QYGM' -- 企业规模
  LEFT JOIN {FDM}.F_PT_FNDS_TBS_VS_BASE_INF T13 -- 交易对手扩展字段 	
     ON A1.vs_no=T13.vs_no
     AND T13.start_dt <= DATE '{ETL_TX_DT}' 
     AND T13.end_dt > DATE '{ETL_TX_DT}'
     AND T13.del_flag <> 'D' 
     AND T13.field_no='SHTYXYDM' -- 社会统一信用代码
  LEFT JOIN {FDM}.F_PT_FNDS_TBS_VS_BASE_INF T14 -- 交易对手扩展字段 	
     ON A1.vs_no=T14.vs_no
     AND T14.START_DT <= DATE '{ETL_TX_DT}' 
     AND T14.END_DT > DATE '{ETL_TX_DT}'
     AND T14.DEL_FLAG <> 'D' 
     AND T14.field_no='ZCDZ' -- 注册地址 
   LEFT JOIN {FDM}.F_PT_FNDS_TBS_VS_BASE_INF T15 -- 交易对手扩展字段 	
     ON A1.vs_no=T15.vs_no
     AND T15.START_DT <= DATE '{ETL_TX_DT}' 
     AND T15.END_DT > DATE '{ETL_TX_DT}'
     AND T15.DEL_FLAG <> 'D' 
     AND T15.field_no='SFTY' -- 客户分类
   LEFT JOIN {FDM}.F_PT_FNDS_TBS_VS_BASE_INF T16 -- 交易对手扩展字段 	
     ON A1.vs_no=T16.vs_no
     AND T16.start_dt <= DATE '{ETL_TX_DT}' 
     AND T16.end_dt > DATE '{ETL_TX_DT}'
     AND T16.del_flag <> 'D' 
     AND T16.field_no='KHGJ' -- 注册国家代码
  
;
-- 新资金外币:
INSERT INTO {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_FNDS_WB
(
  DATA_DT                            
,UNIFI_CUST_NO                       
,CUST_NAME                           
,CUST_CLASS                          
,IDEN_TYPE_CD                        
,IDEN_NO                             
,USCC                                
,ORGAN_CD                            
,RGST_REGIS_NO                       
,RGST_RGST_DT                        
,INDUS_AND_COMME_BIZ_LICEN_NO        
,INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT  
,SHAREHD_FLAG                        
,RURAL_CITY_FLAG                     
,INDUS_CLASS_CD                      
,nation_cd
,STATU_REP_CUST_NAME                 
,STATU_REP_IDEN_TYPE_CD              
,STATU_REP_IDEN_NO      
,statu_rep_tel             
,SET_UP_DT                           
,ENT_SCALE_CD                        
-- ,MYBANK_AFFIR_MINOR_ENT_FLAG         
,LISTE_COMPY_FLAG                    
,dom_resdnt_flag
,RELATED_GRP_CUST_NO                 
,CREATE_ORG_NO                       
,UPDATE_ORG_NO                       
,ADMIN_AREA_CD                       
,OPERA_BIZ_SCOPE                     
,HOLD_TYPE_CD                        
,CREDIT_RATIN_CD                     
,CUST_TYPE_CD                        
,FIN_ORG_CD                          
,BASIC_DEPOSIT_ACCT_NO               
,BASIC_ACCT_OPENBANK_NAME            
,OPERA_STS_CD                        
,RGST_CY_CD                          
,RGST_CAP                            
,RGST_CAP_CURRCY_CD                  
,RGST_ADDR   
,offic_addr
,offic_tel                        
,RCVD_CAP                            
,RCVD_CAP_CURRCY_CD                  
,YEAR_BIZ_INCOME_CURRCY_CD           
,YEAR_BIZ_INCOME_UNIT_CD             
,YEAR_BIZ_INCOME                     
,LAST_YEAR_BIZ_INCOME_CURRCY_CD      
,LAST_YEAR_BIZ_INCOME_UNIT_CD        
,LAST_YEAR_BIZ_INCOME                
,DATA_SOURC                          
)
SELECT DATE '{ETL_TX_DT}'                                                   AS data_dt                             --  数据日期
      ,A1.unifi_cust_no                                                     AS unifi_cust_no                       --  客户号
      ,REPLACE(A1.cust_name, ' ', '')                                       AS cust_name                           --  客户中文名称
	  ,DECODE(T15.field_val,'02','对公','01','同业')                        AS cust_class                          --  同业客户		                         
	  ,A1.iden_type_cd                                                      AS iden_type_cd                        --  证件类型
      ,A1.iden_no                                                           AS iden_no                             --  证件号码
	  ,CASE WHEN NVL(T13.field_val,'')<>'' THEN T13.field_val ELSE NULL END AS uscc                                --  统一社会信用代码
      ,NULL                                                                 AS organ_cd                            --  组织机构代码
	  ,NULL AS rgst_regis_no -- 登记注册号
      ,NULL AS rgst_rgst_dt -- 注册登记日期
	  ,NULL                                                                 AS indus_and_comme_biz_licen_no        --  工商营业执照号
      ,NULL                                                                 AS indus_and_comme_biz_licen_expir_dt  --  工商营业执照到期日
      ,NULL                                                                 AS sharehd_flag                        -- 股东标志 手工补录	
	  ,'' AS rural_city_flag -- 农村城市标志
      ,NULL                                                                 AS indus_class_cd	                   -- 行业类别代码                                                              
      ,CASE WHEN NVL(T16.field_val,'')<>'' THEN T16.field_val ELSE '156' END AS  nation_cd -- 国籍代码
      ,T2.field_val                                                         AS statu_rep_cust_name                 -- 对公客户法定代表人客户姓名
      ,CASE WHEN T3.field_val <> '' THEN '102'  ELSE NULL END               AS statu_rep_iden_type_cd              -- 对公客户法定代表人身份证件类型 居民身份证
      ,T3.field_val                                                         AS statu_rep_iden_no                   -- 对公客户法定代表人身份证件号码
      ,'' as statu_rep_tel -- 法定代表人电话号码
	  ,DECODE(NVL(T1.field_val,''),'',NULL,CDM.UDF_DTCHK(T1.field_val,'{LEG_DATE}')) AS set_up_dt         --  成立日期	  
	  ,CASE WHEN T12.field_val='DX'  then '1'
          WHEN T12.field_val='ZX'  then '2'
			    WHEN T12.field_val='XX'  then '3'
          WHEN T12.field_val='WX'  then '4'
          ELSE NULL
        END                                                             AS ent_scale_cd                        -- 企业规模代码
	  ,NULL                                                               AS liste_compy_flag                    --  上市公司标志
	  ,'' as dom_resdnt_flag -- 境内居民标志
	  ,NULL                                                               AS related_grp_cust_no                 --  关联集团客户号
	  ,A1.create_org                                                      AS create_org_no                       --  创建机构号
	  ,NULL                                                               AS update_org_no                       --  更新机构号
	  ,CASE WHEN NVL(T7.field_val,'')<>'' THEN T7.field_val ELSE NULL END AS admin_area_cd                       --  行政区划代码
	  ,NULL                                                               AS opera_biz_scope                     --  经营（业务）范围
	  ,NULL                                                                  AS hold_type_cd                    --  控股类型代码
	  ,T4.val_related_descn                                                  AS credit_ratin_cd                 --  信用评级代码
	  ,NULL                                                                  AS cust_type_cd                    --  客户类型代码
	  ,T8.field_val                                                          AS fin_org_cd                      --  金融机构代码
	  ,T5.field_val                                                          AS basic_deposit_acct_no           --  基本存款账号
	  ,T6.field_val                                                          AS basic_acct_openbank_name        --  基本账户开户行名称  
	  ,NULL                                                                  AS opera_sts_cd                    --  经营状态代码
	  ,CASE WHEN NVL(T16.field_val,'')<>'' THEN T16.field_val ELSE '156' END AS rgst_cy_cd                      --  注册国家代码
	  ,NULL                                                                  AS rgst_cap                        --  注册资金
	  ,NULL                                                                  AS rgst_cap_currcy_cd              --  注册资金币种代码
	  ,T14.field_val                                                         AS rgst_addr                       --  注册地址
	  ,'' as offic_addr -- 办公地址
      ,'' as offic_tel -- 办公电话
	  ,NULL                                                                  AS rcvd_cap                        --  实收资本
	  ,NULL                                                                  AS rcvd_cap_currcy_cd              --  实收资本币种代码
	  ,''                                                                    AS year_biz_income_currcy_cd       --  年营业收入币种代码
	  ,''                                                                    AS year_biz_income_unit_cd         --  年营业收入单位代码 01 元，02 千元，03 万元 
	  ,NULL                                                                  AS year_biz_income                 --  年营业收入
      ,''                                                                    AS last_year_biz_income_currcy_cd  --  上年营业收入币种
	  ,''                                                                    AS last_year_biz_income_unit_cd    --  上年营业收入单位代码 01 元，02 千元，03 万元
	  ,NULL                                                                  AS last_year_biz_income            --  上年营业收入
      ,A1.data_sourc                                                         AS date_sourcesd                   --    数据来源
 FROM {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP7  A1
 LEFT JOIN {FDM}.F_PT_FNDS_VS_EXT_FIELD T1 -- 交易对手扩展字段 
     ON A1.vs_no=T1.vs_serial_no
	 AND T1.start_dt <= DATE '{ETL_TX_DT}' 
     AND T1.end_dt > DATE '{ETL_TX_DT}' 
	 AND T1.del_flag <> 'D'
     AND T1.field_no='CLRQ' -- 成立日期
 LEFT JOIN {FDM}.F_PT_FNDS_VS_EXT_FIELD T2 -- 交易对手扩展字段 
     ON A1.vs_no=T2.vs_serial_no
	 AND T2.start_dt <= DATE '{ETL_TX_DT}' 
     AND T2.end_dt > DATE '{ETL_TX_DT}' 
	 AND T2.del_flag <> 'D'
     AND T2.field_no='FRDB' -- 法人代表
 LEFT JOIN {FDM}.F_PT_FNDS_VS_EXT_FIELD T3 -- 交易对手扩展字段 
     ON A1.vs_no=T3.vs_serial_no
	 AND T3.START_DT <= DATE '{ETL_TX_DT}' 
     AND T3.END_DT > DATE '{ETL_TX_DT}' 
	 AND T3.DEL_FLAG <> 'D'
     AND T3.field_no='FRSFZH' -- 法人身份证号
 LEFT JOIN {FDM}.F_PT_FNDS_VS_EXT_FIELD T4 -- 交易对手扩展字段 
     ON A1.vs_no=T4.vs_serial_no
	 AND T4.start_dt <= DATE '{ETL_TX_DT}' 
     AND T4.end_dt > DATE '{ETL_TX_DT}' 
	 AND T4.del_flag <> 'D'
     AND T4.field_no='KHPJ' -- 客户评级
 LEFT JOIN {FDM}.F_PT_FNDS_VS_EXT_FIELD T5 -- 交易对手扩展字段 
     ON A1.vs_no=T5.vs_serial_no
	 AND T5.start_dt <= DATE '{ETL_TX_DT}' 
     AND T5.end_dt > DATE '{ETL_TX_DT}' 
	 AND T5.del_flag <> 'D'
     AND T5.field_no='JBCKZH' -- 基本存款账号
 LEFT JOIN {FDM}.F_PT_FNDS_VS_EXT_FIELD T6 -- 交易对手扩展字段 
     ON A1.vs_no=T6.vs_serial_no
	 AND T6.start_dt <= DATE '{ETL_TX_DT}' 
     AND T6.end_dt > DATE '{ETL_TX_DT}'
     AND T6.del_flag <> 'D'	 
     AND T6.field_no='JBZHKHHMC' -- 基本账号开户行名称
 LEFT JOIN {FDM}.F_PT_FNDS_VS_EXT_FIELD T7 -- 交易对手扩展字段 
     ON A1.vs_no=T7.vs_serial_no
     AND T7.START_DT <= DATE '{ETL_TX_DT}' 
     AND T7.END_DT > DATE '{ETL_TX_DT}'
     AND T7.DEL_FLAG <> 'D'	 
     AND T7.field_no='DQDM' -- 地区代码	 
 LEFT JOIN {FDM}.F_PT_FNDS_VS_EXT_FIELD T8 -- 交易对手扩展字段 
     ON A1.vs_no=T8.vs_serial_no
     AND T8.start_dt <= DATE '{ETL_TX_DT}' 
     AND T8.end_dt > DATE '{ETL_TX_DT}' 
     AND T8.del_flag <> 'D'
     AND T8.field_no='JRJGBM' -- 金融机构编码
LEFT JOIN {FDM}.F_PT_FNDS_VS_EXT_FIELD A -- 交易对手扩展字段 	
     ON A1.vs_no=A.vs_serial_no
     AND A.START_DT <= DATE '{ETL_TX_DT}' 
     AND A.END_DT > DATE '{ETL_TX_DT}'
     AND A.DEL_FLAG <> 'D' 
     AND A.field_no='TBSCOUNTERPARTY' -- 二级分类
 LEFT JOIN {FDM}.F_PT_FNDS_VS_EXT_FIELD T11 -- 交易对手扩展字段 	
     ON A1.vs_no=T11.vs_serial_no
     AND T11.start_dt <= DATE '{ETL_TX_DT}' 
     AND T11.end_dt > DATE '{ETL_TX_DT}'
     AND T11.del_flag <> 'D' 
     AND T11.field_no='JJCF' -- 经济成分
 LEFT JOIN {FDM}.F_PT_FNDS_VS_EXT_FIELD T12 -- 交易对手扩展字段 	
     ON A1.vs_no=T12.vs_serial_no
     AND T12.start_dt <= DATE '{ETL_TX_DT}' 
     AND T12.end_dt > DATE '{ETL_TX_DT}'
     AND T12.del_flag <> 'D' 
     AND T12.field_no='QYGM' -- 企业规模
 LEFT JOIN {FDM}.F_PT_FNDS_VS_EXT_FIELD T13 -- 交易对手扩展字段 	
     ON A1.vs_no=T13.vs_serial_no
     AND T13.start_dt <= DATE '{ETL_TX_DT}' 
     AND T13.end_dt > DATE '{ETL_TX_DT}'
     AND T13.del_flag <> 'D' 
     AND T13.field_no='SHTYXYDM' -- 统一社会信用代码
  LEFT JOIN {FDM}.F_PT_FNDS_VS_EXT_FIELD T14 -- 交易对手扩展字段 	
     ON A1.vs_no=T14.vs_serial_no
     AND T14.start_dt <= DATE '{ETL_TX_DT}' 
     AND T14.end_dt > DATE '{ETL_TX_DT}'
     AND T14.del_flag <> 'D' 
     AND T14.field_no='ZCDZ' -- 注册地址 
  LEFT JOIN {FDM}.F_PT_FNDS_VS_EXT_FIELD T15 -- 交易对手扩展字段 	
     ON A1.vs_no=T15.vs_serial_no
     AND T15.start_dt <= DATE '{ETL_TX_DT}' 
     AND T15.end_dt > DATE '{ETL_TX_DT}'
     AND T15.del_flag <> 'D' 
     AND T15.field_no='SFTY' -- 客户分类
  LEFT JOIN {FDM}.F_PT_FNDS_VS_EXT_FIELD T16 -- 交易对手扩展字段 	
     ON A1.vs_no=T16.vs_serial_no
     AND T16.start_dt <= DATE '{ETL_TX_DT}' 
     AND T16.end_dt > DATE '{ETL_TX_DT}'
     AND T16.del_flag <> 'D' 
     AND T16.field_no='KHGJ' -- 注册国家代码
	 ;
-- 金企贷：
INSERT INTO {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SFLN
(
  DATA_DT                            
,UNIFI_CUST_NO                       
,CUST_NAME                           
,CUST_CLASS                          
,IDEN_TYPE_CD                        
,IDEN_NO                             
,USCC                                
,ORGAN_CD                            
,RGST_REGIS_NO                       
,RGST_RGST_DT                        
,INDUS_AND_COMME_BIZ_LICEN_NO        
,INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT  
,SHAREHD_FLAG                        
,RURAL_CITY_FLAG                     
,INDUS_CLASS_CD                      
,nation_cd
,STATU_REP_CUST_NAME                 
,STATU_REP_IDEN_TYPE_CD              
,STATU_REP_IDEN_NO           
,statu_rep_tel        
,SET_UP_DT                           
,ENT_SCALE_CD                        
-- ,MYBANK_AFFIR_MINOR_ENT_FLAG         
,LISTE_COMPY_FLAG                    
,dom_resdnt_flag
,RELATED_GRP_CUST_NO                 
,CREATE_ORG_NO                       
,UPDATE_ORG_NO                       
,ADMIN_AREA_CD                       
,OPERA_BIZ_SCOPE                     
,HOLD_TYPE_CD                        
,CREDIT_RATIN_CD                     
,CUST_TYPE_CD                        
,FIN_ORG_CD                          
,BASIC_DEPOSIT_ACCT_NO               
,BASIC_ACCT_OPENBANK_NAME            
,OPERA_STS_CD                        
,RGST_CY_CD                          
,RGST_CAP                            
,RGST_CAP_CURRCY_CD                  
,RGST_ADDR      
,offic_addr
,offic_tel                     
,RCVD_CAP                            
,RCVD_CAP_CURRCY_CD                  
,YEAR_BIZ_INCOME_CURRCY_CD           
,YEAR_BIZ_INCOME_UNIT_CD             
,YEAR_BIZ_INCOME                     
,LAST_YEAR_BIZ_INCOME_CURRCY_CD      
,LAST_YEAR_BIZ_INCOME_UNIT_CD        
,LAST_YEAR_BIZ_INCOME                
,DATA_SOURC                          
)
SELECT DATE '{ETL_TX_DT}'                                     AS data_dt        -- 数据日期
      ,A1.unifi_cust_no                                       AS unifi_cust_no  -- 客户号
      ,REPLACE(A1.cust_name, ' ', '')                         AS cust_name      -- 客户中文名称
	  ,'对公'                                                 AS cust_class     -- 同业客户		                         
	  ,A1.iden_type_cd                                        AS iden_type_cd   -- 证件类型
      ,A1.iden_no                                             AS iden_no        -- 证件号码
	  ,CASE WHEN NVL(B.uscc,'')<>'' THEN B.uscc ELSE NULL END AS uscc           -- 统一社会信用代码
      ,NULL                                                   AS organ_cd       -- 组织机构代码
	  ,'' AS rgst_regis_no  -- 登记注册号
      ,DECODE(NVL(B.set_up_dt,''),'',NULL,CDM.UDF_DTCHK(B.set_up_dt,'{LEG_DATE}')) AS rgst_rgst_dt   -- 注册登记日期
	  ,CASE WHEN NVL(B.biz_licen_no,'')<>'' THEN B.biz_licen_no ELSE NULL END AS indus_and_comme_biz_licen_no  -- 工商营业执照号
       ,CASE WHEN REPLACE(B.biz_licen_vld_end_dt_term,' ','') ='长期' THEN DATE '99991231'	  
	         WHEN NVL(B.biz_licen_vld_end_dt_term,'') =''  THEN NULL
             WHEN UDF_DTCHK(SUBSTR(TO_CHAR(B.biz_licen_vld_end_dt_term),1,10),'0') = '0' THEN DATE '{LEG_DATE}'  -- 非法日期默认值V
            ELSE B.biz_licen_vld_end_dt_term
        END AS indus_and_comme_biz_licen_expir_dt                    -- 工商营业执照到期日 
      ,NULL                             AS sharehd_flag              -- 股东标志	
	  ,'' AS rural_city_flag -- 农村城市标志
      ,B.indus_direction AS indus_class_cd --  行业类别代码	
      ,'156' as nation_cd -- 国籍代码
      ,CASE
          WHEN C.senior_execu_name <> '' AND C.senior_execu_name IS NOT NULL THEN C.senior_execu_name   
          ELSE NULL  
        END AS statu_rep_cust_name                                   -- 对公客户法定代表人客户姓名
      ,CASE WHEN C.iden_type_cd='01' THEN '102'
       ELSE  C.iden_type_cd END AS statu_rep_iden_type_cd            -- 对公客户法定代表人身份证件类型 居民身份证
      ,C.iden_no  AS statu_rep_iden_no                               -- 对公客户法定代表人身份证件号码
      ,C.contact_phone_no as statu_rep_tel -- 法定代表人电话号码
	  ,DECODE(NVL(B.set_up_dt,''),'',NULL,CDM.UDF_DTCHK(B.set_up_dt,'{LEG_DATE}')) AS set_up_dt  --  成立日期	  
	  ,CASE WHEN NVL(B.ent_scale,'') in ('1','2','3','4') THEN B.ent_scale ELSE NULL END AS ent_scale_cd -- 企业规模代码
	  ,CASE WHEN NVL(B.liste_compy_flag,'')='' THEN '0' ELSE B.liste_compy_flag END AS liste_compy_flag  --  上市公司标志
      ,'' as dom_resdnt_flag -- 境内居民标志
	  ,NULL          AS related_grp_cust_no                --  关联集团客户号
	  ,A1.create_org AS create_org_no                      --  创建机构号
	  ,NULL          AS update_org_no                      --  更新机构号
	  ,CASE WHEN SUBSTR(A1.iden_no,1,6)<>'' THEN SUBSTR(A1.iden_no,1,6) ELSE NULL END AS admin_area_cd  --  行政区划代码
	  ,CASE
          WHEN B.opera_scope <> '' AND B.opera_scope IS NOT NULL THEN SUBSTR(B.opera_scope,1,300) 
          ELSE NULL  
        END                                 AS opera_biz_scope                    --  经营（业务）范围
	  ,CASE WHEN B.holder_type='国有相对控股'     THEN 'A02' -- 国有控股企业-相对控股
            WHEN B.holder_type='国有绝对控股'     THEN 'A01' -- 国有控股企业-绝对控股
            WHEN B.holder_type='集体相对控股'     THEN 'B02' -- 集体控股企业-相对控股
            WHEN B.holder_type='集体绝对控股'     THEN 'B01' -- 集体控股企业-绝对控股
            WHEN B.holder_type='私人相对控股'     THEN 'C02' -- 私人控股企业-相对控股
            WHEN B.holder_type='私人绝对控股'     THEN 'C01' -- 私人控股企业-绝对控股
            WHEN B.holder_type='港澳台商相对控股' THEN 'D02' -- 港澳台商控股企业-相对控股
            WHEN B.holder_type='港澳台商绝对控股' THEN 'D01' -- 港澳台商控股企业-绝对控股
            WHEN B.holder_type='外商相对控股'     THEN 'E02' -- 外商控股企业-相对控股
            WHEN B.holder_type='外商绝对控股'     THEN 'E01' -- 外商控股企业-绝对控股
        END                              AS hold_type_cd                           --  控股类型代码 
	  ,NULL                              AS credit_ratin_cd                        --  信用评级代码
	  ,NULL                              AS cust_type_cd                           --  客户类型代码
	  ,NULL                              AS fin_org_cd                             --  金融机构代码
	  ,NULL                              AS basic_deposit_acct_no                  --  基本存款账号
	  ,NULL                              AS basic_acct_openbank_name               --  基本账户开户行名称
	  
	  ,CASE WHEN B.shutdown_ent_flag='1' THEN '02' ELSE '01' END AS opera_sts_cd  --  经营状态代码
	  ,'156'  AS rgst_cy_cd                                                       --  注册国家代码
	  ,CAST(B.rgst_cap as DECIMAL(18,2))*10000  AS rgst_cap                       --  注册资金
	  ,B.rgst_cap_currcy                        AS rgst_cap_currcy_cd             --  注册资金币种代码
	  ,B.REG_ADDRESS                            AS rgst_addr                      --  注册地址
      ,'' as offic_addr -- 办公地址
      ,'' as offic_tel -- 办公电话
	  ,CAST(b.rcvd_cap as DECIMAL(18,2))*10000  AS rcvd_cap                       --  实收资本
	  ,'156'  AS rcvd_cap_currcy_cd                                               --  实收资本币种代码
	  ,'' AS year_biz_income_currcy_cd         -- 年营业收入币种代码
	  ,'' AS year_biz_income_unit_cd           -- 年营业收入单位代码 01 元，02 千元，03 万元 
	  ,NULL AS year_biz_income                 -- 年营业收入
      ,'' AS last_year_biz_income_currcy_cd    -- 上年营业收入币种 
      ,'' AS last_year_biz_income_unit_cd      -- 上年营业收入单位代码 01 元，02 千元，03 万元
	  ,NULL AS last_year_biz_income            -- 上年营业收入
      ,A1.data_sourc AS date_sourcesd          --  数据来源
  FROM {CDM}.C_CUS_UNIFI_CUST_INF_PUB A1
 INNER JOIN {FDM}.F_PT_SFLN_CUST_INF  B -- 客户信息表
    ON A1.src_sys_cust_no = B.cust_no
   AND B.start_dt <= DATE '{I_LAST_DT}'
   AND B.end_dt > DATE '{I_LAST_DT}'
  LEFT JOIN {FDM}.F_PT_SFLN_ENT_CONTACT_PERSON_BASIC_INF  C -- 企业联系人基本信息
    ON B.cust_no = C.cust_no
   AND C.start_dt <= DATE '{I_LAST_DT}' 
   AND C.end_dt > DATE '{I_LAST_DT}'
   AND C.senior_execu_type_cd='0100' -- 法定代表人
  LEFT JOIN 
   (
     SELECT cust_no, MIN(distr_dt) loan_date
       FROM {FDM}.F_AC_SFLN_LOAN_RCPT_INF A
      WHERE A.start_dt <= DATE '{I_LAST_DT}'
        AND A.end_dt > DATE '{I_LAST_DT}'
      GROUP BY cust_no
    ) SC 
   ON B.cust_no=SC.cust_no 
WHERE A1.data_dt= DATE '{ETL_TX_DT}' 
  AND A1.data_sourc='SFLN'
;
-- 众商贷:
INSERT INTO {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SCLN
(
  DATA_DT                            
,UNIFI_CUST_NO                       
,CUST_NAME                           
,CUST_CLASS                          
,IDEN_TYPE_CD                        
,IDEN_NO                             
,USCC                                
,ORGAN_CD                            
,RGST_REGIS_NO                       
,RGST_RGST_DT                        
,INDUS_AND_COMME_BIZ_LICEN_NO        
,INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT  
,SHAREHD_FLAG                        
,RURAL_CITY_FLAG                     
,INDUS_CLASS_CD                      
,nation_cd
,STATU_REP_CUST_NAME                 
,STATU_REP_IDEN_TYPE_CD              
,STATU_REP_IDEN_NO  
,statu_rep_tel                 
,SET_UP_DT                           
,ENT_SCALE_CD                        
-- ,MYBANK_AFFIR_MINOR_ENT_FLAG         
,LISTE_COMPY_FLAG                    
,dom_resdnt_flag
,RELATED_GRP_CUST_NO                 
,CREATE_ORG_NO                       
,UPDATE_ORG_NO                       
,ADMIN_AREA_CD                       
,OPERA_BIZ_SCOPE                     
,HOLD_TYPE_CD                        
,CREDIT_RATIN_CD                     
,CUST_TYPE_CD                        
,FIN_ORG_CD                          
,BASIC_DEPOSIT_ACCT_NO               
,BASIC_ACCT_OPENBANK_NAME            
,OPERA_STS_CD                        
,RGST_CY_CD                          
,RGST_CAP                            
,RGST_CAP_CURRCY_CD                  
,RGST_ADDR
,offic_addr
,offic_tel                           
,RCVD_CAP                            
,RCVD_CAP_CURRCY_CD                  
,YEAR_BIZ_INCOME_CURRCY_CD           
,YEAR_BIZ_INCOME_UNIT_CD             
,YEAR_BIZ_INCOME                     
,LAST_YEAR_BIZ_INCOME_CURRCY_CD      
,LAST_YEAR_BIZ_INCOME_UNIT_CD        
,LAST_YEAR_BIZ_INCOME                
,DATA_SOURC                          
)
SELECT DATE '{ETL_TX_DT}'                                         AS data_dt                       --  数据日期
      ,A1.unifi_cust_no                                           AS unifi_cust_no                 --  客户号
      ,REPLACE(A1.cust_name, ' ', '')                             AS cust_name                     --  客户中文名称
	  ,'对公'                                                     AS cust_class                    --  同业客户		                         
	  ,A1.iden_type_cd                                            AS iden_type_cd                  --  证件类型
      ,A1.iden_no                                                 AS iden_no                       --  证件号码
	  ,CASE WHEN B.unifi_socie_credit_no='' THEN B.unifi_socie_credit_no ELSE NULL END AS uscc     --  统一社会信用代码
      ,NULL AS organ_cd                                                                            --  组织机构代码
	  ,NULL AS rgst_regis_no  -- 登记注册号
      ,DECODE(NVL(B.set_up_dt,''),'',NULL,CDM.UDF_DTCHK(B.set_up_dt,'{LEG_DATE}')) AS rgst_rgst_dt   -- 注册登记日期
	  ,B.biz_licen_no                                             AS indus_and_comme_biz_licen_no  --  工商营业执照号
      ,CASE 
          WHEN NVL(B.biz_licen_vld_end_dt_term,'')='' THEN NULL
          WHEN UDF_DTCHK(SUBSTR(TO_CHAR(B.biz_licen_vld_end_dt_term),1,10),'0') = '0' THEN date '{LEG_DATE}'  -- 非法日期默认值V
          ELSE B.biz_licen_vld_end_dt_term
        END AS indus_and_comme_biz_licen_expir_dt                                                  --  工商营业执照到期日 
      ,NULL AS sharehd_flag                                                                        -- 股东标志
      ,'' AS rural_city_flag -- 农村城市标志	  
      ,B.indus_direction AS INDUS_CLASS_CD --  行业类别代码 
      ,'156' as nation_cd -- 国籍代码
      ,CASE
          WHEN C.senior_execu_name <> '' AND C.senior_execu_name IS NOT NULL THEN C.senior_execu_name   
          ELSE NULL  
        END AS statu_rep_cust_name                                                                 -- 对公客户法定代表人客户姓名
      ,CASE 
	      WHEN C.iden_type_cd='01' THEN '102' ELSE C.iden_type_cd
	    END AS statu_rep_iden_type_cd                                                              -- 对公客户法定代表人身份证件类型 居民身份证
      ,C.iden_no  AS statu_rep_iden_no                                                             -- 对公客户法定代表人身份证件号码
      ,C.contact_phone_no as statu_rep_tel                                                         -- 法定代表人电话号码
	  ,DECODE(NVL(B.set_up_dt,''),'',NULL,CDM.UDF_DTCHK(B.set_up_dt,'{LEG_DATE}')) AS set_up_dt  --  成立日期	  
	  ,CASE WHEN NVL(B.ent_scale_cd,'') in ('1','2','3','4') THEN B.ent_scale_cd ELSE NULL END AS ent_scale_cd  -- 企业规模代码
	  ,CASE WHEN B.liste_compy_type_cd ='2' OR B.liste_compy_type_cd IS NULL THEN '0' 
			ELSE '1' 
		END                                 AS liste_compy_flag                     -- 上市公司标志
      ,''                                   as dom_resdnt_flag                      -- 境内居民标志
	  ,NULL                                 AS related_grp_cust_no                  -- 关联集团客户号
	  ,A1.create_org                        AS create_org_no                        -- 创建机构号
	  ,NULL                                 AS update_org_no                        -- 更新机构号
	  ,SUBSTR(A1.iden_no,1,6)               AS admin_area_cd                        -- 行政区划代码
	  ,CASE
          WHEN B.opera_scope <> '' AND B.opera_scope IS NOT NULL THEN SUBSTR(B.opera_scope,1,300) 
          ELSE NULL  
        END                                 AS opera_biz_scope                      --  经营（业务）范围
	  ,'C01'                                AS hold_type_cd                         --  控股类型代码 
	  ,NULL                                 AS credit_ratin_cd                      --  信用评级代码
	  ,NULL                                 AS cust_type_cd                         --  客户类型代码
	  ,NULL                                 AS fin_org_cd                           --  金融机构代码
	  ,NULL                                 AS basic_deposit_acct_no                --  基本存款账号
	  ,NULL                                 AS basic_acct_openbank_name             --  基本账户开户行名称
	  
	  ,CASE WHEN B.shutdown_ent_flag='1' THEN '02' ELSE '01' END AS opera_sts_cd    --  经营状态代码
	  ,'156'                                AS rgst_cy_cd                           --  注册国家代码
	  ,B.rgst_cap                           AS rgst_cap                             --  注册资金
	  ,B.rgst_cap_currcy_cd                 AS rgst_cap_currcy_cd                   --  注册资金币种代码
	  ,NULL                                 AS rgst_addr                            --  注册地址
	  ,''                                   as offic_addr                           -- 办公地址
      ,''                                   as offic_tel                            -- 办公电话
	  ,b.rcvd_cap                           AS rcvd_cap                             --  实收资本
	  ,'156'                                AS rcvd_cap_currcy_cd                   --  实收资本币种代码
	  ,''                                   AS year_biz_income_currcy_cd            -- 年营业收入币种代码
	  ,''                                   AS year_biz_income_unit_cd              --  年营业收入单位代码 01 元，02 千元，03 万元 
	  ,NULL                                 AS year_biz_income                      -- 年营业收入
      ,''                                   AS last_year_biz_income_currcy_cd       -- 上年营业收入币种
	  ,''                                   AS last_year_biz_income_unit_cd         --  上年营业收入单位代码 01 元，02 千元，03 万元
	  ,NULL                                 AS last_year_biz_income                 -- 上年营业收入  
      ,A1.data_sourc                        AS date_sourcesd                        --    数据来源
  FROM {CDM}.C_CUS_UNIFI_CUST_INF_PUB A1
 INNER JOIN {FDM}.F_PT_SCLN_CUST_INF   B -- 客户信息表
    ON A1.src_sys_cust_no = B.cust_no
   AND B.start_dt <= DATE '{I_LAST_DT}' 
   AND B.end_dt > DATE '{I_LAST_DT}' 
  LEFT JOIN {FDM}.F_PT_SCLN_STATU_REP_INF   C -- 法定代表人信息(CFSP002000101) 
    ON B.cust_no = C.cust_no
   AND c.start_dt <= DATE '{I_LAST_DT}'  
   AND c.end_dt > DATE '{I_LAST_DT}' 
   AND C.senior_execu_type_cd='0100'
 WHERE A1.data_dt= DATE '{ETL_TX_DT}' 
   AND A1.data_sourc='SCLN' 
;

-- 众商贷债转
INSERT INTO {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SCLN_ZZ
(
  DATA_DT                            
,UNIFI_CUST_NO                       
,CUST_NAME                           
,CUST_CLASS                          
,IDEN_TYPE_CD                        
,IDEN_NO                             
,USCC                                
,ORGAN_CD                            
,RGST_REGIS_NO                       
,RGST_RGST_DT                        
,INDUS_AND_COMME_BIZ_LICEN_NO        
,INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT  
,SHAREHD_FLAG                        
,RURAL_CITY_FLAG                     
,INDUS_CLASS_CD                      
,nation_cd
,STATU_REP_CUST_NAME                 
,STATU_REP_IDEN_TYPE_CD              
,STATU_REP_IDEN_NO    
,statu_rep_tel               
,SET_UP_DT                           
,ENT_SCALE_CD                        
-- ,MYBANK_AFFIR_MINOR_ENT_FLAG         
,LISTE_COMPY_FLAG                    
,dom_resdnt_flag
,RELATED_GRP_CUST_NO                 
,CREATE_ORG_NO                       
,UPDATE_ORG_NO                       
,ADMIN_AREA_CD                       
,OPERA_BIZ_SCOPE                     
,HOLD_TYPE_CD                        
,CREDIT_RATIN_CD                     
,CUST_TYPE_CD                        
,FIN_ORG_CD                          
,BASIC_DEPOSIT_ACCT_NO               
,BASIC_ACCT_OPENBANK_NAME            
,OPERA_STS_CD                        
,RGST_CY_CD                          
,RGST_CAP                            
,RGST_CAP_CURRCY_CD                  
,RGST_ADDR    
,offic_addr
,offic_tel                       
,RCVD_CAP                            
,RCVD_CAP_CURRCY_CD                  
,YEAR_BIZ_INCOME_CURRCY_CD           
,YEAR_BIZ_INCOME_UNIT_CD             
,YEAR_BIZ_INCOME                     
,LAST_YEAR_BIZ_INCOME_CURRCY_CD      
,LAST_YEAR_BIZ_INCOME_UNIT_CD        
,LAST_YEAR_BIZ_INCOME                
,DATA_SOURC                          
)
SELECT DATE '{ETL_TX_DT}'                                         AS data_dt                      --  数据日期
      ,A1.unifi_cust_no                                           AS unifi_cust_no                --  客户号
      ,REPLACE(A1.cust_name, ' ', '')                             AS cust_name                    --  客户中文名称
	  ,'对公'                                                     AS cust_class                   --  同业客户		                         
	  ,A1.iden_type_cd                                            AS iden_type_cd                 --  证件类型
      ,A1.iden_no                                                 AS iden_no                      --  证件号码
	  ,CASE WHEN B.unifi_socie_credit_no='' THEN B.unifi_socie_credit_no ELSE NULL END AS uscc    --  统一社会信用代码
      ,''                                                         AS organ_cd                     --  组织机构代码
	  ,NULL AS rgst_regis_no  -- 登记注册号
      ,DECODE(NVL(B.set_up_dt,''),'',NULL,CDM.UDF_DTCHK(B.set_up_dt,'{LEG_DATE}')) AS rgst_rgst_dt   -- 注册登记日期
	  ,B.biz_licen_no                                             AS indus_and_comme_biz_licen_no --  工商营业执照号
      ,CASE 
          WHEN NVL(B.biz_licen_vld_end_dt_term,'')='' THEN NULL
          WHEN UDF_DTCHK(SUBSTR(TO_CHAR(B.biz_licen_vld_end_dt_term),1,10),'0') = '0' THEN date '{LEG_DATE}'  -- 非法日期默认值V		  
          ELSE B.biz_licen_vld_end_dt_term
        END AS indus_and_comme_biz_licen_expir_dt                                                 --  工商营业执照到期日 
	  ,NULL AS sharehd_flag                                                                       -- 股东标志	
	  ,'' AS rural_city_flag -- 农村城市标志
      ,B.indus_direction AS indus_class_cd   --  行业类别代码 
      ,'156' as nation_cd -- 国籍代码
      ,CASE
          WHEN C.senior_execu_name <> '' AND C.senior_execu_name IS NOT NULL THEN C.senior_execu_name   
          ELSE NULL  
        END AS statu_rep_cust_name                                                 -- 对公客户法定代表人客户姓名
      ,CASE 
	      WHEN C.iden_type_cd='01' THEN '102' ELSE C.iden_type_cd
	    END AS statu_rep_iden_type_cd                                              -- 对公客户法定代表人身份证件类型 居民身份证
      ,C.IDEN_NO  AS statu_rep_iden_no                                             -- 对公客户法定代表人身份证件号码
      ,C.contact_phone_no as statu_rep_tel -- 法定代表人电话号码
	  ,DECODE(NVL(B.set_up_dt,''),'',NULL,CDM.UDF_DTCHK(B.set_up_dt,'{LEG_DATE}')) AS set_up_dt  --  成立日期	  
	  ,CASE WHEN NVL(B.ent_scale_cd,'')  in ('1','2','3','4') THEN B.ent_scale_cd ELSE NULL END AS ent_scale_cd  -- 企业规模代码
	  ,CASE WHEN B.liste_compy_type_cd ='2' OR B.liste_compy_type_cd IS NULL THEN '0' 
			ELSE '1' 
		END AS liste_compy_flag                                                     -- 上市公司标志
      ,''   as dom_resdnt_flag                                                      -- 境内居民标志
	  ,NULL AS related_grp_cust_no                                                  -- 关联集团客户号
	  ,A1.create_org AS create_org_no                                               -- 创建机构号
	  ,NULL AS update_org_no                                                        -- 更新机构号
	  ,SUBSTR(A1.iden_no,1,6) AS admin_area_cd                                      -- 行政区划代码
	  ,CASE
          WHEN B.opera_scope <> '' AND B.opera_scope IS NOT NULL THEN SUBSTR(B.opera_scope,1,300) 
          ELSE NULL  
        END  AS opera_biz_scope                                                     --  经营（业务）范围
	  ,'C01' AS hold_type_cd                                                        --  控股类型代码 
	  ,NULL  AS credit_ratin_cd                                                     --  信用评级代码
	  ,NULL  AS cust_type_cd                                                        --  客户类型代码
	  ,NULL  AS fin_org_cd                                                          --  金融机构代码
	  ,NULL  AS basic_deposit_acct_no                                               --  基本存款账号
	  ,NULL  AS basic_acct_openbank_name                                            --  基本账户开户行名称
	  
	  ,CASE WHEN B.shutdown_ent_flag='1' THEN '02' ELSE '01' END AS opera_sts_cd    --  经营状态代码
	  ,'156'                                AS rgst_cy_cd                           --  注册国家代码
	  ,B.rgst_cap                           AS rgst_cap                             --  注册资金
	  ,B.rgst_cap_currcy_cd                 AS rgst_cap_currcy_cd                   --  注册资金币种代码
	  ,NULL                                 AS rgst_addr                            --  注册地址
	  ,''                                   as offic_addr                           -- 办公地址
      ,''                                   as offic_tel                            -- 办公电话
	  ,b.rcvd_cap                           AS rcvd_cap                             --  实收资本
	  ,'156'                                AS rcvd_cap_currcy_cd                   --  实收资本币种代码
	  ,''                                   AS year_biz_income_currcy_cd            --  年营业收入币种代码
	  ,''                                   AS year_biz_income_unit_cd              --  年营业收入单位代码 01 元，02 千元，03 万元 
	  ,NULL                                 AS year_biz_income                      --  年营业收入
      ,''                                   AS last_year_biz_income_currcy_cd       --  上年营业收入币种
	  ,''                                   AS last_year_biz_income_unit_cd         --  上年营业收入单位代码 01 元，02 千元，03 万元
	  ,NULL                                 AS last_year_biz_income                 --  上年营业收入  
      ,A1.data_sourc AS date_sourcesd    --    数据来源
  FROM {CDM}.C_CUS_UNIFI_CUST_INF_PUB A1
 INNER JOIN {FDM}.F_PT_SCLN_DEBTS_CONVERTED_CUST_INF   B -- 众商贷债转客户信息表
    ON A1.src_sys_cust_no = B.cust_no
   AND b.start_dt <= DATE '{I_LAST_DT}' 
   AND b.end_dt > DATE '{I_LAST_DT}' 
  LEFT JOIN {FDM}.F_PT_SCLN_DEBTS_CONVERTED_STATU_REP_INF  C -- 法定代表人信息(CFSP002000101) 
    ON B.cust_no = C.cust_no
   AND C.start_dt <= DATE '{I_LAST_DT}' 
   AND C.end_dt > DATE '{I_LAST_DT}' 
   AND C.senior_execu_type_cd='0100'
 WHERE A1.data_dt= DATE '{ETL_TX_DT}' 
   AND A1.data_sourc='SCLN' 
;

-- V_STEP_DESC := '客户汇总';
CREATE TEMPORARY  TABLE {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP AS ( 
SELECT T.UNIFI_CUST_NO 
  FROM 
  (
  SELECT UNIFI_CUST_NO   FROM  {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_ECIF T1 -- ECIF对公    
  UNION ALL                 
  SELECT UNIFI_CUST_NO   FROM  {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_CRMS T2 -- 信贷―对公
  UNION ALL                 
  SELECT UNIFI_CUST_NO   FROM  {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_CRBS T3 -- 核心―对公
  UNION ALL                 
  SELECT UNIFI_CUST_NO   FROM  {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SILN T6 -- 微业贷
  UNION  ALL                
  SELECT UNIFI_CUST_NO  FROM  {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_NBMS  T7  -- 新票据
    UNION  ALL                
  SELECT UNIFI_CUST_NO  FROM  {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_FNDS_BB  T8  -- 新资金 本币 
  UNION  ALL                                              
  SELECT UNIFI_CUST_NO  FROM  {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_FNDS_WB  T9  -- 新资金 外币  
  UNION  ALL                
  SELECT UNIFI_CUST_NO FROM  {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SFLN  T10  -- 金企贷
  UNION ALL
  SELECT UNIFI_CUST_NO FROM  {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SCLN T11  -- 众商贷
  UNION ALL 
  SELECT UNIFI_CUST_NO FROM {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SCLN_ZZ T12 -- 众商贷债转
 )T
GROUP BY T.UNIFI_CUST_NO  
 ) 
 ;
INSERT INTO {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF 
SELECT DISTINCT DATE '{ETL_TX_DT}'            AS DATA_DATE                  -- 数据日期       
       ,T1.UNIFI_CUST_NO          AS UNIFI_CUST_NO         -- 客户号
	   ,CASE
            WHEN T21.CUST_NAME IS NOT NULL AND T21.CUST_NAME <> '' THEN T21.CUST_NAME -- 信贷
            WHEN T20.CUST_NAME IS NOT NULL AND T20.CUST_NAME <> '' THEN T20.CUST_NAME -- ECIF        
            WHEN T22.CUST_NAME IS NOT NULL AND T22.CUST_NAME <> '' THEN T22.CUST_NAME -- 核心     
            WHEN T24.CUST_NAME IS NOT NULL AND T24.CUST_NAME <> '' THEN T24.CUST_NAME -- 微业贷
            WHEN T25.CUST_NAME IS NOT NULL AND T25.CUST_NAME <> '' THEN T25.CUST_NAME -- 新票据
			WHEN T26.CUST_NAME IS NOT NULL AND T26.CUST_NAME <> '' THEN T26.CUST_NAME -- 新资金本币
            WHEN T27.CUST_NAME IS NOT NULL AND T27.CUST_NAME <> '' THEN T27.CUST_NAME -- 新资金外币
            WHEN T28.CUST_NAME IS NOT NULL AND T28.CUST_NAME <> '' THEN T28.CUST_NAME -- 金企贷
	        WHEN T29.CUST_NAME IS NOT NULL AND T29.CUST_NAME <> '' THEN T29.CUST_NAME -- 众商贷
	        WHEN T30.CUST_NAME IS NOT NULL AND T30.CUST_NAME <> '' THEN T30.CUST_NAME -- 众商贷债转
            ELSE ''
        END                 AS CUST_NAME          -- 客户名称
       ,CASE
            WHEN T21.CUST_CLASS IS NOT NULL AND T21.CUST_CLASS <> '' THEN T21.CUST_CLASS -- 信贷
            WHEN T20.CUST_CLASS IS NOT NULL AND T20.CUST_CLASS <> '' THEN T20.CUST_CLASS -- ecif
            WHEN T22.CUST_CLASS IS NOT NULL AND T22.CUST_CLASS <> '' THEN T22.CUST_CLASS -- 核心
            WHEN T24.CUST_CLASS IS NOT NULL AND T24.CUST_CLASS <> '' THEN T24.CUST_CLASS -- 微业贷
            WHEN T25.CUST_CLASS IS NOT NULL AND T25.CUST_CLASS <> '' THEN T25.CUST_CLASS -- 新票据
	        WHEN T26.CUST_CLASS IS NOT NULL AND T26.CUST_CLASS <> '' THEN T26.CUST_CLASS -- 新资金本币
            WHEN T27.CUST_CLASS IS NOT NULL AND T27.CUST_CLASS <> '' THEN T27.CUST_CLASS -- 新资金外币
            WHEN T28.CUST_CLASS IS NOT NULL AND T28.CUST_CLASS <> '' THEN T28.CUST_CLASS -- 金企贷
	        WHEN T29.CUST_CLASS IS NOT NULL AND T29.CUST_CLASS <> '' THEN T29.CUST_CLASS -- 众商贷
	        WHEN T30.CUST_CLASS IS NOT NULL AND T30.CUST_CLASS <> '' THEN T30.CUST_CLASS -- 众商贷债转
            ELSE '对公'
         END         AS CUST_CLASS                 -- 客户分类 政务、对公、同业
       ,CASE
	        WHEN T20.IDEN_TYPE_CD IN ('20100', '13700','20600', '20300','20500') THEN T20.IDEN_TYPE_CD -- ECIF对公 20100统一社会信用代码(236) 13700\20600组织机构代码证(21) 20500/20300营业执照(22) 
	        WHEN T21.IDEN_TYPE_CD IN ('20100', '13700','20600', '20300','20500') THEN T21.IDEN_TYPE_CD -- 信贷―对公
	        WHEN T22.IDEN_TYPE_CD IN ('20100', '13700','20600', '20300','20500') THEN T22.IDEN_TYPE_CD -- 核心―对公
            WHEN T20.IDEN_TYPE_CD IS NOT NULL AND T20.IDEN_TYPE_CD <> '' THEN T20.IDEN_TYPE_CD
            WHEN T21.IDEN_TYPE_CD IS NOT NULL AND T21.IDEN_TYPE_CD <> '' THEN T21.IDEN_TYPE_CD
            WHEN T22.IDEN_TYPE_CD IS NOT NULL AND T22.IDEN_TYPE_CD <> '' THEN T22.IDEN_TYPE_CD
            WHEN T24.IDEN_TYPE_CD IS NOT NULL AND T24.IDEN_TYPE_CD <> '' THEN T24.IDEN_TYPE_CD -- 微业贷
            WHEN T25.IDEN_TYPE_CD IS NOT NULL AND T25.IDEN_TYPE_CD <> '' THEN T25.IDEN_TYPE_CD  
	        WHEN T26.IDEN_TYPE_CD IS NOT NULL AND T26.IDEN_TYPE_CD <> '' THEN T26.IDEN_TYPE_CD -- 新资金本币
            WHEN T27.IDEN_TYPE_CD IS NOT NULL AND T27.IDEN_TYPE_CD <> '' THEN T27.IDEN_TYPE_CD -- 新资金
            WHEN T28.IDEN_TYPE_CD IS NOT NULL AND T28.IDEN_TYPE_CD <> '' THEN T28.IDEN_TYPE_CD -- 金企贷
	        WHEN T29.IDEN_TYPE_CD IS NOT NULL AND T29.IDEN_TYPE_CD <> '' THEN T29.IDEN_TYPE_CD -- 众商贷
	        WHEN T30.IDEN_TYPE_CD IS NOT NULL AND T30.IDEN_TYPE_CD <> '' THEN T30.IDEN_TYPE_CD -- 众商贷债转
            ELSE ''
         END AS IDEN_TYPE_CD                      -- 证件类型代码
       ,CASE
	        WHEN T20.IDEN_TYPE_CD IN ('20100', '13700','20600', '20500') THEN T20.IDEN_NO
            WHEN T21.IDEN_TYPE_CD IN ('20100', '13700','20600', '20500') THEN T21.IDEN_NO
            WHEN T22.IDEN_TYPE_CD IN ('20100', '13700','20600', '20500') THEN T22.IDEN_NO
	        
	        WHEN T20.IDEN_NO IS NOT NULL AND T20.IDEN_NO <> '' THEN T20.IDEN_NO
            WHEN T21.IDEN_NO IS NOT NULL AND T21.IDEN_NO <> '' THEN T21.IDEN_NO
            WHEN T22.IDEN_NO IS NOT NULL AND T22.IDEN_NO <> '' THEN T22.IDEN_NO
            WHEN T24.IDEN_NO IS NOT NULL AND T24.IDEN_NO <> '' THEN T24.IDEN_NO
            WHEN T25.IDEN_NO IS NOT NULL AND T25.IDEN_NO <> '' THEN T25.IDEN_NO 
	        WHEN T26.IDEN_NO IS NOT NULL AND T26.IDEN_NO <> '' THEN T26.IDEN_NO -- 新资金本币
            WHEN T27.IDEN_NO IS NOT NULL AND T27.IDEN_NO <> '' THEN T27.IDEN_NO -- 新资金外币
            WHEN T28.IDEN_NO IS NOT NULL AND T28.IDEN_NO <> '' THEN T28.IDEN_NO -- 金企贷
            WHEN T29.IDEN_NO IS NOT NULL AND T29.IDEN_NO <> '' THEN T29.IDEN_NO -- 众商贷
            WHEN T30.IDEN_NO IS NOT NULL AND T30.IDEN_NO <> '' THEN T30.IDEN_NO -- 众商贷债转
            ELSE ''
        END AS IDEN_NO -- 证件号码
       ,CASE
	        WHEN T20.USCC IS NOT NULL AND T20.USCC <> '' THEN T20.USCC
            WHEN T21.USCC IS NOT NULL AND T21.USCC <> '' THEN T21.USCC
            WHEN T24.USCC IS NOT NULL AND T24.USCC <> '' THEN T24.USCC
            WHEN T25.USCC IS NOT NULL AND T25.USCC <> '' THEN T25.USCC 
	        WHEN T26.USCC IS NOT NULL AND T26.USCC <> '' THEN T26.USCC -- 新资金本币
            WHEN T27.USCC IS NOT NULL AND T27.USCC <> '' THEN T27.USCC -- 新资金外币
            WHEN T28.USCC IS NOT NULL AND T28.USCC <> '' THEN T28.USCC -- 金企贷
            WHEN T29.USCC IS NOT NULL AND T29.USCC <> '' THEN T29.USCC -- 众商贷
            WHEN T30.USCC IS NOT NULL AND T30.USCC <> '' THEN T30.USCC -- 众商贷债转
            ELSE ''
         END AS USCC                                      -- 统一社会信用代码
       ,CASE
            WHEN T21.ORGAN_CD IS NOT NULL AND T21.ORGAN_CD <> '' THEN T21.ORGAN_CD
	        WHEN T20.ORGAN_CD IS NOT NULL AND T20.ORGAN_CD <> '' THEN T20.ORGAN_CD
            WHEN T24.ORGAN_CD IS NOT NULL AND T24.ORGAN_CD <> '' THEN T24.ORGAN_CD
            WHEN T25.ORGAN_CD IS NOT NULL AND T25.ORGAN_CD <> '' THEN T25.ORGAN_CD 
	        WHEN T26.ORGAN_CD IS NOT NULL AND T26.ORGAN_CD <> '' THEN T26.ORGAN_CD -- 新资金本币
            WHEN T27.ORGAN_CD IS NOT NULL AND T27.ORGAN_CD <> '' THEN T27.ORGAN_CD -- 新资金外币
            WHEN T28.ORGAN_CD IS NOT NULL AND T28.ORGAN_CD <> '' THEN T28.ORGAN_CD -- 金企贷
            WHEN T29.ORGAN_CD IS NOT NULL AND T29.ORGAN_CD <> '' THEN T29.ORGAN_CD -- 众商贷
            WHEN T30.ORGAN_CD IS NOT NULL AND T30.ORGAN_CD <> '' THEN T30.ORGAN_CD -- 众商贷债转
            ELSE ''
         END AS ORGAN_CD                                  -- 组织机构代码
	   ,NVL(T20.RGST_REGIS_NO,'') AS RGST_REGIS_NO                 -- 登记注册号
       ,CASE 
	       WHEN T21.RGST_RGST_DT IS NOT NULL AND T21.RGST_RGST_DT <> '' THEN T21.RGST_RGST_DT
           WHEN T24.RGST_RGST_DT IS NOT NULL AND T24.RGST_RGST_DT <> '' THEN T24.RGST_RGST_DT
           WHEN T28.RGST_RGST_DT IS NOT NULL AND T28.RGST_RGST_DT <> '' THEN T28.RGST_RGST_DT
           WHEN T29.RGST_RGST_DT IS NOT NULL AND T29.RGST_RGST_DT <> '' THEN T29.RGST_RGST_DT
           WHEN T30.RGST_RGST_DT IS NOT NULL AND T30.RGST_RGST_DT <> '' THEN T30.RGST_RGST_DT
		   ELSE DATE '{NULL_DATE}'
        END AS RGST_RGST_DT		   -- 注册登记日期
       ,CASE
           WHEN T21.INDUS_AND_COMME_BIZ_LICEN_NO IS NOT NULL AND T21.INDUS_AND_COMME_BIZ_LICEN_NO <> '' THEN T21.INDUS_AND_COMME_BIZ_LICEN_NO
	       WHEN T20.INDUS_AND_COMME_BIZ_LICEN_NO IS NOT NULL AND T20.INDUS_AND_COMME_BIZ_LICEN_NO <> '' THEN T20.INDUS_AND_COMME_BIZ_LICEN_NO
           WHEN T24.INDUS_AND_COMME_BIZ_LICEN_NO IS NOT NULL AND T24.INDUS_AND_COMME_BIZ_LICEN_NO <> '' THEN T24.INDUS_AND_COMME_BIZ_LICEN_NO
           WHEN T25.INDUS_AND_COMME_BIZ_LICEN_NO IS NOT NULL AND T25.INDUS_AND_COMME_BIZ_LICEN_NO <> '' THEN T25.INDUS_AND_COMME_BIZ_LICEN_NO -- 霍艳龙 20221220
           WHEN T26.INDUS_AND_COMME_BIZ_LICEN_NO IS NOT NULL AND T26.INDUS_AND_COMME_BIZ_LICEN_NO <> '' THEN T26.INDUS_AND_COMME_BIZ_LICEN_NO -- 新资金本币
           WHEN T27.INDUS_AND_COMME_BIZ_LICEN_NO IS NOT NULL AND T27.INDUS_AND_COMME_BIZ_LICEN_NO <> '' THEN T27.INDUS_AND_COMME_BIZ_LICEN_NO -- 新资金外币
	       WHEN T28.INDUS_AND_COMME_BIZ_LICEN_NO IS NOT NULL AND T28.INDUS_AND_COMME_BIZ_LICEN_NO <> '' THEN T28.INDUS_AND_COMME_BIZ_LICEN_NO -- 金企贷
           WHEN T29.INDUS_AND_COMME_BIZ_LICEN_NO IS NOT NULL AND T29.INDUS_AND_COMME_BIZ_LICEN_NO <> '' THEN T29.INDUS_AND_COMME_BIZ_LICEN_NO -- 众商贷
           WHEN T30.INDUS_AND_COMME_BIZ_LICEN_NO IS NOT NULL AND T30.INDUS_AND_COMME_BIZ_LICEN_NO <> '' THEN T30.INDUS_AND_COMME_BIZ_LICEN_NO -- 众商贷债转
           ELSE ''
        END AS INDUS_AND_COMME_BIZ_LICEN_NO              -- 工商营业执照号
       ,CASE
           WHEN T21.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT IS NOT NULL AND T21.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT<>'{LEG_DATE}' AND T21.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT<>DATE '{LEG_DATE}' THEN T21.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT
	       WHEN T20.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT IS NOT NULL AND T20.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT<>'{LEG_DATE}' AND T20.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT<>DATE '{LEG_DATE}' THEN T20.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT
           WHEN T24.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT IS NOT NULL AND T24.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT<>'{LEG_DATE}' AND T24.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT<>DATE '{LEG_DATE}' THEN T24.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT
           WHEN T25.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT IS NOT NULL AND T25.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT<>'{LEG_DATE}' AND T25.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT<>DATE '{LEG_DATE}' THEN T25.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT 
           WHEN T26.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT IS NOT NULL AND T26.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT<>'{LEG_DATE}' AND T26.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT<>DATE '{LEG_DATE}' THEN T26.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT -- 新资金本币
           WHEN T27.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT IS NOT NULL AND T27.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT<>'{LEG_DATE}' AND T27.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT<>DATE '{LEG_DATE}' THEN T27.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT -- 新资金外币    
	       WHEN T28.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT IS NOT NULL AND T28.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT<>'{LEG_DATE}' AND T28.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT<>DATE '{LEG_DATE}' THEN T28.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT -- 金企贷
           WHEN T29.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT IS NOT NULL AND T29.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT<>'{LEG_DATE}' AND T29.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT<>DATE '{LEG_DATE}' THEN T29.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT -- 众商贷
           WHEN T30.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT IS NOT NULL AND T30.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT<>'{LEG_DATE}' AND T30.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT<>DATE '{LEG_DATE}' THEN T30.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT -- 众商贷债转
           
		   WHEN T21.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT IS NOT NULL  THEN T21.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT
		   WHEN T20.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT IS NOT NULL  THEN T20.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT
		   WHEN T24.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT IS NOT NULL  THEN T24.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT
		   WHEN T25.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT IS NOT NULL  THEN T25.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT
		   WHEN T26.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT IS NOT NULL  THEN T26.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT
		   WHEN T27.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT IS NOT NULL  THEN T27.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT
		   WHEN T28.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT IS NOT NULL  THEN T28.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT
		   WHEN T29.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT IS NOT NULL  THEN T29.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT
		   WHEN T30.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT IS NOT NULL  THEN T30.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT

		   ELSE DATE '{NULL_DATE}'
        END AS INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT        -- 工商营业执照到期日
	   ,CASE
           WHEN T20.SHAREHD_FLAG IS NOT NULL AND T20.SHAREHD_FLAG <> ''  THEN T20.SHAREHD_FLAG -- ECIF
           WHEN T21.SHAREHD_FLAG IS NOT NULL AND T21.SHAREHD_FLAG  <> '' THEN T21.SHAREHD_FLAG
           WHEN T24.SHAREHD_FLAG IS NOT NULL AND T24.SHAREHD_FLAG <> '' THEN T24.SHAREHD_FLAG -- 微业贷
           WHEN T26.SHAREHD_FLAG IS NOT NULL AND T26.SHAREHD_FLAG <> '' THEN T26.SHAREHD_FLAG
           WHEN T27.SHAREHD_FLAG IS NOT NULL AND T27.SHAREHD_FLAG <> '' THEN T27.SHAREHD_FLAG
		   WHEN T28.SHAREHD_FLAG IS NOT NULL AND T28.SHAREHD_FLAG <> '' THEN T28.SHAREHD_FLAG
           ELSE ''
         END AS SHAREHD_FLAG      -- 股东标志
	   ,CASE
           WHEN T21.RURAL_CITY_FLAG IS NOT NULL AND T21.RURAL_CITY_FLAG <> '' THEN T21.RURAL_CITY_FLAG 
           WHEN T20.RURAL_CITY_FLAG IS NOT NULL AND T20.RURAL_CITY_FLAG <> '' THEN T20.RURAL_CITY_FLAG
           WHEN T24.RURAL_CITY_FLAG IS NOT NULL AND T24.RURAL_CITY_FLAG <> '' THEN T24.RURAL_CITY_FLAG
           WHEN T26.RURAL_CITY_FLAG IS NOT NULL AND T26.RURAL_CITY_FLAG <> '' THEN T26.RURAL_CITY_FLAG
           WHEN T27.RURAL_CITY_FLAG IS NOT NULL AND T27.RURAL_CITY_FLAG <> '' THEN T27.RURAL_CITY_FLAG
		   WHEN T28.RURAL_CITY_FLAG IS NOT NULL AND T28.RURAL_CITY_FLAG <> '' THEN T28.RURAL_CITY_FLAG
		   WHEN T29.RURAL_CITY_FLAG IS NOT NULL AND T29.RURAL_CITY_FLAG <> '' THEN T29.RURAL_CITY_FLAG
		   WHEN T30.RURAL_CITY_FLAG IS NOT NULL AND T30.RURAL_CITY_FLAG <> '' THEN T30.RURAL_CITY_FLAG
           ELSE ''
         END AS RURAL_CITY_FLAG      -- 农村城市标志
       ,CASE 
	         WHEN T20.INDUS_CLASS_CD IS NOT NULL AND T20.INDUS_CLASS_CD <>'' THEN T20.INDUS_CLASS_CD
           WHEN T21.INDUS_CLASS_CD IS NOT NULL AND T21.INDUS_CLASS_CD <>'' THEN T21.INDUS_CLASS_CD -- 信贷
           WHEN T22.INDUS_CLASS_CD IS NOT NULL AND T22.INDUS_CLASS_CD <>'' THEN T22.INDUS_CLASS_CD -- 核心
           WHEN T24.INDUS_CLASS_CD IS NOT NULL AND T24.INDUS_CLASS_CD <>'' THEN T24.INDUS_CLASS_CD
		   WHEN T25.INDUS_CLASS_CD IS NOT NULL AND T25.INDUS_CLASS_CD <>'' THEN T25.INDUS_CLASS_CD
           WHEN T26.INDUS_CLASS_CD IS NOT NULL AND T26.INDUS_CLASS_CD <>'' THEN T26.INDUS_CLASS_CD			 
           WHEN T28.INDUS_CLASS_CD IS NOT NULL AND T28.INDUS_CLASS_CD <>'' THEN T28.INDUS_CLASS_CD   
           WHEN T29.INDUS_CLASS_CD IS NOT NULL AND T29.INDUS_CLASS_CD <>'' THEN T29.INDUS_CLASS_CD
           WHEN T30.INDUS_CLASS_CD IS NOT NULL AND T30.INDUS_CLASS_CD <>'' THEN T30.INDUS_CLASS_CD
           ELSE '' 
	    END AS INDUS_CLASS_CD                            -- 行业类别代码
	   ,CASE 
           WHEN T22.NATION_CD IS NOT NULL AND T22.NATION_CD <>'' THEN T22.NATION_CD -- 核心
           WHEN T20.NATION_CD IS NOT NULL AND T20.NATION_CD <>'' THEN T20.NATION_CD
           WHEN T21.NATION_CD IS NOT NULL AND T21.NATION_CD <>'' THEN T21.NATION_CD -- 信贷
           WHEN T24.NATION_CD IS NOT NULL AND T24.NATION_CD <>'' THEN T24.NATION_CD
           WHEN T25.NATION_CD IS NOT NULL AND T25.NATION_CD <>'' THEN T25.NATION_CD
           WHEN T26.NATION_CD IS NOT NULL AND T26.NATION_CD <>'' THEN T26.NATION_CD
           WHEN T28.NATION_CD IS NOT NULL AND T28.NATION_CD <>'' THEN T28.NATION_CD
           WHEN T29.NATION_CD IS NOT NULL AND T29.NATION_CD <>'' THEN T29.NATION_CD
           WHEN T30.NATION_CD IS NOT NULL AND T30.NATION_CD <>'' THEN T30.NATION_CD
               ELSE ''
        END AS NATION_CD -- 国籍代码
       ,CASE
	         WHEN T21.STATU_REP_CUST_NAME IS NOT NULL AND T21.STATU_REP_CUST_NAME <>'' THEN T21.STATU_REP_CUST_NAME
           WHEN T20.STATU_REP_CUST_NAME IS NOT NULL AND T20.STATU_REP_CUST_NAME <>'' THEN T20.STATU_REP_CUST_NAME
           WHEN T22.STATU_REP_CUST_NAME IS NOT NULL AND T22.STATU_REP_CUST_NAME <>'' THEN T22.STATU_REP_CUST_NAME
           WHEN T24.STATU_REP_CUST_NAME IS NOT NULL AND T24.STATU_REP_CUST_NAME <>'' THEN T24.STATU_REP_CUST_NAME
           WHEN T26.STATU_REP_CUST_NAME IS NOT NULL AND T26.STATU_REP_CUST_NAME <>'' THEN T26.STATU_REP_CUST_NAME 
           WHEN T27.STATU_REP_CUST_NAME IS NOT NULL AND T27.STATU_REP_CUST_NAME <>'' THEN T27.STATU_REP_CUST_NAME		  
           WHEN T28.STATU_REP_CUST_NAME IS NOT NULL AND T28.STATU_REP_CUST_NAME <>'' THEN T28.STATU_REP_CUST_NAME         
           WHEN T29.STATU_REP_CUST_NAME IS NOT NULL AND T29.STATU_REP_CUST_NAME <>'' THEN T29.STATU_REP_CUST_NAME 
           WHEN T30.STATU_REP_CUST_NAME IS NOT NULL AND T30.STATU_REP_CUST_NAME <>'' THEN T30.STATU_REP_CUST_NAME 
           ELSE '' 
		END AS  STATU_REP_CUST_NAME -- 法定代表人客户姓名
      ,CASE    
	         WHEN T21.STATU_REP_IDEN_TYPE_CD IS NOT NULL AND T21.STATU_REP_IDEN_TYPE_CD <>'' THEN T21.STATU_REP_IDEN_TYPE_CD
           WHEN T20.STATU_REP_IDEN_TYPE_CD IS NOT NULL AND T20.STATU_REP_IDEN_TYPE_CD <>'' THEN T20.STATU_REP_IDEN_TYPE_CD
           WHEN T22.STATU_REP_IDEN_TYPE_CD IS NOT NULL AND T22.STATU_REP_IDEN_TYPE_CD <>'' THEN T22.STATU_REP_IDEN_TYPE_CD
           WHEN T24.STATU_REP_IDEN_TYPE_CD IS NOT NULL AND T24.STATU_REP_IDEN_TYPE_CD <>'' THEN T24.STATU_REP_IDEN_TYPE_CD
		       WHEN T26.STATU_REP_IDEN_TYPE_CD IS NOT NULL AND T26.STATU_REP_IDEN_TYPE_CD <>'' THEN T26.STATU_REP_IDEN_TYPE_CD
           WHEN T27.STATU_REP_IDEN_TYPE_CD IS NOT NULL AND T27.STATU_REP_IDEN_TYPE_CD <>'' THEN T27.STATU_REP_IDEN_TYPE_CD
           WHEN T28.STATU_REP_IDEN_TYPE_CD IS NOT NULL AND T28.STATU_REP_IDEN_TYPE_CD <>'' THEN T28.STATU_REP_IDEN_TYPE_CD
           WHEN T29.STATU_REP_IDEN_TYPE_CD IS NOT NULL AND T29.STATU_REP_IDEN_TYPE_CD <>'' THEN T29.STATU_REP_IDEN_TYPE_CD
           WHEN T30.STATU_REP_IDEN_TYPE_CD IS NOT NULL AND T30.STATU_REP_IDEN_TYPE_CD <>'' THEN T30.STATU_REP_IDEN_TYPE_CD
           ELSE '' 
		 END AS  STATU_REP_IDEN_TYPE_CD -- 法定代表人证件类型代码
      ,CASE    
	         WHEN T21.STATU_REP_IDEN_NO IS NOT NULL AND T21.STATU_REP_IDEN_NO <>'' THEN T21.STATU_REP_IDEN_NO
           WHEN T20.STATU_REP_IDEN_NO IS NOT NULL AND T20.STATU_REP_IDEN_NO <>'' THEN T20.STATU_REP_IDEN_NO
           WHEN T22.STATU_REP_IDEN_NO IS NOT NULL AND T22.STATU_REP_IDEN_NO <>'' THEN T22.STATU_REP_IDEN_NO
           WHEN T24.STATU_REP_IDEN_NO IS NOT NULL AND T24.STATU_REP_IDEN_NO <>'' THEN T24.STATU_REP_IDEN_NO
           WHEN T26.STATU_REP_IDEN_NO IS NOT NULL AND T26.STATU_REP_IDEN_NO <>'' THEN T26.STATU_REP_IDEN_NO
           WHEN T27.STATU_REP_IDEN_NO IS NOT NULL AND T27.STATU_REP_IDEN_NO <>'' THEN T27.STATU_REP_IDEN_NO 		  
           WHEN T28.STATU_REP_IDEN_NO IS NOT NULL AND T28.STATU_REP_IDEN_NO <>'' THEN T28.STATU_REP_IDEN_NO          
           WHEN T29.STATU_REP_IDEN_NO IS NOT NULL AND T29.STATU_REP_IDEN_NO <>'' THEN T29.STATU_REP_IDEN_NO
           WHEN T30.STATU_REP_IDEN_NO IS NOT NULL AND T30.STATU_REP_IDEN_NO <>'' THEN T30.STATU_REP_IDEN_NO
           ELSE '' 
		END AS  STATU_REP_IDEN_NO -- 法定代表人证件号码
      ,CASE
           WHEN T22.statu_rep_tel IS NOT NULL AND T22.statu_rep_tel <>'' THEN T22.statu_rep_tel
           WHEN T21.statu_rep_tel IS NOT NULL AND T21.statu_rep_tel <>'' THEN T21.statu_rep_tel
           WHEN T20.statu_rep_tel IS NOT NULL AND T20.statu_rep_tel <>'' THEN T20.statu_rep_tel
           WHEN T24.statu_rep_tel IS NOT NULL AND T24.statu_rep_tel <>'' THEN T24.statu_rep_tel
           WHEN T26.statu_rep_tel IS NOT NULL AND T26.statu_rep_tel <>'' THEN T26.statu_rep_tel
           WHEN T27.statu_rep_tel IS NOT NULL AND T27.statu_rep_tel <>'' THEN T27.statu_rep_tel
           WHEN T28.statu_rep_tel IS NOT NULL AND T28.statu_rep_tel <>'' THEN T28.statu_rep_tel
           WHEN T29.statu_rep_tel IS NOT NULL AND T29.statu_rep_tel <>'' THEN T29.statu_rep_tel
           WHEN T30.statu_rep_tel IS NOT NULL AND T30.statu_rep_tel <>'' THEN T30.statu_rep_tel
           ELSE ''
        END as statu_rep_tel -- 法定代表人电话号码	  
	  ,CASE    
		  WHEN T21.SET_UP_DT IS NOT NULL AND T21.SET_UP_DT<>'{LEG_DATE}' AND T21.SET_UP_DT<>DATE '{LEG_DATE}' THEN T21.SET_UP_DT
		  WHEN T20.SET_UP_DT IS NOT NULL AND T20.SET_UP_DT<>'{LEG_DATE}' AND T20.SET_UP_DT<>DATE '{LEG_DATE}' THEN T20.SET_UP_DT
		  WHEN T22.SET_UP_DT IS NOT NULL AND T22.SET_UP_DT<>'{LEG_DATE}' AND T22.SET_UP_DT<>DATE '{LEG_DATE}' THEN T22.SET_UP_DT
		  WHEN T24.SET_UP_DT IS NOT NULL AND T24.SET_UP_DT<>'{LEG_DATE}' AND T24.SET_UP_DT<>DATE '{LEG_DATE}' THEN T24.SET_UP_DT
		  WHEN T26.SET_UP_DT IS NOT NULL AND T26.SET_UP_DT<>'{LEG_DATE}' AND T26.SET_UP_DT<>DATE '{LEG_DATE}' THEN T26.SET_UP_DT
		  WHEN T27.SET_UP_DT IS NOT NULL AND T27.SET_UP_DT<>'{LEG_DATE}' AND T27.SET_UP_DT<>DATE '{LEG_DATE}' THEN T27.SET_UP_DT   		  
		  WHEN T28.SET_UP_DT IS NOT NULL AND T28.SET_UP_DT<>'{LEG_DATE}' AND T28.SET_UP_DT<>DATE '{LEG_DATE}' THEN T28.SET_UP_DT         
		  WHEN T29.SET_UP_DT IS NOT NULL AND T29.SET_UP_DT<>'{LEG_DATE}' AND T29.SET_UP_DT<>DATE '{LEG_DATE}' THEN T29.SET_UP_DT
		  WHEN T30.SET_UP_DT IS NOT NULL AND T30.SET_UP_DT<>'{LEG_DATE}' AND T30.SET_UP_DT<>DATE '{LEG_DATE}' THEN T30.SET_UP_DT
		  WHEN T21.SET_UP_DT IS NOT NULL THEN T21.SET_UP_DT
		  WHEN T20.SET_UP_DT IS NOT NULL THEN T20.SET_UP_DT
		  WHEN T22.SET_UP_DT IS NOT NULL THEN T22.SET_UP_DT
		  WHEN T24.SET_UP_DT IS NOT NULL THEN T24.SET_UP_DT
		  WHEN T26.SET_UP_DT IS NOT NULL THEN T26.SET_UP_DT
		  WHEN T27.SET_UP_DT IS NOT NULL THEN T27.SET_UP_DT
		  WHEN T28.SET_UP_DT IS NOT NULL THEN T28.SET_UP_DT
		  WHEN T29.SET_UP_DT IS NOT NULL THEN T29.SET_UP_DT
		  WHEN T30.SET_UP_DT IS NOT NULL THEN T30.SET_UP_DT
		  ELSE DATE '{NULL_DATE}'
		END AS SET_UP_DT                                 -- 成立日期
		
	  ,CASE 
		   WHEN T21.ENT_SCALE_CD IS NOT NULL AND T21.ENT_SCALE_CD <>'' THEN T21.ENT_SCALE_CD 
		   WHEN T20.ENT_SCALE_CD IS NOT NULL AND T20.ENT_SCALE_CD <>'' THEN T20.ENT_SCALE_CD
		   WHEN T22.ENT_SCALE_CD IS NOT NULL AND T22.ENT_SCALE_CD <>'' THEN T22.ENT_SCALE_CD
		   WHEN T24.ENT_SCALE_CD IS NOT NULL AND T24.ENT_SCALE_CD <>'' THEN T24.ENT_SCALE_CD
		   WHEN T25.ENT_SCALE_CD IS NOT NULL AND T25.ENT_SCALE_CD <>'' THEN T25.ENT_SCALE_CD
		   WHEN T26.ENT_SCALE_CD IS NOT NULL AND T26.ENT_SCALE_CD <>'' THEN T26.ENT_SCALE_CD 
		   WHEN T27.ENT_SCALE_CD IS NOT NULL AND T27.ENT_SCALE_CD <>'' THEN T27.ENT_SCALE_CD			 
		   WHEN T28.ENT_SCALE_CD IS NOT NULL AND T28.ENT_SCALE_CD <>'' THEN T28.ENT_SCALE_CD  
		   WHEN T29.ENT_SCALE_CD IS NOT NULL AND T29.ENT_SCALE_CD <>'' THEN T29.ENT_SCALE_CD 
		   WHEN T30.ENT_SCALE_CD IS NOT NULL AND T30.ENT_SCALE_CD <>'' THEN T30.ENT_SCALE_CD 
		   ELSE '' 
	    END AS ENT_SCALE_CD                              -- 企业规模代码
      ,CASE WHEN T21.MYBANK_AFFIR_MINOR_ENT_FLAG IS NOT NULL THEN T21.MYBANK_AFFIR_MINOR_ENT_FLAG
	        ELSE ''
		END AS MYBANK_AFFIR_MINOR_ENT_FLAG
	  ,CASE    
	       WHEN T21.LISTE_COMPY_FLAG IS NOT NULL AND T21.LISTE_COMPY_FLAG <>'' THEN T21.LISTE_COMPY_FLAG
           WHEN T20.LISTE_COMPY_FLAG IS NOT NULL AND T20.LISTE_COMPY_FLAG <>'' THEN T20.LISTE_COMPY_FLAG
           WHEN T24.LISTE_COMPY_FLAG IS NOT NULL AND T24.LISTE_COMPY_FLAG <>'' THEN T24.LISTE_COMPY_FLAG          
           WHEN T28.LISTE_COMPY_FLAG IS NOT NULL AND T28.LISTE_COMPY_FLAG <>'' THEN T28.LISTE_COMPY_FLAG          
           WHEN T29.LISTE_COMPY_FLAG IS NOT NULL AND T29.LISTE_COMPY_FLAG <>'' THEN T29.LISTE_COMPY_FLAG
           WHEN T30.LISTE_COMPY_FLAG IS NOT NULL AND T30.LISTE_COMPY_FLAG <>'' THEN T30.LISTE_COMPY_FLAG
           ELSE '' 
		END AS LISTE_COMPY_FLAG                          -- 上市公司标志
      ,CASE    
           WHEN T22.dom_resdnt_flag IS NOT NULL AND T22.dom_resdnt_flag <>'' THEN T22.dom_resdnt_flag
           WHEN T21.dom_resdnt_flag IS NOT NULL AND T21.dom_resdnt_flag <>'' THEN T21.dom_resdnt_flag
           WHEN T20.dom_resdnt_flag IS NOT NULL AND T20.dom_resdnt_flag <>'' THEN T20.dom_resdnt_flag
           WHEN T24.dom_resdnt_flag IS NOT NULL AND T24.dom_resdnt_flag <>'' THEN T24.dom_resdnt_flag
           WHEN T26.dom_resdnt_flag IS NOT NULL AND T26.dom_resdnt_flag <>'' THEN T26.dom_resdnt_flag
           WHEN T27.dom_resdnt_flag IS NOT NULL AND T27.dom_resdnt_flag <>'' THEN T27.dom_resdnt_flag
           WHEN T28.dom_resdnt_flag IS NOT NULL AND T28.dom_resdnt_flag <>'' THEN T28.dom_resdnt_flag
           WHEN T29.dom_resdnt_flag IS NOT NULL AND T29.dom_resdnt_flag <>'' THEN T29.dom_resdnt_flag
           WHEN T30.dom_resdnt_flag IS NOT NULL AND T30.dom_resdnt_flag <>'' THEN T30.dom_resdnt_flag
           ELSE ''
        END as dom_resdnt_flag -- 境内居民标志
      ,CASE
	       WHEN T21.RELATED_GRP_CUST_NO IS NOT NULL THEN T21.RELATED_GRP_CUST_NO
           WHEN T20.RELATED_GRP_CUST_NO IS NOT NULL THEN T20.RELATED_GRP_CUST_NO
           ELSE '' 
		END AS RELATED_GRP_CUST_NO                       -- 关联集团客户号
      ,CASE 
	         WHEN T21.CREATE_ORG_NO IS NOT NULL AND T21.CREATE_ORG_NO <> '' THEN T21.CREATE_ORG_NO -- 信贷
           WHEN T20.CREATE_ORG_NO IS NOT NULL AND T20.CREATE_ORG_NO <> '' THEN T20.CREATE_ORG_NO -- ecif
           WHEN T22.CREATE_ORG_NO IS NOT NULL AND T22.CREATE_ORG_NO <> '' THEN T22.CREATE_ORG_NO -- 核心
           WHEN T24.CREATE_ORG_NO IS NOT NULL AND T24.CREATE_ORG_NO <> '' THEN T24.CREATE_ORG_NO -- 微业贷
           WHEN T25.CREATE_ORG_NO IS NOT NULL AND T25.CREATE_ORG_NO <> '' THEN T25.CREATE_ORG_NO -- NBMS
	         WHEN T26.CREATE_ORG_NO IS NOT NULL AND T26.CREATE_ORG_NO <> '' THEN T26.CREATE_ORG_NO -- 新资金本币
           WHEN T27.CREATE_ORG_NO IS NOT NULL AND T27.CREATE_ORG_NO <> '' THEN T27.CREATE_ORG_NO -- 新资金外币
           WHEN T28.CREATE_ORG_NO IS NOT NULL AND T28.CREATE_ORG_NO <> '' THEN T28.CREATE_ORG_NO -- 金企贷
	         WHEN T29.CREATE_ORG_NO IS NOT NULL AND T29.CREATE_ORG_NO <> '' THEN T29.CREATE_ORG_NO -- 众商贷
	         WHEN T30.CREATE_ORG_NO IS NOT NULL AND T30.CREATE_ORG_NO <> '' THEN T30.CREATE_ORG_NO -- 众商贷债转
          ELSE '' 
	   END AS CREATE_ORG_NO  -- 创建机构号
	  ,CASE WHEN T21.UPDATE_ORG_NO IS NOT NULL AND T21.UPDATE_ORG_NO <>'' THEN T21.UPDATE_ORG_NO
          WHEN T20.UPDATE_ORG_NO IS NOT NULL AND T20.UPDATE_ORG_NO <>'' THEN T20.UPDATE_ORG_NO	
          WHEN T22.UPDATE_ORG_NO IS NOT NULL AND T22.UPDATE_ORG_NO <>'' THEN T22.UPDATE_ORG_NO	
			ELSE '' 
		END AS UPDATE_ORG_NO  -- 更新机构号
      ,CASE 
	         WHEN T20.ADMIN_AREA_CD IS NOT NULL AND T20.ADMIN_AREA_CD <>'' THEN T20.ADMIN_AREA_CD
           WHEN T21.ADMIN_AREA_CD IS NOT NULL AND T21.ADMIN_AREA_CD <>'' THEN T21.ADMIN_AREA_CD
           WHEN T22.ADMIN_AREA_CD IS NOT NULL AND T22.ADMIN_AREA_CD <>'' THEN T22.ADMIN_AREA_CD
           WHEN T24.ADMIN_AREA_CD IS NOT NULL AND T24.ADMIN_AREA_CD <>'' THEN T24.ADMIN_AREA_CD
           WHEN T26.ADMIN_AREA_CD IS NOT NULL AND T26.ADMIN_AREA_CD <>'' THEN T26.ADMIN_AREA_CD
           WHEN T27.ADMIN_AREA_CD IS NOT NULL AND T27.ADMIN_AREA_CD <>'' THEN T27.ADMIN_AREA_CD
           WHEN T28.ADMIN_AREA_CD IS NOT NULL AND T28.ADMIN_AREA_CD <>'' THEN T28.ADMIN_AREA_CD
           WHEN T29.ADMIN_AREA_CD IS NOT NULL AND T29.ADMIN_AREA_CD <>'' THEN T29.ADMIN_AREA_CD
           WHEN T30.ADMIN_AREA_CD IS NOT NULL AND T30.ADMIN_AREA_CD <>'' THEN T30.ADMIN_AREA_CD
           ELSE '' 
		END AS  ADMIN_AREA_CD                         -- 行政区划代码
    ,CASE 
	       WHEN T21.OPERA_BIZ_SCOPE IS NOT NULL AND T21.OPERA_BIZ_SCOPE <>'' THEN T21.OPERA_BIZ_SCOPE
         WHEN T20.OPERA_BIZ_SCOPE IS NOT NULL AND T20.OPERA_BIZ_SCOPE <>'' THEN T20.OPERA_BIZ_SCOPE
         WHEN T22.OPERA_BIZ_SCOPE IS NOT NULL AND T22.OPERA_BIZ_SCOPE <>'' THEN T22.OPERA_BIZ_SCOPE
         WHEN T24.OPERA_BIZ_SCOPE IS NOT NULL AND T24.OPERA_BIZ_SCOPE <>'' THEN T24.OPERA_BIZ_SCOPE
         WHEN T28.OPERA_BIZ_SCOPE IS NOT NULL AND T28.OPERA_BIZ_SCOPE <>'' THEN T28.OPERA_BIZ_SCOPE
         WHEN T29.OPERA_BIZ_SCOPE IS NOT NULL AND T29.OPERA_BIZ_SCOPE <>'' THEN T29.OPERA_BIZ_SCOPE
         WHEN T30.OPERA_BIZ_SCOPE IS NOT NULL AND T30.OPERA_BIZ_SCOPE <>'' THEN T30.OPERA_BIZ_SCOPE
         ELSE '' 
	  END AS OPERA_BIZ_SCOPE                           -- 经营业务范围
    ,CASE 
	       WHEN T21.HOLD_TYPE_CD IS NOT NULL AND T21.HOLD_TYPE_CD <>'' THEN T21.HOLD_TYPE_CD 
         WHEN T20.HOLD_TYPE_CD IS NOT NULL AND T20.HOLD_TYPE_CD <>'' THEN T20.HOLD_TYPE_CD 
         WHEN T24.HOLD_TYPE_CD IS NOT NULL AND T24.HOLD_TYPE_CD <>'' THEN T24.HOLD_TYPE_CD 
		     WHEN T26.HOLD_TYPE_CD IS NOT NULL AND T26.HOLD_TYPE_CD <>'' THEN T26.HOLD_TYPE_CD
         WHEN T28.HOLD_TYPE_CD IS NOT NULL AND T28.HOLD_TYPE_CD <>'' THEN T28.HOLD_TYPE_CD
         WHEN T29.HOLD_TYPE_CD IS NOT NULL AND T29.HOLD_TYPE_CD <>'' THEN T29.HOLD_TYPE_CD
         WHEN T30.HOLD_TYPE_CD IS NOT NULL AND T30.HOLD_TYPE_CD <>'' THEN T30.HOLD_TYPE_CD
         ELSE '' 
	  END AS HOLD_TYPE_CD                              -- 控股类型代码
      ,CASE    
	        WHEN T21.CREDIT_RATIN_CD IS NOT NULL AND T21.CREDIT_RATIN_CD <>'' THEN T21.CREDIT_RATIN_CD
          WHEN T20.CREDIT_RATIN_CD IS NOT NULL AND T20.CREDIT_RATIN_CD <>'' THEN T20.CREDIT_RATIN_CD
          WHEN T24.CREDIT_RATIN_CD IS NOT NULL AND T24.CREDIT_RATIN_CD <>'' THEN T24.CREDIT_RATIN_CD 
		      WHEN T26.CREDIT_RATIN_CD IS NOT NULL AND T26.CREDIT_RATIN_CD <>'' THEN T26.CREDIT_RATIN_CD
          WHEN T27.CREDIT_RATIN_CD IS NOT NULL AND T27.CREDIT_RATIN_CD <>'' THEN T27.CREDIT_RATIN_CD
          WHEN T28.CREDIT_RATIN_CD IS NOT NULL AND T28.CREDIT_RATIN_CD <>'' THEN T28.CREDIT_RATIN_CD
          WHEN T29.CREDIT_RATIN_CD IS NOT NULL AND T29.CREDIT_RATIN_CD <>'' THEN T29.CREDIT_RATIN_CD
          WHEN T30.CREDIT_RATIN_CD IS NOT NULL AND T30.CREDIT_RATIN_CD <>'' THEN T30.CREDIT_RATIN_CD
          ELSE '' 
	   END AS CREDIT_RATIN_CD                           -- 信用评级代码
    ,CASE 
	       WHEN T21.CUST_TYPE_CD IS NOT NULL AND T21.CUST_TYPE_CD <> '' THEN T21.CUST_TYPE_CD
         WHEN T20.CUST_TYPE_CD IS NOT NULL AND T20.CUST_TYPE_CD <> '' THEN T20.CUST_TYPE_CD 
         WHEN T22.CUST_TYPE_CD IS NOT NULL AND T22.CUST_TYPE_CD <> '' THEN T22.CUST_TYPE_CD 
         WHEN T24.CUST_TYPE_CD IS NOT NULL AND T24.CUST_TYPE_CD <> '' THEN T24.CUST_TYPE_CD 
		     WHEN T26.CUST_TYPE_CD IS NOT NULL AND T26.CUST_TYPE_CD <> '' THEN T26.CUST_TYPE_CD 
         WHEN T27.CUST_TYPE_CD IS NOT NULL AND T27.CUST_TYPE_CD <> '' THEN T27.CUST_TYPE_CD  
         WHEN T28.CUST_TYPE_CD IS NOT NULL AND T28.CUST_TYPE_CD <> '' THEN T28.CUST_TYPE_CD  
         WHEN T29.CUST_TYPE_CD IS NOT NULL AND T29.CUST_TYPE_CD <> '' THEN T29.CUST_TYPE_CD 
         WHEN T30.CUST_TYPE_CD IS NOT NULL AND T30.CUST_TYPE_CD <> '' THEN T30.CUST_TYPE_CD 
         ELSE '' 
	  END AS CUST_TYPE_CD                              -- 客户类型代码
    ,CASE    
	       WHEN T21.FIN_ORG_CD IS NOT NULL AND T21.FIN_ORG_CD <>'' THEN T21.FIN_ORG_CD
         WHEN T20.FIN_ORG_CD IS NOT NULL AND T20.FIN_ORG_CD <>'' THEN T20.FIN_ORG_CD
		     WHEN T26.FIN_ORG_CD IS NOT NULL AND T26.FIN_ORG_CD <>'' THEN T26.FIN_ORG_CD
         WHEN T27.FIN_ORG_CD IS NOT NULL AND T27.FIN_ORG_CD <>'' THEN T27.FIN_ORG_CD
         ELSE '' 
	  END AS FIN_ORG_CD                                -- 金融机构代码
    ,CASE    
	       WHEN T21.unit_lvl_cd IS NOT NULL AND T21.unit_lvl_cd <>'' THEN T21.unit_lvl_cd
         ELSE '' 
	  END AS unit_lvl_cd                                -- 单位级别代码
    ,CASE   
	       WHEN T20.BASIC_DEPOSIT_ACCT_NO IS NOT NULL AND T20.BASIC_DEPOSIT_ACCT_NO <>'' THEN T20.BASIC_DEPOSIT_ACCT_NO -- ecif
	       WHEN T22.BASIC_DEPOSIT_ACCT_NO IS NOT NULL AND T22.BASIC_DEPOSIT_ACCT_NO <>'' THEN T22.BASIC_DEPOSIT_ACCT_NO -- 核心
         WHEN T21.BASIC_DEPOSIT_ACCT_NO IS NOT NULL AND T21.BASIC_DEPOSIT_ACCT_NO <>'' THEN T21.BASIC_DEPOSIT_ACCT_NO -- 信贷
         WHEN T24.BASIC_DEPOSIT_ACCT_NO IS NOT NULL AND T24.BASIC_DEPOSIT_ACCT_NO <>'' THEN T24.BASIC_DEPOSIT_ACCT_NO
         WHEN T26.BASIC_DEPOSIT_ACCT_NO IS NOT NULL AND T26.BASIC_DEPOSIT_ACCT_NO <>'' THEN T26.BASIC_DEPOSIT_ACCT_NO  
         WHEN T27.BASIC_DEPOSIT_ACCT_NO IS NOT NULL AND T27.BASIC_DEPOSIT_ACCT_NO <>'' THEN T27.BASIC_DEPOSIT_ACCT_NO		 
         WHEN T28.BASIC_DEPOSIT_ACCT_NO IS NOT NULL AND T28.BASIC_DEPOSIT_ACCT_NO <>'' THEN T28.BASIC_DEPOSIT_ACCT_NO     
         ELSE '' 
	  END AS BASIC_DEPOSIT_ACCT_NO                     -- 基本存款账号
    ,CASE 
	       WHEN T20.BASIC_ACCT_OPENBANK_NAME IS NOT NULL AND T20.BASIC_ACCT_OPENBANK_NAME <>'' THEN T20.BASIC_ACCT_OPENBANK_NAME
	       WHEN T22.BASIC_ACCT_OPENBANK_NAME IS NOT NULL AND T22.BASIC_ACCT_OPENBANK_NAME <>'' THEN T22.BASIC_ACCT_OPENBANK_NAME
         WHEN T21.BASIC_ACCT_OPENBANK_NAME IS NOT NULL AND T21.BASIC_ACCT_OPENBANK_NAME <>'' THEN T21.BASIC_ACCT_OPENBANK_NAME
         WHEN T24.BASIC_ACCT_OPENBANK_NAME IS NOT NULL AND T24.BASIC_ACCT_OPENBANK_NAME <>'' THEN T24.BASIC_ACCT_OPENBANK_NAME
		     WHEN T26.BASIC_ACCT_OPENBANK_NAME IS NOT NULL AND T26.BASIC_ACCT_OPENBANK_NAME <>'' THEN T26.BASIC_ACCT_OPENBANK_NAME
         WHEN T27.BASIC_ACCT_OPENBANK_NAME IS NOT NULL AND T27.BASIC_ACCT_OPENBANK_NAME <>'' THEN T27.BASIC_ACCT_OPENBANK_NAME
         WHEN T28.BASIC_ACCT_OPENBANK_NAME IS NOT NULL AND T28.BASIC_ACCT_OPENBANK_NAME <>'' THEN T28.BASIC_ACCT_OPENBANK_NAME
         ELSE '' 
	  END AS BASIC_ACCT_OPENBANK_NAME                  -- 基本账户开户行名称
    ,CASE 
	       WHEN T21.OPERA_STS_CD IS NOT NULL AND T21.OPERA_STS_CD <>'' THEN T21.OPERA_STS_CD
         WHEN T20.OPERA_STS_CD IS NOT NULL AND T20.OPERA_STS_CD <>'' THEN T20.OPERA_STS_CD
         WHEN T22.OPERA_STS_CD IS NOT NULL AND T22.OPERA_STS_CD <>'' THEN T22.OPERA_STS_CD
         WHEN T24.OPERA_STS_CD IS NOT NULL AND T24.OPERA_STS_CD <>'' THEN T24.OPERA_STS_CD
         WHEN T28.OPERA_STS_CD IS NOT NULL AND T28.OPERA_STS_CD <>'' THEN T28.OPERA_STS_CD
         WHEN T29.OPERA_STS_CD IS NOT NULL AND T29.OPERA_STS_CD <>'' THEN T29.OPERA_STS_CD
         WHEN T30.OPERA_STS_CD IS NOT NULL AND T30.OPERA_STS_CD <>'' THEN T30.OPERA_STS_CD
         ELSE '' 
	  END AS OPERA_STS_CD                              -- 经营状态代码
    ,CASE 
	       WHEN T21.opera_addr IS NOT NULL AND T21.opera_addr <>'' THEN T21.opera_addr
         ELSE '' 
	  END AS opera_addr                              -- 经营地址
    ,CASE 
	       WHEN T20.RGST_CY_CD IS NOT NULL AND T20.RGST_CY_CD <>'' THEN T20.RGST_CY_CD
         WHEN T21.RGST_CY_CD IS NOT NULL AND T21.RGST_CY_CD <>'' THEN T21.RGST_CY_CD
         WHEN T22.RGST_CY_CD IS NOT NULL AND T22.RGST_CY_CD <>'' THEN T22.RGST_CY_CD
         WHEN T24.RGST_CY_CD IS NOT NULL AND T24.RGST_CY_CD <>'' THEN T24.RGST_CY_CD
         WHEN T25.RGST_CY_CD IS NOT NULL AND T25.RGST_CY_CD <>'' THEN T25.RGST_CY_CD
         WHEN T26.RGST_CY_CD IS NOT NULL AND T26.RGST_CY_CD <>'' THEN T26.RGST_CY_CD
         WHEN T27.RGST_CY_CD IS NOT NULL AND T27.RGST_CY_CD <>'' THEN T27.RGST_CY_CD
         WHEN T28.RGST_CY_CD IS NOT NULL AND T28.RGST_CY_CD <>'' THEN T28.RGST_CY_CD
         WHEN T29.RGST_CY_CD IS NOT NULL AND T29.RGST_CY_CD <>'' THEN T29.RGST_CY_CD
         WHEN T30.RGST_CY_CD IS NOT NULL AND T30.RGST_CY_CD <>'' THEN T30.RGST_CY_CD
         ELSE '' 
	  END AS  RGST_CY_CD                                -- 注册国家代码
    ,CASE 
	     WHEN T21.RGST_CAP IS NOT NULL AND T21.RGST_CAP > 0 THEN T21.RGST_CAP
         WHEN T20.RGST_CAP IS NOT NULL AND T20.RGST_CAP > 0 THEN T20.RGST_CAP
         WHEN T22.RGST_CAP IS NOT NULL AND T22.RGST_CAP > 0 THEN T22.RGST_CAP
         WHEN T24.RGST_CAP IS NOT NULL AND T24.RGST_CAP > 0 THEN T24.RGST_CAP
         WHEN T28.RGST_CAP IS NOT NULL AND T28.RGST_CAP > 0 THEN T28.RGST_CAP
         WHEN T29.RGST_CAP IS NOT NULL AND T29.RGST_CAP > 0 THEN T29.RGST_CAP
         WHEN T30.RGST_CAP IS NOT NULL AND T30.RGST_CAP > 0 THEN T30.RGST_CAP
         ELSE 0 
	  END AS  RGST_CAP                                  -- 注册资金
    ,CASE 
	     WHEN T21.RGST_CAP_CURRCY_CD IS NOT NULL AND T21.RGST_CAP_CURRCY_CD <>'' THEN T21.RGST_CAP_CURRCY_CD
         WHEN T20.RGST_CAP_CURRCY_CD IS NOT NULL AND T20.RGST_CAP_CURRCY_CD <>'' THEN T20.RGST_CAP_CURRCY_CD
         WHEN T22.RGST_CAP_CURRCY_CD IS NOT NULL AND T22.RGST_CAP_CURRCY_CD <>'' THEN T22.RGST_CAP_CURRCY_CD
         WHEN T24.RGST_CAP_CURRCY_CD IS NOT NULL AND T24.RGST_CAP_CURRCY_CD <>'' THEN T24.RGST_CAP_CURRCY_CD
         WHEN T28.RGST_CAP_CURRCY_CD IS NOT NULL AND T28.RGST_CAP_CURRCY_CD <>'' THEN T28.RGST_CAP_CURRCY_CD
         WHEN T29.RGST_CAP_CURRCY_CD IS NOT NULL AND T29.RGST_CAP_CURRCY_CD <>'' THEN T29.RGST_CAP_CURRCY_CD
         WHEN T30.RGST_CAP_CURRCY_CD IS NOT NULL AND T30.RGST_CAP_CURRCY_CD <>'' THEN T30.RGST_CAP_CURRCY_CD
         ELSE '' 
	  END AS RGST_CAP_CURRCY_CD                        -- 注册资金币种代码
    ,CASE 
	     WHEN T20.RGST_ADDR IS NOT NULL AND T20.RGST_ADDR <>'' THEN T20.RGST_ADDR
         WHEN T22.RGST_ADDR IS NOT NULL AND T22.RGST_ADDR <>'' THEN T22.RGST_ADDR
         WHEN T21.RGST_ADDR IS NOT NULL AND T21.RGST_ADDR <>'' THEN T21.RGST_ADDR
         WHEN T24.RGST_ADDR IS NOT NULL AND T24.RGST_ADDR <>'' THEN T24.RGST_ADDR
         WHEN T28.RGST_ADDR IS NOT NULL AND T28.RGST_ADDR <>'' THEN T28.RGST_ADDR
         WHEN T29.RGST_ADDR IS NOT NULL AND T29.RGST_ADDR <>'' THEN T29.RGST_ADDR
         WHEN T30.RGST_ADDR IS NOT NULL AND T30.RGST_ADDR <>'' THEN T30.RGST_ADDR
         ELSE '' 
	  END AS RGST_ADDR        -- 注册地址 
    ,CASE 
         WHEN T22.offic_addr IS NOT NULL AND T22.offic_addr <>'' THEN T22.offic_addr
         WHEN T21.offic_addr IS NOT NULL AND T21.offic_addr <>'' THEN T21.offic_addr
         WHEN T20.offic_addr IS NOT NULL AND T20.offic_addr <>'' THEN T20.offic_addr
         WHEN T24.offic_addr IS NOT NULL AND T24.offic_addr <>'' THEN T24.offic_addr
         WHEN T28.offic_addr IS NOT NULL AND T28.offic_addr <>'' THEN T28.offic_addr
         WHEN T29.offic_addr IS NOT NULL AND T29.offic_addr <>'' THEN T29.offic_addr
         WHEN T30.offic_addr IS NOT NULL AND T30.offic_addr <>'' THEN T30.offic_addr
         ELSE ''
      END AS offic_addr -- 办公地址
    ,CASE
         WHEN T22.offic_tel IS NOT NULL AND T22.offic_tel <>'' THEN T22.offic_tel
         WHEN T21.offic_tel IS NOT NULL AND T21.offic_tel <>'' THEN T21.offic_tel
         WHEN T20.offic_tel IS NOT NULL AND T20.offic_tel <>'' THEN T20.offic_tel
         WHEN T24.offic_tel IS NOT NULL AND T24.offic_tel <>'' THEN T24.offic_tel
         WHEN T28.offic_tel IS NOT NULL AND T28.offic_tel <>'' THEN T28.offic_tel
         WHEN T29.offic_tel IS NOT NULL AND T29.offic_tel <>'' THEN T29.offic_tel
         WHEN T30.offic_tel IS NOT NULL AND T30.offic_tel <>'' THEN T30.offic_tel
         ELSE ''
      END AS offic_tel -- 办公电话
    ,CASE
	     WHEN T21.RCVD_CAP IS NOT NULL AND T21.RCVD_CAP > 0 THEN T21.RCVD_CAP
         WHEN T20.RCVD_CAP IS NOT NULL AND T20.RCVD_CAP > 0 THEN T20.RCVD_CAP
         WHEN T22.RCVD_CAP IS NOT NULL AND T22.RCVD_CAP > 0 THEN T22.RCVD_CAP
         WHEN T24.RCVD_CAP IS NOT NULL AND T24.RCVD_CAP > 0 THEN T24.RCVD_CAP   
         WHEN T28.RCVD_CAP IS NOT NULL AND T28.RCVD_CAP > 0 THEN T28.RCVD_CAP  
         WHEN T29.RCVD_CAP IS NOT NULL AND T29.RCVD_CAP > 0 THEN T29.RCVD_CAP
         WHEN T30.RCVD_CAP IS NOT NULL AND T30.RCVD_CAP > 0 THEN T30.RCVD_CAP
         ELSE 0 
	 END AS RCVD_CAP                                        -- 实收资本
    ,CASE 
	       WHEN T21.RCVD_CAP_CURRCY_CD IS NOT NULL AND T21.RCVD_CAP_CURRCY_CD <>'' THEN T21.RCVD_CAP_CURRCY_CD
         WHEN T20.RCVD_CAP_CURRCY_CD IS NOT NULL AND T20.RCVD_CAP_CURRCY_CD <>'' THEN T20.RCVD_CAP_CURRCY_CD
         WHEN T22.RCVD_CAP_CURRCY_CD IS NOT NULL AND T22.RCVD_CAP_CURRCY_CD <>'' THEN T22.RCVD_CAP_CURRCY_CD
         WHEN T24.RCVD_CAP_CURRCY_CD IS NOT NULL AND T24.RCVD_CAP_CURRCY_CD <>'' THEN T24.RCVD_CAP_CURRCY_CD
         WHEN T28.RCVD_CAP_CURRCY_CD IS NOT NULL AND T28.RCVD_CAP_CURRCY_CD <>'' THEN T28.RCVD_CAP_CURRCY_CD
         WHEN T29.RCVD_CAP_CURRCY_CD IS NOT NULL AND T29.RCVD_CAP_CURRCY_CD <>'' THEN T29.RCVD_CAP_CURRCY_CD
         WHEN T30.RCVD_CAP_CURRCY_CD IS NOT NULL AND T30.RCVD_CAP_CURRCY_CD <>'' THEN T30.RCVD_CAP_CURRCY_CD
         ELSE '' 
	END AS RCVD_CAP_CURRCY_CD                              -- 实收资本币种代码
	,NVL(T21.YEAR_BIZ_INCOME_CURRCY_CD,'')         AS YEAR_BIZ_INCOME_CURRCY_CD      -- 年营业收入币种代码
	,NVL(T21.YEAR_BIZ_INCOME_UNIT_CD,'')           AS YEAR_BIZ_INCOME_UNIT_CD        -- 年营业收入单位代码 01 元，02 千元，03 万元 
	,NVL(T21.YEAR_BIZ_INCOME,0)                    AS YEAR_BIZ_INCOME                -- 年营业收入
	,NVL(T21.LAST_YEAR_BIZ_INCOME_CURRCY_CD,'')    AS LAST_YEAR_BIZ_INCOME_CURRCY_CD -- 上年营业收入币种代码
	,NVL(T21.LAST_YEAR_BIZ_INCOME_UNIT_CD,'')      AS LAST_YEAR_BIZ_INCOME_UNIT_CD   -- 上年营业收入单位代码 01 元，02 千元，03 万元
	,NVL(T21.LAST_YEAR_BIZ_INCOME,0)               AS LAST_YEAR_BIZ_INCOME           -- 上年营业收入
    ,NVL(T21.EMP_PERSON_CNT,0)                     AS EMP_PERSON_CNT                 -- 员工人数
	,CASE 
	     WHEN T21.DATA_SOURC IS NOT NULL AND T21.DATA_SOURC <> ''  THEN T21.DATA_SOURC
       WHEN T20.DATA_SOURC IS NOT NULL AND T20.DATA_SOURC <> ''  THEN T20.DATA_SOURC
       WHEN T22.DATA_SOURC IS NOT NULL AND T22.DATA_SOURC <> ''  THEN T22.DATA_SOURC
       WHEN T24.DATA_SOURC IS NOT NULL AND T24.DATA_SOURC <> ''  THEN T24.DATA_SOURC
       WHEN T25.DATA_SOURC IS NOT NULL AND T25.DATA_SOURC <> ''  THEN T25.DATA_SOURC
		   WHEN T26.DATA_SOURC IS NOT NULL AND T26.DATA_SOURC <> ''  THEN T26.DATA_SOURC
       WHEN T27.DATA_SOURC IS NOT NULL AND T27.DATA_SOURC <> ''  THEN T27.DATA_SOURC
       WHEN T28.DATA_SOURC IS NOT NULL AND T28.DATA_SOURC <> ''  THEN T28.DATA_SOURC
       WHEN T29.DATA_SOURC IS NOT NULL AND T29.DATA_SOURC <> ''  THEN T29.DATA_SOURC
       WHEN T30.DATA_SOURC IS NOT NULL AND T30.DATA_SOURC <> ''  THEN T30.DATA_SOURC
       ELSE '' 
	  END AS  DATA_SOURC -- 数据来源	  
  FROM {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TMP  T1 -- 客户汇总 
  LEFT JOIN {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_ECIF T20
    ON T1.unifi_cust_no = T20.unifi_cust_no
  LEFT JOIN {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_CRMS  T21
    ON T1.unifi_cust_no=T21.unifi_cust_no 
  LEFT JOIN {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_CRBS  T22
    ON T1.unifi_cust_no=T22.unifi_cust_no  	
 /*   LEFT JOIN {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_UPDB  T23 -- 直销银行
    ON T1.unifi_cust_no=T23.unifi_cust_no   */  
  LEFT JOIN {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SILN  T24 -- 微业贷
    ON T1.unifi_cust_no=T24.unifi_cust_no  
  LEFT JOIN   {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_NBMS   T25  -- 新票据
   ON T1.unifi_cust_no=T25.unifi_cust_no 
    LEFT JOIN   {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_FNDS_BB   T26  -- 新资金 本币
   ON T1.unifi_cust_no=T26.unifi_cust_no 
   LEFT JOIN   {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_FNDS_WB   T27 -- 新资金 外币
   ON T1.unifi_cust_no=T27.unifi_cust_no 
   LEFT JOIN  {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SFLN  T28 -- 金企贷
   ON T1.unifi_cust_no=T28.unifi_cust_no 
   LEFT JOIN  {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SCLN  T29
   ON T1.unifi_cust_no=T29.unifi_cust_no  
   LEFT JOIN {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_SCLN_ZZ  T30
   ON T1.unifi_cust_no=T30.unifi_cust_no  
;

-- 数据来自非以上系统，单独建一张临时表，防止数据丢失
DROP TABLE IF EXISTS {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TI ;
CREATE TEMPORARY TABLE {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TI (
 cust_name                              VARCHAR(300)   DEFAULT NULL COMMENT '客户名称'
,tech_innov_ent_flag                    VARCHAR(10)    DEFAULT NULL COMMENT '科创企业标志'
,tech_innov_ent_type_cd                 VARCHAR(60)    DEFAULT NULL COMMENT '科创企业类型代码'
,tech_innov_ent_type                    VARCHAR(500)   DEFAULT NULL COMMENT '科创企业类型'
) DISTRIBUTED BY('cust_name');

INSERT INTO {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TI
SELECT 
       enterprise_name AS  cust_name   -- 企业名称
	  ,'1' AS tech_innov_ent_flag      -- 科创企业标志
      ,GROUP_CONCAT(DISTINCT enterprise_type_code SEPARATOR '|') AS ent_type_cd  -- 科创企业类型代码
      ,GROUP_CONCAT(DISTINCT enterprise_type_name SEPARATOR '|')AS ent_type_list -- 科创企业类型
    FROM {ODM}.O_CCOM_ENTERPRISE_LIST_IMPORT_DETAIL        -- 科创企业名单，一个企业可能有多种类型 
    WHERE start_dt<= DATE'{ETL_TX_DT}'
    AND end_dt> DATE'{ETL_TX_DT}'
    AND del_ind <>'D'
    AND data_status='2' -- 有效数据
    AND enterprise_type_code IN ( 'B' -- 科技型中小企业名单
                        ,'C' -- 专精特新中小企业名单
                        ,'D' -- 专精特新小巨人企业名单
                        ,'E' -- 国家技术创新示范企业名单
                        ,'F' -- 制造业单项冠军企业名单
                        ,'G' -- 高新技术企业名单
                        ,'H' -- 科创企业名单
                        ,'I' -- 创新型中小企业名单
                        ,'J' -- 河北省省级科技型中小企业名单
                       )  -- 剔除A省内农业龙头企业名单（非科创类）,随着监管名单类型更新，此限制条件需迭代
GROUP BY enterprise_name
;


INSERT INTO {CDM}.C_CUS_PUB_CUST_BASIC_INF
SELECT DATA_DT
       ,TRIM(T1.UNIFI_CUST_NO)
       ,TRIM(T1.CUST_NAME)
       ,TRIM(T1.CUST_CLASS)
       ,TRIM(T1.IDEN_TYPE_CD)
       ,TRIM(T1.IDEN_NO)
       ,TRIM(T1.USCC)
       ,TRIM(T1.ORGAN_CD)
       ,TRIM(T1.RGST_REGIS_NO)
       ,TRIM(T1.RGST_RGST_DT)
       ,TRIM(T1.INDUS_AND_COMME_BIZ_LICEN_NO)
       ,TRIM(T1.INDUS_AND_COMME_BIZ_LICEN_EXPIR_DT)
       ,TRIM(T1.SHAREHD_FLAG)
	   ,TRIM(T1.RURAL_CITY_FLAG)
       ,TRIM(T1.INDUS_CLASS_CD)
       ,TRIM(T1.nation_cd)
       ,TRIM(T1.STATU_REP_CUST_NAME)
       ,TRIM(T1.STATU_REP_IDEN_TYPE_CD)
       ,TRIM(T1.STATU_REP_IDEN_NO)
       ,trim(T1.statu_rep_tel)
       ,TRIM(T1.SET_UP_DT)
       ,TRIM(T1.ENT_SCALE_CD)
	   ,TRIM(T1.MYBANK_AFFIR_MINOR_ENT_FLAG)
       ,TRIM(T1.LISTE_COMPY_FLAG)
       ,TRIM(T1.dom_resdnt_flag)
       ,TRIM(T1.RELATED_GRP_CUST_NO)
       ,TRIM(T1.CREATE_ORG_NO)
       ,TRIM(T1.UPDATE_ORG_NO)
	   ,NVL(T2.tech_innov_ent_flag,'0')
	   ,NVL(T2.tech_innov_ent_type_cd,'')
	   ,NVL(T2.tech_innov_ent_type,'')
       ,TRIM(T1.ADMIN_AREA_CD)
       ,TRIM(T1.OPERA_BIZ_SCOPE)
       ,TRIM(T1.HOLD_TYPE_CD)
       ,TRIM(T1.CREDIT_RATIN_CD)
       ,TRIM(T1.CUST_TYPE_CD)
       ,TRIM(T1.FIN_ORG_CD)
       ,TRIM(T1.unit_lvl_cd)
       ,TRIM(T1.BASIC_DEPOSIT_ACCT_NO)
       ,TRIM(T1.BASIC_ACCT_OPENBANK_NAME)
       ,TRIM(T1.OPERA_STS_CD)
       ,TRIM(T1.opera_addr)
       ,TRIM(T1.RGST_CY_CD)
       ,TRIM(T1.RGST_CAP)
       ,TRIM(T1.RGST_CAP_CURRCY_CD)
       ,TRIM(T1.RGST_ADDR)
       ,TRIM(T1.offic_addr)
       ,TRIM(T1.offic_tel)
       ,TRIM(T1.RCVD_CAP)
       ,TRIM(T1.RCVD_CAP_CURRCY_CD)
       ,TRIM(T1.YEAR_BIZ_INCOME_CURRCY_CD)
       ,TRIM(T1.YEAR_BIZ_INCOME_UNIT_CD)
       ,TRIM(T1.YEAR_BIZ_INCOME)
       ,TRIM(T1.LAST_YEAR_BIZ_INCOME_CURRCY_CD)
       ,TRIM(T1.LAST_YEAR_BIZ_INCOME_UNIT_CD)
       ,TRIM(T1.LAST_YEAR_BIZ_INCOME)
       ,TRIM(T1.EMP_PERSON_CNT)
       ,TRIM(T1.DATA_SOURC)
FROM {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF T1
LEFT JOIN {CDM}.VT_C_CUS_PUB_CUST_BASIC_INF_TI T2
  ON TRIM(T1.cust_name) = T2.cust_name
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