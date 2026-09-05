param(
    [string]$EnvironmentFile,
    [string]$JavaExecutable,
    [switch]$Force
)

. (Join-Path $PSScriptRoot 'LocalRuntime.Common.ps1')
if (-not $EnvironmentFile) { $EnvironmentFile = $script:JshPosDefaultEnvFile }
$EnvironmentFile = [System.IO.Path]::GetFullPath($EnvironmentFile)
if ((Test-Path -LiteralPath $EnvironmentFile) -and -not $Force) {
    Write-Host "Local environment already exists: $EnvironmentFile"
    exit 0
}

function New-RandomValue {
    param([int]$Bytes = 32)
    $buffer = New-Object byte[] $Bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($buffer)
    return [Convert]::ToBase64String($buffer).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-RandomBase64 {
    $buffer = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($buffer)
    return [Convert]::ToBase64String($buffer)
}

$java = Resolve-JshPosJava -Explicit $JavaExecutable
$signingLines = & $java (Join-Path $PSScriptRoot 'GenerateLocalSigningKey.java')
if ($LASTEXITCODE -ne 0 -or @($signingLines).Count -ne 2) {
    throw 'Failed to generate local Ed25519 signing material.'
}

$content = @(
    '# Generated for one workstation only. Never commit, share, or use in production.',
    "MYSQL_ROOT_PASSWORD=$(New-RandomValue)",
    'MYSQL_DATABASE=jshpos',
    'MYSQL_USER=jshpos',
    "MYSQL_PASSWORD=$(New-RandomValue)",
    'MYSQL_PORT=3306',
    "REDIS_PASSWORD=$(New-RandomValue)",
    'REDIS_PORT=6379',
    "JSH_LOCAL_JWT_SECRET=$(New-RandomValue -Bytes 48)",
    'JSH_LOCAL_ACTUATOR_USERNAME=local-ops',
    "JSH_LOCAL_ACTUATOR_PASSWORD=$(New-RandomValue)",
    "JSH_MEMBER_LOOKUP_KEY_B64=$(New-RandomBase64)",
    "JSH_MEMBER_ENCRYPTION_KEY_B64=$(New-RandomBase64)",
    'JSH_MEMBER_KEY_VERSION=1',
    "JSH_REPORT_DOWNLOAD_HMAC_KEY_B64=$(New-RandomBase64)",
    "JSH_REPORT_CURSOR_HMAC_KEY_B64=$(New-RandomBase64)",
    "JSH_REPORT_INVENTORY_CURSOR_HMAC_KEY_B64=$(New-RandomBase64)",
    'JSH_LOCAL_PACKAGE_SIGNING_KEY_ID=LOCAL_DEV_1'
) + $signingLines

New-Item -ItemType Directory -Path (Split-Path -Parent $EnvironmentFile) -Force | Out-Null
[System.IO.File]::WriteAllLines($EnvironmentFile, $content, [System.Text.UTF8Encoding]::new($false))
Write-Host "Generated local-only environment: $EnvironmentFile"
