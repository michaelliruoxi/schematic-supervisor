# Live dashboard for the Schematic Supervisor monitor

- Date: 2026-09-26
- Status: approved; implemented on branch `feature/live-dashboard`
- Scope: sub-project 1 of 2. Sub-project 2 (build strategies: chunk-by-chunk order, material
  deferral, switching strategy from the exe) gets its own spec.

## 1. Summary

Turn `SchematicSupervisor.exe` into a dashboard that sits on the same screen as Minecraft. A small
always-on-top card shows the build state, an exact "% built", verification progress, and a stage
timeline. An expanded full window adds a chunk map, attention details with fix hints, all
controls, and the existing materials/activity/raw-data views. The mod gains one read-only endpoint,
`GET /v1/progress`, and two status fields. The exe also gains basic debug support: a log file,
crash capture, a refresh loop that cannot freeze, and specific connection errors.

## 2. Goals and non-goals

Goals:

1. Show exact build progress and the current stage/chunk at a glance, over Minecraft.
2. Show the whole project visually: a stage timeline and a per-chunk map.
3. Pause, Continue, Start, Stop, and Scan depots from the exe under today's safety rules.
4. Make "the build needs attention" noticeable and say what to do about it.
5. Record the cause whenever the exe itself misbehaves.

Non-goals for this spec:

- Build strategies (sub-project 2).
- Event recorder, diagnostic bundle, replay mode, AI runner panel, Depots/Execution deep-dive tabs.
- Phone or LAN access. Everything stays on `127.0.0.1`.
- Changes to the MCP bridge tools or the AI runner.
- Retiring the legacy `python -m supervisor_companion` app and its incident receiver.

## 3. Facts this design relies on

- The exe runs `launcher.py` → `desktop.main()` → `MonitorService` (polls `GET /v1/observation`
  every second through `AgentBridge`) → Tk window in `monitor_ui.py`. It writes no log file;
  exceptions are replaced by fixed strings (`desktop.py:120`, `monitor.py:109`, `monitor.py:190`),
  and the packaged exe is windowed, so tracebacks are lost.
- `MonitorWindow._tick` renders before rescheduling (`monitor_ui.py:290`), so one exception stops
  all refreshes.
- All transport failures become `connection: "offline"` (`agent_bridge.py:86`).
- The mod builds from `LayerBuildSchedule`: an ordered list of pieces, each one stage
  (`STRUCTURE`, `LIGHTING`, `TILL`, `PLANT`) at one Y in one chunk, plus a cursor. Chunk indexes
  are row-major from the layout origin, X first (`ChunkLayout`).
- The observation reports `current_layer` (stage index/total, Y, chunk index/total) but not stage
  sizes or per-chunk status. It is capped at 64 KB and already trims sections to fit
  (`SupervisorProtocolJson.java:41`, `:379`).
- Start resets the cursor to the first piece (`SchematicSupervisor.java:102`). A schedule
  conversion on load keeps only the cursor (`PlantingModeTransition`).
- In-game commands dispatch controls with `request_id` null
  (`SupervisorRuntimeController.java:162-178`); the AI runner uses `agent-…`; the exe sends plain
  UUIDs.
- When the mod has a pairing token (it creates `protocol-token.txt` on first launch), every
  observation read and every control POST requires it (`ControlHttpServer.java:175`, `:331`). The
  exe's current text "Pause and Stop remain available" without pairing (`monitor_ui.py:327`) is
  therefore wrong in the normal setup.
- Runtime state names: `IDLE`, `LOADING`, `ERROR`, `STOPPED`, `BUILDING`, `RESTOCKING`,
  `VERIFYING`, `STUCK`, `PAUSED`, `DONE`.

## 4. Decisions

| # | Decision | Reason |
|---|---|---|
| 1 | Progress comes from a new mod endpoint `GET /v1/progress`. | Exact; the observation is near its 64 KB cap; rebuilding the schedule in Python would drift. |
| 2 | Stay on API version 1. | Additive only; every client rejects `protocol_version != 1`. |
| 3 | "% built" = finished actions / all actions. An action is one placement, till, or plant target in the active schedule. A piece counts once the cursor has passed it. | Matches how the mod works; avoids the README's warning about material counts. |
| 4 | Final verification is shown separately as "Verify n/2", not folded into the %. | Verification has no action count; two clean passes are a separate gate. |
| 5 | One Tk window with two layouts (compact card, full window). | One taskbar button (needed for flashing); no custom borderless-window code. |
| 6 | The card is layout C (stage timeline), chosen in review. | |
| 7 | Card controls: Pause↔Continue toggle, Scan depots, Expand. Start and Stop only in the full window; Stop asks for confirmation. | Avoids accidental run-level actions on a small card. |
| 8 | Alerts flash the taskbar on entering PAUSED, ERROR, or DONE, or on losing the connection for 15 s during active work. Never at startup, never for the exe's own Pause, never for STUCK. | STUCK is the mod's own recovery; it pauses (and alerts) if recovery fails. |
| 9 | Fix hints come from a rule table; unknown messages are shown verbatim. | Hints can go stale; raw text is always available. |
| 10 | Keep today's `connection` values; add a `detail` code and a specific message. | The MCP bridge and runner read `connection`. |
| 11 | The exe's control request IDs become `monitor-<uuid>`. | Lets the UI say where the last control came from and suppress alerts for its own Pause. |
| 12 | Logs and window state live next to the exe (`logs\monitor.log`, `monitor-state.json`), falling back to `%LOCALAPPDATA%\SchematicSupervisor\` when that folder is not writable. | Near the project's other evidence; `companion/dist/` is already git-ignored. |
| 13 | Runtime stays standard-library only (Tk, ctypes, winreg, winsound). | Existing companion rule. |
| 14 | The new exe works against the currently installed mod, with an approximate % and an "update the mod" note. | The exe can ship before the mod is reinstalled. |
| 15 | Mod version stays 0.1.0. The exe version goes to 0.3.0 in all three places that carry it today (`__version__` 0.1.0, `pyproject.toml` 0.1.0, `version_info.txt` 0.2.0), so they agree. | Install scripts track jars by SHA-256 and file name; the About view reads `__version__`. |

## 5. Architecture and data flow

```
Minecraft client thread, every tick
  SchematicSupervisor.progressKey()  ──►  ProgressSnapshotCache
      key changed?  ──►  ScheduleProgress.of(...)  ──►  encodeProgress()  ──►  bytes, revision++
  observe()  ──►  AgentObservation + progress_revision + last_progress_at  ──►  observation bytes

HTTP threads (ControlHttpServer)
  GET /v1/observation  ──►  cached observation bytes
  GET /v1/progress     ──►  cached progress bytes

Exe worker thread (MonitorService)
  every 1 s: GET /v1/observation
  GET /v1/progress when progress_revision changes, on (re)connect, and every 30 s
  pace samples, control delivery

Exe UI thread, every 250 ms
  snapshot  ──►  view_model.build()  ──►  card / full window
  AttentionTracker  ──►  taskbar flash, optional beep
```

## 6. Mod changes

### 6.1 `GET /v1/progress`

Access rules match `/v1/observation`: loopback only; GET only (405 otherwise); same token check
(401); no query string (404); 503 until the first snapshot exists.

Response when a plan is loaded:

```json
{
  "protocol_version": 1,
  "available": true,
  "revision": 1842,
  "plan_id": "sha256:c9a2…",
  "schedule_id": "layers-v1-structure-first-deferred-planting",
  "layout": {"origin_x": 512, "origin_z": -1686, "columns": 7, "rows": 7},
  "totals": {"actions": 651993, "done": 358596, "stages": 76, "current_stage": 30},
  "materials": {"dirt": {"planned": 313600, "done": 172480}, "glowstone": {"planned": 12250, "done": 0},
                "birch_planks": {"planned": 12543, "done": 0}},
  "stages": [
    {"kind": "STRUCTURE", "y": -63, "actions": 12544, "done": 12544, "chunks": "DDDD…"},
    {"kind": "STRUCTURE", "y": -34, "actions": 8820, "done": 7200, "chunks": "DDDD…DC--.-----"}
  ],
  "chunk_detail_truncated": false
}
```

| Field | Type | Meaning |
|---|---|---|
| `revision` | int ≥ 0 | Same value as the observation's `progress_revision`. |
| `plan_id`, `schedule_id` | string | Identify the loaded plan and schedule variant. |
| `layout` | object | Chunk coordinates of the layout origin, and its size in chunks. |
| `totals.actions`, `totals.done` | int | All actions in the schedule; actions in finished pieces. `0 ≤ done ≤ actions`. |
| `totals.stages` | int | Number of stages. |
| `totals.current_stage` | int or null | 1-based stage being built; null during final verification and after completion. |
| `materials` | object | Items per material (`dirt`, `wheat_seeds`, `glowstone`, `birch_planks`): `planned` in the whole schedule and `done` in finished pieces, counted like `totals.done`. One item per placement and one seed per plant target; tilling uses none. Added 2026-09-28; older mods omit it. |
| `stages[]` | array | One entry per stage, in schedule order. |
| `stages[].kind` | string | `STRUCTURE`, `LIGHTING`, `TILL`, `PLANT`. Clients must accept unknown kinds. |
| `stages[].y` | int | Stage Y level. |
| `stages[].actions`, `stages[].done` | int | Actions in the stage and in its finished pieces. |
| `stages[].chunks` | string or null | One character per layout chunk, row-major: `D` done, `C` current, `-` to do, `.` no work for that chunk at this stage. Null when omitted for size. |
| `chunk_detail_truncated` | bool | True when some `chunks` strings were omitted to stay within 64 KB. The current stage's string is always kept. |

Response when no plan is loaded:

```json
{"protocol_version": 1, "available": false, "revision": 1843, "reason": "No plan is loaded."}
```

`reason` is one of: "No plan is loaded.", "The plan is loading.", "The supervisor runtime is
closed.", "The plan has too many stages to report progress." The last one appears only when even
the per-stage totals, without chunk letters, would exceed 64 KB (more than about 650 stages).

### 6.2 Observation additions

- `progress_revision` (int): increments whenever the `/v1/progress` content changes. It restarts
  when the game restarts, so clients refetch on any difference, not only on increase.
- `last_progress_at` (ISO-8601 UTC string or null): wall-clock time of the last confirmed progress,
  meaning the moment the execution progress marker changed or material use was confirmed
  (`SchematicSupervisor.java:400`). It is not reset by pause, resume, or checkpoint restore. It is
  null until the first progress since the plan was loaded, because each plan load creates a new
  supervisor. The mod's existing stall clock
  (`lastProgressAt`) is unchanged; it skips wait time and resets on resume, so it is unsuitable for
  display.

Both are top-level fields, which the observation encoder never trims.

### 6.3 Counting rules and edge cases

| Situation | Result |
|---|---|
| Piece index < cursor | `D`; its actions count as done. |
| Piece index == cursor while building | `C`; counts 0 until finished. |
| Piece index > cursor | `-`. |
| Final-verification chunk repair (`repairChunkIndex` ≥ 0). The mod rewinds the cursor to the dirty chunk's first piece and re-walks only that chunk's pieces (`SchematicSupervisor.java:975`). | Pieces of every other chunk stay `D`. The repair chunk's pieces before the cursor are `D`, the piece at the cursor is `C`, and its later pieces are `-`. The % dips by at most one chunk's share. |
| Chunk has no work at a stage | `.`; excluded from that stage's counts. |
| Final verification or `DONE` (cursor == schedule size) | All `D`; `current_stage` null; `done == actions`. |
| Stop, then Start | Superseded on 2026-09-28: Start runs the build check, begins at the first unfinished piece, and reports pieces the check found finished as `D` (`2026-09-28-start-build-check-design.md`). Before that, Start began at the first piece and progress restarted at 0%. |
| Schedule conversion on load (`glowstoneAfterStructure` or `deferPlanting` changed) | Finished pieces later in the new order show `-` until reached, so the % can dip. Sub-project 2 removes this by storing finished pieces. |
| Deferred planting | No `PLANT` stages, so seeds are not counted. |
| Reset or Unload in game | No plan: `available: false`. |

### 6.4 Implementation

Core (pure, testable without Minecraft):

- `ScheduleProgress` (new):
  - `index(SchematicPlan, LayerBuildSchedule)` computes the per-schedule static data once: the
    stage of each piece, its chunk index, and prefix sums of action counts.
  - `of(Index, int cursor, int repairChunkIndex)` returns the layout, per-stage data (kind, Y,
    actions, done, status letters on demand), and totals, following the rules in 6.3.
- `SchematicSupervisor`:
  - `progressKey()`: a small record (plan ID, schedule ID, cursor, repair chunk), cheap enough to
    call every tick.
  - `progress()`: builds the `ScheduleProgress`.
  - `lastConfirmedProgressAt()`: an `Instant` or null, set in the progress branch at line 400.
    After recovery resumes a still-running command, the first poll only re-reads the executor's
    marker, so an old marker value doesn't count as new progress.

Fabric:

- `ProgressSnapshotCache` (new): holds the last key, revision, and encoded bytes. `update(key,
  builder)` rebuilds only when the key changes and bumps the revision. It produces the unavailable
  payload when there is no supervisor. On a build failure it keeps the previous bytes, reports the
  failure once through an injected callback (the runtime logs it), and doesn't retry until the key
  changes.
- `SupervisorProtocolJson.encodeProgress(ScheduleProgress, long revision)` and
  `encodeProgressUnavailable(String reason, long revision)`. The encoder estimates the size first.
  If the full per-stage detail would exceed 64 KB, it writes `chunks` only for the current stage
  and sets `chunk_detail_truncated`.
- `AgentObservation`: add `progressRevision` and `lastProgressAt` through a `withProgress(...)`
  wither and a compatibility constructor, following the existing `withDepots`/`withExecution`
  pattern. `encodeObservation` writes both fields.
- `ControlHttpServer`: optional `Supplier<byte[]> progress`; route `/v1/progress` next to
  `/v1/observation`.
- `SupervisorRuntimeController`: where `observationSnapshot` is built (line 1620), update the cache
  first, then build the observation with the current revision and `last_progress_at`.
- `SchematicSupervisorClient`: pass the progress supplier to `ControlHttpServer`.

### 6.5 Java tests

- `ScheduleProgressTest`: fresh plan (first piece current, rest to do); cursor mid-stage; stage
  boundary; chunks without work (`.`); final verification and `DONE`; repair chunk; structure-first
  and interleaved schedules give equal totals; deferred planting excludes plant targets; totals
  equal the sum of all work orders; empty schedule.
- `ProgressSnapshotCacheTest`: rebuild only on key change; revision increments; unavailable payload;
  build failure keeps previous bytes.
- `SupervisorProtocolJsonTest`: exact progress shape; unavailable shape; truncation over 64 KB keeps
  the current stage; observation includes `progress_revision` and `last_progress_at` (null and set).
- `ControlHttpServerTest`: `/v1/progress` 200 with token, 401 without, 405 for POST, 503 before the
  first snapshot; other paths and query strings still 404.
- `SchematicSupervisorTest`: `lastConfirmedProgressAt` is null initially, set on progress, and
  unchanged by pause, resume, and restore.

## 7. Exe changes

### 7.1 Window model

- One Tk root with two layouts. It opens in the last used layout (default: compact).
- Compact card: about 320 × 210 at 100% scaling; always on top (pin toggle); fixed size; default
  position is the top-right of the primary screen's work area with a 16 px margin; draggable;
  position remembered. A saved position that is off every screen resets to the default.
- Full window: about 960 × 720; resizable; on top only when "Keep on top" is enabled.
- Window title: `<State> · <pct>% · Schematic Supervisor`, so the taskbar shows status.
- Theme: light or dark palette following the Windows app theme (registry
  `AppsUseLightTheme`), with a dark title bar in dark mode (`DwmSetWindowAttribute`). Read once at
  startup.
- DPI: the process is made system-DPI aware before Tk starts, so text is crisp.
- Single instance: a named mutex. A second launch shows "The monitor is already open." and exits.
- Focus: clicking the window takes focus from Minecraft. The mod keeps building, because it
  suppresses its automatic pause menu during active work (`BackgroundTickPolicy`). An always-on-top
  window shows over Minecraft only in windowed or borderless mode; documented in the README.

```
Compact card
┌────────────────────────────────────────────┐
│ [Building]         Progress 4s ago   (pin) │
│ 55% built                       Verify 0/2 │
│ ▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▯▯▯▯▯▯▯▯▯▯▯▯ │
│ Structure             Lights       Till    │
│ Now: Structure · Y −34 · chunk 41 of 49    │
│ [Pause] [Scan depots]            [Expand]  │
└────────────────────────────────────────────┘

Full window
┌──────────────────────────────────────────────────────────────┐
│ [Building] 55% built · Verify 0/2 · Progress 4s ago [Compact]│
│ 214 per min · ≈ 6 h 20 m left, excluding verification        │
│ Last control: Continue · from this monitor                    │
├──────────────────────────────────────────────────────────────┤
│ Attention (only when something needs attention)              │
├───────────────────────────────┬──────────────────────────────┤
│ Stage timeline (click a stage)│ Chunk map, north up          │
│ Stage 30 of 76 · Structure    │ [This stage] [All stages]    │
├───────────────────────────────┴──────────────────────────────┤
│ [Start] [Pause] [Continue] [Stop] [Scan depots] [Refresh] [⋯]│
│ Continue accepted: Resumed.                                   │
├──────────────────────────────────────────────────────────────┤
│ Status details | Materials | Activity | Raw data             │
└──────────────────────────────────────────────────────────────┘
```

### 7.2 Compact card

Rows:

1. State label, a status text on the right, and a pin icon (always on top). Status text by state:
   - `BUILDING`, `RESTOCKING`, `STUCK`: "Progress 4s ago" from `last_progress_at`; amber after
     2 minutes; "No progress yet" when null.
   - `VERIFYING`: "Checking the build".
   - `LOADING`: "Loading 45%" from `loading_progress` (a 0–1 fraction).
   - `PAUSED`, `STOPPED`, `ERROR`, `IDLE`: "for 12 m", measured from when the exe saw the state
     change; hidden if unknown.
   - `DONE`: "Finished".
2. "55% built" and "Verify n/2" (`stable_verification_passes`, capped at 2). "—" with "No build
   loaded" when progress is unavailable.
3. Stage timeline: one equal-width segment per stage, green done, blue current, fainter green partly
   done (a stage behind the current one that a chunk repair is re-walking), grey to do. When there
   are more stages than pixels, each pixel column shows its most important status: current, then
   to do, then partly done, then done. Hover shows "Stage 30 · Structure · Y −34 · 82%". A click opens the full window
   with that stage selected.
4. Kind labels under the timeline for runs of the same kind (Structure, Lights, Till, Plant). With
   more than four runs (interleaved schedules) the labels become "Build" and "Farm".
5. "Now: Structure · Y −34 · chunk 41 of 49", replaced by an amber or red attention line when
   attention is needed. Attention is needed when:
   - the state is `PAUSED`, `STUCK`, or `ERROR`;
   - telemetry isn't fresh;
   - the state isn't active and the mod reports blockers. Active means `BUILDING`, `RESTOCKING`,
     `VERIFYING`, or `LOADING`. Blockers describe why Start or Continue is refused, so they're
     hidden during active work.

   The line shows the first of: the hint for `last_error`; the first blocker that has a hint; the
   raw `last_error`; the first raw blocker; the connection message. Because the card has a fixed
   size, a long line ends with an ellipsis and shows in full on hover.
6. Buttons:
   - Pause/Continue toggle: Pause while the state is active or `STUCK`, Continue while it is
     `PAUSED`, hidden otherwise.
   - Scan depots: enabled only when allowed under 7.4.
   - Expand.

State labels:

| State | Label | Color |
|---|---|---|
| `BUILDING`, `RESTOCKING`, `VERIFYING`, `LOADING` | Building, Restocking, Verifying, Loading | green |
| `STUCK` | Recovering | amber |
| `PAUSED` | Paused | amber |
| `ERROR` | Error | red |
| `DONE` | Done | blue |
| `IDLE`, `STOPPED` | Idle, Stopped | grey |
| connection problem | from 7.7 | grey |

When telemetry is not fresh, values turn grey, the % gains "(last known)", and the label shows the
connection problem.

### 7.3 Full window

- Header: the card's information plus pace and ETA (7.8), last control ("Last control: Pause ·
  from in game", from `last_control.action` and the request-ID source: `monitor-` this monitor,
  `agent-` the AI runner, null in game, anything else another tool), and a Compact button.
- Attention panel, shown only when needed:
  - `last_error` as the current error when the state is `PAUSED`, `STUCK`, `ERROR`, or `STOPPED`.
    During active work it appears as "Last error (may be old)", because the mod keeps old error
    text while building.
  - Each blocker with its fix hint, when the state isn't active (see 7.2).
  - The connection problem with its message and the token file path.
- Project panel:
  - A large stage timeline; clicking selects a stage.
  - A chunk map for the selected stage. It follows the current stage until another stage is
    selected, and "Back to current" returns to it.
  - While the pointer is over the timeline, the map and its caption preview the stage under the
    pointer, the one a click there would select. Moving off the timeline shows the selected or
    current stage again; the preview never changes the selection.
  - A "This stage / All stages" toggle. All stages shades each chunk by the share of its stages
    finished, and doesn't change on hover.
  - Legend, north-up orientation, and hover showing chunk index, chunk coordinates, and status.
  - Cells are at most 28 px and shrink to fit the panel, down to 2 px, so the map never scrolls.
  - When `chunks` is null for a stage: "Chunk detail isn't available for this stage."
- Controls: Start, Pause, Continue, Stop, Scan depots, Refresh (F5), and a result line.
- Tabs:
  - Status details: today's Overview execution, player, world, and recovery text, so nothing is
    lost.
  - Materials: On hand and Shortfall from the observation; Planned, Placed, and Remaining from the
    progress `materials`, so they match the % built. A mod without `materials` gets the old ledger
    columns (Used is what the run used since the last Start) and a note to update the mod. Changed
    2026-09-28: the ledger restarts at every Start, so after a build check it showed finished work
    as remaining.
  - Activity: today's events plus stage changes, plan changes, and alerts.
  - Raw data: today's Details tab with "Save snapshot".
- Menu (⋯): Open log folder; Sound on attention (off by default); Keep full window on top; Reset
  card position; About (exe version, protocol version, plan ID, token file path and whether it
  exists).

### 7.4 Controls

Unchanged rules:

- Start, Continue, and Scan depots need fresh telemetry, pairing, and the action in
  `allowed_actions`. They carry the displayed run/state/control-sequence guards.
- Pause and Stop need only a connection.
- One active control at a time.
- Unknown outcomes are reported and never retried.
- A slower Start/Continue is followed by reasserting a newer Pause/Stop, as today.

Changes:

- Request IDs are `monitor-<uuid>`.
- Stop asks for confirmation. Its text now describes the build check on Start; see
  `2026-09-28-start-build-check-design.md`.
- The pairing text is corrected. Without the pairing file the monitor can neither read status nor
  send controls; the message says so and names the expected path.
- When `control_token_configured` is false, the message says: "The mod has no pairing token. Start,
  Continue, and Scan depots are unavailable; Pause and Stop work."

### 7.5 Alerts

`AttentionTracker` (pure) compares the previous and current view on each UI tick.

| Trigger | Alert |
|---|---|
| State enters `PAUSED`, unless `last_control` is a `PAUSE` this monitor sent in the last 60 s | yes |
| State enters `ERROR` | yes |
| State enters `DONE` | yes |
| Connection is not online for 15 s and the last known state was `BUILDING`, `RESTOCKING`, `VERIFYING`, `STUCK`, or `LOADING` | yes, once per outage |
| First snapshot after startup | no |
| `STUCK`, slow progress, plan changes | no |

An alert calls `FlashWindowEx` with `FLASHW_ALL | FLASHW_TIMERNOFG`, flashing until the window is
focused. It plays `MessageBeep` when "Sound on attention" is on, and adds an Activity entry. Color
and title changes happen regardless of alerts.

### 7.6 Fix hints

`hints.py` holds ordered rules: a match on the message text and a hint. The first matching rule
wins. Tests pin each rule to the exact mod string.

| Mod message (match) | Hint |
|---|---|
| "Join the target world before starting or resuming." | Open Minecraft and join the world this build belongs to. |
| "The loaded run belongs to a different server, save, or dimension." | You're in another world or dimension. Go back to the build's world, or run `/schematic-supervisor unload` in game. |
| contains "requires flight to already be active" | Run `/schematic-supervisor takeoff` in game, then Continue. |
| "Wait for registered-depot scans to finish." | Chests are still being scanned. This clears on its own. |
| starts "Registered depots cannot satisfy the exact material shortage:" | Put the missing items in a registered chest, then press Scan depots. |
| starts "Depot withdrawal failed: real chest stock does not cover" | A chest held less than expected. Restock it, then press Scan depots. |
| starts "Inventory capacity blocked:" | The inventory is too full. The mod's cleanup usually clears this; if not, free some slots in game. |
| "The checkpoint requires reconciliation; use an explicit reset before restarting." | Check the inventory in game, then run `/schematic-supervisor reset`. This discards the saved position; placed blocks stay and are re-checked. |
| "A local protocol token is required to activate automation." | Restart Minecraft; the mod creates its pairing file at startup. |
| contains "Manual movement input interrupted" | A movement key was pressed during flight. Release it, then Continue. |
| "The supervisor runtime is closed." | Restart Minecraft. |
| starts "The last dirt purchase was not acknowledged." | A dirt purchase hasn't been confirmed yet. Wait a moment; if it doesn't clear, check the inventory in game and restart Minecraft. |
| "Takeoff requires flight permission already granted by the server." | The server hasn't granted flight. Turn on your server flight ability, then run `/schematic-supervisor takeoff`. |
| starts "Flight placement will not overwrite an occupied target at " | A block that isn't in the plan sits on a target (position in the message). Remove it in game if that's safe, then Continue. |
| contains "Advisor unavailable after deterministic recovery" | The mod's own recovery didn't fix this. The cause is the text before "Advisor unavailable"; that part only means no diagnosis helper is running. |

The advisor rule is last because the mod appends "Advisor unavailable after deterministic
recovery" to other causes, so a cause with its own rule keeps its hint.

Messages without a rule show only their raw text.

### 7.7 Connection diagnosis

`AgentBridge` keeps `connection` as today and adds `detail`:

| `detail` | Cause | Card label | Message |
|---|---|---|---|
| `refused` | TCP connection refused | Not running | Minecraft isn't running, or the mod isn't loaded. Nothing is listening on 127.0.0.1:8765. |
| `timeout` | No answer within the timeout | Not responding | Minecraft didn't answer within 3 s. It may be frozen, loading, or lagging badly. |
| `reset` | Connection dropped mid-response | Connection dropped | Minecraft may have closed or restarted. |
| `not_ready` | HTTP 503 | Starting | The mod is starting and waiting for its first game tick. |
| `token_missing` | 401, no token available | Pairing needed | Pairing file not found at `<path>`. Launch the game once so the mod creates it. |
| `token_rejected` | 401 with a token sent | Pairing rejected | The pairing file doesn't match this game. Check that the monitor reads it from the same game folder: `<path>`. |
| `invalid` | Bad JSON or schema | Version mismatch | The mod sent data this monitor doesn't understand. The mod and monitor versions may not match. |
| `stale` | Observation older than 15 s | Stale | Minecraft stopped reporting. It may be on a loading screen or frozen. |
| `http_error` | Other HTTP status | Error | The mod returned HTTP `<code>`. (The bridge message stays neutral because MCP clients see it too; the monitor logs every connection change.) |

`configuration_error` (an unreadable or invalid pairing token) has no detail code; the card labels
it "Pairing invalid".

`--check-connection` output includes `detail` and also reads `/v1/progress`.

### 7.8 Pace and ETA

- A sample of `(time, totals.done)` is taken on every poll (once a second) while the state is
  `BUILDING`, `RESTOCKING`, or `STUCK`. Time in other states is excluded.
- Rate = actions finished per minute over the last 10 minutes of active time. Shown after at least
  3 minutes of active samples, as "214 per min" (tooltip: "blocks placed, tilled, or planted per
  minute").
- ETA = remaining actions / rate, shown as "≈ 6 h 20 m left, excluding verification"; hidden when
  not active or when the rate is 0.
- Samples reset when `plan_id` or `schedule_id` changes or `done` decreases.

### 7.9 Older mod fallback

- A 404 from `/v1/progress` marks progress unsupported until the next reconnect, rechecked every
  5 minutes.
- Approximate % = `(index − 1 + (chunk_index − 1) / chunk_total) / total` from `current_layer`,
  shown as "≈ 38%" with "Update the mod for exact progress".
- The timeline uses equal segments from `index`/`total`. The chunk map shows "Update the mod to see
  the chunk map."
- Other `/v1/progress` failures while the observation works keep the last progress, show "Progress
  data unavailable", and log the cause.

### 7.10 Logging and crash handling

- File: always `monitor.log`, rotating at the config defaults (1 MB, 4 backups). The config's `logging`
  section can change the directory, level, size, and backup count, but not the file name; a
  relative directory resolves against the exe folder.
- Format: time, level, thread, message.
- Logged:
  - A session header: exe version, Python version, mod URL, token file path and whether it
    exists, config source.
  - Connection changes with `detail`.
  - State, stage, and plan changes.
  - Progress fetches that change the revision.
  - Control requests and outcomes.
  - Alerts.
  - Exceptions with tracebacks, from `sys.excepthook`, `threading.excepthook`, Tk's
    `report_callback_exception`, the poll loop, and control delivery.
- Repeated identical exceptions are logged at most once a minute.
- A redaction filter masks the pairing token value and any `X-Supervisor-Token` value. Tokens are
  never logged on purpose.
- The UI keeps showing fixed text for exceptions and adds "Details are in the log".
- The refresh loop (`SafeTicker`) always reschedules. A render error shows a red banner "Display
  error; details are in the log" until a render succeeds.

### 7.11 Module layout

| Module | Responsibility |
|---|---|
| `agent_bridge.py` | Adds `progress()`, the `detail` codes, and caller-supplied request IDs (already supported). |
| `progress.py` (new, pure) | Validates the `/v1/progress` payload strictly (types, bounds, `D C - .` alphabet, string length = columns × rows, ≤ 4096 stages); derives the %, stage status, per-chunk shares, and the fallback from `current_layer`. |
| `pace.py` (new, pure) | Rate and ETA. |
| `hints.py` (new, pure) | Fix-hint rules. |
| `view_model.py` (new, pure) | Builds `CardView` and `FullView` from the snapshot, progress, pace, hints, and UI state: texts, color kinds, button states, title. |
| `alerts.py` (new, pure) | `AttentionTracker`. |
| `ui_state.py` (new) | Loads and saves `monitor-state.json` (layout mode, positions, settings) with validation. |
| `win32.py` (new) | `FlashWindowEx`, `MessageBeep`, DPI awareness, single-instance mutex, virtual-screen bounds, theme and dark title bar. No-ops off Windows. |
| `monitor.py` | Adds progress fetching, pace sampling, `monitor-` request IDs, and logging. Its public API is unchanged. |
| `monitor_ui.py` | Window shell: layout switching, `SafeTicker`, alerts, menu. |
| `ticker.py` (new, pure) | `SafeTicker`: runs the render callback, logs failures, and always reschedules through an injected scheduler. |
| `ui_geometry.py` (new, pure) | Timeline segments and pixel-column aggregation, label spans, map cell sizing and hit testing. Kind-label runs live in `view_model.py`. |
| `ui_theme.py` (new) | Light and dark palettes, tone colors, and ttk styles. |
| `ui_card.py`, `ui_full.py` (new) | Tk widgets for the two layouts; they only draw a view model. |
| `ui_canvas.py` (new) | Timeline and chunk-map canvases and a tooltip helper; draws what `ui_geometry.py` computes. |
| `desktop.py` | Startup order: config → `--check-config` → bridge → `--check-connection` (each check mode returns at its step, so diagnostics run while a monitor is open) → DPI → single instance → logging and hooks → service → window. |
| `logging_setup.py` | Adds monitor logging with the redaction filter. |

The legacy app modules (`__main__.py`, `app.py`, `ui.py`, `server.py`, `diagnosis.py`) are not
changed.

### 7.12 Python tests

New:

- `test_progress.py`: valid payload; each malformed variant; unavailable; truncated; percent and
  stage status; per-chunk shares; fallback formula.
- `test_pace.py`: window, 3-minute threshold, inactive time excluded, resets.
- `test_hints.py`: every rule against its exact mod string; unknown text returns none.
- `test_view_model.py`: building; paused with error and hint; stale; each connection `detail`;
  `DONE`; `LOADING`; old-mod fallback; truncated detail; title text; button states; old
  `last_error` during building; last-control source.
- `test_alerts.py`: no alert at startup; `PAUSED` alerts; own Pause suppressed; `ERROR` and `DONE`
  alert; connection loss alerts once after 15 s; `STUCK` does not alert.
- `test_ui_state.py`: round trip, invalid file ignored, off-screen position reset.
- `test_ui_geometry.py`: timeline segments, pixel-column aggregation, kind-label runs (including
  the "Build"/"Farm" fallback), map cell sizing.
- `test_logging_setup.py`: file location and fallback; redaction; excepthook writes a traceback.
- `test_ticker.py`: a raising render is logged, shows the banner state, and still reschedules.

Updated:

- `test_agent_bridge.py`: `progress()` success, 404, 401, invalid; `detail` for refused (a
  closed port), timeout (a slow fixture), and reset.
- `test_monitor.py`: progress fetched only on revision change, on reconnect, and on the 30 s
  refresh; request IDs start with `monitor-`; existing tests keep passing. Existing fixtures answer
  every GET with an observation, so they must tolerate an invalid progress payload.
- `test_desktop.py`: `--check-connection` now makes two GET requests.

Tests stay headless: no Tk window is created. Tk modules contain drawing code only.

## 8. Compatibility

| Combination | Result |
|---|---|
| Old exe + new mod | Unaffected; new fields ignored. |
| New exe + old mod | Approximate progress (7.9). |
| MCP bridge and AI runner | No code changes. `supervisor_observe` output gains the two new fields automatically; `connection` values are unchanged. |
| Existing checkpoints | Unaffected; nothing is persisted by this change. |

## 9. Rollout and acceptance

1. Initialize git and commit the current tree as a baseline (`.git` is empty today). Needs the
   owner's OK.
2. Exe foundation: logging and hooks, `SafeTicker`, connection `detail`, view-model extraction,
   pairing-text fix. Works with the current mod.
3. Mod: endpoint, observation fields, Java tests; `.\gradlew.bat clean check build`.
4. Exe: progress, pace, hints, alerts, card, full window, UI state, single instance, DPI, theme;
   `python -m unittest discover -s tests -v`.
5. Docs: `companion/README.md` (monitor usage, alerts, log location, fullscreen note,
   `/v1/progress` contract) and the root README's companion paragraph. Set the version to 0.3.0
   in `__init__.py`, `pyproject.toml`, and `version_info.txt`, then rebuild with `build_exe.ps1`.
6. Install the jar with Minecraft closed, using `scripts/update_automation_mod.py --artifact …
   --expected-sha256 … --test-report …`; start Minecraft, rejoin, Continue.

Live acceptance checklist:

- [ ] Before the mod update, the new exe shows "≈ %" and the update note without errors.
- [ ] After the update, the card matches `/schematic-supervisor status` for state, stage, Y, and
      chunk.
- [ ] Finishing a chunk updates the % and the map within 2 s.
- [ ] Pause from the card: state changes within 2 s, no flash. Pause in game: flash.
- [ ] Continue works when paired; disabled with the pairing message when the token file is
      renamed.
- [ ] Closing Minecraft shows "Not running" within 5 s; relaunching reconnects on its own.
- [ ] Suspending the Minecraft process (Resource Monitor → Suspend) shows "Not responding".
- [ ] The card stays on top of windowed Minecraft, and its position survives an exe restart.
- [ ] A second exe launch reports "already open".
- [ ] `monitor.log` has the session header and transitions, and no token value.

## 10. Risks

| Risk | Mitigation |
|---|---|
| Tk can't match the HTML mockups exactly. | Shared palette and spacing; layout and content match. |
| Exclusive fullscreen Minecraft hides the card. | Documented; use windowed or borderless. |
| The % dips after Stop/Start or a schedule conversion. | Documented in the UI and README; sub-project 2 fixes conversions. |
| Hints drift from mod messages. | Tests pinned to the current strings; raw text always shown. |
| Rebuilding progress on huge plans costs client-thread time. | Static data cached per schedule; rebuild only on change; output capped at 64 KB. |
| Refactoring the window regresses existing behavior. | `MonitorService` API and tests are kept; display logic moves into tested view models. |
| `/v1/progress` reveals the build's location. | Same token as the observation, which already includes player position. |

## 11. Deferred

- Sub-project 2, build strategies:
  - chunk-by-chunk order;
  - deferring any material;
  - switching strategy from the exe while paused;
  - storing finished pieces in the checkpoint, which removes the % dip after conversions.
- Event recorder, diagnostic bundle, replay mode.
- AI runner panel; Depots and Execution tabs.
- Progress in the MCP `supervisor_observe` tool.
- Retiring or relabeling the legacy companion app.
- A mod option to skip the advisor request when no companion runs, which would remove the
  "Advisor unavailable" noise.
