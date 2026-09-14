SELECT CONCAT('@@BLOCK ', proc_name, ' | ', block_seq, '@@') AS marker, LEFT(sql_content, 900)
FROM sql_block_info
WHERE (proc_name = 'dwa_a_d_pt_pos_merch_info_sum' AND block_seq = 3)
   OR (proc_name = 'dwa_c_agt_deposit_acct_intst' AND block_seq = 3)
   OR (proc_name = 'dwa_pid_a_d_eq_rpt_gov_qlfy_info' AND block_seq = 3)
   OR (proc_name = 'dwa_pid_a_g_ln_new_distr_loan_int_rate' AND block_seq = 10)
   OR (proc_name = 'dwa_pid_a_d_fi_gl_inf' AND block_seq = 10)
   OR (proc_name = 'dwa_pid_a_g_ln_loan_org_itnet_sum' AND block_seq = 3)
   OR (proc_name = 'dwa_pid_a_g_ln_loan_org_itnet_sum' AND block_seq = 5)
   OR (proc_name = 'dwa_pid_a_g_ln_new_distr_loan_int_rate' AND block_seq = 17)
   OR (proc_name = 'DWA_PID_A_D_LN_LOAN_ACCT_INF' AND block_seq = 3)
ORDER BY proc_name, block_seq;
