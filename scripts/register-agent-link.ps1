param(
    [string]$PythonExe = "",
    [string]$AgentExecutable = "",
    [string]$TokenFile = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$settingsPath = Join-Path $projectRoot "companion\agent.local.json"
if (Test-Path -LiteralPath $settingsPath) {
    $localSettings = Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json
    if (-not $PythonExe) { $PythonExe = $localSettings.python_exe }
    if (-not $AgentExecutable) { $AgentExecutable = $localSettings.agent_executable }
    if (-not $TokenFile) { $TokenFile = $localSettings.token_file }
}
if (-not $PythonExe) { $PythonExe = (Get-Command python -ErrorAction Stop).Source }
if (-not $AgentExecutable) { $AgentExecutable = (Get-Command codex -ErrorAction Stop).Source }
if (-not $TokenFile) { $TokenFile = Join-Path $projectRoot "runtime\game\config\schematic-supervisor\protocol-token.txt" }
& $AgentExecutable mcp add schematic-supervisor -- $PythonExe `
    (Join-Path $projectRoot "companion\agent_launcher.py") serve --token-file $TokenFile
exit $LASTEXITCODE
