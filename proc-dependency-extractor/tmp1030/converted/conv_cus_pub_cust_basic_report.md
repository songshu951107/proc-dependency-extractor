# 转换报告（tmp1030\dwa_c_cus_pub_cust_basic_inf.py → tmp1030\converted\dwa_c_cus_pub_cust_basic_inf.py）

## 表：F_PT_ECIF_PUB_CUST_INF → O_ECIF_M_CI_ORG

- table_ref：**3 处**
- rename：**22 处**

| 行号 | 类型 | 原文 | 转换后 |
|------|------|------|--------|
| 332 | rename | `A.uscc_cd` | `A.busi_lic_no` |
| 332 | rename | `A.uscc_cd` | `A.busi_lic_no` |
| 332 | rename | `A.uscc_cd` | `A.busi_lic_no` |
| 335 | rename | `A.org_cd` | `A.org_code` |
| 335 | rename | `A.org_cd` | `A.org_code` |
| 340 | rename | `A.uscc_cd` | `A.busi_lic_no` |
| 340 | rename | `A.uscc_cd` | `A.busi_lic_no` |
| 344 | rename | `A.credit_loan_subdv_indus_type_cd` | `A.industry_sub_type` |
| 345 | rename | `A.CY_OR_ZONE_CD` | `A.nation_code` |
| 352 | rename | `A.STATU_REP_CONTACT_PHONE_NO` | `A.legal_repr_tel` |
| 354 | rename | `A.ent_scale_pboc` | `A.ent_scale_rh` |
| 354 | rename | `A.ent_scale_pboc` | `A.ent_scale_rh` |
| 360 | rename | `A.admin_area_cd` | `A.area_code` |
| 360 | rename | `A.admin_area_cd` | `A.area_code` |
| 375 | rename | `A.cy_or_zone_cd` | `A.nation_code` |
| 375 | rename | `A.cy_or_zone_cd` | `A.nation_code` |
| 394 | table_ref | `{FDM}.F_PT_ECIF_PUB_CUST_INF A` | `{ODM}.O_ECIF_M_CI_ORG A` |
| 395 | rename | `A.CUST_NO` | `A.cust_id` |
| 404 | rename | `A.cust_no` | `A.cust_id` |
| 545 | rename | `C.cy_or_zone_cd` | `C.nation_code` |
| 545 | rename | `C.cy_or_zone_cd` | `C.nation_code` |
| 618 | table_ref | `{FDM}.F_PT_ECIF_PUB_CUST_INF C` | `{ODM}.O_ECIF_M_CI_ORG C` |
| 619 | rename | `C.cust_no` | `C.cust_id` |
| 799 | table_ref | `{FDM}.F_PT_ECIF_PUB_CUST_INF C` | `{ODM}.O_ECIF_M_CI_ORG C` |
| 800 | rename | `C.cust_no` | `C.cust_id` |

## 残留检查

✅ 无旧表引用残留、无别名作用域内旧列残留。
