param([string]$EnvironmentFile)

. (Join-Path $PSScriptRoot 'LocalRuntime.Common.ps1')
if (-not $EnvironmentFile) { $EnvironmentFile = $script:JshPosDefaultEnvFile }
$EnvironmentFile = [System.IO.Path]::GetFullPath($EnvironmentFile)
Import-JshPosEnvironment -Path $EnvironmentFile
$docker = Resolve-JshPosDocker
$schema = Invoke-JshPosContainerMySql -Docker $docker -EnvironmentFile $EnvironmentFile -Sql @'
SELECT COALESCE(MAX(version),'NONE') FROM jshpos_flyway_schema_history WHERE success=1;
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();
SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE();
'@
$values = @($schema | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($values.Count -lt 3) { throw "Unexpected schema result: $($values -join ', ')" }
if ($values[-3] -ne '202609050090') { throw "Expected Flyway V90, got $($values[-3])" }
if ([int]$values[-2] -lt 300) { throw "Expected complete local schema, got $($values[-2]) tables" }
if ($values[-1] -ne '0') { throw "Expected zero physical foreign keys, got $($values[-1])" }
Invoke-WebRequest -Uri 'http://127.0.0.1:8080/' -UseBasicParsing -TimeoutSec 5 | Out-Null
Invoke-WebRequest -Uri 'http://127.0.0.1:4173/' -UseBasicParsing -TimeoutSec 5 | Out-Null
Write-Host "LOCAL RUNTIME OK: Flyway=$($values[-3]), tables=$($values[-2]), foreignKeys=0, server/web reachable"
