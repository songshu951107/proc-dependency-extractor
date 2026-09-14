# 全量 proc 重解析脚本
# 用法: .\reparse_all.ps1            (断点续跑, 跳过进度文件中已完成的 proc)
#       .\reparse_all.ps1 -Fresh     (全量重跑, 清空进度/失败/proc列表缓存后开始)
param(
    [switch]$Fresh
)
$ErrorActionPreference = 'Continue'
$base = 'D:\HB\work_zuoye\proc-dependency-extractor'
$procListFile = Join-Path $base '.tmp_proc_list.csv'
$progressFile = Join-Path $base '.tmp_reparse_progress.txt'
$failLog = Join-Path $base '.tmp_reparse_fail.txt'

if ($Fresh) {
    foreach ($f in @($progressFile, $failLog, $procListFile)) {
        if (Test-Path $f) { Remove-Item $f -Force }
    }
    Write-Host "[Fresh] 已清空进度/失败日志/proc列表缓存, 将全量重跑"
}

if (-not (Test-Path $procListFile)) {
    Write-Host "proc list not found, fetching from MySQL..."
    $env:MYSQL_PWD = 'root'
    mysql -uroot -D proc_extractor -N -e "SELECT CONCAT(proc_name, ',', REPLACE(file_path,'/','\\')) FROM proc_info ORDER BY proc_name;" | Out-File -FilePath $procListFile -Encoding Default
}
$procList = Import-Csv $procListFile -Header procName, filePath -Encoding Default
if (-not $procList -or $procList.Count -eq 0) {
    Write-Host "Error: proc list is empty"
    exit 1
}
Write-Host "Total procs: $($procList.Count)"

$done = @{}
if (Test-Path $progressFile) {
    Get-Content $progressFile -Encoding Default | ForEach-Object {
        if ($_ -and $_ -match '^(.+?)\|(.+)$') { $done[$Matches[1]] = $Matches[2] }
    }
}
Write-Host "Completed: $($done.Count) / $($procList.Count)"

$idx = 0
$ok = 0
$fail = 0
foreach ($p in $procList) {
    $idx++
    $procName = $p.procName
    $filePath = $p.filePath
    if (-not $procName -or -not $filePath) { continue }
    if ($done.ContainsKey($procName)) { continue }
    Write-Host "[$idx/$($procList.Count)] $procName ..."
    try {
        $body = @{ path = $filePath } | ConvertTo-Json
        $r = Invoke-WebRequest -Method Post -Uri 'http://localhost:8080/api/proc/extract' -Body $body -ContentType 'application/json' -TimeoutSec 180 -UseBasicParsing
        $j = $r.Content | ConvertFrom-Json
        if ($j.code -eq 200) {
            $ok++
            "$procName|OK" | Out-File $progressFile -Append -Encoding Default
        } else {
            $fail++
            $msg = $j.message
            Write-Host "  FAIL code=$($j.code) msg=$msg"
            "$procName|FAIL: $msg" | Out-File $failLog -Append -Encoding Default
            "$procName|FAIL" | Out-File $progressFile -Append -Encoding Default
        }
    } catch {
        $fail++
        $msg = $_.Exception.Message
        Write-Host "  ERROR: $msg"
        "$procName|ERR: $msg" | Out-File $failLog -Append -Encoding Default
        "$procName|ERR" | Out-File $progressFile -Append -Encoding Default
    }
    if ($idx % 10 -eq 0) { Write-Host "  --- Progress: $idx/$($procList.Count) ok=$ok fail=$fail ---" }
}
Write-Host "=== Done: ok=$ok fail=$fail total=$($procList.Count) ==="
Write-Host "Progress: $progressFile"
Write-Host "Failures: $failLog"