$ErrorActionPreference = 'Stop'
$script:JshPosRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$script:JshPosComposeFile = Join-Path $script:JshPosRoot 'infra\compose\compose.yaml'
$script:JshPosDefaultEnvFile = Join-Path $script:JshPosRoot 'infra\local\.env.runtime.local'
$script:JshPosRuntimeDirectory = Join-Path $script:JshPosRoot '.local'

function Invoke-JshPosChecked {
    param(
        [Parameter(Mandatory)][string]$Executable,
        [Parameter(ValueFromRemainingArguments)][string[]]$Arguments
    )
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Executable exited with code $LASTEXITCODE"
    }
}

function Resolve-JshPosJava {
    param([string]$Explicit)
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($Explicit) { $candidates.Add($Explicit) }
    if ($env:JAVA_HOME) { $candidates.Add((Join-Path $env:JAVA_HOME 'bin\java.exe')) }
    $command = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($command) { $candidates.Add($command.Source) }
    if ($env:USERPROFILE) {
        Get-ChildItem (Join-Path $env:USERPROFILE '.jdks') -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { $candidates.Add((Join-Path $_.FullName 'bin\java.exe')) }
    }
    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (Test-Path -LiteralPath $candidate) { return (Resolve-Path $candidate).Path }
    }
    throw 'JDK 21 not found. Set -JavaExecutable or JAVA_HOME.'
}

function Resolve-JshPosDocker {
    $command = Get-Command docker.exe -ErrorAction SilentlyContinue
    if (-not $command) { $command = Get-Command docker -ErrorAction SilentlyContinue }
    if (-not $command) {
        throw 'Docker Desktop / Docker Compose v2 not found. Install and start Docker, then rerun.'
    }
    return $command.Source
}

function Resolve-JshPosPnpm {
    $command = Get-Command pnpm.cmd -ErrorAction SilentlyContinue
    if (-not $command) { $command = Get-Command pnpm -ErrorAction SilentlyContinue }
    if (-not $command) { throw 'pnpm 10.33.x not found on PATH.' }
    return $command.Source
}

function Import-JshPosEnvironment {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Local environment file not found: $Path"
    }
    foreach ($line in Get-Content -LiteralPath $Path -Encoding utf8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $parts = $trimmed.Split('=', 2)
        if ($parts.Count -ne 2 -or -not $parts[0].Trim()) {
            throw "Invalid local environment line: $line"
        }
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1], 'Process')
    }
    $required = @(
        'MYSQL_ROOT_PASSWORD', 'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD', 'MYSQL_PORT',
        'REDIS_PASSWORD', 'REDIS_PORT', 'JSH_LOCAL_JWT_SECRET', 'JSH_LOCAL_ACTUATOR_USERNAME',
        'JSH_LOCAL_ACTUATOR_PASSWORD', 'JSH_MEMBER_LOOKUP_KEY_B64',
        'JSH_MEMBER_ENCRYPTION_KEY_B64', 'JSH_MEMBER_KEY_VERSION',
        'JSH_REPORT_DOWNLOAD_HMAC_KEY_B64', 'JSH_REPORT_CURSOR_HMAC_KEY_B64',
        'JSH_REPORT_INVENTORY_CURSOR_HMAC_KEY_B64', 'JSH_LOCAL_PACKAGE_SIGNING_KEY_ID',
        'JSH_LOCAL_PACKAGE_SIGNING_PKCS8_B64', 'JSH_LOCAL_PACKAGE_SIGNING_PUBLIC_B64'
    )
    foreach ($name in $required) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if (-not $value -or $value.StartsWith('replace-')) { throw "Missing local value: $name" }
    }
}

function Get-JshPosComposeArguments {
    param([Parameter(Mandatory)][string]$EnvironmentFile)
    return @('compose', '--project-name', 'jshpos-local', '--env-file', $EnvironmentFile,
        '-f', $script:JshPosComposeFile)
}

function Invoke-JshPosContainerMySql {
    param(
        [Parameter(Mandatory)][string]$Docker,
        [Parameter(Mandatory)][string]$EnvironmentFile,
        [Parameter(Mandatory)][string]$Sql
    )
    $arguments = @(Get-JshPosComposeArguments -EnvironmentFile $EnvironmentFile) + @(
        'exec', '-T', 'mysql', 'sh', '-lc',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot "$MYSQL_DATABASE" --batch --skip-column-names'
    )
    $result = $Sql | & $Docker @arguments
    if ($LASTEXITCODE -ne 0) { throw 'MySQL command failed.' }
    return @($result)
}

function Set-JshPosApplicationEnvironment {
    New-Item -ItemType Directory -Path $script:JshPosRuntimeDirectory -Force | Out-Null
    $logDirectory = Join-Path $script:JshPosRuntimeDirectory 'logs'
    $tempDirectory = Join-Path $script:JshPosRuntimeDirectory 'temp'
    New-Item -ItemType Directory -Path $logDirectory,$tempDirectory -Force | Out-Null
    $env:JSH_LOCAL_DB_URL = "jdbc:mysql://127.0.0.1:$($env:MYSQL_PORT)/$($env:MYSQL_DATABASE)?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&nullCatalogMeansCurrent=true"
    $env:JSH_LOCAL_DB_USERNAME = $env:MYSQL_USER
    $env:JSH_LOCAL_DB_PASSWORD = $env:MYSQL_PASSWORD
    $env:JSH_LOCAL_REDIS_HOST = '127.0.0.1'
    $env:JSH_LOCAL_REDIS_PORT = $env:REDIS_PORT
    $env:JSH_LOCAL_REDIS_PASSWORD = $env:REDIS_PASSWORD
    $env:JSH_LOCAL_MULTIPART_TEMP_DIR = Join-Path $tempDirectory 'multipart'
    $env:JSH_LOCAL_ATTACHMENT_TEMP_DIR = Join-Path $tempDirectory 'service-attachments'
    $env:JSH_LOCAL_PACKAGE_ROOT = Join-Path $script:JshPosRuntimeDirectory 'packages'
    $env:JSH_REPORT_ARTIFACT_ROOT = Join-Path $script:JshPosRuntimeDirectory 'reports'
    New-Item -ItemType Directory -Path $env:JSH_LOCAL_MULTIPART_TEMP_DIR,
        $env:JSH_LOCAL_ATTACHMENT_TEMP_DIR,$env:JSH_LOCAL_PACKAGE_ROOT,$env:JSH_REPORT_ARTIFACT_ROOT -Force |
        Out-Null
}
