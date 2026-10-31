param([switch]$SkipInstall, [switch]$SkipSmoke)
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $workspaceRoot

& .\.venv\Scripts\python.exe -m unittest discover -s tests -v
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& .\.venv\Scripts\python.exe -m unittest discover -s evaluation/tests -v
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Push-Location -LiteralPath (Join-Path $workspaceRoot 'java-service')
try {
    if (Test-Path -LiteralPath '.\mvnw.cmd') {
        $env:MAVEN_USER_HOME = Join-Path $workspaceRoot 'java-service\.m2'
        & .\mvnw.cmd -s .mvn/settings.xml '-Dmaven.repo.local=.m2' verify
    } else {
        & mvn -s .mvn/settings.xml '-Dmaven.repo.local=.m2' verify
    }
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally { Pop-Location }
Push-Location -LiteralPath (Join-Path $workspaceRoot 'frontend')
try {
    if (-not $SkipInstall) { & npm.cmd ci --cache .npm-cache; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }
    & npm.cmd test
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally { Pop-Location }

if (-not $SkipSmoke) {
    & .\.venv\Scripts\python.exe scripts/smoke.py
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
