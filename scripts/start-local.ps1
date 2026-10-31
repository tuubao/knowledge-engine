param(
    [Parameter(Mandatory=$true)]
    [ValidateSet('engine','java','frontend')]
    [string]$Service,
    [switch]$Cpu
)

$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $workspaceRoot

# 三个终端读取同一份配置，密钥只写入本地 .env，不出现在命令行参数中。
$envPath = Join-Path $workspaceRoot '.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    Copy-Item -LiteralPath (Join-Path $workspaceRoot '.env.example') -Destination $envPath
}
foreach ($line in Get-Content -LiteralPath $envPath -Encoding utf8) {
    if ($line -match '^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
        $configName = $Matches[1]
        $configValue = $Matches[2].Trim().Trim('"').Trim("'")
        # 当前终端显式覆盖的值优先，例如 BGE_DEVICE=cpu。
        if (-not [Environment]::GetEnvironmentVariable($configName, 'Process')) {
            [Environment]::SetEnvironmentVariable($configName, $configValue, 'Process')
        }
    }
}
if (-not $env:RAG_SERVICE_KEY -or $env:RAG_SERVICE_KEY -eq 'replace-with-a-random-service-key') {
    $randomBytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $generator.GetBytes($randomBytes)
    $generator.Dispose()
    $env:RAG_SERVICE_KEY = [Convert]::ToBase64String($randomBytes)
    $text = Get-Content -LiteralPath $envPath -Raw -Encoding utf8
    $entry = 'RAG_SERVICE_KEY=' + $env:RAG_SERVICE_KEY
    if ($text -match '(?m)^RAG_SERVICE_KEY=.*$') {
        $text = [regex]::Replace($text, '(?m)^RAG_SERVICE_KEY=.*$', $entry)
    } else {
        $text += "`n$entry`n"
    }
    [System.IO.File]::WriteAllText($envPath, $text, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host '已生成内部服务密钥并保存到本地 .env。'
}
if ($Cpu) {
    $env:BGE_DEVICE = 'cpu'
    $env:BGE_FP16 = 'false'
}

switch ($Service) {
    'engine' {
        $pythonExecutable = Join-Path $workspaceRoot '.venv\Scripts\python.exe'
        if (-not (Test-Path -LiteralPath $pythonExecutable)) {
            throw '请先创建 .venv 并安装 requirements.txt。'
        }
        & $pythonExecutable -m uvicorn rag_engine.app:app --host 127.0.0.1 --port 8000 --workers 1
    }
    'java' {
        Set-Location -LiteralPath (Join-Path $workspaceRoot 'java-service')
        $env:MAVEN_USER_HOME = Join-Path $workspaceRoot 'java-service\.m2'
        if (Test-Path -LiteralPath '.\mvnw.cmd') { & .\mvnw.cmd -s .mvn/settings.xml '-Dmaven.repo.local=.m2' spring-boot:run }
        else { & mvn -s .mvn/settings.xml '-Dmaven.repo.local=.m2' spring-boot:run }
    }
    'frontend' {
        Set-Location -LiteralPath (Join-Path $workspaceRoot 'frontend')
        & npm.cmd run dev -- --host 127.0.0.1
    }
}
exit $LASTEXITCODE
