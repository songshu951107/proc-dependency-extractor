# *****************************************头部注释模块**************************************
#脚本名称：dwa_f_pt_ecif_pub_cust_inf.py
#功能说明：ECIF对公客户信息FDM脚本
#更新方式：F4：基础层带删除拉链
#更新对象：FDM.F_PT_ECIF_PUB_CUST_INF
#创 建 人：xuqihui
#创建日期：2023-08-07
#运行周期：日
#备    注：数据仓库缓冲层到基础层数据拉链处理
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
dwa_username = etl.dwa_usr_fdm
dwa_password = etl.dwa_pwd_fdm
TDM = etl.dwa_db_tdm
ODM = etl.dwa_db_odm
FDM = etl.dwa_db_fdm

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
        gsql_str_p = f"set -o pipefail; gccli -h {dwa_host} -P {dwa_port} -u {dwa_username} -p\"{dwa_password}\" -D {dwa_db_fdm} -v -v |tee -a {log_filename}"
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

/*防止批量重跑处理,1.删除新增数据 2.打开闭链数据*/
DELETE FROM {FDM}.F_PT_ECIF_PUB_CUST_INF    -- ECIF对公客户信息
 WHERE start_dt >=DATE '{ETL_TX_DT}';

UPDATE {FDM}.F_PT_ECIF_PUB_CUST_INF    -- ECIF对公客户信息
   SET end_dt=DATE '{MAX_DATE}'
 WHERE end_dt >=DATE '{ETL_TX_DT}' 
   AND end_dt <DATE '{MAX_DATE}';
   
 
/*SETP1:创建临时表,存放新增修改删除数据,ST为数据源临时表，VT是处理临时表*/
DROP TABLE IF EXISTS {FDM}.ST_F_PT_ECIF_PUB_CUST_INF;
CREATE TEMPORARY TABLE IF NOT EXISTS {FDM}.ST_F_PT_ECIF_PUB_CUST_INF
AS(
SELECT F.cust_no  -- 客户号
      ,F.core_cust_no  -- 核心客户号
      ,F.org_cust_type_cd  -- 机构客户类型代码
      ,F.credit_loan_cust_type_cd  -- 信贷客户类型代码
      ,F.cy_or_zone_cd  -- 国家或地区代码
      ,F.admin_area_cd  -- 行政区划代码
      ,F.org_type_cd  -- 组织机构类型代码
      ,F.core_org_sub_type_cd  -- 核心组织机构子类型代码
      ,F.org_cd  -- 组织机构代码
      ,F.org_rgst_dt  -- 组织机构登记日期
      ,F.org_vld_dt  -- 组织机构有效日期
      ,F.uscc_cd  -- 统一社会信用代码
      ,F.main_biz_indus_class_cd  -- 主营行业分类代码
      ,F.depu_batt_indus_class_cd  -- 副营行业分类代码
      ,F.ent_nature_cd  -- 企业性质代码
      ,F.bankg_ent_scale_cd  -- 银监企业规模代码
      ,F.ent_scale_pboc  -- 企业规模人行
      ,F.ent_scale_deposit  -- 企业规模存款
      ,F.asset_scale_cd  -- 资产规模代码
      ,F.emp_scale_cd  -- 员工规模代码
      ,F.emp_person_cnt  -- 员工人数
      ,F.economy_type_cd  -- 经济类型代码
      ,F.hold_type_cd  -- 控股类型代码
      ,F.govern_structure  -- 政务民生标志
      ,F.in_cll_type_cd  -- 行内行业类别代码
      ,F.indus_class  -- 行业分类
      ,F.ent_sjectn  -- 企业隶属
      ,F.set_up_dt  -- 成立日期
      ,F.main_biz_biz  -- 主营业务
      ,F.con_oper_biz  -- 兼营业务
      ,F.zone_cd  -- 地区代码
      ,F.loan_card_no_cd  -- 贷款卡号码
      ,F.loan_card_sts_cd  -- 贷款卡状态代码
      ,F.loan_card_passwd  -- 贷款卡密码
      ,F.loan_card_annu_revi_dt  -- 贷款卡年审日期
      ,F.hold_sharehd_or_actual_ctrl_person  -- 控股股东或实际控制人
      ,F.lp_rep_iden_type_cd  -- 法人代表证件类型代码
      ,F.lp_prop  -- 法人属性
      ,F.statu_rep_name  -- 法定代表人名称
      ,F.statu_rep_gender_cd  -- 法定代表人性别代码
      ,F.statu_rep_iden_type_cd  -- 法定代表人证件类型代码
      ,F.statu_rep_iden_no  -- 法定代表人证件号码
      ,F.lp_iden_vld_dt  -- 法人证件有效日期
      ,F.statu_rep_contact_phone_no  -- 法定代表人联系电话
      ,F.statu_rep_hou_reg_addr  -- 法定代表人户籍地址
      ,F.statu_rep_photo  -- 法定代表人照片
      ,F.statu_rep_local_cy_or_zone  -- 法定代表人所在国家或地区
      ,F.finan_report_type_cd  -- 财务报表类型代码
      ,F.tot_asset  -- 总资产
      ,F.net_asset  -- 净资产
      ,F.tot_liabi  -- 总负债
      ,F.year_income  -- 年收入
      ,F.year_prft  -- 年利润
      ,F.indus_sts  -- 行业地位
      ,F.and_mybank_related_rela  -- 与我行关联关系
      ,F.and_mybank_coope_rela  -- 与我行合作关系
      ,F.mybank_sharehd_flag  -- 我行股东标志
      ,F.poses_mybank_shares_amt  -- 拥有我行股份金额
      ,F.commu_addr  -- 通讯地址
      ,F.posta_cd  -- 邮政编码
      ,F.contact_person  -- 联系人
      ,F.contact_phone_no  -- 联系电话
      ,F.fax_no  -- 传真号码
      ,F.mail_addr  -- 邮件地址
      ,F.hmpg  -- 主页
      ,F.national_economy_dept  -- 国民经济部门
      ,F.rmk  -- 备注
      ,F.last_update_sys  -- 最后更新系统
      ,F.last_update_person  -- 最后更新人
      ,F.last_update_tm  -- 最后更新时间
      ,F.txn_seq_no  -- 交易流水号
      ,F.zone_name  -- 地区名称
      ,F.ent_out_tube_cd  -- 企业外管代码
      ,F.tech_lvl  -- 技术水平
      ,F.cust_simp_spel  -- 客户简拼
      ,F.ent_scale_credit  -- 企业规模征信
      ,F.ent_scale_bse  -- 企业规模巴塞尔
      ,F.credit_loan_subdv_indus_type_cd  -- 信贷细分行业类型代码
      ,F.three_agric_flag  -- 三农标志
      ,F.tourism_health_cd  -- 旅游健康行业分类代码
      ,F.tourism_or_heal_indu  -- 旅游业或健康业
      ,F.del_flag  -- 删除标志
      ,F.start_dt  -- 开始日期
      ,F.end_dt  -- 结束日期
  FROM {FDM}.F_PT_ECIF_PUB_CUST_INF F
 LIMIT 0 
);

DROP TABLE IF EXISTS {FDM}.VT_F_PT_ECIF_PUB_CUST_INF;
CREATE TEMPORARY TABLE IF NOT EXISTS {FDM}.VT_F_PT_ECIF_PUB_CUST_INF
AS(
SELECT F.cust_no  -- 客户号
      ,F.core_cust_no  -- 核心客户号
      ,F.org_cust_type_cd  -- 机构客户类型代码
      ,F.credit_loan_cust_type_cd  -- 信贷客户类型代码
      ,F.cy_or_zone_cd  -- 国家或地区代码
      ,F.admin_area_cd  -- 行政区划代码
      ,F.org_type_cd  -- 组织机构类型代码
      ,F.core_org_sub_type_cd  -- 核心组织机构子类型代码
      ,F.org_cd  -- 组织机构代码
      ,F.org_rgst_dt  -- 组织机构登记日期
      ,F.org_vld_dt  -- 组织机构有效日期
      ,F.uscc_cd  -- 统一社会信用代码
      ,F.main_biz_indus_class_cd  -- 主营行业分类代码
      ,F.depu_batt_indus_class_cd  -- 副营行业分类代码
      ,F.ent_nature_cd  -- 企业性质代码
      ,F.bankg_ent_scale_cd  -- 银监企业规模代码
      ,F.ent_scale_pboc  -- 企业规模人行
      ,F.ent_scale_deposit  -- 企业规模存款
      ,F.asset_scale_cd  -- 资产规模代码
      ,F.emp_scale_cd  -- 员工规模代码
      ,F.emp_person_cnt  -- 员工人数
      ,F.economy_type_cd  -- 经济类型代码
      ,F.hold_type_cd  -- 控股类型代码
      ,F.govern_structure  -- 政务民生标志
      ,F.in_cll_type_cd  -- 行内行业类别代码
      ,F.indus_class  -- 行业分类
      ,F.ent_sjectn  -- 企业隶属
      ,F.set_up_dt  -- 成立日期
      ,F.main_biz_biz  -- 主营业务
      ,F.con_oper_biz  -- 兼营业务
      ,F.zone_cd  -- 地区代码
      ,F.loan_card_no_cd  -- 贷款卡号码
      ,F.loan_card_sts_cd  -- 贷款卡状态代码
      ,F.loan_card_passwd  -- 贷款卡密码
      ,F.loan_card_annu_revi_dt  -- 贷款卡年审日期
      ,F.hold_sharehd_or_actual_ctrl_person  -- 控股股东或实际控制人
      ,F.lp_rep_iden_type_cd  -- 法人代表证件类型代码
      ,F.lp_prop  -- 法人属性
      ,F.statu_rep_name  -- 法定代表人名称
      ,F.statu_rep_gender_cd  -- 法定代表人性别代码
      ,F.statu_rep_iden_type_cd  -- 法定代表人证件类型代码
      ,F.statu_rep_iden_no  -- 法定代表人证件号码
      ,F.lp_iden_vld_dt  -- 法人证件有效日期
      ,F.statu_rep_contact_phone_no  -- 法定代表人联系电话
      ,F.statu_rep_hou_reg_addr  -- 法定代表人户籍地址
      ,F.statu_rep_photo  -- 法定代表人照片
      ,F.statu_rep_local_cy_or_zone  -- 法定代表人所在国家或地区
      ,F.finan_report_type_cd  -- 财务报表类型代码
      ,F.tot_asset  -- 总资产
      ,F.net_asset  -- 净资产
      ,F.tot_liabi  -- 总负债
      ,F.year_income  -- 年收入
      ,F.year_prft  -- 年利润
      ,F.indus_sts  -- 行业地位
      ,F.and_mybank_related_rela  -- 与我行关联关系
      ,F.and_mybank_coope_rela  -- 与我行合作关系
      ,F.mybank_sharehd_flag  -- 我行股东标志
      ,F.poses_mybank_shares_amt  -- 拥有我行股份金额
      ,F.commu_addr  -- 通讯地址
      ,F.posta_cd  -- 邮政编码
      ,F.contact_person  -- 联系人
      ,F.contact_phone_no  -- 联系电话
      ,F.fax_no  -- 传真号码
      ,F.mail_addr  -- 邮件地址
      ,F.hmpg  -- 主页
      ,F.national_economy_dept  -- 国民经济部门
      ,F.rmk  -- 备注
      ,F.last_update_sys  -- 最后更新系统
      ,F.last_update_person  -- 最后更新人
      ,F.last_update_tm  -- 最后更新时间
      ,F.txn_seq_no  -- 交易流水号
      ,F.zone_name  -- 地区名称
      ,F.ent_out_tube_cd  -- 企业外管代码
      ,F.tech_lvl  -- 技术水平
      ,F.cust_simp_spel  -- 客户简拼
      ,F.ent_scale_credit  -- 企业规模征信
      ,F.ent_scale_bse  -- 企业规模巴塞尔
      ,F.credit_loan_subdv_indus_type_cd  -- 信贷细分行业类型代码
      ,F.three_agric_flag  -- 三农标志
      ,F.tourism_health_cd  -- 旅游健康行业分类代码
      ,F.tourism_or_heal_indu  -- 旅游业或健康业
      ,F.del_flag  -- 删除标志
      ,F.start_dt  -- 开始日期
      ,F.end_dt  -- 结束日期
  FROM {FDM}.F_PT_ECIF_PUB_CUST_INF F
 LIMIT 0 
);


"""

# -生成mapping映射- #
bsqlmapping="""
INSERT INTO {FDM}.ST_F_PT_ECIF_PUB_CUST_INF (
       cust_no  -- 客户号
      ,core_cust_no  -- 核心客户号
      ,org_cust_type_cd  -- 机构客户类型代码
      ,credit_loan_cust_type_cd  -- 信贷客户类型代码
      ,cy_or_zone_cd  -- 国家或地区代码
      ,admin_area_cd  -- 行政区划代码
      ,org_type_cd  -- 组织机构类型代码
      ,core_org_sub_type_cd  -- 核心组织机构子类型代码
      ,org_cd  -- 组织机构代码
      ,org_rgst_dt  -- 组织机构登记日期
      ,org_vld_dt  -- 组织机构有效日期
      ,uscc_cd  -- 统一社会信用代码
      ,main_biz_indus_class_cd  -- 主营行业分类代码
      ,depu_batt_indus_class_cd  -- 副营行业分类代码
      ,ent_nature_cd  -- 企业性质代码
      ,bankg_ent_scale_cd  -- 银监企业规模代码
      ,ent_scale_pboc  -- 企业规模人行
      ,ent_scale_deposit  -- 企业规模存款
      ,asset_scale_cd  -- 资产规模代码
      ,emp_scale_cd  -- 员工规模代码
      ,emp_person_cnt  -- 员工人数
      ,economy_type_cd  -- 经济类型代码
      ,hold_type_cd  -- 控股类型代码
      ,govern_structure  -- 政务民生标志
      ,in_cll_type_cd  -- 行内行业类别代码
      ,indus_class  -- 行业分类
      ,ent_sjectn  -- 企业隶属
      ,set_up_dt  -- 成立日期
      ,main_biz_biz  -- 主营业务
      ,con_oper_biz  -- 兼营业务
      ,zone_cd  -- 地区代码
      ,loan_card_no_cd  -- 贷款卡号码
      ,loan_card_sts_cd  -- 贷款卡状态代码
      ,loan_card_passwd  -- 贷款卡密码
      ,loan_card_annu_revi_dt  -- 贷款卡年审日期
      ,hold_sharehd_or_actual_ctrl_person  -- 控股股东或实际控制人
      ,lp_rep_iden_type_cd  -- 法人代表证件类型代码
      ,lp_prop  -- 法人属性
      ,statu_rep_name  -- 法定代表人名称
      ,statu_rep_gender_cd  -- 法定代表人性别代码
      ,statu_rep_iden_type_cd  -- 法定代表人证件类型代码
      ,statu_rep_iden_no  -- 法定代表人证件号码
      ,lp_iden_vld_dt  -- 法人证件有效日期
      ,statu_rep_contact_phone_no  -- 法定代表人联系电话
      ,statu_rep_hou_reg_addr  -- 法定代表人户籍地址
      ,statu_rep_photo  -- 法定代表人照片
      ,statu_rep_local_cy_or_zone  -- 法定代表人所在国家或地区
      ,finan_report_type_cd  -- 财务报表类型代码
      ,tot_asset  -- 总资产
      ,net_asset  -- 净资产
      ,tot_liabi  -- 总负债
      ,year_income  -- 年收入
      ,year_prft  -- 年利润
      ,indus_sts  -- 行业地位
      ,and_mybank_related_rela  -- 与我行关联关系
      ,and_mybank_coope_rela  -- 与我行合作关系
      ,mybank_sharehd_flag  -- 我行股东标志
      ,poses_mybank_shares_amt  -- 拥有我行股份金额
      ,commu_addr  -- 通讯地址
      ,posta_cd  -- 邮政编码
      ,contact_person  -- 联系人
      ,contact_phone_no  -- 联系电话
      ,fax_no  -- 传真号码
      ,mail_addr  -- 邮件地址
      ,hmpg  -- 主页
      ,national_economy_dept  -- 国民经济部门
      ,rmk  -- 备注
      ,last_update_sys  -- 最后更新系统
      ,last_update_person  -- 最后更新人
      ,last_update_tm  -- 最后更新时间
      ,txn_seq_no  -- 交易流水号
      ,zone_name  -- 地区名称
      ,ent_out_tube_cd  -- 企业外管代码
      ,tech_lvl  -- 技术水平
      ,cust_simp_spel  -- 客户简拼
      ,ent_scale_credit  -- 企业规模征信
      ,ent_scale_bse  -- 企业规模巴塞尔
      ,credit_loan_subdv_indus_type_cd  -- 信贷细分行业类型代码
      ,three_agric_flag  -- 三农标志
      ,tourism_health_cd  -- 旅游健康行业分类代码
      ,tourism_or_heal_indu  -- 旅游业或健康业
      ,del_flag  -- 删除标志
      ,start_dt  -- 开始日期
      ,end_dt  -- 结束日期
)
SELECT T1.CUST_ID
      ,T2.CORE_NO
      ,T1.ORG_CUST_TYPE
      ,T1.CHURCUSTYPE
      ,T1.NATION_CODE
      ,T1.AREA_CODE
      ,T1.ORG_TYPE
      ,T1.ORG_SUB_TYPE
      ,T1.ORG_CODE
      ,T1.ORG_REG_DATE
      ,T1.ORG_EXP_DATE
      ,T1.BUSI_LIC_NO
      ,T1.MAIN_INDUSTRY
      ,T1.MINOR_INDUSTRY
      ,T1.ENT_PROPERTY
      ,T1.ENT_SCALE
      ,T1.ENT_SCALE_RH
      ,T1.ENT_SCALE_CK
      ,T1.ASSETS_SCALE
      ,T1.EMPLOYEE_SCALE
      ,T1.EMPLOYEENUMBER
      ,T1.ECONOMIC_TYPE
      ,T1.COM_HOLD_TYPE
      ,T1.GOVERN_STRUCTURE
      ,T1.IN_CLL_TYPE
      ,T1.INDUSTRY_CATEGORY
      ,T1.ENT_BELONG
      ,T1.BUILD_DATE
      ,T1.MAIN_BUSINESS
      ,T1.MINOR_BUSINESS
      ,T1.ZONE_CODE
      ,T1.LOAN_CARD_NO
      ,T1.LOAN_CARD_STAT
      ,T1.LOAD_CARD_PWD
      ,T1.LOAD_CARD_AUDIT_DT
      ,T1.KONGGR
      ,T1.FRDBLB
      ,T1.FRJMSX
      ,T1.LEGAL_REPR_NAME
      ,T1.LEGAL_REPR_GENDER
      ,T1.LEGAL_REPR_IDENT_TYPE
      ,T1.LEGAL_REPR_IDENT_NO
      ,T1.FRZJYX
      ,T1.LEGAL_REPR_TEL
      ,T1.LEGAL_REPR_ADDR
      ,T1.LEGAL_REPR_PHOTO
      ,T1.LEGAL_REPR_NATION_CODE
      ,T1.FIN_REP_TYPE
      ,T1.TOTAL_ASSETS
      ,T1.NET_ASSETS
      ,T1.TOTAL_DEBT
      ,T1.ANNUAL_INCOME
      ,T1.ANNUAL_PROFIT
      ,T1.INDUSTRY_POSITION
      ,T1.CUS_BANK_REL
      ,T1.CUS_CORP_REL
      ,T1.IS_STOCK_HOLDER
      ,T1.HOLD_STOCK_AMT
      ,T1.ORG_ADDR
      ,T1.ORG_ZIPCODE
      ,T1.ORG_CUS
      ,T1.ORG_TEL
      ,T1.ORG_FEX
      ,T1.ORG_EMAIL
      ,T1.ORG_HOMEPAGE
      ,T1.NATIECODEPT
      ,T1.REMARKS
      ,T1.LAST_UPDATE_SYS
      ,T1.LAST_UPDATE_USER
      ,T1.LAST_UPDATE_TM
      ,T1.TX_SEQ_NO
      ,T1.ZONE_NAME
      ,T1.OUTER_MGR_CODE
      ,T1.TECH_LEVEL
      ,T1.NAME_SIMP_SPELL
      ,T1.ENT_SCALE_ZX
      ,T1.ENT_SCALE_BSE
      ,T1.INDUSTRY_SUB_TYPE
      ,T1.IS_THREE_AGRICULTURE
      ,T1.TOURISM_HEALTH
      ,T1.TOURISM_OR_HEALTH
      ,''
      ,DATE'{ETL_TX_DT}'
      ,DATE'{MAX_DATE}'
  FROM TDM.T_ECIF_M_CI_ORG_{ETL_TX_DT} T1
  LEFT JOIN ODM.O_ECIF_M_CI_CUSTOMER T2 
    ON T1.cust_id=T2.cust_id
   AND T2.start_dt<=DATE'{ETL_TX_DT}'
   AND T2.end_dt >DATE'{ETL_TX_DT}'
   AND T2.del_ind<>'D' 
;

"""

# -bsql2算法逻辑主体实现- #
bsql2="""

/*SETP2:向临时表插入新增、修改数据*/
INSERT INTO {FDM}.VT_F_PT_ECIF_PUB_CUST_INF
SELECT  T01.cust_no  -- 客户号
       ,T01.core_cust_no  -- 核心客户号
       ,T01.org_cust_type_cd  -- 机构客户类型代码
       ,T01.credit_loan_cust_type_cd  -- 信贷客户类型代码
       ,T01.cy_or_zone_cd  -- 国家或地区代码
       ,T01.admin_area_cd  -- 行政区划代码
       ,T01.org_type_cd  -- 组织机构类型代码
       ,T01.core_org_sub_type_cd  -- 核心组织机构子类型代码
       ,T01.org_cd  -- 组织机构代码
       ,T01.org_rgst_dt  -- 组织机构登记日期
       ,T01.org_vld_dt  -- 组织机构有效日期
       ,T01.uscc_cd  -- 统一社会信用代码
       ,T01.main_biz_indus_class_cd  -- 主营行业分类代码
       ,T01.depu_batt_indus_class_cd  -- 副营行业分类代码
       ,T01.ent_nature_cd  -- 企业性质代码
       ,T01.bankg_ent_scale_cd  -- 银监企业规模代码
       ,T01.ent_scale_pboc  -- 企业规模人行
       ,T01.ent_scale_deposit  -- 企业规模存款
       ,T01.asset_scale_cd  -- 资产规模代码
       ,T01.emp_scale_cd  -- 员工规模代码
       ,T01.emp_person_cnt  -- 员工人数
       ,T01.economy_type_cd  -- 经济类型代码
       ,T01.hold_type_cd  -- 控股类型代码
       ,T01.govern_structure  -- 政务民生标志
       ,T01.in_cll_type_cd  -- 行内行业类别代码
       ,T01.indus_class  -- 行业分类
       ,T01.ent_sjectn  -- 企业隶属
       ,T01.set_up_dt  -- 成立日期
       ,T01.main_biz_biz  -- 主营业务
       ,T01.con_oper_biz  -- 兼营业务
       ,T01.zone_cd  -- 地区代码
       ,T01.loan_card_no_cd  -- 贷款卡号码
       ,T01.loan_card_sts_cd  -- 贷款卡状态代码
       ,T01.loan_card_passwd  -- 贷款卡密码
       ,T01.loan_card_annu_revi_dt  -- 贷款卡年审日期
       ,T01.hold_sharehd_or_actual_ctrl_person  -- 控股股东或实际控制人
       ,T01.lp_rep_iden_type_cd  -- 法人代表证件类型代码
       ,T01.lp_prop  -- 法人属性
       ,T01.statu_rep_name  -- 法定代表人名称
       ,T01.statu_rep_gender_cd  -- 法定代表人性别代码
       ,T01.statu_rep_iden_type_cd  -- 法定代表人证件类型代码
       ,T01.statu_rep_iden_no  -- 法定代表人证件号码
       ,T01.lp_iden_vld_dt  -- 法人证件有效日期
       ,T01.statu_rep_contact_phone_no  -- 法定代表人联系电话
       ,T01.statu_rep_hou_reg_addr  -- 法定代表人户籍地址
       ,T01.statu_rep_photo  -- 法定代表人照片
       ,T01.statu_rep_local_cy_or_zone  -- 法定代表人所在国家或地区
       ,T01.finan_report_type_cd  -- 财务报表类型代码
       ,T01.tot_asset  -- 总资产
       ,T01.net_asset  -- 净资产
       ,T01.tot_liabi  -- 总负债
       ,T01.year_income  -- 年收入
       ,T01.year_prft  -- 年利润
       ,T01.indus_sts  -- 行业地位
       ,T01.and_mybank_related_rela  -- 与我行关联关系
       ,T01.and_mybank_coope_rela  -- 与我行合作关系
       ,T01.mybank_sharehd_flag  -- 我行股东标志
       ,T01.poses_mybank_shares_amt  -- 拥有我行股份金额
       ,T01.commu_addr  -- 通讯地址
       ,T01.posta_cd  -- 邮政编码
       ,T01.contact_person  -- 联系人
       ,T01.contact_phone_no  -- 联系电话
       ,T01.fax_no  -- 传真号码
       ,T01.mail_addr  -- 邮件地址
       ,T01.hmpg  -- 主页
       ,T01.national_economy_dept  -- 国民经济部门
       ,T01.rmk  -- 备注
       ,T01.last_update_sys  -- 最后更新系统
       ,T01.last_update_person  -- 最后更新人
       ,T01.last_update_tm  -- 最后更新时间
       ,T01.txn_seq_no  -- 交易流水号
       ,T01.zone_name  -- 地区名称
       ,T01.ent_out_tube_cd  -- 企业外管代码
       ,T01.tech_lvl  -- 技术水平
       ,T01.cust_simp_spel  -- 客户简拼
       ,T01.ent_scale_credit  -- 企业规模征信
       ,T01.ent_scale_bse  -- 企业规模巴塞尔
       ,T01.credit_loan_subdv_indus_type_cd  -- 信贷细分行业类型代码
       ,T01.three_agric_flag  -- 三农标志
       ,T01.tourism_health_cd  -- 旅游健康行业分类代码
       ,T01.tourism_or_heal_indu  -- 旅游业或健康业
      ,' '                  -- 删除标记  
      ,DATE '{ETL_TX_DT}'   -- 开始日期  
      ,DATE '{MAX_DATE}'    -- 结束日期
  FROM (
SELECT T.cust_no  -- 客户号
      ,T.core_cust_no  -- 核心客户号
      ,T.org_cust_type_cd  -- 机构客户类型代码
      ,T.credit_loan_cust_type_cd  -- 信贷客户类型代码
      ,T.cy_or_zone_cd  -- 国家或地区代码
      ,T.admin_area_cd  -- 行政区划代码
      ,T.org_type_cd  -- 组织机构类型代码
      ,T.core_org_sub_type_cd  -- 核心组织机构子类型代码
      ,T.org_cd  -- 组织机构代码
      ,T.org_rgst_dt  -- 组织机构登记日期
      ,T.org_vld_dt  -- 组织机构有效日期
      ,T.uscc_cd  -- 统一社会信用代码
      ,T.main_biz_indus_class_cd  -- 主营行业分类代码
      ,T.depu_batt_indus_class_cd  -- 副营行业分类代码
      ,T.ent_nature_cd  -- 企业性质代码
      ,T.bankg_ent_scale_cd  -- 银监企业规模代码
      ,T.ent_scale_pboc  -- 企业规模人行
      ,T.ent_scale_deposit  -- 企业规模存款
      ,T.asset_scale_cd  -- 资产规模代码
      ,T.emp_scale_cd  -- 员工规模代码
      ,T.emp_person_cnt  -- 员工人数
      ,T.economy_type_cd  -- 经济类型代码
      ,T.hold_type_cd  -- 控股类型代码
      ,T.govern_structure  -- 政务民生标志
      ,T.in_cll_type_cd  -- 行内行业类别代码
      ,T.indus_class  -- 行业分类
      ,T.ent_sjectn  -- 企业隶属
      ,T.set_up_dt  -- 成立日期
      ,T.main_biz_biz  -- 主营业务
      ,T.con_oper_biz  -- 兼营业务
      ,T.zone_cd  -- 地区代码
      ,T.loan_card_no_cd  -- 贷款卡号码
      ,T.loan_card_sts_cd  -- 贷款卡状态代码
      ,T.loan_card_passwd  -- 贷款卡密码
      ,T.loan_card_annu_revi_dt  -- 贷款卡年审日期
      ,T.hold_sharehd_or_actual_ctrl_person  -- 控股股东或实际控制人
      ,T.lp_rep_iden_type_cd  -- 法人代表证件类型代码
      ,T.lp_prop  -- 法人属性
      ,T.statu_rep_name  -- 法定代表人名称
      ,T.statu_rep_gender_cd  -- 法定代表人性别代码
      ,T.statu_rep_iden_type_cd  -- 法定代表人证件类型代码
      ,T.statu_rep_iden_no  -- 法定代表人证件号码
      ,T.lp_iden_vld_dt  -- 法人证件有效日期
      ,T.statu_rep_contact_phone_no  -- 法定代表人联系电话
      ,T.statu_rep_hou_reg_addr  -- 法定代表人户籍地址
      ,T.statu_rep_photo  -- 法定代表人照片
      ,T.statu_rep_local_cy_or_zone  -- 法定代表人所在国家或地区
      ,T.finan_report_type_cd  -- 财务报表类型代码
      ,T.tot_asset  -- 总资产
      ,T.net_asset  -- 净资产
      ,T.tot_liabi  -- 总负债
      ,T.year_income  -- 年收入
      ,T.year_prft  -- 年利润
      ,T.indus_sts  -- 行业地位
      ,T.and_mybank_related_rela  -- 与我行关联关系
      ,T.and_mybank_coope_rela  -- 与我行合作关系
      ,T.mybank_sharehd_flag  -- 我行股东标志
      ,T.poses_mybank_shares_amt  -- 拥有我行股份金额
      ,T.commu_addr  -- 通讯地址
      ,T.posta_cd  -- 邮政编码
      ,T.contact_person  -- 联系人
      ,T.contact_phone_no  -- 联系电话
      ,T.fax_no  -- 传真号码
      ,T.mail_addr  -- 邮件地址
      ,T.hmpg  -- 主页
      ,T.national_economy_dept  -- 国民经济部门
      ,T.rmk  -- 备注
      ,T.last_update_sys  -- 最后更新系统
      ,T.last_update_person  -- 最后更新人
      ,T.last_update_tm  -- 最后更新时间
      ,T.txn_seq_no  -- 交易流水号
      ,T.zone_name  -- 地区名称
      ,T.ent_out_tube_cd  -- 企业外管代码
      ,T.tech_lvl  -- 技术水平
      ,T.cust_simp_spel  -- 客户简拼
      ,T.ent_scale_credit  -- 企业规模征信
      ,T.ent_scale_bse  -- 企业规模巴塞尔
      ,T.credit_loan_subdv_indus_type_cd  -- 信贷细分行业类型代码
      ,T.three_agric_flag  -- 三农标志
      ,T.tourism_health_cd  -- 旅游健康行业分类代码
      ,T.tourism_or_heal_indu  -- 旅游业或健康业
  FROM {FDM}.ST_F_PT_ECIF_PUB_CUST_INF T
EXCEPT ALL
SELECT F.cust_no  -- 客户号
      ,F.core_cust_no  -- 核心客户号
      ,F.org_cust_type_cd  -- 机构客户类型代码
      ,F.credit_loan_cust_type_cd  -- 信贷客户类型代码
      ,F.cy_or_zone_cd  -- 国家或地区代码
      ,F.admin_area_cd  -- 行政区划代码
      ,F.org_type_cd  -- 组织机构类型代码
      ,F.core_org_sub_type_cd  -- 核心组织机构子类型代码
      ,F.org_cd  -- 组织机构代码
      ,F.org_rgst_dt  -- 组织机构登记日期
      ,F.org_vld_dt  -- 组织机构有效日期
      ,F.uscc_cd  -- 统一社会信用代码
      ,F.main_biz_indus_class_cd  -- 主营行业分类代码
      ,F.depu_batt_indus_class_cd  -- 副营行业分类代码
      ,F.ent_nature_cd  -- 企业性质代码
      ,F.bankg_ent_scale_cd  -- 银监企业规模代码
      ,F.ent_scale_pboc  -- 企业规模人行
      ,F.ent_scale_deposit  -- 企业规模存款
      ,F.asset_scale_cd  -- 资产规模代码
      ,F.emp_scale_cd  -- 员工规模代码
      ,F.emp_person_cnt  -- 员工人数
      ,F.economy_type_cd  -- 经济类型代码
      ,F.hold_type_cd  -- 控股类型代码
      ,F.govern_structure  -- 政务民生标志
      ,F.in_cll_type_cd  -- 行内行业类别代码
      ,F.indus_class  -- 行业分类
      ,F.ent_sjectn  -- 企业隶属
      ,F.set_up_dt  -- 成立日期
      ,F.main_biz_biz  -- 主营业务
      ,F.con_oper_biz  -- 兼营业务
      ,F.zone_cd  -- 地区代码
      ,F.loan_card_no_cd  -- 贷款卡号码
      ,F.loan_card_sts_cd  -- 贷款卡状态代码
      ,F.loan_card_passwd  -- 贷款卡密码
      ,F.loan_card_annu_revi_dt  -- 贷款卡年审日期
      ,F.hold_sharehd_or_actual_ctrl_person  -- 控股股东或实际控制人
      ,F.lp_rep_iden_type_cd  -- 法人代表证件类型代码
      ,F.lp_prop  -- 法人属性
      ,F.statu_rep_name  -- 法定代表人名称
      ,F.statu_rep_gender_cd  -- 法定代表人性别代码
      ,F.statu_rep_iden_type_cd  -- 法定代表人证件类型代码
      ,F.statu_rep_iden_no  -- 法定代表人证件号码
      ,F.lp_iden_vld_dt  -- 法人证件有效日期
      ,F.statu_rep_contact_phone_no  -- 法定代表人联系电话
      ,F.statu_rep_hou_reg_addr  -- 法定代表人户籍地址
      ,F.statu_rep_photo  -- 法定代表人照片
      ,F.statu_rep_local_cy_or_zone  -- 法定代表人所在国家或地区
      ,F.finan_report_type_cd  -- 财务报表类型代码
      ,F.tot_asset  -- 总资产
      ,F.net_asset  -- 净资产
      ,F.tot_liabi  -- 总负债
      ,F.year_income  -- 年收入
      ,F.year_prft  -- 年利润
      ,F.indus_sts  -- 行业地位
      ,F.and_mybank_related_rela  -- 与我行关联关系
      ,F.and_mybank_coope_rela  -- 与我行合作关系
      ,F.mybank_sharehd_flag  -- 我行股东标志
      ,F.poses_mybank_shares_amt  -- 拥有我行股份金额
      ,F.commu_addr  -- 通讯地址
      ,F.posta_cd  -- 邮政编码
      ,F.contact_person  -- 联系人
      ,F.contact_phone_no  -- 联系电话
      ,F.fax_no  -- 传真号码
      ,F.mail_addr  -- 邮件地址
      ,F.hmpg  -- 主页
      ,F.national_economy_dept  -- 国民经济部门
      ,F.rmk  -- 备注
      ,F.last_update_sys  -- 最后更新系统
      ,F.last_update_person  -- 最后更新人
      ,F.last_update_tm  -- 最后更新时间
      ,F.txn_seq_no  -- 交易流水号
      ,F.zone_name  -- 地区名称
      ,F.ent_out_tube_cd  -- 企业外管代码
      ,F.tech_lvl  -- 技术水平
      ,F.cust_simp_spel  -- 客户简拼
      ,F.ent_scale_credit  -- 企业规模征信
      ,F.ent_scale_bse  -- 企业规模巴塞尔
      ,F.credit_loan_subdv_indus_type_cd  -- 信贷细分行业类型代码
      ,F.three_agric_flag  -- 三农标志
      ,F.tourism_health_cd  -- 旅游健康行业分类代码
      ,F.tourism_or_heal_indu  -- 旅游业或健康业
  FROM {FDM}.F_PT_ECIF_PUB_CUST_INF F
 WHERE F.end_dt = DATE '{MAX_DATE}' 
   AND F.del_flag <> 'D'
) T01;
   
/*STEP3:向临时表插入增量数据(DELETE)*/
INSERT INTO {FDM}.VT_F_PT_ECIF_PUB_CUST_INF
SELECT F.cust_no  -- 客户号
      ,F.core_cust_no  -- 核心客户号
      ,F.org_cust_type_cd  -- 机构客户类型代码
      ,F.credit_loan_cust_type_cd  -- 信贷客户类型代码
      ,F.cy_or_zone_cd  -- 国家或地区代码
      ,F.admin_area_cd  -- 行政区划代码
      ,F.org_type_cd  -- 组织机构类型代码
      ,F.core_org_sub_type_cd  -- 核心组织机构子类型代码
      ,F.org_cd  -- 组织机构代码
      ,F.org_rgst_dt  -- 组织机构登记日期
      ,F.org_vld_dt  -- 组织机构有效日期
      ,F.uscc_cd  -- 统一社会信用代码
      ,F.main_biz_indus_class_cd  -- 主营行业分类代码
      ,F.depu_batt_indus_class_cd  -- 副营行业分类代码
      ,F.ent_nature_cd  -- 企业性质代码
      ,F.bankg_ent_scale_cd  -- 银监企业规模代码
      ,F.ent_scale_pboc  -- 企业规模人行
      ,F.ent_scale_deposit  -- 企业规模存款
      ,F.asset_scale_cd  -- 资产规模代码
      ,F.emp_scale_cd  -- 员工规模代码
      ,F.emp_person_cnt  -- 员工人数
      ,F.economy_type_cd  -- 经济类型代码
      ,F.hold_type_cd  -- 控股类型代码
      ,F.govern_structure  -- 政务民生标志
      ,F.in_cll_type_cd  -- 行内行业类别代码
      ,F.indus_class  -- 行业分类
      ,F.ent_sjectn  -- 企业隶属
      ,F.set_up_dt  -- 成立日期
      ,F.main_biz_biz  -- 主营业务
      ,F.con_oper_biz  -- 兼营业务
      ,F.zone_cd  -- 地区代码
      ,F.loan_card_no_cd  -- 贷款卡号码
      ,F.loan_card_sts_cd  -- 贷款卡状态代码
      ,F.loan_card_passwd  -- 贷款卡密码
      ,F.loan_card_annu_revi_dt  -- 贷款卡年审日期
      ,F.hold_sharehd_or_actual_ctrl_person  -- 控股股东或实际控制人
      ,F.lp_rep_iden_type_cd  -- 法人代表证件类型代码
      ,F.lp_prop  -- 法人属性
      ,F.statu_rep_name  -- 法定代表人名称
      ,F.statu_rep_gender_cd  -- 法定代表人性别代码
      ,F.statu_rep_iden_type_cd  -- 法定代表人证件类型代码
      ,F.statu_rep_iden_no  -- 法定代表人证件号码
      ,F.lp_iden_vld_dt  -- 法人证件有效日期
      ,F.statu_rep_contact_phone_no  -- 法定代表人联系电话
      ,F.statu_rep_hou_reg_addr  -- 法定代表人户籍地址
      ,F.statu_rep_photo  -- 法定代表人照片
      ,F.statu_rep_local_cy_or_zone  -- 法定代表人所在国家或地区
      ,F.finan_report_type_cd  -- 财务报表类型代码
      ,F.tot_asset  -- 总资产
      ,F.net_asset  -- 净资产
      ,F.tot_liabi  -- 总负债
      ,F.year_income  -- 年收入
      ,F.year_prft  -- 年利润
      ,F.indus_sts  -- 行业地位
      ,F.and_mybank_related_rela  -- 与我行关联关系
      ,F.and_mybank_coope_rela  -- 与我行合作关系
      ,F.mybank_sharehd_flag  -- 我行股东标志
      ,F.poses_mybank_shares_amt  -- 拥有我行股份金额
      ,F.commu_addr  -- 通讯地址
      ,F.posta_cd  -- 邮政编码
      ,F.contact_person  -- 联系人
      ,F.contact_phone_no  -- 联系电话
      ,F.fax_no  -- 传真号码
      ,F.mail_addr  -- 邮件地址
      ,F.hmpg  -- 主页
      ,F.national_economy_dept  -- 国民经济部门
      ,F.rmk  -- 备注
      ,F.last_update_sys  -- 最后更新系统
      ,F.last_update_person  -- 最后更新人
      ,F.last_update_tm  -- 最后更新时间
      ,F.txn_seq_no  -- 交易流水号
      ,F.zone_name  -- 地区名称
      ,F.ent_out_tube_cd  -- 企业外管代码
      ,F.tech_lvl  -- 技术水平
      ,F.cust_simp_spel  -- 客户简拼
      ,F.ent_scale_credit  -- 企业规模征信
      ,F.ent_scale_bse  -- 企业规模巴塞尔
      ,F.credit_loan_subdv_indus_type_cd  -- 信贷细分行业类型代码
      ,F.three_agric_flag  -- 三农标志
      ,F.tourism_health_cd  -- 旅游健康行业分类代码
      ,F.tourism_or_heal_indu  -- 旅游业或健康业
      ,'D'        -- D-删除 
      ,DATE '{ETL_TX_DT}'   -- 开始日期  
      ,DATE '{MAX_DATE}'    -- 结束日期
  FROM {FDM}.F_PT_ECIF_PUB_CUST_INF F
  LEFT JOIN {FDM}.ST_F_PT_ECIF_PUB_CUST_INF T
    ON F.cust_no = T.cust_no
 WHERE F.end_dt = DATE '{MAX_DATE}'
   AND F.del_flag<>'D'
   AND T.cust_no IS NULL
;


/*STEP4:将目标表中需要闭链的数据闭链*/
UPDATE {FDM}.F_PT_ECIF_PUB_CUST_INF F    -- ECIF对公客户信息
   SET F.end_dt= DATE '{ETL_TX_DT}'
 WHERE EXISTS( 
SELECT 1 
  FROM {FDM}.VT_F_PT_ECIF_PUB_CUST_INF T   -- ECIF对公客户信息
 WHERE F.end_dt = DATE '{MAX_DATE}'
   AND T.cust_no = F.cust_no
);


/*STEP5:后将新增与修改部分增量数据插入到目标表*/
INSERT INTO {FDM}.F_PT_ECIF_PUB_CUST_INF   -- ECIF对公客户信息
SELECT T.cust_no  -- 客户号
      ,T.core_cust_no  -- 核心客户号
      ,T.org_cust_type_cd  -- 机构客户类型代码
      ,T.credit_loan_cust_type_cd  -- 信贷客户类型代码
      ,T.cy_or_zone_cd  -- 国家或地区代码
      ,T.admin_area_cd  -- 行政区划代码
      ,T.org_type_cd  -- 组织机构类型代码
      ,T.core_org_sub_type_cd  -- 核心组织机构子类型代码
      ,T.org_cd  -- 组织机构代码
      ,T.org_rgst_dt  -- 组织机构登记日期
      ,T.org_vld_dt  -- 组织机构有效日期
      ,T.uscc_cd  -- 统一社会信用代码
      ,T.main_biz_indus_class_cd  -- 主营行业分类代码
      ,T.depu_batt_indus_class_cd  -- 副营行业分类代码
      ,T.ent_nature_cd  -- 企业性质代码
      ,T.bankg_ent_scale_cd  -- 银监企业规模代码
      ,T.ent_scale_pboc  -- 企业规模人行
      ,T.ent_scale_deposit  -- 企业规模存款
      ,T.asset_scale_cd  -- 资产规模代码
      ,T.emp_scale_cd  -- 员工规模代码
      ,T.emp_person_cnt  -- 员工人数
      ,T.economy_type_cd  -- 经济类型代码
      ,T.hold_type_cd  -- 控股类型代码
      ,T.govern_structure  -- 政务民生标志
      ,T.in_cll_type_cd  -- 行内行业类别代码
      ,T.indus_class  -- 行业分类
      ,T.ent_sjectn  -- 企业隶属
      ,T.set_up_dt  -- 成立日期
      ,T.main_biz_biz  -- 主营业务
      ,T.con_oper_biz  -- 兼营业务
      ,T.zone_cd  -- 地区代码
      ,T.loan_card_no_cd  -- 贷款卡号码
      ,T.loan_card_sts_cd  -- 贷款卡状态代码
      ,T.loan_card_passwd  -- 贷款卡密码
      ,T.loan_card_annu_revi_dt  -- 贷款卡年审日期
      ,T.hold_sharehd_or_actual_ctrl_person  -- 控股股东或实际控制人
      ,T.lp_rep_iden_type_cd  -- 法人代表证件类型代码
      ,T.lp_prop  -- 法人属性
      ,T.statu_rep_name  -- 法定代表人名称
      ,T.statu_rep_gender_cd  -- 法定代表人性别代码
      ,T.statu_rep_iden_type_cd  -- 法定代表人证件类型代码
      ,T.statu_rep_iden_no  -- 法定代表人证件号码
      ,T.lp_iden_vld_dt  -- 法人证件有效日期
      ,T.statu_rep_contact_phone_no  -- 法定代表人联系电话
      ,T.statu_rep_hou_reg_addr  -- 法定代表人户籍地址
      ,T.statu_rep_photo  -- 法定代表人照片
      ,T.statu_rep_local_cy_or_zone  -- 法定代表人所在国家或地区
      ,T.finan_report_type_cd  -- 财务报表类型代码
      ,T.tot_asset  -- 总资产
      ,T.net_asset  -- 净资产
      ,T.tot_liabi  -- 总负债
      ,T.year_income  -- 年收入
      ,T.year_prft  -- 年利润
      ,T.indus_sts  -- 行业地位
      ,T.and_mybank_related_rela  -- 与我行关联关系
      ,T.and_mybank_coope_rela  -- 与我行合作关系
      ,T.mybank_sharehd_flag  -- 我行股东标志
      ,T.poses_mybank_shares_amt  -- 拥有我行股份金额
      ,T.commu_addr  -- 通讯地址
      ,T.posta_cd  -- 邮政编码
      ,T.contact_person  -- 联系人
      ,T.contact_phone_no  -- 联系电话
      ,T.fax_no  -- 传真号码
      ,T.mail_addr  -- 邮件地址
      ,T.hmpg  -- 主页
      ,T.national_economy_dept  -- 国民经济部门
      ,T.rmk  -- 备注
      ,T.last_update_sys  -- 最后更新系统
      ,T.last_update_person  -- 最后更新人
      ,T.last_update_tm  -- 最后更新时间
      ,T.txn_seq_no  -- 交易流水号
      ,T.zone_name  -- 地区名称
      ,T.ent_out_tube_cd  -- 企业外管代码
      ,T.tech_lvl  -- 技术水平
      ,T.cust_simp_spel  -- 客户简拼
      ,T.ent_scale_credit  -- 企业规模征信
      ,T.ent_scale_bse  -- 企业规模巴塞尔
      ,T.credit_loan_subdv_indus_type_cd  -- 信贷细分行业类型代码
      ,T.three_agric_flag  -- 三农标志
      ,T.tourism_health_cd  -- 旅游健康行业分类代码
      ,T.tourism_or_heal_indu  -- 旅游业或健康业
      ,T.del_flag            -- 删除标记  
      ,DATE '{ETL_TX_DT}'   -- 开始日期  
      ,DATE '{MAX_DATE}'    -- 结束日期
  FROM {FDM}.VT_F_PT_ECIF_PUB_CUST_INF T  
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