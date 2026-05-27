param()

$ErrorActionPreference = "Stop"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")

function Test-CommandExists {
    param([Parameter(Mandatory = $true)][string]$Name)

    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) {
        Write-Host "[ok] $Name -> $($cmd.Source)"
        return $true
    }

    Write-Host "[missing] $Name"
    return $false
}

function Test-EnvFile {
    param(
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [Parameter(Mandatory = $true)][string]$ExamplePath
    )

    $file = Join-Path $RepoRoot $RelativePath
    $example = Join-Path $RepoRoot $ExamplePath

    if (Test-Path $file) {
        Write-Host "[ok] $RelativePath"
        return
    }

    if (Test-Path $example) {
        Write-Host "[missing] $RelativePath (copy from $ExamplePath)"
        return
    }

    Write-Host "[missing] $RelativePath and $ExamplePath"
}

function Test-PortOpen {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [int]$TimeoutMs = 300
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync("127.0.0.1", $Port)
        if (-not $task.Wait($TimeoutMs)) {
            return $false
        }
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

Write-Host "== Tooling =="
Test-CommandExists "python" | Out-Null
Test-CommandExists "node" | Out-Null
Test-CommandExists "npm" | Out-Null
Test-CommandExists "java" | Out-Null
Test-CommandExists "mvn" | Out-Null
Test-CommandExists "docker" | Out-Null
Test-CommandExists "docker-compose" | Out-Null

Write-Host ""
Write-Host "== Env files =="
Test-EnvFile "agent-api\.env" "agent-api\.env.example"
Test-EnvFile "front\.env" "front\.env.example"
Test-EnvFile "secrets\gee-service-account.json" "secrets\gee-service-account.json.example"

Write-Host ""
Write-Host "== Important ports =="
foreach ($port in 3000, 8000, 8001, 8080, 8101, 8102, 8103, 8104, 8105, 9000, 9001) {
    if (Test-PortOpen $port) {
        Write-Host "[open] $port"
    } else {
        Write-Host "[free/closed] $port"
    }
}
