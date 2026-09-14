INSERT INTO PID.A_G_AGT_ORG_REPORT_SUM
\nSELECT DATE '{ETL_TX_DT}'                                                 AS DATA_DT
\n     , T1.ORG_NO
\n     , T1.ORG_ABRE
\n     , T1.LEVEL_SCD_ORG_NO
\n     , T1.LEVEL_SCD_ORG_ABRE
\n     , T1.ORG_PROV
\n     , CASE WHEN T1.HIGH_CARD_TYPE IN ('金卡', 'IC如意金卡')
\n                THEN '2'
\n            WHEN T1.HIGH_CARD_TYPE IN ('白金卡')
\n                THEN '3'
\n            WHEN T1.HIGH_CARD_TYPE IN ('钻石卡', 'IC如意财富卡')
\n                THEN '4'
\n                ELSE '1'
\n       END                                                                AS CARD_LVL_ID
\n     , T1.IS_IC_CARD
\n     , T1.UNIONPAY_CARD_FLAG
\n     
\n     , SUM(CASE WHEN T1.VRFCTN_DT > DATE '{ETL_TX_DT}' AND T1.ORIGINAL_ISSUED_DT <= DATE '{ETL_TX_DT}' THEN '1'
\n                                                                                                       ELSE '0'
\n           END)                                                           AS TOT_VLD_CARD          
\n     , SUM(CASE WHEN T1.VRFCTN_DT > DATE '{ETL_TX_DT}' AND T1.ORIGINAL_ISSUED_DT <= DATE '{ETL_TX_DT}'
\n                    THEN NVL(T3.ACCT_BAL, 0)
\n                    ELSE '0'
\n           END)                                                           AS TOT_VLD_DEP_BAL       
\n     , SUM(CASE WHEN T1.VRFCTN_DT > DATE '{ETL_TX_DT}' AND T1.DORMANT_FLG = '1' THEN '1' ELSE '0' END)          AS TOT_DORMANT_CARD      
\n     , SUM(CASE WHEN T1.VRFCTN_DT > DATE '{ETL_TX_DT}' AND T1.LONG_DORMANT_FLG = '1' THEN '1' ELSE '0' END)     AS TOT_LONG_DORMANT_CARD 
\n     , SUM(CASE WHEN T1.VRFCTN_DT > DATE '{ETL_TX_DT}' AND T1.ORIGINAL_ISSUED_DT <= DATE '{ETL_TX_DT}'
\n        AND EXP_FLAG = '1' AND T1.IS_IC_CARD = '1' 
\n                    THEN '1'
\n                WHEN T1.IS_IC_CARD = '0'  
\n                    THEN '0'
\n                    ELSE '0'
\n           END)                                                           AS TOT_EXP_CARD          
\n     , SUM(CASE WHEN T1.VRFCTN_DT > DATE '{ETL_TX_DT}' AND T1.ORIGINAL_ISSUED_DT <= DATE '{ETL_TX_DT}'
\n        AND EXP_FLAG = '0' AND NVL(T1.RENEW_CARD_TMS, 0) > 0 AND T1.IS_IC_CARD = '1' 
\n                    THEN '1'
\n                WHEN T1.IS_IC_CARD = '0'  
\n                    THEN '0'
\n                    ELSE '0'
\n           END)                                                           AS TOT_EXP_RENEW_CARD    
\n     , SUM(CASE WHEN T1.ORIGINAL_ISSUED_DT <= DATE '{ETL_TX_DT}' AND NVL(T1.RENEW_CARD_TMS, 0) = 0 THEN '1'
\n                                                                 ELSE '0'
\n           END)                                                           AS TOT_ISS_CARD          
\n     , SUM(CASE WHEN T1.VRFCTN_DT <= DATE '{ETL_TX_DT}' THEN '1'
\n                                                                                         ELSE '0'
\n           END)                                                           AS TOT_INVLD_CARD        
\n     , SUM(CASE WHEN NVL(T1.RENEW_CARD_TMS, 0) > 0 THEN '1' ELSE '0' END) AS TOT_RENEW_CARD        
\n     
\n     , SUM(CASE WHEN T1.ORIGINAL_ISSUED_DT = DATE '{ETL_TX_DT}' AND NVL(T1.RENEW_CARD_TMS, 0) = 0 THEN '1'
\n                                                                ELSE '0'
\n           END)                                                           AS DAILY_ISS_CARD        
\n     , SUM(CASE WHEN T1.VRFCTN_DT = DATE '{ETL_TX_DT}' THEN '1'
\n                                                                                             ELSE '0'
\n           END)                                                           AS DAILY_INVLD_CARD      
\n     , SUM(CASE WHEN T1.RENEW_CARD_DT = DATE '{ETL_TX_DT}' AND T1.ORIGINAL_ISSUED_DT <= DATE '{ETL_TX_DT}'
\n        AND NVL(T1.RENEW_CARD_TMS, 0) > 0 THEN '1'
\n                                          ELSE '0'
\n           END)                                                           AS DAILY_RENEW_CARD      
\n     
\n     , SUM(CASE WHEN T1.ORIGINAL_ISSUED_DT BETWEEN  DATE '{I_MB_DT}' AND DATE '{ETL_TX_DT}' AND NVL(T1.RENEW_CARD_TMS, 0) = 0 THEN '1'
\n                                                                                      ELSE '0'
\n           END)                                                           AS MONTHLY_ISS_CARD      
\n     , SUM(CASE WHEN T1.VRFCTN_DT BETWEEN  DATE '{I_MB_DT}' AND DATE '{ETL_TX_DT}'
\n                    THEN '1'
\n                    ELSE '0'
\n           END)                                                           AS MONTHLY_INVLD_CARD    
\n     , SUM(CASE WHEN T1.RENEW_CARD_DT BETWEEN DATE '{I_MB_DT}' AND DATE '{ETL_TX_DT}'
\n        AND T1.ORIGINAL_ISSUED_DT <= T1.RENEW_CARD_DT
\n        AND NVL(T1.RENEW_CARD_TMS, 0) > 0 THEN '1'
\n                                          ELSE '0'
\n           END)                                                           AS MONTHLY_RENEW_CARD    
\nFROM PID.A_G_AGT_ORG_REPORT_DTL T1
\nLEFT JOIN (
\n              SELECT CUST_ACCT_NO, SUM(ACCT_BAL) AS ACCT_BAL
\n              FROM CDM.C_AGT_DEPOSIT_ACCT_INF
\n              WHERE DATA_DT = DATE '{ETL_TX_DT}'
\n                AND (SUBSTR(SUBJ_NO, 1, 4) IN ('2203', '2204') 
\n                      OR SUBSTR(SUBJ_NO, 1, 6) IN ('220602', '220702') 
\n                      OR SUBSTR(SUBJ_NO, 1, 8)
\n                          IN ('22050201', '22050202', '22100102', '22100202') 
\n                      OR SUBSTR(SUBJ_NO, 1, 10) IN ('2725010102')) 
\n              GROUP BY 1
\n              )                 T3
\n          ON T3.CUST_ACCT_NO = T1.CUST_ACCT_NO
\nWHERE T1.DATA_DT = DATE '{ETL_TX_DT}'
\n  AND T1.DEBIT_CARD_FLAG = '1' 
\n  AND T1.IS_ELEC_ACCT = '0'
\n  AND T1.CARD_NATURE_CD = '0'
\nGROUP BY 1, 2, 3, 4, 5, 6, 7, 8, 9
