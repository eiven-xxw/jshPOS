param(
    [string]$EnvironmentFile,
    [string]$JavaExecutable,
    [switch]$SkipBuild,
    [switch]$SkipWeb
)

. (Join-Path $PSScriptRoot 'LocalRuntime.Common.ps1')
if (-not $EnvironmentFile) { $EnvironmentFile = $script:JshPosDefaultEnvFile }
$EnvironmentFile = [System.IO.Path]::GetFullPath($EnvironmentFile)
& (Join-Path $PSScriptRoot 'Initialize-LocalDatabase.ps1') -EnvironmentFile $EnvironmentFile
Import-JshPosEnvironment -Path $EnvironmentFile
Set-JshPosApplicationEnvironment
$java = Resolve-JshPosJava -Explicit $JavaExecutable
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java)
$serverJar = Join-Path $script:JshPosRoot 'server\ruoyi-admin\target\ruoyi-admin.jar'

if (-not $SkipBuild) {
    Push-Location (Join-Path $script:JshPosRoot 'server')
    try {
        Invoke-JshPosChecked '.\mvnw.cmd' '-Plocal' '-DskipTests' '-pl' 'ruoyi-admin' '-am' 'package'
    }
    finally { Pop-Location }
}
if (-not (Test-Path -LiteralPath $serverJar)) { throw "Server JAR not found: $serverJar" }

$pidDirectory = Join-Path $script:JshPosRuntimeDirectory 'pids'
$logDirectory = Join-Path $script:JshPosRuntimeDirectory 'logs'
New-Item -ItemType Directory -Path $pidDirectory,$logDirectory -Force | Out-Null
$serverPidFile = Join-Path $pidDirectory 'server.pid'
if (Test-Path -LiteralPath $serverPidFile) {
    $oldPid = [int](Get-Content -LiteralPath $serverPidFile -Raw)
    if (Get-Process -Id $oldPid -ErrorAction SilentlyContinue) {
        throw "Local server is already running with PID $oldPid. Run Stop-Local.ps1 first."
    }
}

$serverProcess = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $serverJar, '--spring.profiles.active=local'
) -WorkingDirectory $script:JshPosRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $logDirectory 'server.out.log') `
    -RedirectStandardError (Join-Path $logDirectory 'server.err.log')
[System.IO.File]::WriteAllText($serverPidFile, [string]$serverProcess.Id)

$serverReady = $false
for ($attempt = 1; $attempt -le 90; $attempt++) {
    if (-not (Get-Process -Id $serverProcess.Id -ErrorAction SilentlyContinue)) {
        throw "Server stopped during startup. See $logDirectory"
    }
    try {
        Invoke-WebRequest -Uri 'http://127.0.0.1:8080/' -UseBasicParsing -TimeoutSec 2 | Out-Null
        $serverReady = $true
        break
    }
    catch { Start-Sleep -Seconds 2 }
}
if (-not $serverReady) { throw "Server did not become ready within 180 seconds. See $logDirectory" }

$docker = Resolve-JshPosDocker
$schema = Invoke-JshPosContainerMySql -Docker $docker -EnvironmentFile $EnvironmentFile -Sql @'
SELECT COALESCE(MAX(version),'NONE') FROM jshpos_flyway_schema_history WHERE success=1;
SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE();
'@
$values = @($schema | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($values.Count -lt 2 -or $values[-2] -ne '202609050090' -or $values[-1] -ne '0') {
    throw "Schema validation failed: $($values -join ', ')"
}

if (-not $SkipWeb) {
    $pnpm = Resolve-JshPosPnpm
    Push-Location (Join-Path $script:JshPosRoot 'admin-web')
    try { Invoke-JshPosChecked $pnpm 'install' '--frozen-lockfile' }
    finally { Pop-Location }
    $webProcess = Start-Process -FilePath $pnpm -ArgumentList @('dev') `
        -WorkingDirectory (Join-Path $script:JshPosRoot 'admin-web') -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logDirectory 'web.out.log') `
        -RedirectStandardError (Join-Path $logDirectory 'web.err.log')
    [System.IO.File]::WriteAllText((Join-Path $pidDirectory 'web.pid'), [string]$webProcess.Id)
}

Write-Host 'Local runtime is ready:'
Write-Host '  Server:  http://127.0.0.1:8080'
if (-not $SkipWeb) { Write-Host '  Web:     http://127.0.0.1:4173' }
Write-Host '  Swagger: http://127.0.0.1:8080/swagger-ui/index.html'
Write-Host '  MySQL:   Flyway V90, physical foreign keys = 0'
Write-Host "  Logs:    $logDirectory"
