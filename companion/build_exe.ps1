param(
    [string]$PythonExe = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot
if (-not $PythonExe) {
    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if ($pythonCommand) { $PythonExe = $pythonCommand.Source }
    elseif (Test-Path -LiteralPath (Join-Path $projectRoot 'agent.local.json')) {
        $settings = Get-Content -LiteralPath (Join-Path $projectRoot 'agent.local.json') -Raw | ConvertFrom-Json
        $PythonExe = $settings.python_exe
    }
}
if (-not $PythonExe) { throw 'Specify -PythonExe with a Python 3.11+ interpreter.' }
$previousPythonPath = $env:PYTHONPATH
$localTools = Join-Path $projectRoot '.build-tools'
if (Test-Path -LiteralPath $localTools) {
    $env:PYTHONPATH = "$localTools;$previousPythonPath"
}

Push-Location -LiteralPath $projectRoot
try {
    & $PythonExe -m PyInstaller --version | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "PyInstaller is not installed for the selected Python interpreter."
    }

    & $PythonExe -m PyInstaller `
        --noconfirm `
        --clean `
        --onefile `
        --windowed `
        --name "SchematicSupervisor" `
        --version-file (Join-Path $projectRoot 'version_info.txt') `
        --paths $projectRoot `
        (Join-Path $projectRoot "launcher.py")
    if ($LASTEXITCODE -ne 0) {
        throw "Executable packaging failed."
    }
}
finally {
    $env:PYTHONPATH = $previousPythonPath
    Pop-Location
}
