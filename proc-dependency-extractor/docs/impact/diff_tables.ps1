param([string]$basePath,[string]$newPath)
function Index($path){
  $j=Get-Content $path -Raw -Encoding UTF8|ConvertFrom-Json; $d=$j.data; $m=@{}
  foreach($p in $d.impactedByDepth.PSObject.Properties){ foreach($t in @($p.Value)){ $m[$t.name]=$t } }
  return $m
}
$b=Index $basePath; $n=Index $newPath
$onlyB=$b.Keys|Where-Object{-not $n.ContainsKey($_)}; $onlyN=$n.Keys|Where-Object{-not $b.ContainsKey($_)}
Write-Host("仅base有: "+$onlyB.Count); Write-Host("仅new有: "+$onlyN.Count)
$diffNames=@(); $summary=@{}
foreach($k in $b.Keys){ if(-not $n.ContainsKey($k)){continue}; $bt=$b[$k];$nt=$n[$k]; $fields=@('depth','shortName','layer','temp','impactSource','scriptFile'); $changed=@()
  foreach($f in $fields){ $bv=$bt."$f";$nv=$nt."$f"; if(("$bv" -ne "$nv")){ $changed+="$f=<$bv|$nv>" } }
  # columns compare
  $bc=@($bt.columns)-join',';$nc=@($nt.columns)-join','; if($bc -ne $nc){ $changed+="columns(len $($bc.Length) vs $($nc.Length))" }
  if($changed.Count -gt 0){ $diffNames+=$k; foreach($c in $changed){ if(-not $summary.ContainsKey($k)){ $summary[$k]=@() }; $summary[$k]+=$c } }
}
Write-Host("有差异的表数: "+$diffNames.Count)
$diffNames|Sort-Object|ForEach-Object{ Write-Host ("  "+$_+" :: "+($summary[$_]-join' ; ')) }