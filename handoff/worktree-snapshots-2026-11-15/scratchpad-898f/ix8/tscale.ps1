param([int]$warm=15,[int]$meas=30,[string]$tag="smoke",[string]$extra="")
$L="C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
$log="C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\ix8\$tag.log"
python $L acquire customwf-ix8 "tscale load"
try {
  Set-Location D:\wt-scale\sangam
  $a = @("-o","-Dsangam.target=target-scale","-pl","sangam-server","-am","verify","-Dsangam.db=postgres","-Dsangam.tscale=true",
    "-Dsangam.tscale.warmupSeconds=$warm","-Dsangam.tscale.measureSeconds=$meas","-Dsangam.tscale.out=D:\wt-scale\tscale-$tag",
    "-Dtest=NoUnit","-Dsurefire.failIfNoSpecifiedTests=false","-Dit.test=TScaleLoadIT","-Dfailsafe.failIfNoSpecifiedTests=false")
  if ($extra) { $a += $extra.Split(" ") }
  & .\mvnw.cmd @a *> $log
} finally { python $L release customwf-ix8 }
"FINISHED $tag"
