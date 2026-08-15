param(
    [switch]$InstallDependencies
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

Write-Host '鲸熵汇 T0 开发环境检查'
Write-Host "Root: $root"
git --version
node --version
pnpm --version

if (Get-Command java -ErrorAction SilentlyContinue) {
    java -version
}
else {
    Write-Warning 'java is not on PATH; configure a supported JDK 21 while keeping server source level 17.'
}

if (Get-Command flutter -ErrorAction SilentlyContinue) {
    flutter --version
}
else {
    Write-Warning 'flutter is not on PATH; install the pinned 3.47.0 stable SDK.'
}

if ($InstallDependencies) {
    Push-Location (Join-Path $root 'admin-web')
    try { pnpm install --frozen-lockfile }
    finally { Pop-Location }

    if (-not (Test-Path (Join-Path $root 'infra\compose\.env'))) {
        Copy-Item -LiteralPath (Join-Path $root 'infra\compose\.env.example') -Destination (Join-Path $root 'infra\compose\.env')
        Write-Warning 'Created infra/compose/.env. Replace every sample password before starting containers.'
    }
}
