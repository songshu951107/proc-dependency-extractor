# -*- coding: utf-8 -*-
"""
迷你测试：验证 transform_table_mapping.py 的转换正确性与词法纪律。
覆盖场景：
  1) 普通列改名（含大小写混排）
  2) core_cust_no 引用改写 + LEFT JOIN 注入（FROM 主表 / LEFT JOIN 两场景）
  3) 拉链过滤 del_flag → del_ind
  4) 词法纪律：注释/字符串/{占位符} 内的旧表名旧列名不得触碰
  5) 语句级作用域隔离：同块内同别名指向不同表，非 F 表语句不得被改
"""
import sys
import os

ETL_TX_DT = sys.argv[1] if len(sys.argv) > 1 else '20260101'

bsql="""

/*防止批量重跑处理*/
DELETE FROM {FDM}.F_PT_ECIF_PUB_CUST_INF    -- 删除当日数据（注释里的旧表名不得改）
 WHERE start_dt >=DATE '{ETL_TX_DT}';

/*场景1+3：普通消费——列改名 + 拉链过滤*/
INSERT INTO {FDM}.DWA_TARGET
SELECT T1.cust_no          -- 客户号（注释里的 cust_no 不得改）
      ,T1.CORE_CUST_NO     -- 核心客户号 → 注入 JOIN 取 CORE_NO
      ,T1.org_cust_type_cd
      ,'F_PT_ECIF_PUB_CUST_INF 字符串里的表名不得改'
  FROM {FDM}.F_PT_ECIF_PUB_CUST_INF T1
 WHERE T1.del_flag<>'D'
   AND T1.end_dt=DATE '{MAX_DATE}'
   AND T1.core_cust_no IS NOT NULL;

/*场景2：LEFT JOIN 消费——注入点应在 ON 条件之后*/
INSERT INTO {FDM}.DWA_TARGET2
SELECT A.cust_name
      ,F.cust_no
      ,F.core_cust_no
  FROM {FDM}.F_CUST_BASE A
  LEFT JOIN {FDM}.F_PT_ECIF_PUB_CUST_INF F
    ON A.cust_no=F.cust_no
 WHERE A.stat_cd='01';

/*场景5：作用域隔离——T1 指向其他表，T1.cust_no 不得改*/
INSERT INTO {FDM}.DWA_TARGET3
SELECT T1.cust_no
      ,T1.amt
  FROM {FDM}.F_OTHER_TABLE T1
 WHERE T1.cust_no IS NOT NULL
   AND T1.dt='{ETL_TX_DT}';

"""
