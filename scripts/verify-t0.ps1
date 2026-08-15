param(
    [switch]$SkipServer,
    [switch]$SkipAdmin,
    [switch]$SkipFlutter,
    [switch]$SkipAndroidBuild,
    [switch]$SkipInfrastructure,
    [string]$PythonExecutable = $env:JSH_POS_PYTHON,
    [string]$FlutterExecutable = $env:JSH_POS_FLUTTER
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$artifactDir = Join-Path $root 'artifacts\t0'
New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null

$results = [System.Collections.Generic.List[object]]::new()

function Add-GateResult {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Status,
        [double]$Seconds = 0,
        [string]$Detail = ''
    )
    $results.Add([pscustomobject]@{
        gate = $Name
        status = $Status
        seconds = [math]::Round($Seconds, 2)
        detail = $Detail
    })
}

function Invoke-Gate {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][scriptblock]$Action
    )
    $started = Get-Date
    try {
        & $Action
        Add-GateResult -Name $Name -Status 'PASS' -Seconds (((Get-Date) - $started).TotalSeconds)
    }
    catch {
        Add-GateResult -Name $Name -Status 'FAIL' -Seconds (((Get-Date) - $started).TotalSeconds) -Detail $_.Exception.Message
    }
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory)][string]$Executable,
        [Parameter(ValueFromRemainingArguments)][string[]]$Arguments
    )
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Executable exited with code $LASTEXITCODE"
    }
}

function Resolve-Executable {
    param(
        [string]$Explicit,
        [Parameter(Mandatory)][string[]]$Names,
        [Parameter(Mandatory)][string[]]$ProbeArguments
    )
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($Explicit) {
        $candidates.Add($Explicit)
    }
    foreach ($name in $Names) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) {
            $candidates.Add($command.Source)
        }
    }
    foreach ($candidate in $candidates | Select-Object -Unique) {
        try {
            & $candidate @ProbeArguments *> $null
            if ($LASTEXITCODE -eq 0) {
                return $candidate
            }
        }
        catch {
            continue
        }
    }
    return $null
}

$python = Resolve-Executable -Explicit $PythonExecutable -Names @('python', 'py') -ProbeArguments @('--version')
if (-not $python) {
    throw 'Python 3 is required. Set JSH_POS_PYTHON to a working executable.'
}

try {
    Invoke-Gate 'structure' { Invoke-Checked $python (Join-Path $root 'scripts\check_t0_structure.py') }
    Invoke-Gate 'rtm' { Invoke-Checked $python (Join-Path $root 'scripts\check_rtm.py') }
    Invoke-Gate 'contracts' { Invoke-Checked $python (Join-Path $root 'scripts\check_contracts.py') }

    if ($SkipServer) {
        Add-GateResult -Name 'server' -Status 'SKIP' -Detail 'Requested by caller.'
    }
    else {
        Invoke-Gate 'server' {
            Push-Location (Join-Path $root 'server')
            try { Invoke-Checked '.\mvnw.cmd' '-DskipTests=false' 'clean' 'verify' }
            finally { Pop-Location }
        }
    }

    if ($SkipAdmin) {
        Add-GateResult -Name 'admin-web' -Status 'SKIP' -Detail 'Requested by caller.'
    }
    else {
        Invoke-Gate 'admin-web' {
            Push-Location (Join-Path $root 'admin-web')
            try {
                Invoke-Checked 'pnpm' 'install' '--frozen-lockfile'
                Invoke-Checked 'pnpm' 'audit' '--registry' 'https://registry.npmjs.org' '--audit-level' 'high'
                Invoke-Checked 'pnpm' 'lint:eslint'
                Invoke-Checked 'pnpm' 'typecheck'
                Invoke-Checked 'pnpm' 'test:unit'
                Invoke-Checked 'pnpm' 'build:prod'
            }
            finally { Pop-Location }
        }
    }

    if ($SkipFlutter) {
        Add-GateResult -Name 'device-adapter' -Status 'SKIP' -Detail 'Requested by caller.'
        Add-GateResult -Name 'flutter-pos' -Status 'SKIP' -Detail 'Requested by caller.'
        Add-GateResult -Name 'android-apk' -Status 'SKIP' -Detail 'Requested by caller.'
    }
    else {
        $flutter = Resolve-Executable -Explicit $FlutterExecutable -Names @('flutter') -ProbeArguments @('--version')
        if (-not $flutter) {
            Add-GateResult -Name 'device-adapter' -Status 'FAIL' -Detail 'Set JSH_POS_FLUTTER to Flutter 3.47.0.'
            Add-GateResult -Name 'flutter-pos' -Status 'FAIL' -Detail 'Set JSH_POS_FLUTTER to Flutter 3.47.0.'
            Add-GateResult -Name 'android-apk' -Status 'FAIL' -Detail 'Flutter is unavailable.'
        }
        else {
            $flutterVersion = (& $flutter '--version') -join "`n"
            if ($flutterVersion -notmatch 'Flutter 3\.47\.0') {
                Add-GateResult -Name 'device-adapter' -Status 'FAIL' -Detail 'Flutter must be exactly 3.47.0.'
                Add-GateResult -Name 'flutter-pos' -Status 'FAIL' -Detail 'Flutter must be exactly 3.47.0.'
                Add-GateResult -Name 'android-apk' -Status 'FAIL' -Detail 'Flutter version mismatch.'
            }
            else {
                Invoke-Gate 'device-adapter' {
                    Push-Location (Join-Path $root 'packages\pos_device_adapter')
                    try {
                        Invoke-Checked $flutter 'pub' 'get' '--enforce-lockfile'
                        Invoke-Checked $flutter 'analyze' '--fatal-infos'
                        Invoke-Checked $flutter 'test'
                    }
                    finally { Pop-Location }
                }
                Invoke-Gate 'flutter-pos' {
                    Push-Location (Join-Path $root 'pos-flutter')
                    try {
                        Invoke-Checked $flutter 'pub' 'get' '--enforce-lockfile'
                        Invoke-Checked $flutter 'analyze' '--fatal-infos'
                        Invoke-Checked $flutter 'test'
                    }
                    finally { Pop-Location }
                }
                if ($SkipAndroidBuild) {
                    Add-GateResult -Name 'android-apk' -Status 'SKIP' -Detail 'Android SDK unavailable on this workstation; CI remains mandatory.'
                }
                else {
                    Invoke-Gate 'android-apk' {
                        Push-Location (Join-Path $root 'pos-flutter')
                        try { Invoke-Checked $flutter 'build' 'apk' '--debug' }
                        finally { Pop-Location }
                    }
                }
            }
        }
    }

    if ($SkipInfrastructure) {
        Add-GateResult -Name 'compose' -Status 'SKIP' -Detail 'Docker unavailable on this workstation; CI remains mandatory.'
    }
    else {
        $docker = Resolve-Executable -Names @('docker') -ProbeArguments @('--version')
        if (-not $docker) {
            Add-GateResult -Name 'compose' -Status 'FAIL' -Detail 'Docker with Compose v2 is required.'
        }
        else {
            Invoke-Gate 'compose' {
                Push-Location (Join-Path $root 'infra\compose')
                try { Invoke-Checked $docker 'compose' '--env-file' '.env.example' 'config' '--quiet' }
                finally { Pop-Location }
            }
        }
    }
}
finally {
    $summary = [pscustomobject]@{
        generated_at = (Get-Date).ToUniversalTime().ToString('o')
        root = $root
        results = $results
    }
    $summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $artifactDir 'summary.json') -Encoding utf8
    $results | Format-Table -AutoSize
}

if ($results.status -contains 'FAIL') {
    exit 1
}
