param(
    [string]$EnvironmentFile,
    [switch]$KeepInfrastructure
)

. (Join-Path $PSScriptRoot 'LocalRuntime.Common.ps1')
if (-not $EnvironmentFile) { $EnvironmentFile = $script:JshPosDefaultEnvFile }
$EnvironmentFile = [System.IO.Path]::GetFullPath($EnvironmentFile)
$pidDirectory = Join-Path $script:JshPosRuntimeDirectory 'pids'
foreach ($name in @('web', 'server')) {
    $pidFile = Join-Path $pidDirectory "$name.pid"
    if (-not (Test-Path -LiteralPath $pidFile)) { continue }
    $processId = [int](Get-Content -LiteralPath $pidFile -Raw)
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($process) {
        Stop-Process -Id $processId
        $process.WaitForExit(10000) | Out-Null
    }
    Remove-Item -LiteralPath $pidFile -Force
}
if (-not $KeepInfrastructure -and (Test-Path -LiteralPath $EnvironmentFile)) {
    $docker = Resolve-JshPosDocker
    $compose = Get-JshPosComposeArguments -EnvironmentFile $EnvironmentFile
    Invoke-JshPosChecked $docker @($compose + @('stop'))
}
Write-Host 'Local processes stopped. Database volume and generated local secrets were preserved.'
