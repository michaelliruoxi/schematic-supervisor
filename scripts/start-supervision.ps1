param(
    [string]$PythonExe = "",
    [string]$AgentExecutable = "",
    [string]$TokenFile = "",
    [string]$Model = "",
    [string]$ReasoningEffort = "",
    [ValidateSet("", "on-error", "continuous", "disabled")]
    [string]$ModelPolicy = "",
    [string]$Objective = "Complete the selected schematic in horizontal layers, let the mod handle routine work, and repair evidenced supervisor bugs when it cannot recover.",
    [switch]$Inspect,
    [switch]$Once,
    [switch]$NoActivation
)

$ErrorActionPreference = "Stop"
$explicitAgentExecutable = $PSBoundParameters.ContainsKey("AgentExecutable")
if ($explicitAgentExecutable -and [string]::IsNullOrWhiteSpace($AgentExecutable)) {
    throw "-AgentExecutable requires an existing executable path or command name."
}
$projectRoot = Split-Path -Parent $PSScriptRoot
$settingsPath = Join-Path $projectRoot "companion\agent.local.json"
if (Test-Path -LiteralPath $settingsPath) {
    $localSettings = Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json
    if (-not $PythonExe) { $PythonExe = $localSettings.python_exe }
    if (-not $AgentExecutable) { $AgentExecutable = $localSettings.agent_executable }
    if (-not $TokenFile) { $TokenFile = $localSettings.token_file }
    if (-not $Model) { $Model = $localSettings.model }
    if (-not $ReasoningEffort) { $ReasoningEffort = $localSettings.reasoning_effort }
    if (-not $ModelPolicy) { $ModelPolicy = $localSettings.model_policy }
}
if (-not $ModelPolicy) { $ModelPolicy = "on-error" }
$agentCommand = $null
if (-not [string]::IsNullOrWhiteSpace($AgentExecutable)) {
    $agentCommand = Get-Command -Name $AgentExecutable -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
}
if (-not $agentCommand) {
    if ($explicitAgentExecutable) {
        throw "The explicit -AgentExecutable '$AgentExecutable' could not be found. Supply an existing executable path or command name."
    }
    # A saved application bundle path can expire after an update; resolve the current installation.
    $agentCommand = Get-Command codex -CommandType Application -ErrorAction Stop | Select-Object -First 1
}
$AgentExecutable = $agentCommand.Source
if (-not $PythonExe) { $PythonExe = (Get-Command python -ErrorAction Stop).Source }
if (-not $TokenFile) { $TokenFile = Join-Path $projectRoot "runtime\game\config\schematic-supervisor\protocol-token.txt" }

$runnerArguments = @(
    (Join-Path $projectRoot "companion\agent_launcher.py"), "run",
    "--workspace", $projectRoot, "--agent-executable", $AgentExecutable,
    "--token-file", $TokenFile, "--objective", $Objective, "--model-policy", $ModelPolicy
)
if ($Inspect) { $runnerArguments += "--inspect" }
if ($Model) { $runnerArguments += @("--model", $Model) }
if ($ReasoningEffort) { $runnerArguments += @("--reasoning-effort", $ReasoningEffort) }
if ($Once) { $runnerArguments += "--once" }
if (-not $NoActivation -and -not $Inspect) {
    $runnerArguments += @("--allow-start", "--allow-resume", "--allow-repair")
}
& $PythonExe @runnerArguments
exit $LASTEXITCODE
