param(
    [ValidateSet("all", "infra", "agent", "api", "web")]
    [string]$Only = "all",
    [switch]$SkipInstall,
    [switch]$SkipInfra
)

$ErrorActionPreference = "Stop"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")

function ConvertTo-PSLiteral {
    param([Parameter(Mandatory = $true)][string]$Value)
    return "'" + $Value.Replace("'", "''") + "'"
}

function Copy-EnvIfMissing {
    param(
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [Parameter(Mandatory = $true)][string]$ExamplePath
    )

    $file = Join-Path $RepoRoot $RelativePath
    $example = Join-Path $RepoRoot $ExamplePath

    if ((Test-Path $file) -or -not (Test-Path $example)) {
        return
    }

    Copy-Item -LiteralPath $example -Destination $file
    Write-Host "[env] created $RelativePath from $ExamplePath"
}

function Start-Terminal {
    param(
        [Parameter(Mandatory = $true)][string]$Title,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$Command
    )

    $work = ConvertTo-PSLiteral $WorkingDirectory
    $fullCommand = "`$Host.UI.RawUI.WindowTitle = '$Title'; Set-Location -LiteralPath $work; $Command"
    Start-Process powershell.exe -ArgumentList @("-NoExit", "-ExecutionPolicy", "Bypass", "-Command", $fullCommand) | Out-Null
    Write-Host "[start] $Title"
}

function Invoke-ComposeUp {
    $composeFile = Join-Path $RepoRoot "docker\docker-compose.yml"
    if (Get-Command "docker-compose" -ErrorAction SilentlyContinue) {
        docker-compose -f $composeFile up -d --build
        return
    }

    docker compose -f $composeFile up -d --build
}

$agentDir = Join-Path $RepoRoot "agent-api"
$apiDir = Join-Path $RepoRoot "back\raster-api"
$webDir = Join-Path $RepoRoot "front"

Copy-EnvIfMissing "agent-api\.env" "agent-api\.env.example"
Copy-EnvIfMissing "front\.env" "front\.env.example"

if (-not $SkipInstall) {
    if ($Only -in @("all", "agent")) {
        $venvPython = Join-Path $agentDir ".venv\Scripts\python.exe"
        if (-not (Test-Path $venvPython)) {
            Write-Host "[install] creating Python venv"
            python -m venv (Join-Path $agentDir ".venv")
        }
        Write-Host "[install] agent-api requirements"
        & $venvPython -m pip install -r (Join-Path $agentDir "requirements.txt")
        & $venvPython -m pip install -e $agentDir
    }

    if ($Only -in @("all", "web") -and -not (Test-Path (Join-Path $webDir "node_modules"))) {
        Write-Host "[install] frontend packages"
        Push-Location $webDir
        npm install
        Pop-Location
    }
}

if (-not $SkipInfra -and $Only -in @("all", "infra")) {
    Write-Host "[infra] docker compose up"
    Invoke-ComposeUp
}

if ($Only -eq "infra") {
    return
}

if ($Only -in @("all", "agent")) {
    $python = Join-Path $agentDir ".venv\Scripts\python.exe"
    if (-not (Test-Path $python)) {
        $python = "python"
    }
    $agentSrc = Join-Path $agentDir "src"
    Start-Terminal "precision-observer agent-api :8001" $agentDir "`$env:PYTHONPATH = $(ConvertTo-PSLiteral $agentSrc); & $(ConvertTo-PSLiteral $python) -m uvicorn agent_api.main:app --host 0.0.0.0 --port 8001 --reload"
}

if ($Only -in @("all", "api")) {
    if (Get-Command "mvn" -ErrorAction SilentlyContinue) {
        Start-Terminal "precision-observer raster-api :8080" $apiDir "mvn spring-boot:run"
    } else {
        Write-Host "[skip] raster-api: mvn was not found. Install Maven or add it to PATH."
    }
}

if ($Only -in @("all", "web")) {
    Start-Terminal "precision-observer front :3000" $webDir "npm run dev"
}

Write-Host ""
Write-Host "Front:     http://localhost:3000"
Write-Host "Spring:    http://localhost:8080"
Write-Host "Agent API: http://localhost:8001"
