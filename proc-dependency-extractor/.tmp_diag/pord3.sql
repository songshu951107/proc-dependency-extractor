INSERT INTO PID.VT_A_G_LN_NEW_PORD_SUM
\nselect 
\n    DATE '{ETL_TX_DT}' as  data_dt 
\n   ,case when t.new_prod_no = '享贷' then '01' when t.new_prod_no = '房E贷' then '02' when t.new_prod_no = '优易贷' then '03' when t.new_prod_no = '科创贷' then '04' end  
\n   ,t.new_prod_no 
\n   ,t.org_no  
\n   ,t.fiveclass_class_cd
\n   ,t.loan_bal 
\n   ,t.nor_principal 
\n   ,t.overdue_principal 
\n   ,t.slugg_principal  
\n   ,t.doubt_acct_principal 
\n   ,t.vrfctn_principal 
\n   ,t.vrfctn_intst  
\n   ,t.overdue_intst 
\n   ,t.off_bal_overdue_int 
\n   ,0 
\n   ,0 
\n   ,0 
\n   ,0 
\n   ,0 
\n   ,0 
\n   ,0  
\n   ,0  
\n   ,0  
\n   ,0 
\n   ,t.risk_bal 
\n   ,t.overdue_1day_bal 
\n   ,t.overdue_30day_bal  
\n   ,0 
\n   ,0 
\n   ,0 
\n   ,0 
\n   ,0 
\n   ,0 
\n   ,0 
\n   ,0 
\n   ,t.int_income 
\n   ,0 
\n   ,0 
\nfrom(
\n    select case when PROD_NO in ('1110250','1110260','1110240','1110270') then '享贷'
\n          when PROD_NO in ('1110080','1110131') then '房E贷'
\n          when minor_ent_charact_prod_type_cd in ('70','82') then '优易贷'
\n          when  minor_ent_charact_prod_type_cd in ('73') then '科创贷'
\n          else '' end as new_prod_no
\n    ,manager_org_no as org_no  
\n    ,substr(fiveclass_class_rs_cd,1,2) as fiveclass_class_cd  
\n    ,sum(loan_bal)  as loan_bal 
\n    ,sum(nor_principal)  as nor_principal 
\n    ,sum(overdue_principal)  as overdue_principal 
\n    ,sum(slugg_principal)  as slugg_principal 
\n    ,sum(doubt_acct_principal)  as doubt_acct_principal 
\n    ,sum(vrfctn_principal)  as vrfctn_principal 
\n    ,sum(vrfctn_intst)  as vrfctn_intst 
\n    ,sum(overdue_intst)  as overdue_intst 
\n    ,sum(off_bal_overdue_int)  as off_bal_overdue_int 
\n    ,sum(case when b.OBJ_NO is not null then loan_bal else 0 end ) as risk_bal 
\n    ,sum(case when overdue_days>=1 then loan_bal else 0 end) as overdue_1day_bal 
\n    ,sum(case when overdue_days>29 then loan_bal else 0 end) as overdue_30day_bal 
\n    ,sum(int_income) as int_income 
\n    from cdm.c_agt_loan_rcpt_inf a
\n    left join (
\n               select OBJ_NO
\n               from fdm.F_EV_CRMS_LOAN_BIZ_RISK_WARN_DTL
\n               where DATE '{ETL_TX_DT}' between start_dt and end_dt-1 
\n                 and del_flag <> 'D'
\n               group by OBJ_NO
\n              ) b 
\n           on a.cust_no = b.OBJ_NO
\n    left join (
\n              select T_ACCOUNT_NUMBER,sum(m1.ACC_INT_YEAR*d.DOM_CURRCY_CONVT_PRICE) as int_income
\n              ,sum(m1.FTP_INT_YEAR_AJUST*d.DOM_CURRCY_CONVT_PRICE) as FTP_INT_YEAR_AJUST
\n              from fdm.F_EV_FTPS_PUB_CUST_INF m1
\n              left join CDM.C_COM_EXCH_RATE d
\n                     on m1.iso_currency_cd = d.CURRCY_CD
\n                    and d.DATA_DT =DATE '{ETL_TX_DT}'
\n              where etl_tx_dt = DATE '{ETL_TX_DT}'
\n              group by T_ACCOUNT_NUMBER
\n              ) c
\n           on a.loan_rcpt_no = c.T_ACCOUNT_NUMBER
\n    where a.DATA_DT = DATE '{ETL_TX_DT}'
\n      and (a.PROD_NO in (
\n      '1110250','1110260','1110240','1110270','1110080','1110131'
\n      )
\n      or a.minor_ent_charact_prod_type_cd in ('70','82','73')
\n      )
\n      and a.manager_org_no not like '%99'  
\n    group by case when PROD_NO in ('1110250','1110260','1110240','1110270') then '享贷'
\n                  when PROD_NO in ('1110080','1110131') then '房E贷'
\n                  when minor_ent_charact_prod_type_cd in ('70','82') then '优易贷'
\n                  when  minor_ent_charact_prod_type_cd in ('73') then '科创贷'
\n                  else '' end
\n             ,manager_org_no
\n             ,substr(fiveclass_class_rs_cd,1,2)
\n)t
