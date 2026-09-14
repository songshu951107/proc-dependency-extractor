$lines = [IO.File]::ReadAllLines('d:\HB\work_zuoye\proc-dependency-extractor\.tmp_real_broken_sql.txt')
$start = -1
for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -like 'dwa_pid_a_g_ln_new_pord_sum|||3|||*') { $start = $i; break } }
$end = $start + 1
while ($end -lt $lines.Count -and $lines[$end] -notmatch '\|\|\|\d+\|\|\|') { $end++ }
$sql = ($lines[$start..($end-1)] -join " 
\)
Write-Output $sql