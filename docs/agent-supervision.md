# Agent supervision

The local bridge exposes live mod state and bounded controls. The runner defaults
to `on-error`: ready, armed activation is deterministic, and active mod work
does not invoke the model. An unresolved fault can request one structured
decision per distinct incident; unchanged status polling does not repeat it.
Decisions are checked against fresh observations before any allowed action.
The deterministic builder still owns pathfinding, placement, farming, restocking,
and final verification. Construction follows horizontal layers across the whole
farm: structural planes and their underside lighting, then farming from top down.

Both parts are optional. The mod builds on its own, and the desktop monitor
([`companion/README.md`](../companion/README.md)) needs neither.

## Setup

- Launcher profile: **Schematic Supervisor**, installed as described in
  [`profile.md`](profile.md#install-the-profile).
- Game directory: `runtime\game`.
- The local `schematic-supervisor` MCP server runs `companion\agent_launcher.py serve`;
  `scripts\register-agent-link.ps1` registers it with the agent CLI.
- Machine-specific interpreter, CLI, pairing paths, and the runner's model settings live in the
  ignored `companion\agent.local.json`. No account credentials are copied into this project.

The runner requests the model and reasoning effort saved in `agent.local.json`. There is no
automatic model fallback. Override them with `-Model` and `-ReasoningEffort` after checking
compatibility.

Launch the profile and join the intended world. Enable an existing flight ability,
select exactly one enabled supported Litematica sub-region,
and register the intended material chests. The checked-in farm already contains
one sub-region; horizontal build stages are selected automatically, with no need
to split the schematic. This initial world setup is required.

## Run the supervisor

```powershell
.\scripts\start-supervision.ps1 -Inspect
.\scripts\start-supervision.ps1
```

The first command checks local sign-in and live mod readiness without requesting
a model decision. The second monitors the mod continuously and arms the selected
build and fault investigation. Its default `-ModelPolicy on-error` uses no model
calls during loading, building, restocking, verification, or the mod's own
recovery. An unchanged fault is not repeatedly sent to the model as time or
status text changes. `-ModelPolicy disabled` prevents all model calls;
`-ModelPolicy continuous` restores periodic model decisions. Keep the runner
process open. `-NoActivation` disables automatic start,
resume, depot scans, and repairs while retaining Pause/Stop. `-Once` limits it
to one supervision cycle and pauses unfinished active work on exit.

Routine mod work can run without CLI authentication; model-assisted fault diagnosis still
requires it. The process must remain running; it is not a scheduled desktop task. Its default
policy permits starting/resuming this build, scanning registered depots, and investigating
reproducible code defects. It stops for operator Pause/Stop, connection/agent failure, verified
completion, or a repair needing installation and restart. It does not silently repeat a run after
Stop or completion.

The runner honors in-game Pause/Stop, rejects stale decisions, and exits on
verified completion or a condition requiring operator action. Repairs require
an acknowledged pause and fresh inactive game state before workspace edits.
A rebuilt Java mod must be installed and the game restarted before it can be
verified in-world; the runner reports this boundary instead of replacing a
loaded jar. Missing materials, flight, or placement configuration still need
to be supplied. An agent connection does not provide unrestricted gameplay.

The runner uses the mod for routine navigation, purchasing, restocking, storage, and
construction. With `on-error`, ordinary progress makes no model calls. An unchanged capacity-only
blocker waits for a feasible supply route; the runner takes that path only when every current
blocker is a capacity constraint, so a simultaneous unrelated fault still reaches normal fault
handling.

## Restart supervision later

Verify that no runner is live before starting one. `scripts/start-supervision.ps1` resolves a
stale saved agent-executable path through `Get-Command`; an explicitly supplied invalid override
still fails.

`companion/agent.local.json` contains launcher settings read by `scripts/start-supervision.ps1`;
do not pass it as `--config` to `agent_launcher.py run`. Start the build runner only after a fresh
inspection confirms the mod is connected to the expected world, to avoid an offline startup exit.
If Minecraft Launcher does not open through an executable path, use its registered Windows
application entry.

1. Open the dedicated Schematic Supervisor profile and join the original world. Keep the existing
   placement, transform and single enabled sub-region. Respect an explicit Stop.
2. Check actual runner processes before launching another. A saved PID or `active-run.json`
   status alone is not a live process check. From the project directory, inspect the connected
   mod without activating a build:

   ```powershell
   .\scripts\start-supervision.ps1 -Inspect -Once
   ```

3. Confirm the expected plan, paused checkpoint, planting mode, connected world, flight readiness
   and settled receipts. Let the registered-depot scans refresh before using their stock counts.
   Missing observations mean unknown, not zero stock. Diagnose fresh startup or mixin errors
   before proceeding.
4. Start one runner with the existing local runtime configuration and an explicit construction
   objective, for example:

   ```powershell
   .\scripts\start-supervision.ps1 -ModelPolicy on-error `
     -Objective "Complete the selected schematic in horizontal layers. Let the mod handle routine work and repair evidenced unresolved supervisor bugs. Preserve the checkpoint, material ledgers and temporary support ownership. Require full-volume verification before reporting completion."
   ```

The normal script invocation permits Start, Resume and evidenced repairs; it can load the saved build and resume once the fresh guards pass. `-NoActivation` omits these permissions, and `-Inspect` sends no build controls. The script remains running in its terminal, so leave that session alive. Verify a new run ID, live BUILDING state and confirmed placement progress rather than assuming launch succeeded. This starts the runner, not Minecraft or a login session.

## Agent tools and safeguards

The registered `schematic-supervisor` MCP server exposes `supervisor_observe`
and `supervisor_control`. Reopen the desktop task/app to load a newly registered
server. The continuous runner can use the connection independently of the
desktop task.

`supervisor_observe` reads `GET /v1/observation`. It reports connection/freshness,
world readiness, allowed controls, current layer/phase/chunk, progress/material ledger,
errors, run identity, and the most recent operator control sequence. The optional
desktop companion can contribute incident details, but is not needed to connect.

Live observations include optional `inventory`, `shop`, `player`, and `depots` fields.
These report real main/offhand slots, cursor contents, tool durability, material
totals, dirt capacity, current shop entries and purchase state, player position,
and flight status. Reads use immutable client-thread snapshots and do not advance
shopping or interact with the world. Depot readings distinguish unknown stock
from scanned contents and retain terminal scan errors.

`supervisor_control` accepts START, RESUME, SCAN_DEPOTS, PAUSE, or STOP. Expanding
actions require a token, a fresh observation, an advertised allowed action, and
matching run/state/operator-sequence guards. The mod checks these guards on its
client thread. A world/placement change invalidates an initial decision. Pause
and Stop remain available even if telemetry is stale or authentication is absent.

Token files are loaded dynamically, so registration works before the first game
launch. A missing or invalid token never grants expanding controls. The bridge
uses bounded HTTP bodies, disables proxy routing and redirects for loopback
requests, and distinguishes a rejection from a timeout with unknown outcome.

Decision calls use read-only workspace access. Repairs use workspace write access
with automatic approval review, after a confirmed pause and continuously checked
inactive state. They retain project instructions and permission boundaries.
The runner never bypasses the sandbox or automatically resets checkpoints. The
parent process holds the game controls; child agent invocations disable this
bridge to prevent bypassing the parent controller. Account/model usage is
recorded by the CLI; runner decisions and reports are under `data\agent-runner`.
