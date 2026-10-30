# 转换报告（tmp1030\dwa_c_agt_deposit_acct_inf.py → tmp1030\converted\dwa_c_agt_deposit_acct_inf.py）

## 表：F_PT_ECIF_PUB_CUST_INF → O_ECIF_M_CI_ORG

- table_ref：**1 处**
- rename：**2 处**

| 行号 | 类型 | 原文 | 转换后 |
|------|------|------|--------|
| 216 | table_ref | `{FDM}.F_PT_ECIF_PUB_CUST_INF T2` | `{ODM}.O_ECIF_M_CI_ORG T2` |
| 217 | rename | `t2.cust_no` | `t2.cust_id` |
| 220 | rename | `t2.del_flag` | `t2.del_ind` |

## 残留检查

✅ 无旧表引用残留、无别名作用域内旧列残留。
