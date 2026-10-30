# -*- coding: utf-8 -*-
"""复杂形态迷你测试：子查询嵌套 / GROUP BY / UNION / 逗号连接（用后即删也行）"""

bsql="""

/*场景6：子查询嵌套——外层别名 S，内层别名 T2*/
INSERT INTO {FDM}.DWA_TARGET4
SELECT S.cust_no
      ,S.core_cust_no
      ,S.cnt
  FROM (
        SELECT T2.cust_no
              ,T2.core_cust_no
              ,COUNT(1) AS cnt
          FROM {FDM}.F_PT_ECIF_PUB_CUST_INF T2
         WHERE T2.del_flag<>'D'
         GROUP BY T2.cust_no, T2.core_cust_no
       ) S
 WHERE S.cnt > 1;

/*场景7：UNION 两段各自消费*/
INSERT INTO {FDM}.DWA_TARGET5
SELECT X.cust_no, X.core_cust_no FROM (
  SELECT T3.cust_no, T3.core_cust_no
    FROM {FDM}.F_PT_ECIF_PUB_CUST_INF T3
   WHERE T3.end_dt=DATE '{MAX_DATE}'
  UNION ALL
  SELECT T4.cust_no, T4.core_cust_no
    FROM {FDM}.F_PT_ECIF_PUB_CUST_INF T4
   WHERE T4.core_cust_no IS NULL
) X;

/*场景8：逗号连接 FROM*/
INSERT INTO {FDM}.DWA_TARGET6
SELECT B.cust_no, B.core_cust_no, C.area_nm
  FROM {FDM}.F_PT_ECIF_PUB_CUST_INF B, {FDM}.F_AREA C
 WHERE B.area_cd=C.area_cd
   AND B.core_cust_no IS NOT NULL;

"""
