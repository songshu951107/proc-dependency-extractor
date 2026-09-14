param(
  [string]$basePath,
  [string]$newPath
)
function Load-Tables($path) {
  $j = Get-Content $path -Raw | ConvertFrom-Json
  $d = $j.data
  $set = New-Object System.Collections.Generic.HashSet[string]
  $perDepth = @{}
  foreach($p in $d.impactedByDepth.PSObject.Properties) {
    $cnt = @($p.Value).Count
    $perDepth[$p.Name] = $cnt
    foreach($t in @($p.Value)) {
      $k = "{0}|{1}|{2}|{3}|{4}|{5}|{6}" -f $t.name,$t.depth,$t.shortName,$t.layer,$t.temp,$t.impactSource,$t.scriptFile
      [void]$set.Add($k)
    }
  }
  return ,($set, $perDepth)
}
$r = Load-Tables $basePath
$bSet = $r[0]; $bDepth = $r[1]
$r2 = Load-Tables $newPath
$nSet = $r2[0]; $nDepth = $r2[1]

Write-Host "=== 影响表 key 集合 (name|depth|shortName|layer|temp|impactSource|scriptFile) ==="
Write-Host ("base unique: " + $bSet.Count)
Write-Host ("new unique : " + $nSet.Count)
Write-Host "--- 仅 base 存在 (应为空 = 无回归) ---"
$onlyBase = $bSet | Where-Object { -not $nSet.Contains($_) }
if($onlyBase.Count -eq 0){ Write-Host "(none)" } else { $onlyBase | Select-Object -First 20 }
Write-Host "--- 仅 new 存在 (应为空 = 无新增) ---"
$onlyNew = $nSet | Where-Object { -not $bSet.Contains($_) }
if($onlyNew.Count -eq 0){ Write-Host "(none)" } else { $onlyNew | Select-Object -First 20 }
Write-Host "=== 每深度表数量 ==="
$all = ($bDepth.Keys + $nDepth.Keys | Sort-Object {[int]$_} -Unique)
foreach($k in $all){ $mark = if($bDepth[$k] -ne $nDepth[$k]){"  <-- DIFF"}else{""}; Write-Host ("depth {0}: base={1} new={2}{3}" -f $k, $bDepth[$k], $nDepth[$k], $mark) }