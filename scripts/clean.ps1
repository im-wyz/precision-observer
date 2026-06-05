param(
    [switch]$Deep
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Remove-RepoPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path $Path)) {
        return
    }

    $resolved = (Resolve-Path $Path).Path
    if (-not $resolved.StartsWith($RepoRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove path outside repo: $resolved"
    }

    Remove-Item -LiteralPath $resolved -Recurse -Force
    Write-Host "[removed] $($resolved.Substring($RepoRoot.Length + 1))"
}

$targets = @(
    "front\dist",
    "back\raster-api\target"
)

if ($Deep) {
    $targets += @(
        "agent-api\.venv",
        "front\node_modules"
    )
}

foreach ($relative in $targets) {
    Remove-RepoPath (Join-Path $RepoRoot $relative)
}

$cacheNames = @("__pycache__", ".pytest_cache", ".ruff_cache", ".mypy_cache")
foreach ($cacheName in $cacheNames) {
    Get-ChildItem -LiteralPath $RepoRoot -Recurse -Directory -Force -Filter $cacheName |
        Where-Object {
            $_.FullName -notlike (Join-Path $RepoRoot "front\node_modules\*") -and
            $_.FullName -notlike (Join-Path $RepoRoot "agent-api\.venv\*")
        } |
        ForEach-Object {
            Remove-RepoPath $_.FullName
        }
}

Get-ChildItem -LiteralPath $RepoRoot -Recurse -Directory -Force -Filter "*.egg-info" |
    Where-Object { $_.FullName -notlike (Join-Path $RepoRoot "agent-api\.venv\*") } |
    ForEach-Object {
        Remove-RepoPath $_.FullName
}

Write-Host "Clean complete."
