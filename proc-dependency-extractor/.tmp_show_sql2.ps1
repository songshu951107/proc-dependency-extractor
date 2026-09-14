$lines = [IO.File]::ReadAllLines('d:\HB\work_zuoye\proc-dependency-extractor\.tmp_real_broken_sql.txt')

function GetSql($proc, $seq) {
    $start = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -like ($proc + '|||' + $seq + '|||*')) { $start = $i; break }
    }
    if ($start -lt 0) { return $null }
    $end = $start + 1
    while ($end -lt $lines.Count -and $lines[$end] -notmatch '\|\|\|\d+\|\|\|') { $end++ }
    $raw = $lines[$start..($end - 1)] -join "`n"
    return $raw -replace ('^.*?\|\|\|' + $seq + '\|\|\|'), ''
}

$s = GetSql 'dwa_pid_a_g_ln_new_pord_sum' '3'
# 找第二层 from（分支自己的 FROM）
$idx = $s.IndexOf('group by')
Write-Output "=== b3 完整后半段（分支 FROM 部分） ==="
if ($idx -gt 0) { Write-Output $s.Substring([Math]::Max(0, $idx - 1400), [Math]::Min(1400, $s.Length - $idx + 1400)) }
