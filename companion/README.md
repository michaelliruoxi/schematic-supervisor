# Schematic Supervisor Companion

## Windows live monitor

Double-click **`dist\SchematicSupervisor.exe`**. No Python installation or configuration is needed
to use the packaged app in this project. It finds the dedicated profile's pairing file on its own
and reads the mod every second. Minecraft must be running with Schematic Supervisor enabled.

The monitor opens as a small card that stays on top of other windows (Unpin turns that off); later
it reopens in the layout you last used. The card shows:

- the build state and how long ago the mod last confirmed progress;
- **% built**: the share of planned placements, tilling, and planting that is finished (final
  verification is shown separately as "Verify n/2");
- a stage timeline: green done, blue current, fainter green partly done, grey to do (click a stage
  to open it in the full window);
- one line saying what's happening now, or what needs attention and how to fix it;
- **Pause**/**Continue** and **Scan depots**.

**Expand** opens the full window:

- the stage timeline, and a chunk map for any stage or for all stages (point at a stage to preview
  its chunks, click it to keep them);
- build speed and time remaining;
- where the last control came from;
- every current problem with its fix;
- all controls (Start, Pause, Continue, Stop, Scan depots);
- the Status details, Materials, Activity, and Raw data tabs.

**Compact** returns to the card. **More** has Open log folder, Sound on attention, Keep full window
on top, Reset card position, and About.

When the build pauses, reports an error, finishes, or loses its connection for 15 seconds during
work, the taskbar button flashes. It also beeps if Sound on attention is on. A Pause sent from the
monitor itself doesn't flash.

### Phone notifications

The same alerts can go to your phone through [ntfy](https://ntfy.sh) (a free app; no account
needed) or a Discord webhook. They are off until you add a `notify` section to a `config.json`
beside the executable:

```json
{
  "notify": {
    "ntfy_url": "https://ntfy.sh/pick-a-long-random-topic-name",
    "discord_webhook_url": "https://discord.com/api/webhooks/…"
  }
}
```

Use either address or both. For ntfy, subscribe to the same topic in the app. A pause notification
carries the cause and its fix hint; the cause can include world coordinates. Anyone who knows an
ntfy topic can read it, and anyone with a webhook address can post to it, so treat both like
passwords; `--check-config` prints them as `(set)`. Alerts are at least `minimum_interval_seconds`
apart (default 60), sending times out after `timeout_seconds` (default 5), and a failed send is
logged without affecting the monitor. The monitor must be running to send them.

The card shows over Minecraft only when Minecraft runs windowed or borderless; exclusive
fullscreen can cover it. Clicking the card takes focus from Minecraft, and the mod keeps building.
Only one monitor can run at a time.

Opening the monitor never starts or resumes construction, and closing it leaves the build
unchanged. Stop asks for confirmation.

Start first runs the mod's build check: the state shows **Checking** with a percentage for a few
seconds while the mod reads what is already built. Construction then starts at the first unfinished
piece and skips finished ones, so the percentage is right straight away. The Activity tab records
the result. If the check found wrong or extra blocks that the builder won't fix, the full window
lists them under attention with the first block's coordinates; Status details lists up to ten.
Chunks that were out of range are noted and checked when the build reaches them. Pause during the
check cancels it without starting. A mod without the check starts from the first stage and
re-checks placed blocks, so the percentage starts over and climbs quickly.

Older mods without the progress endpoint still work. The monitor shows an approximate percentage
and asks you to update the mod for exact progress and the chunk map.

Logs are written to `logs\monitor.log` next to the executable, or to
`%LOCALAPPDATA%\SchematicSupervisor\logs` if that folder isn't writable. The pairing token is never
written to the log.

The mod writes its pairing file to `config\schematic-supervisor\protocol-token.txt` in the game
folder (the file contains a credential; do not share it). The monitor looks for it beside itself and
up to three folders above, which finds `runtime\game` in this project, and then in the default
launcher's game folder, `%APPDATA%\.minecraft`. For any other game folder, supply it explicitly:

```powershell
.\SchematicSupervisor.exe --token-file "$env:APPDATA\.minecraft\config\schematic-supervisor\protocol-token.txt"
```

Read-only connection diagnostics, including progress, for the packaged executable:

```powershell
.\SchematicSupervisor.exe --check-connection --output connection.json
```

From source, run `python launcher.py`. `--mod-url` accepts only loopback URLs; `--poll-interval`
sets the observation interval in seconds (default 1).

### Progress data

The monitor reads `GET {mod.base_url}/v1/progress` with the same token as `/v1/observation`. The
response has per-stage totals and one letter per chunk: `D` done, `C` current, `-` to do, `.` no
work at that stage. The monitor fetches it when the observation's `progress_revision` changes,
after a reconnect, and every 30 seconds. The observation also reports `last_progress_at`, the
wall-clock time of the last confirmed progress. The full contract is in
[`docs/design/live-dashboard.md`](../docs/design/live-dashboard.md). Progress also counts items per
material, planned and in finished pieces; the Materials tab shows them as Placed and Remaining.
With an older mod the tab shows the material ledger instead, which counts only what the run used
since the last Start.

Pieces the start build check found finished count as done (`D`) even when they come after the
current piece. The observation's `build_check` object reports the check while it runs and its last
result; see [`docs/design/start-build-check.md`](../docs/design/start-build-check.md).

## Legacy incident receiver

This directory contains the small Python desktop companion for the deterministic
Fabric build supervisor. It displays the latest chunk, phase, tracked materials,
Baritone status, and last error; sends Start, Pause, Resume, and Stop controls;
and provides tightly constrained incident diagnosis.

For active agent supervision, use `agent_launcher.py serve` for the stdio MCP
bridge and `agent_launcher.py run` for the continuous runner. The prepared
Windows entry point is `..\scripts\start-supervision.ps1`; use `-Inspect` first.
These components connect directly to the mod and do not require the desktop
window. See [the agent supervision guide](../docs/agent-supervision.md).

The companion never chooses coordinates, places or breaks blocks, sends chat or
commands, shops, or directly controls the player. It has no database. The mod
remains responsible for deterministic building and recovery.

## Safety behavior

The only accepted diagnosis actions are:

- `WAIT`
- `REPATH`
- `RESTOCK`
- `RETRY_CHUNK`
- `RETURN_TO_SAFE_POSITION`
- `PAUSE_AND_ALERT`

Every provider response is parsed and checked against that enum. A disabled,
unavailable, malformed, or out-of-policy provider becomes `PAUSE_AND_ALERT`.
The companion also makes an independent best-effort `PAUSE` control request to
the mod. If that request cannot be delivered, the incident response still tells
the mod to pause and the delivery failure is shown in the window.

Both companion and mod protocol addresses must be loopback hosts. An optional
shared token can be read from an environment variable; token values and AI API
keys are never stored in JSON configuration.

## Run from source

Python 3.11 or newer is required. Runtime code uses only the standard library.

```powershell
Set-Location companion
Copy-Item config.disabled.example.json config.json
python -m supervisor_companion --check-config --config config.json
python -m supervisor_companion --config config.json
```

For the mod's generated pairing credential, add
`--token-file ..\runtime\game\config\schematic-supervisor\protocol-token.txt`
after the first game launch. The explicit environment-token configuration
continues to take precedence.

The first command validates configuration without binding a port, contacting an
AI endpoint, or opening a window. The second starts the loopback receiver and
the Tk window. Do not run the second command until the mod-side protocol is
ready.

The disabled example is the safe default: all submitted incidents pause for
operator review. Use the Ollama or HTTP example only after configuring the
corresponding provider.

## Localhost protocol

The default companion receiver is `http://127.0.0.1:8766`.

### Mod to companion: status

`POST /v1/status` with `Content-Type: application/json`:

```json
{
  "current_chunk": {"index": 7, "total": 49, "x": 12, "z": -3},
  "phase": "PLANTING",
  "materials": {
    "dirt": {"available": 412, "required": 600, "missing": 188},
    "seeds": {"available": 2048, "required": 1900, "missing": 0},
    "food": 32
  },
  "baritone_status": "Returning to chunk",
  "last_error": null,
  "updated_at": "2026-07-22T21:00:00.000Z"
}
```

Chunk indexes are 1-based. Coordinates are optional and may be omitted. A
material can be an available integer or an object with available, optional
required, and optional missing counts.

### Mod to companion: unresolved incident

`POST /v1/incidents`:

```json
{
  "incident_id": "plan-a-chunk-7-2",
  "category": "NO_PROGRESS",
  "summary": "No verified progress after deterministic recovery",
  "recovery_exhausted": true,
  "status": {
    "current_chunk": {"index": 7, "total": 49},
    "phase": "BUILDING",
    "materials": {"dirt": {"available": 412, "required": 600}},
    "baritone_status": "Path cancelled",
    "last_error": "No progress for 15 seconds"
  },
  "attempted_recovery": ["WAIT_FOR_LAG", "RESTART_PATH", "RETURN_SAFE"],
  "details": {
    "plan_id": "plan-a",
    "server_lag_suspected": false,
    "stalled_seconds": 45,
    "missing_materials": {}
  }
}
```

The synchronous response is always constrained:

```json
{
  "incident_id": "plan-a-chunk-7-2",
  "action": "REPATH",
  "reason": "Cancel and restart only the current deterministic path.",
  "source": "ollama",
  "used_fallback": false
}
```

Non-pause diagnosis is considered only when `recovery_exhausted` is true, a
status snapshot is present, and at least one deterministic recovery attempt is
listed. Incomplete evidence fails closed to `PAUSE_AND_ALERT`. Completed
decisions are cached for the most recent 256 incident IDs in memory, so an
identical HTTP retry returns the same result without calling the provider or
repeating a Pause side effect. Reusing an incident ID for changed data returns
HTTP 409.

`GET /v1/status` returns the latest in-memory view. `GET /v1/health` returns a
small health response. There is no persistence beyond rotating text logs.

When `server.token_env` is configured, send that value in
`X-Supervisor-Token` for status and incident requests. Health remains available
without a token.

POST requests require exactly one decimal `Content-Length`; transfer encodings
and truncated bodies are rejected. Error responses close the connection so
unread body bytes cannot be processed as a later request.

### Companion to mod: operator control

The companion posts to `{mod.base_url}/v1/control`:

```json
{
  "action": "PAUSE",
  "request_id": "a-generated-uuid",
  "sent_at": "2026-07-22T21:00:00.000+00:00"
}
```

`action` is exactly `START`, `PAUSE`, `RESUME`, or `STOP`. The mod should return:

```json
{"accepted": true, "message": "Paused.", "state": "STUCK"}
```

The `accepted` boolean is required. An empty response, HTTP 204, or a response
without an explicit `true` acknowledgment is a delivery failure. Redirects are
never followed, so a validated loopback destination cannot cross into a
different network trust boundary.

When `mod.token_env` is configured, the companion sends its value in
`X-Supervisor-Token`.

The mod accepts unauthenticated loopback Pause and Stop as fail-safe reductions
in activity. Start and Resume require `SCHEMATIC_PROTOCOL_TOKEN`, and every
control request ID is one-use with a 30-second freshness window.

Controls still queued on the client thread after the mod's three-second
deadline are cancelled. If execution has already started, a timeout reports
that the outcome is unknown; check status before retrying Start or Resume.

## Diagnosis providers

For `"provider": "ollama"`, the companion calls the local `/api/chat` endpoint
with streaming disabled, JSON format requested, temperature zero, the fixed
safety instructions, and structured incident data.

For `"provider": "http"`, the endpoint receives:

```json
{
  "schema_version": 1,
  "instructions": "fixed safety instructions",
  "allowed_actions": ["WAIT", "REPATH", "RESTOCK", "RETRY_CHUNK", "RETURN_TO_SAFE_POSITION", "PAUSE_AND_ALERT"],
  "incident": {"incident_id": "..."}
}
```

The simplest valid response is `{"action":"WAIT"}`. Direct decisions, a
`decision` object, Ollama `message.content`, and chat-completions-style
`choices[0].message.content` JSON are understood; all are subjected to the same
allowlist. Provider prose is never forwarded to the mod or displayed. The
human-readable `reason` in the companion response is selected from fixed local
text after the action passes validation.

## Tests

Tests are headless and use temporary loopback servers. They do not start
Minecraft, the Tk window, Ollama, or an external endpoint.

```powershell
Set-Location companion
python -m unittest discover -s tests -v
```

## Build the Windows executable

Packaging is optional and uses PyInstaller as a build-only dependency:

```powershell
python -m pip install ".[build]"
.\build_exe.ps1 -PythonExe python
```

The output is `dist\SchematicSupervisor.exe`. The monitor uses built-in defaults
and automatic pairing. A validated `config.json` beside the executable or an
explicit `--config` path can override the defaults.
