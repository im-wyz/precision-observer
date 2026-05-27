param(
    [switch]$Deep
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

$targets = @(
    "agent-api\__pycache__",
    "agent-api\src\agent_api\__pycache__",
    "agent-api\src\agent_api\tools\__pycache__",
    "agent-api\src\agent_api\mcp_bridge\__pycache__",
    "agent-api\src\agent_api\rules\__pycache__",
    "agent-api\src\precision_observer_agent_api.egg-info",
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
    $path = Join-Path $RepoRoot $relative
    if (-not (Test-Path $path)) {
        continue
    }

    $resolved = (Resolve-Path $path).Path
    if (-not $resolved.StartsWith($RepoRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove path outside repo: $resolved"
    }

    Remove-Item -LiteralPath $resolved -Recurse -Force
    Write-Host "[removed] $relative"
}

Write-Host "Clean complete."
