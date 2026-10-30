# *****************************************头部注释模块**************************************
#脚本名称：dwa_o_ecif_m_ci_org.py
#功能说明：ODM拉链脚本
#更新方式：T4：带删除拉链
#更新对象：ODM.O_ECIF_M_CI_ORG
#创 建 人：shileqi
#创建日期：2025-09-01
#运行周期：日
#备    注：数据仓库缓冲层到贴源层带删除拉链数据处理
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
dwa_db_odm = etl.dwa_db_odm
dwa_username = etl.dwa_usr_odm
dwa_password = etl.dwa_pwd_odm
TDM = etl.dwa_db_tdm
ODM = etl.dwa_db_odm

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
        gsql_str_p = f"set -o pipefail; gccli -h {dwa_host} -P {dwa_port} -u {dwa_username} -p\"{dwa_password}\" -D {dwa_db_odm} -v -v |tee -a {log_filename}"
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
bsql="""

/*防止批量重跑处理,1.删除新增数据 2.打开闭链数据*/
DELETE FROM {ODM}.O_ECIF_M_CI_ORG    -- 
 WHERE start_dt >=DATE '{ETL_TX_DT}';
select sysdate();

UPDATE {ODM}.O_ECIF_M_CI_ORG    -- 
   SET end_dt=DATE '{MAX_DATE}'
 WHERE end_dt >=DATE '{ETL_TX_DT}' 
   AND end_dt <DATE '{MAX_DATE}';
   
   
/*SETP1:创建临时表,存放新增修改删除数据*/
DROP TABLE IF EXISTS {ODM}.VT_O_ECIF_M_CI_ORG;
CREATE TEMPORARY TABLE IF NOT EXISTS {ODM}.VT_O_ECIF_M_CI_ORG
DISTRIBUTED BY (
       'cust_id'
)
AS(
SELECT O.cust_id  -- 客户标识
      ,O.org_cust_type  -- 机构客户类型
      ,O.cif_alias  -- 用户网银别名
      ,O.churcustype  -- 信贷客户类型
      ,O.nation_code  -- 国家或地区代码
      ,O.area_code  -- 行政区划代码
      ,O.org_type  -- 组织机构类型
      ,O.org_sub_type  -- 组织机构子类型（核心）
      ,O.org_code  -- 组织机构代码
      ,O.org_reg_date  -- 组织机构登记日期
      ,O.org_exp_date  -- 组织机构有效日期
      ,O.org_code_unit  -- 代码证颁发机关
      ,O.org_code_ann_date  -- 代码证年检到期日
      ,O.busi_lic_no  -- 统一社会信用代码号
      ,O.main_industry  -- 行业分类（主营）
      ,O.minor_industry  -- 行业分类（副营）
      ,O.industry_division  -- 产业划分
      ,O.industry_char  -- 行业特征
      ,O.ent_property  -- 企业性质
      ,O.ent_scale  -- 企业规模（银监）
      ,O.ent_scale_rh  -- 企业规模（人行）
      ,O.ent_scale_ck  -- 企业规模（存款）
      ,O.assets_scale  -- 资产规模
      ,O.employee_scale  -- 员工规模
      ,O.employeenumber  -- 员工人数
      ,O.economic_type  -- 经济类型
      ,O.nxshgm  -- 销售规模
      ,O.smallentscope  -- 小企业类型
      ,O.com_hold_type  -- 控股类型
      ,O.org_form  -- 组织形式
      ,O.govern_structure  -- 是否政务民生0否,1是
      ,O.in_cll_type  -- 行内行业类别
      ,O.industry_category  -- 行业分类（企业规模）
      ,O.invest_type  -- 投资主体
      ,O.ent_belong  -- 企业隶属
      ,O.build_date  -- 成立日期
      ,O.super_dept  -- 主管部门
      ,O.main_business  -- 主营业务
      ,O.minor_business  -- 客户兼营业务
      ,O.business_mode  -- 经营方式
      ,O.jycszk  -- 经营场所状况
      ,O.busi_start_date  -- 开始营业时间
      ,O.indu_deve_prospect  -- 行业发展前景
      ,O.fund_source  -- 经费来源
      ,O.zone_code  -- 地区代码
      ,O.fexc_prm_code  -- 外汇许可证号码
      ,O.top_corp_level  -- 产业化龙头企业级别
      ,O.com_sp_business  -- 特种经营标识
      ,O.com_sp_lic_no  -- 特种经营许可证编号
      ,O.com_sp_detail  -- 特种经营情况
      ,O.com_sp_lic_org  -- 特种许可证颁发机关
      ,O.com_sp_str_date  -- 特种经营起始日期
      ,O.com_sp_end_date  -- 特种经营到期日期
      ,O.loan_cust_rank  -- 信贷客户分类
      ,O.loan_cust_stat  -- 信贷客户状态
      ,O.loan_card_flag  -- 有无贷款卡
      ,O.loan_card_no  -- 贷款卡号码
      ,O.loan_card_stat  -- 贷款卡状态
      ,O.load_card_pwd  -- 贷款卡密码
      ,O.load_card_audit_dt  -- 贷款卡年审日期
      ,O.konggr  -- 控股股东或实际控制人
      ,O.frdblb  -- 法人代表类别
      ,O.frjmsx  -- 法人属性
      ,O.legal_repr_name  -- 法定代表人名称
      ,O.legal_repr_gender  -- 法定代表人性别
      ,O.legal_repr_ident_type  -- 法定代表人证件类型
      ,O.legal_repr_ident_no  -- 法定代表人证件号码
      ,O.frzjyx  -- 法人证件有效日期
      ,O.legal_repr_tel  -- 法定代表人联系电话
      ,O.legal_repr_addr  -- 法定代表人户籍地址
      ,O.legal_repr_photo  -- 法定代表人照片
      ,O.legal_repr_nation_code  -- 法定代表人所在国家（地区）
      ,O.fin_rep_type  -- 财务报表类型
      ,O.total_assets  -- 总资产
      ,O.net_assets  -- 净资产
      ,O.total_debt  -- 总负债
      ,O.annual_income  -- 年收入
      ,O.annual_profit  -- 年利润
      ,O.industry_position  -- 行业地位
      ,O.cus_bank_rel  -- 与我行关联关系
      ,O.cus_corp_rel  -- 与我行合作关系
      ,O.is_stock_holder  -- 是否我行股东
      ,O.hold_stock_amt  -- 拥有我行股份金额
      ,O.org_addr  -- 通讯地址
      ,O.org_zipcode  -- 邮政编码
      ,O.org_cus  -- 联系人
      ,O.org_tel  -- 联系电话
      ,O.org_fex  -- 传真号码
      ,O.org_email  -- 邮件地址
      ,O.org_homepage  -- 主页
      ,O.org_weibo  -- 微博
      ,O.org_weixin  -- 微信
      ,O.last_dealings_desc  -- 前次来行状况
      ,O.natiecodept  -- 国民经济部门
      ,O.remarks  -- 备注
      ,O.last_update_sys  -- 最后更新系统
      ,O.last_update_user  -- 最后更新人
      ,O.last_update_tm  -- 最后更新时间
      ,O.tx_seq_no  -- 交易流水号
      ,O.zone_name  -- 地区名称
      ,O.outer_mgr_code  -- 企业外管代码
      ,O.tech_level  -- 技术水平
      ,O.name_simp_spell  -- 客户简拼
      ,O.used_name  -- 曾用名
      ,O.ent_scale_zx  -- 企业规模（征信）
      ,O.ent_scale_bse  -- 企业规模（巴塞尔）
      ,O.industry_sub_type  -- 信贷细分行业类型
      ,O.is_three_agriculture  -- 是否三农
      ,O.tourism_health  -- 旅游健康行业分类
      ,O.tourism_or_health  -- 旅游业/健康业
      ,O.te_ding_fei  -- 特定非金融客户
      ,O.check_results  -- 受益人核对结果
      ,O.check_date  -- 受益人核对日期
      ,' ' as del_ind  -- 删除标识
  FROM {ODM}.O_ECIF_M_CI_ORG O
 LIMIT 0 
);

/*SETP2:向临时表插入新增、修改数据*/
INSERT INTO {ODM}.VT_O_ECIF_M_CI_ORG
SELECT  T01.cust_id  -- 客户标识
       ,T01.org_cust_type  -- 机构客户类型
       ,T01.cif_alias  -- 用户网银别名
       ,T01.churcustype  -- 信贷客户类型
       ,T01.nation_code  -- 国家或地区代码
       ,T01.area_code  -- 行政区划代码
       ,T01.org_type  -- 组织机构类型
       ,T01.org_sub_type  -- 组织机构子类型（核心）
       ,T01.org_code  -- 组织机构代码
       ,T01.org_reg_date  -- 组织机构登记日期
       ,T01.org_exp_date  -- 组织机构有效日期
       ,T01.org_code_unit  -- 代码证颁发机关
       ,T01.org_code_ann_date  -- 代码证年检到期日
       ,T01.busi_lic_no  -- 统一社会信用代码号
       ,T01.main_industry  -- 行业分类（主营）
       ,T01.minor_industry  -- 行业分类（副营）
       ,T01.industry_division  -- 产业划分
       ,T01.industry_char  -- 行业特征
       ,T01.ent_property  -- 企业性质
       ,T01.ent_scale  -- 企业规模（银监）
       ,T01.ent_scale_rh  -- 企业规模（人行）
       ,T01.ent_scale_ck  -- 企业规模（存款）
       ,T01.assets_scale  -- 资产规模
       ,T01.employee_scale  -- 员工规模
       ,T01.employeenumber  -- 员工人数
       ,T01.economic_type  -- 经济类型
       ,T01.nxshgm  -- 销售规模
       ,T01.smallentscope  -- 小企业类型
       ,T01.com_hold_type  -- 控股类型
       ,T01.org_form  -- 组织形式
       ,T01.govern_structure  -- 是否政务民生0否,1是
       ,T01.in_cll_type  -- 行内行业类别
       ,T01.industry_category  -- 行业分类（企业规模）
       ,T01.invest_type  -- 投资主体
       ,T01.ent_belong  -- 企业隶属
       ,T01.build_date  -- 成立日期
       ,T01.super_dept  -- 主管部门
       ,T01.main_business  -- 主营业务
       ,T01.minor_business  -- 客户兼营业务
       ,T01.business_mode  -- 经营方式
       ,T01.jycszk  -- 经营场所状况
       ,T01.busi_start_date  -- 开始营业时间
       ,T01.indu_deve_prospect  -- 行业发展前景
       ,T01.fund_source  -- 经费来源
       ,T01.zone_code  -- 地区代码
       ,T01.fexc_prm_code  -- 外汇许可证号码
       ,T01.top_corp_level  -- 产业化龙头企业级别
       ,T01.com_sp_business  -- 特种经营标识
       ,T01.com_sp_lic_no  -- 特种经营许可证编号
       ,T01.com_sp_detail  -- 特种经营情况
       ,T01.com_sp_lic_org  -- 特种许可证颁发机关
       ,T01.com_sp_str_date  -- 特种经营起始日期
       ,T01.com_sp_end_date  -- 特种经营到期日期
       ,T01.loan_cust_rank  -- 信贷客户分类
       ,T01.loan_cust_stat  -- 信贷客户状态
       ,T01.loan_card_flag  -- 有无贷款卡
       ,T01.loan_card_no  -- 贷款卡号码
       ,T01.loan_card_stat  -- 贷款卡状态
       ,T01.load_card_pwd  -- 贷款卡密码
       ,T01.load_card_audit_dt  -- 贷款卡年审日期
       ,T01.konggr  -- 控股股东或实际控制人
       ,T01.frdblb  -- 法人代表类别
       ,T01.frjmsx  -- 法人属性
       ,T01.legal_repr_name  -- 法定代表人名称
       ,T01.legal_repr_gender  -- 法定代表人性别
       ,T01.legal_repr_ident_type  -- 法定代表人证件类型
       ,T01.legal_repr_ident_no  -- 法定代表人证件号码
       ,T01.frzjyx  -- 法人证件有效日期
       ,T01.legal_repr_tel  -- 法定代表人联系电话
       ,T01.legal_repr_addr  -- 法定代表人户籍地址
       ,T01.legal_repr_photo  -- 法定代表人照片
       ,T01.legal_repr_nation_code  -- 法定代表人所在国家（地区）
       ,T01.fin_rep_type  -- 财务报表类型
       ,T01.total_assets  -- 总资产
       ,T01.net_assets  -- 净资产
       ,T01.total_debt  -- 总负债
       ,T01.annual_income  -- 年收入
       ,T01.annual_profit  -- 年利润
       ,T01.industry_position  -- 行业地位
       ,T01.cus_bank_rel  -- 与我行关联关系
       ,T01.cus_corp_rel  -- 与我行合作关系
       ,T01.is_stock_holder  -- 是否我行股东
       ,T01.hold_stock_amt  -- 拥有我行股份金额
       ,T01.org_addr  -- 通讯地址
       ,T01.org_zipcode  -- 邮政编码
       ,T01.org_cus  -- 联系人
       ,T01.org_tel  -- 联系电话
       ,T01.org_fex  -- 传真号码
       ,T01.org_email  -- 邮件地址
       ,T01.org_homepage  -- 主页
       ,T01.org_weibo  -- 微博
       ,T01.org_weixin  -- 微信
       ,T01.last_dealings_desc  -- 前次来行状况
       ,T01.natiecodept  -- 国民经济部门
       ,T01.remarks  -- 备注
       ,T01.last_update_sys  -- 最后更新系统
       ,T01.last_update_user  -- 最后更新人
       ,T01.last_update_tm  -- 最后更新时间
       ,T01.tx_seq_no  -- 交易流水号
       ,T01.zone_name  -- 地区名称
       ,T01.outer_mgr_code  -- 企业外管代码
       ,T01.tech_level  -- 技术水平
       ,T01.name_simp_spell  -- 客户简拼
       ,T01.used_name  -- 曾用名
       ,T01.ent_scale_zx  -- 企业规模（征信）
       ,T01.ent_scale_bse  -- 企业规模（巴塞尔）
       ,T01.industry_sub_type  -- 信贷细分行业类型
       ,T01.is_three_agriculture  -- 是否三农
       ,T01.tourism_health  -- 旅游健康行业分类
       ,T01.tourism_or_health  -- 旅游业/健康业
       ,T01.te_ding_fei  -- 特定非金融客户
       ,T01.check_results  -- 受益人核对结果
       ,T01.check_date  -- 受益人核对日期
      ,' '             -- 删除标识
  FROM (
SELECT T1.cust_id  -- 客户标识
      ,T1.org_cust_type  -- 机构客户类型
      ,T1.cif_alias  -- 用户网银别名
      ,T1.churcustype  -- 信贷客户类型
      ,T1.nation_code  -- 国家或地区代码
      ,T1.area_code  -- 行政区划代码
      ,T1.org_type  -- 组织机构类型
      ,T1.org_sub_type  -- 组织机构子类型（核心）
      ,T1.org_code  -- 组织机构代码
      ,T1.org_reg_date  -- 组织机构登记日期
      ,T1.org_exp_date  -- 组织机构有效日期
      ,T1.org_code_unit  -- 代码证颁发机关
      ,T1.org_code_ann_date  -- 代码证年检到期日
      ,T1.busi_lic_no  -- 统一社会信用代码号
      ,T1.main_industry  -- 行业分类（主营）
      ,T1.minor_industry  -- 行业分类（副营）
      ,T1.industry_division  -- 产业划分
      ,T1.industry_char  -- 行业特征
      ,T1.ent_property  -- 企业性质
      ,T1.ent_scale  -- 企业规模（银监）
      ,T1.ent_scale_rh  -- 企业规模（人行）
      ,T1.ent_scale_ck  -- 企业规模（存款）
      ,T1.assets_scale  -- 资产规模
      ,T1.employee_scale  -- 员工规模
      ,T1.employeenumber  -- 员工人数
      ,T1.economic_type  -- 经济类型
      ,T1.nxshgm  -- 销售规模
      ,T1.smallentscope  -- 小企业类型
      ,T1.com_hold_type  -- 控股类型
      ,T1.org_form  -- 组织形式
      ,T1.govern_structure  -- 是否政务民生0否,1是
      ,T1.in_cll_type  -- 行内行业类别
      ,T1.industry_category  -- 行业分类（企业规模）
      ,T1.invest_type  -- 投资主体
      ,T1.ent_belong  -- 企业隶属
      ,T1.build_date  -- 成立日期
      ,T1.super_dept  -- 主管部门
      ,T1.main_business  -- 主营业务
      ,T1.minor_business  -- 客户兼营业务
      ,T1.business_mode  -- 经营方式
      ,T1.jycszk  -- 经营场所状况
      ,T1.busi_start_date  -- 开始营业时间
      ,T1.indu_deve_prospect  -- 行业发展前景
      ,T1.fund_source  -- 经费来源
      ,T1.zone_code  -- 地区代码
      ,T1.fexc_prm_code  -- 外汇许可证号码
      ,T1.top_corp_level  -- 产业化龙头企业级别
      ,T1.com_sp_business  -- 特种经营标识
      ,T1.com_sp_lic_no  -- 特种经营许可证编号
      ,T1.com_sp_detail  -- 特种经营情况
      ,T1.com_sp_lic_org  -- 特种许可证颁发机关
      ,T1.com_sp_str_date  -- 特种经营起始日期
      ,T1.com_sp_end_date  -- 特种经营到期日期
      ,T1.loan_cust_rank  -- 信贷客户分类
      ,T1.loan_cust_stat  -- 信贷客户状态
      ,T1.loan_card_flag  -- 有无贷款卡
      ,T1.loan_card_no  -- 贷款卡号码
      ,T1.loan_card_stat  -- 贷款卡状态
      ,T1.load_card_pwd  -- 贷款卡密码
      ,T1.load_card_audit_dt  -- 贷款卡年审日期
      ,T1.konggr  -- 控股股东或实际控制人
      ,T1.frdblb  -- 法人代表类别
      ,T1.frjmsx  -- 法人属性
      ,T1.legal_repr_name  -- 法定代表人名称
      ,T1.legal_repr_gender  -- 法定代表人性别
      ,T1.legal_repr_ident_type  -- 法定代表人证件类型
      ,T1.legal_repr_ident_no  -- 法定代表人证件号码
      ,T1.frzjyx  -- 法人证件有效日期
      ,T1.legal_repr_tel  -- 法定代表人联系电话
      ,T1.legal_repr_addr  -- 法定代表人户籍地址
      ,T1.legal_repr_photo  -- 法定代表人照片
      ,T1.legal_repr_nation_code  -- 法定代表人所在国家（地区）
      ,T1.fin_rep_type  -- 财务报表类型
      ,T1.total_assets  -- 总资产
      ,T1.net_assets  -- 净资产
      ,T1.total_debt  -- 总负债
      ,T1.annual_income  -- 年收入
      ,T1.annual_profit  -- 年利润
      ,T1.industry_position  -- 行业地位
      ,T1.cus_bank_rel  -- 与我行关联关系
      ,T1.cus_corp_rel  -- 与我行合作关系
      ,T1.is_stock_holder  -- 是否我行股东
      ,T1.hold_stock_amt  -- 拥有我行股份金额
      ,T1.org_addr  -- 通讯地址
      ,T1.org_zipcode  -- 邮政编码
      ,T1.org_cus  -- 联系人
      ,T1.org_tel  -- 联系电话
      ,T1.org_fex  -- 传真号码
      ,T1.org_email  -- 邮件地址
      ,T1.org_homepage  -- 主页
      ,T1.org_weibo  -- 微博
      ,T1.org_weixin  -- 微信
      ,T1.last_dealings_desc  -- 前次来行状况
      ,T1.natiecodept  -- 国民经济部门
      ,T1.remarks  -- 备注
      ,T1.last_update_sys  -- 最后更新系统
      ,T1.last_update_user  -- 最后更新人
      ,T1.last_update_tm  -- 最后更新时间
      ,T1.tx_seq_no  -- 交易流水号
      ,T1.zone_name  -- 地区名称
      ,T1.outer_mgr_code  -- 企业外管代码
      ,T1.tech_level  -- 技术水平
      ,T1.name_simp_spell  -- 客户简拼
      ,T1.used_name  -- 曾用名
      ,T1.ent_scale_zx  -- 企业规模（征信）
      ,T1.ent_scale_bse  -- 企业规模（巴塞尔）
      ,T1.industry_sub_type  -- 信贷细分行业类型
      ,T1.is_three_agriculture  -- 是否三农
      ,T1.tourism_health  -- 旅游健康行业分类
      ,T1.tourism_or_health  -- 旅游业/健康业
      ,T1.te_ding_fei  -- 特定非金融客户
      ,T1.check_results  -- 受益人核对结果
      ,T1.check_date  -- 受益人核对日期
  FROM {TDM}.T_ECIF_M_CI_ORG_{ETL_TX_DT}  T1 
EXCEPT ALL
SELECT O.cust_id  -- 客户标识
      ,O.org_cust_type  -- 机构客户类型
      ,O.cif_alias  -- 用户网银别名
      ,O.churcustype  -- 信贷客户类型
      ,O.nation_code  -- 国家或地区代码
      ,O.area_code  -- 行政区划代码
      ,O.org_type  -- 组织机构类型
      ,O.org_sub_type  -- 组织机构子类型（核心）
      ,O.org_code  -- 组织机构代码
      ,O.org_reg_date  -- 组织机构登记日期
      ,O.org_exp_date  -- 组织机构有效日期
      ,O.org_code_unit  -- 代码证颁发机关
      ,O.org_code_ann_date  -- 代码证年检到期日
      ,O.busi_lic_no  -- 统一社会信用代码号
      ,O.main_industry  -- 行业分类（主营）
      ,O.minor_industry  -- 行业分类（副营）
      ,O.industry_division  -- 产业划分
      ,O.industry_char  -- 行业特征
      ,O.ent_property  -- 企业性质
      ,O.ent_scale  -- 企业规模（银监）
      ,O.ent_scale_rh  -- 企业规模（人行）
      ,O.ent_scale_ck  -- 企业规模（存款）
      ,O.assets_scale  -- 资产规模
      ,O.employee_scale  -- 员工规模
      ,O.employeenumber  -- 员工人数
      ,O.economic_type  -- 经济类型
      ,O.nxshgm  -- 销售规模
      ,O.smallentscope  -- 小企业类型
      ,O.com_hold_type  -- 控股类型
      ,O.org_form  -- 组织形式
      ,O.govern_structure  -- 是否政务民生0否,1是
      ,O.in_cll_type  -- 行内行业类别
      ,O.industry_category  -- 行业分类（企业规模）
      ,O.invest_type  -- 投资主体
      ,O.ent_belong  -- 企业隶属
      ,O.build_date  -- 成立日期
      ,O.super_dept  -- 主管部门
      ,O.main_business  -- 主营业务
      ,O.minor_business  -- 客户兼营业务
      ,O.business_mode  -- 经营方式
      ,O.jycszk  -- 经营场所状况
      ,O.busi_start_date  -- 开始营业时间
      ,O.indu_deve_prospect  -- 行业发展前景
      ,O.fund_source  -- 经费来源
      ,O.zone_code  -- 地区代码
      ,O.fexc_prm_code  -- 外汇许可证号码
      ,O.top_corp_level  -- 产业化龙头企业级别
      ,O.com_sp_business  -- 特种经营标识
      ,O.com_sp_lic_no  -- 特种经营许可证编号
      ,O.com_sp_detail  -- 特种经营情况
      ,O.com_sp_lic_org  -- 特种许可证颁发机关
      ,O.com_sp_str_date  -- 特种经营起始日期
      ,O.com_sp_end_date  -- 特种经营到期日期
      ,O.loan_cust_rank  -- 信贷客户分类
      ,O.loan_cust_stat  -- 信贷客户状态
      ,O.loan_card_flag  -- 有无贷款卡
      ,O.loan_card_no  -- 贷款卡号码
      ,O.loan_card_stat  -- 贷款卡状态
      ,O.load_card_pwd  -- 贷款卡密码
      ,O.load_card_audit_dt  -- 贷款卡年审日期
      ,O.konggr  -- 控股股东或实际控制人
      ,O.frdblb  -- 法人代表类别
      ,O.frjmsx  -- 法人属性
      ,O.legal_repr_name  -- 法定代表人名称
      ,O.legal_repr_gender  -- 法定代表人性别
      ,O.legal_repr_ident_type  -- 法定代表人证件类型
      ,O.legal_repr_ident_no  -- 法定代表人证件号码
      ,O.frzjyx  -- 法人证件有效日期
      ,O.legal_repr_tel  -- 法定代表人联系电话
      ,O.legal_repr_addr  -- 法定代表人户籍地址
      ,O.legal_repr_photo  -- 法定代表人照片
      ,O.legal_repr_nation_code  -- 法定代表人所在国家（地区）
      ,O.fin_rep_type  -- 财务报表类型
      ,O.total_assets  -- 总资产
      ,O.net_assets  -- 净资产
      ,O.total_debt  -- 总负债
      ,O.annual_income  -- 年收入
      ,O.annual_profit  -- 年利润
      ,O.industry_position  -- 行业地位
      ,O.cus_bank_rel  -- 与我行关联关系
      ,O.cus_corp_rel  -- 与我行合作关系
      ,O.is_stock_holder  -- 是否我行股东
      ,O.hold_stock_amt  -- 拥有我行股份金额
      ,O.org_addr  -- 通讯地址
      ,O.org_zipcode  -- 邮政编码
      ,O.org_cus  -- 联系人
      ,O.org_tel  -- 联系电话
      ,O.org_fex  -- 传真号码
      ,O.org_email  -- 邮件地址
      ,O.org_homepage  -- 主页
      ,O.org_weibo  -- 微博
      ,O.org_weixin  -- 微信
      ,O.last_dealings_desc  -- 前次来行状况
      ,O.natiecodept  -- 国民经济部门
      ,O.remarks  -- 备注
      ,O.last_update_sys  -- 最后更新系统
      ,O.last_update_user  -- 最后更新人
      ,O.last_update_tm  -- 最后更新时间
      ,O.tx_seq_no  -- 交易流水号
      ,O.zone_name  -- 地区名称
      ,O.outer_mgr_code  -- 企业外管代码
      ,O.tech_level  -- 技术水平
      ,O.name_simp_spell  -- 客户简拼
      ,O.used_name  -- 曾用名
      ,O.ent_scale_zx  -- 企业规模（征信）
      ,O.ent_scale_bse  -- 企业规模（巴塞尔）
      ,O.industry_sub_type  -- 信贷细分行业类型
      ,O.is_three_agriculture  -- 是否三农
      ,O.tourism_health  -- 旅游健康行业分类
      ,O.tourism_or_health  -- 旅游业/健康业
      ,O.te_ding_fei  -- 特定非金融客户
      ,O.check_results  -- 受益人核对结果
      ,O.check_date  -- 受益人核对日期
  FROM {ODM}.O_ECIF_M_CI_ORG O
 WHERE O.end_dt = DATE '{MAX_DATE}' 
   AND O.del_ind<>'D'
) T01
;

/*STEP3:向临时表插入增量数据(DELETE)*/
select sysdate();
INSERT INTO {ODM}.VT_O_ECIF_M_CI_ORG
SELECT O.cust_id  -- 客户标识
      ,O.org_cust_type  -- 机构客户类型
      ,O.cif_alias  -- 用户网银别名
      ,O.churcustype  -- 信贷客户类型
      ,O.nation_code  -- 国家或地区代码
      ,O.area_code  -- 行政区划代码
      ,O.org_type  -- 组织机构类型
      ,O.org_sub_type  -- 组织机构子类型（核心）
      ,O.org_code  -- 组织机构代码
      ,O.org_reg_date  -- 组织机构登记日期
      ,O.org_exp_date  -- 组织机构有效日期
      ,O.org_code_unit  -- 代码证颁发机关
      ,O.org_code_ann_date  -- 代码证年检到期日
      ,O.busi_lic_no  -- 统一社会信用代码号
      ,O.main_industry  -- 行业分类（主营）
      ,O.minor_industry  -- 行业分类（副营）
      ,O.industry_division  -- 产业划分
      ,O.industry_char  -- 行业特征
      ,O.ent_property  -- 企业性质
      ,O.ent_scale  -- 企业规模（银监）
      ,O.ent_scale_rh  -- 企业规模（人行）
      ,O.ent_scale_ck  -- 企业规模（存款）
      ,O.assets_scale  -- 资产规模
      ,O.employee_scale  -- 员工规模
      ,O.employeenumber  -- 员工人数
      ,O.economic_type  -- 经济类型
      ,O.nxshgm  -- 销售规模
      ,O.smallentscope  -- 小企业类型
      ,O.com_hold_type  -- 控股类型
      ,O.org_form  -- 组织形式
      ,O.govern_structure  -- 是否政务民生0否,1是
      ,O.in_cll_type  -- 行内行业类别
      ,O.industry_category  -- 行业分类（企业规模）
      ,O.invest_type  -- 投资主体
      ,O.ent_belong  -- 企业隶属
      ,O.build_date  -- 成立日期
      ,O.super_dept  -- 主管部门
      ,O.main_business  -- 主营业务
      ,O.minor_business  -- 客户兼营业务
      ,O.business_mode  -- 经营方式
      ,O.jycszk  -- 经营场所状况
      ,O.busi_start_date  -- 开始营业时间
      ,O.indu_deve_prospect  -- 行业发展前景
      ,O.fund_source  -- 经费来源
      ,O.zone_code  -- 地区代码
      ,O.fexc_prm_code  -- 外汇许可证号码
      ,O.top_corp_level  -- 产业化龙头企业级别
      ,O.com_sp_business  -- 特种经营标识
      ,O.com_sp_lic_no  -- 特种经营许可证编号
      ,O.com_sp_detail  -- 特种经营情况
      ,O.com_sp_lic_org  -- 特种许可证颁发机关
      ,O.com_sp_str_date  -- 特种经营起始日期
      ,O.com_sp_end_date  -- 特种经营到期日期
      ,O.loan_cust_rank  -- 信贷客户分类
      ,O.loan_cust_stat  -- 信贷客户状态
      ,O.loan_card_flag  -- 有无贷款卡
      ,O.loan_card_no  -- 贷款卡号码
      ,O.loan_card_stat  -- 贷款卡状态
      ,O.load_card_pwd  -- 贷款卡密码
      ,O.load_card_audit_dt  -- 贷款卡年审日期
      ,O.konggr  -- 控股股东或实际控制人
      ,O.frdblb  -- 法人代表类别
      ,O.frjmsx  -- 法人属性
      ,O.legal_repr_name  -- 法定代表人名称
      ,O.legal_repr_gender  -- 法定代表人性别
      ,O.legal_repr_ident_type  -- 法定代表人证件类型
      ,O.legal_repr_ident_no  -- 法定代表人证件号码
      ,O.frzjyx  -- 法人证件有效日期
      ,O.legal_repr_tel  -- 法定代表人联系电话
      ,O.legal_repr_addr  -- 法定代表人户籍地址
      ,O.legal_repr_photo  -- 法定代表人照片
      ,O.legal_repr_nation_code  -- 法定代表人所在国家（地区）
      ,O.fin_rep_type  -- 财务报表类型
      ,O.total_assets  -- 总资产
      ,O.net_assets  -- 净资产
      ,O.total_debt  -- 总负债
      ,O.annual_income  -- 年收入
      ,O.annual_profit  -- 年利润
      ,O.industry_position  -- 行业地位
      ,O.cus_bank_rel  -- 与我行关联关系
      ,O.cus_corp_rel  -- 与我行合作关系
      ,O.is_stock_holder  -- 是否我行股东
      ,O.hold_stock_amt  -- 拥有我行股份金额
      ,O.org_addr  -- 通讯地址
      ,O.org_zipcode  -- 邮政编码
      ,O.org_cus  -- 联系人
      ,O.org_tel  -- 联系电话
      ,O.org_fex  -- 传真号码
      ,O.org_email  -- 邮件地址
      ,O.org_homepage  -- 主页
      ,O.org_weibo  -- 微博
      ,O.org_weixin  -- 微信
      ,O.last_dealings_desc  -- 前次来行状况
      ,O.natiecodept  -- 国民经济部门
      ,O.remarks  -- 备注
      ,O.last_update_sys  -- 最后更新系统
      ,O.last_update_user  -- 最后更新人
      ,O.last_update_tm  -- 最后更新时间
      ,O.tx_seq_no  -- 交易流水号
      ,O.zone_name  -- 地区名称
      ,O.outer_mgr_code  -- 企业外管代码
      ,O.tech_level  -- 技术水平
      ,O.name_simp_spell  -- 客户简拼
      ,O.used_name  -- 曾用名
      ,O.ent_scale_zx  -- 企业规模（征信）
      ,O.ent_scale_bse  -- 企业规模（巴塞尔）
      ,O.industry_sub_type  -- 信贷细分行业类型
      ,O.is_three_agriculture  -- 是否三农
      ,O.tourism_health  -- 旅游健康行业分类
      ,O.tourism_or_health  -- 旅游业/健康业
      ,O.te_ding_fei  -- 特定非金融客户
      ,O.check_results  -- 受益人核对结果
      ,O.check_date  -- 受益人核对日期
      ,'D'        -- D-删除 
  FROM {ODM}.O_ECIF_M_CI_ORG O
  LEFT JOIN {TDM}.T_ECIF_M_CI_ORG_{ETL_TX_DT} T
    ON O.cust_id = T.cust_id
 WHERE O.end_dt = DATE '{MAX_DATE}'
   AND O.del_ind<>'D'
   AND T.cust_id IS NULL
;
select sysdate();

/*STEP4:将目标表中需要闭链的数据闭链*/
UPDATE {ODM}.O_ECIF_M_CI_ORG O    -- 
   SET O.end_dt= DATE '{ETL_TX_DT}'
 WHERE EXISTS( 
SELECT 1 
  FROM {ODM}.VT_O_ECIF_M_CI_ORG T   
 WHERE 1 = 1 
   AND O.end_dt = DATE '{MAX_DATE}'
   AND T.cust_id = O.cust_id
);
select sysdate();

/*STEP5:后将新增与修改部分增量数据插入到目标表*/
INSERT INTO {ODM}.O_ECIF_M_CI_ORG   -- 
SELECT T.cust_id  -- 客户标识
      ,T.org_cust_type  -- 机构客户类型
      ,T.cif_alias  -- 用户网银别名
      ,T.churcustype  -- 信贷客户类型
      ,T.nation_code  -- 国家或地区代码
      ,T.area_code  -- 行政区划代码
      ,T.org_type  -- 组织机构类型
      ,T.org_sub_type  -- 组织机构子类型（核心）
      ,T.org_code  -- 组织机构代码
      ,T.org_reg_date  -- 组织机构登记日期
      ,T.org_exp_date  -- 组织机构有效日期
      ,T.org_code_unit  -- 代码证颁发机关
      ,T.org_code_ann_date  -- 代码证年检到期日
      ,T.busi_lic_no  -- 统一社会信用代码号
      ,T.main_industry  -- 行业分类（主营）
      ,T.minor_industry  -- 行业分类（副营）
      ,T.industry_division  -- 产业划分
      ,T.industry_char  -- 行业特征
      ,T.ent_property  -- 企业性质
      ,T.ent_scale  -- 企业规模（银监）
      ,T.ent_scale_rh  -- 企业规模（人行）
      ,T.ent_scale_ck  -- 企业规模（存款）
      ,T.assets_scale  -- 资产规模
      ,T.employee_scale  -- 员工规模
      ,T.employeenumber  -- 员工人数
      ,T.economic_type  -- 经济类型
      ,T.nxshgm  -- 销售规模
      ,T.smallentscope  -- 小企业类型
      ,T.com_hold_type  -- 控股类型
      ,T.org_form  -- 组织形式
      ,T.govern_structure  -- 是否政务民生0否,1是
      ,T.in_cll_type  -- 行内行业类别
      ,T.industry_category  -- 行业分类（企业规模）
      ,T.invest_type  -- 投资主体
      ,T.ent_belong  -- 企业隶属
      ,T.build_date  -- 成立日期
      ,T.super_dept  -- 主管部门
      ,T.main_business  -- 主营业务
      ,T.minor_business  -- 客户兼营业务
      ,T.business_mode  -- 经营方式
      ,T.jycszk  -- 经营场所状况
      ,T.busi_start_date  -- 开始营业时间
      ,T.indu_deve_prospect  -- 行业发展前景
      ,T.fund_source  -- 经费来源
      ,T.zone_code  -- 地区代码
      ,T.fexc_prm_code  -- 外汇许可证号码
      ,T.top_corp_level  -- 产业化龙头企业级别
      ,T.com_sp_business  -- 特种经营标识
      ,T.com_sp_lic_no  -- 特种经营许可证编号
      ,T.com_sp_detail  -- 特种经营情况
      ,T.com_sp_lic_org  -- 特种许可证颁发机关
      ,T.com_sp_str_date  -- 特种经营起始日期
      ,T.com_sp_end_date  -- 特种经营到期日期
      ,T.loan_cust_rank  -- 信贷客户分类
      ,T.loan_cust_stat  -- 信贷客户状态
      ,T.loan_card_flag  -- 有无贷款卡
      ,T.loan_card_no  -- 贷款卡号码
      ,T.loan_card_stat  -- 贷款卡状态
      ,T.load_card_pwd  -- 贷款卡密码
      ,T.load_card_audit_dt  -- 贷款卡年审日期
      ,T.konggr  -- 控股股东或实际控制人
      ,T.frdblb  -- 法人代表类别
      ,T.frjmsx  -- 法人属性
      ,T.legal_repr_name  -- 法定代表人名称
      ,T.legal_repr_gender  -- 法定代表人性别
      ,T.legal_repr_ident_type  -- 法定代表人证件类型
      ,T.legal_repr_ident_no  -- 法定代表人证件号码
      ,T.frzjyx  -- 法人证件有效日期
      ,T.legal_repr_tel  -- 法定代表人联系电话
      ,T.legal_repr_addr  -- 法定代表人户籍地址
      ,T.legal_repr_photo  -- 法定代表人照片
      ,T.legal_repr_nation_code  -- 法定代表人所在国家（地区）
      ,T.fin_rep_type  -- 财务报表类型
      ,T.total_assets  -- 总资产
      ,T.net_assets  -- 净资产
      ,T.total_debt  -- 总负债
      ,T.annual_income  -- 年收入
      ,T.annual_profit  -- 年利润
      ,T.industry_position  -- 行业地位
      ,T.cus_bank_rel  -- 与我行关联关系
      ,T.cus_corp_rel  -- 与我行合作关系
      ,T.is_stock_holder  -- 是否我行股东
      ,T.hold_stock_amt  -- 拥有我行股份金额
      ,T.org_addr  -- 通讯地址
      ,T.org_zipcode  -- 邮政编码
      ,T.org_cus  -- 联系人
      ,T.org_tel  -- 联系电话
      ,T.org_fex  -- 传真号码
      ,T.org_email  -- 邮件地址
      ,T.org_homepage  -- 主页
      ,T.org_weibo  -- 微博
      ,T.org_weixin  -- 微信
      ,T.last_dealings_desc  -- 前次来行状况
      ,T.natiecodept  -- 国民经济部门
      ,T.remarks  -- 备注
      ,T.last_update_sys  -- 最后更新系统
      ,T.last_update_user  -- 最后更新人
      ,T.last_update_tm  -- 最后更新时间
      ,T.tx_seq_no  -- 交易流水号
      ,T.zone_name  -- 地区名称
      ,T.outer_mgr_code  -- 企业外管代码
      ,T.tech_level  -- 技术水平
      ,T.name_simp_spell  -- 客户简拼
      ,T.used_name  -- 曾用名
      ,T.ent_scale_zx  -- 企业规模（征信）
      ,T.ent_scale_bse  -- 企业规模（巴塞尔）
      ,T.industry_sub_type  -- 信贷细分行业类型
      ,T.is_three_agriculture  -- 是否三农
      ,T.tourism_health  -- 旅游健康行业分类
      ,T.tourism_or_health  -- 旅游业/健康业
      ,T.te_ding_fei  -- 特定非金融客户
      ,T.check_results  -- 受益人核对结果
      ,T.check_date  -- 受益人核对日期
      ,T.del_ind   -- 删除标记  
      ,DATE '{ETL_TX_DT}'   -- 开始日期  
      ,DATE '{MAX_DATE}'    -- 结束日期
  FROM {ODM}.VT_O_ECIF_M_CI_ORG T  
;
select sysdate();

"""

# -- 主调程序-- #
bformatsql = bsql.format_map(vars())
ret_code = execute_gbase_sql(bformatsql)
if ret_code :
    log.error(" ERROR - ERROR .")
    exit(-1)
else:
    log.info("SUCESS . ")