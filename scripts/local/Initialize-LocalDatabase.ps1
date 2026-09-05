param([string]$EnvironmentFile)

. (Join-Path $PSScriptRoot 'LocalRuntime.Common.ps1')
if (-not $EnvironmentFile) { $EnvironmentFile = $script:JshPosDefaultEnvFile }
$EnvironmentFile = [System.IO.Path]::GetFullPath($EnvironmentFile)
if (-not (Test-Path -LiteralPath $EnvironmentFile)) {
    & (Join-Path $PSScriptRoot 'New-LocalEnvironment.ps1') -EnvironmentFile $EnvironmentFile
}
Import-JshPosEnvironment -Path $EnvironmentFile
$docker = Resolve-JshPosDocker
$compose = Get-JshPosComposeArguments -EnvironmentFile $EnvironmentFile

Invoke-JshPosChecked $docker @($compose + @('up', '-d', 'mysql', 'redis'))
$ready = $false
for ($attempt = 1; $attempt -le 60; $attempt++) {
    & $docker @($compose + @('exec', '-T', 'mysql', 'sh', '-lc',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqladmin ping -h 127.0.0.1 -uroot --silent')) *> $null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $ready) { throw 'MySQL did not become ready within 120 seconds.' }

$existing = Invoke-JshPosContainerMySql -Docker $docker -EnvironmentFile $EnvironmentFile `
    -Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='sys_user';"
if (($existing | Select-Object -Last 1).Trim() -eq '0') {
    $baseSql = Get-Content -LiteralPath (Join-Path $script:JshPosRoot 'server\script\sql\ry_vue_5.X.sql') -Raw -Encoding utf8
    Invoke-JshPosContainerMySql -Docker $docker -EnvironmentFile $EnvironmentFile -Sql $baseSql | Out-Null
    Write-Host 'Imported RuoYi foundation tables and local synthetic administrator seed.'
}
else {
    Write-Host 'RuoYi foundation tables already exist; import skipped.'
}
Write-Host 'MySQL and Redis are ready. Business tables will migrate through Flyway when the server starts.'
