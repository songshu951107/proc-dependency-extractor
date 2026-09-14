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
if ($s) {
    $fi = $s.IndexOf('from')
    Write-Output "=== new_pord_sum b3 FROM ==="
    Write-Output $s.Substring($fi, [Math]::Min(900, $s.Length - $fi))
}

$s2 = GetSql 'dwa_pid_a_d_pi_new_gl_d_b' '12'
if (-not $s2) {
    Write-Output "=== pi_new_gl_d_b 块清单 ==="
    Select-String -Path 'd:\HB\work_zuoye\proc-dependency-extractor\.tmp_real_broken_sql.txt' -Pattern 'pi_new_gl_d_b\|\|\|' |
        ForEach-Object { $_.Line.Substring(0, [Math]::Min(60, $_.Line.Length)) } | Select-Object -First 15
} else {
    $fi2 = [Math]::Max(0, $s2.IndexOf('from'))
    Write-Output "=== pi_new_gl_d_b b12 FROM ==="
    Write-Output $s2.Substring($fi2, [Math]::Min(700, $s2.Length - $fi2))
}

$s3 = GetSql 'dwa_pid_a_g_dp_deposit_report' '2'
if ($s3) {
    Write-Output "=== deposit_report b2 头部 ==="
    Write-Output $s3.Substring(0, [Math]::Min(400, $s3.Length))
}
