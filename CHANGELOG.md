# Changelog

## 2026-10-01: Mod 0.2.0 and monitor 0.4.0: any full block, opt-in shop routes, MIT license

The mod jar is now `schematic-supervisor-0.2.0.jar`; install it with Minecraft closed. The profile
updater replaces the 0.1.0 jar under the new name.

- **Any full block.** A schematic may use farmland, wheat, and any full block without properties
  (stone, planks, wool, concrete, glass, and so on), supplied from registered chests. Blocks that
  need a direction or other property, fall, hold data, or are collected as clearing waste (moss
  blocks) are refused when the placement loads, with the reason. Checkpoints name such blocks by ID
  (`minecraft:stone`); journals and probes keep the names they had (`DIRT`), and older checkpoints
  read unchanged.
- **Shop purchases are opt-in.** Nothing is bought unless `config\schematic-supervisor\shop.json`
  has `"enabled": true`. Its other keys describe the shop's menus for the Glowstone and Birch
  Planks routes, defaulting to the captured layout; the dirt flow and the `/shop` command name
  follow it too. With purchases off, `buyMaterialsInPlace` does nothing and `buy-dirt` is refused.
  The profile updater writes `{"enabled": true}` for a profile installed before this file existed,
  so the dedicated profile keeps buying; the installer takes `--shop-purchases`.
- **Monitor:** a copy outside this project finds the pairing file of the default launcher profile
  in `%APPDATA%\.minecraft` by itself.
- The mod and monitor are MIT licensed (`LICENSE`), and `fabric.mod.json` names the author.

## 2026-10-01: The build recovers from lost flight and brief server trouble by itself (mod and monitor)

The game logs held 72 pauses between 9 and 29 September. "Active flight was lost" caused 8 of the 17
on 28 and 29 September, and every recovery that ended without a running advisor paused, however
brief the cause. The mod jar must be installed with Minecraft closed, and the monitor exe rebuilt.

- **Lost flight.** When flight the build relies on switches off while the server still allows it,
  recovery takes off again before anything else (before any lag wait, so a player whose flight
  dropped mid-air stops falling) and restarts the current piece from where the player hovers. Once
  per incident; confirmed placing or planting renews it.
- **Start and Resume take off by themselves** when the player stands and the server allows flight,
  then start or resume once airborne. Pause or Stop cancels that takeoff. Without server flight the
  blocker now reads "This floating schematic needs flight, and the server has not granted it."
- **Transient causes are retried** after 30, 90, and 270 seconds before the build pauses: the server
  not confirming a change in time, an obstructed or unloaded route segment, no confirmed progress, a
  chunk that did not arrive, or an entity standing on a target. The state stays `STUCK` while it
  waits. Causes that need you (movement keys, server corrections, unplanned blocks, missing
  materials, reconciliation) still pause at once, and an advisor's advice to pause is followed.
- Checkpoints save the new recovery stages as stages older mods can read.
- **Monitor:** alerts can also go to your phone through ntfy or a Discord webhook (a `notify` section
  in `config.json`; off by default). New fix hints cover the retry, restored-flight, and
  flight-not-granted messages, and the runner waits on the new flight blocker like the old one.

## 2026-09-29: Descents no longer land and switch flight off (mod)

- The build paused with "Active flight was lost" just after a restock, with the player standing
  on the roof above its next target. Coming back from a chest on the roof, a route flies over the
  target and descends until the roof stops it, ending 0.1 blocks above the roof.
- Vanilla flight keeps 60% of the vertical speed each tick. At the end of a leg the mod stopped
  the player only if its speed still matched the last step to within 0.07 blocks per tick, which a
  descent faster than 0.175 no longer does one tick later. The rest of the step carried the player
  up to about half a block further down onto the roof, and landing switches flight off. Whether it
  happened depended on the height the descent started from: two starting heights in three landed.
- When a leg ends, the mod now stops whatever vanilla flight left of its own last step, on every
  route: building, chests, verification, disposal, and approaches. Pushes that aren't left from
  that step are still not erased.
- The mod jar must be installed with Minecraft closed.

## 2026-09-29: Restock routes out of the farm (mod)

- A restock paused with "Flight route search reached its bounded node limit" while planting deep
  inside the farm. Routes to a chest far above first fly at the current height to the chest's
  column, but between farm planes the column under the roof's chest wall is closed by glowstone.
  That search could never finish and used the whole route budget, so no climb was planned.
- The staging search now uses at most a third of the budget. If it can't reach the column, the
  route stages at the nearest reached cell within four blocks of it, or plans the rest directly.
- If no route reaches a chest, the restock skips it and plans from the other scanned chests,
  instead of pausing. It pauses after three skipped chests, and interference such as movement
  input or lost flight still pauses at once. A pause while a skip is being reported no longer
  asks for Reset.
- The mod jar must be installed with Minecraft closed.

## 2026-09-29: Restocking no longer stalls behind the inventory or Esc menu (mod)

- Restocking, depot scans, and surplus disposal could wait indefinitely with the inventory or Esc
  menu open, showing "Restocking is waiting for the current page and inventory cursor to clear."
  The mod asked vanilla whether a mouse button was held. Vanilla sets that flag on a click in
  gameplay and clears it only on a release with no screen open. After right-clicking a chest, whose
  screen opens before the button comes up, the flag stayed set until the next click in gameplay, so
  the mod never left the page. It now reads the physical button state from the window.
- The same stale flag made the mod forget the page it should reopen after restocking, and could
  stop surplus disposal with "A held mouse button is an active player interaction."
- While a page holds restocking or a depot visit, the status now names the reason, for example
  "Restocking is waiting to leave the open page: chat is open."
- The mod jar must be installed with Minecraft closed.

## 2026-09-29: Work continues with the inventory or Esc menu open (mod)

- Depot scans no longer wait for you to close the inventory, the Esc menu, or a settings page.
  Before, the scans queued after joining never started while a page was open, so Start and Resume
  stayed blocked on "Wait for registered-depot scans to finish."
- Routes to registered chests keep flying with the page open. At the chest, the mod leaves an idle
  page the same guarded way restocking does (empty cursor and crafting grid, no mouse button held),
  and waits there while chat is open.
- When no restock, shop, or depot work is left, the mod reopens the page it left: a fresh
  inventory, or the same Esc or settings screen. It doesn't if you moved, clicked, or opened
  another screen in the meantime.
- A `/shop` purchase that has to reopen the shop leaves an idle page instead of failing.
- With the inventory visible, a full hotbar is refilled by swapping out a plain build supply or
  pickup, as in game, instead of waiting for an empty slot. Tools and special items stay put.
- `/schematic-supervisor takeoff` and `approach` keep going with the inventory or a settings page
  open, or with Minecraft in the background. A container, another screen, or an item on the
  cursor still stops them.
- In a single-player world, depot scans, takeoff, and approaches keep the world running behind the
  Esc menu, as building already did.
- The mod jar must be installed with Minecraft closed.

## 2026-09-29: Repository layout

The mod and the monitor are unchanged.

- The farm schematic moved to `schematics/wheatfarm_v2.litematic`, with its measurements in
  `schematics/README.md`.
- All script tests are in `scripts/tests/`, from `tests_py/` and `companion/tests/`. Run them
  with `python -m unittest discover -s scripts/tests`.
- The README is shorter. Running a build is in `docs/operating.md`, the automation profile and
  its tools in `docs/profile.md` (formerly `docs/build-workflow.md`), and the agent runner in
  `docs/agent-supervision.md`. Design specs and performance notes are in `docs/design/`.
- The dated reports, the build-workflow log, and the live dashboard implementation plan left the
  repository. The originals are archived locally in `data/reports/`.
- `docs/releasing.md` describes how to build release files and what to settle before publishing.

## 2026-09-29: Faster placing, planting, and moving between chunks (mod)

Four builder changes from the efficiency investigation. The mod jar must be installed with
Minecraft closed.

- **Block placement** (dirt, birch planks, Glowstone). The 4-tick click interval now runs from the
  previous click, through the server's acknowledgement, as it already did for tilling and planting.
  The next block is chosen in the tick the server confirms, and a block already in reach needs no
  navigation tick. Each placement used to wait for the acknowledgement plus 7 ticks, about 10 ticks
  in all; now it is about 4. Over 39 hours of structure building the farm placed 1.27 blocks a
  second, with about 60% of that time in the fixed cycle.
- **Planting.** Crop rows up to 3 blocks apart form a strip that is planted in one pass, and
  strips alternate direction. When no cell is in reach, the move aims up to 4 blocks past the first
  unfinished cell along its strip, once per cell. Before, each move stopped as soon as the next
  cell was barely reachable: a live sample planted 3.2 seeds per move, with 1-block hops and about
  four 5–8-block backtracks per chunk, and spent 42% of its time moving.
- **Chunk order.** Every stage visits its chunks along a tour that steps only between neighbouring
  chunks and ends beside its first chunk, instead of row by row. Row by row, each stage flew about
  100 blocks back at the end of six rows, and the next stage started at the opposite corner; one
  stage change measured 42 s over a 200-block route. Schedule IDs become `layers-v2…`. A checkpoint
  saved with a `layers-v1…` schedule is converted when it loads: finished pieces stay finished, the
  build continues at the first unfinished piece in the new order, and its state and material ledger
  are kept. `scripts/reconcile_clearing.py` still accepts only its original version-one incidents.
- **Flight speed.** Each step now moves up to 0.5 blocks horizontally and 0.375 vertically, the
  vanilla flying speeds without sprinting, instead of 0.25 blocks in any direction. This covers
  building, depot and shop trips, and approaches. Every step is still checked for collisions,
  received chunks, and the operation's allowed space. If the server starts correcting the player's
  position ("The server moved the player outside the expected flight segment"), report it.

## 2026-09-28: Planting routes survive a briefly unloaded target chunk (mod)

- A planting or tilling route that detoured out of the target's receive window, such as a descent
  outside the farm's north edge toward a target six chunks away, restarted a chunk approach each
  time the target chunk unloaded. That approach stopped at the same chunk boundary, so the player
  hovered in place until the 3,600-tick progress limit paused the build. The route now keeps its
  segment guards and inspects the target again once its chunk returns, as placement and stem
  routes already did. The mod jar must be installed with Minecraft closed.

## 2026-09-28: Materials tab counts placed materials (mod and monitor)

The Materials tab took Used and Remaining from the material ledger, which restarts at every Start.
After a build check found most of the farm built, it still listed all the dirt, Glowstone, and
birch planks as remaining. The numbers are right once both the mod jar (installed with Minecraft
closed) and the exe are updated.

- Mod: `/v1/progress` adds `materials`, the items per material planned in the schedule and done in
  finished pieces, counted like the % built.
- Monitor: the Materials tab shows Planned, Placed, and Remaining from those counts, and Status
  details shows "Materials placed: n / m planned". With a mod that doesn't send them, the tab keeps
  the ledger columns, labelled Used, with a note to update the mod.

## 2026-09-28: Chunk map preview on hover (monitor)

Only the monitor changes; it takes effect once the exe is rebuilt. The mod is unchanged.

- In the full window, pointing at a stage on the timeline shows that stage's chunk map and
  caption. Moving the pointer off the timeline shows the stage you last clicked again, or the stage
  being built when none is selected. Hovering doesn't change the selection or "Back to current",
  and the All stages map ignores it.
- The hint under the timeline reads "Hover a stage to preview its chunks. Click to keep it."

## 2026-09-28: Farm schematic fix

- `wheatfarm_v2.litematic` was missing one wheat plant at relative `(0, 19, 90)`. The build check
  found wheat growing there and reported it as an extra block the builder won't fix. The cell is
  now `wheat[age=0]`, so the farm plans 156,800 seeds and 495,193 blocks. Both the bundled copy
  and the installed copy in `runtime\game\schematics` changed.
- The plan ID changes to `sha256:38e55e5c…e4a2`, so the next load starts a new build state
  under `builds\`. The old root checkpoint is kept unchanged, and the build check on Start finds the
  finished work again.

## 2026-09-28: Build check on Start (mod and monitor)

Start no longer re-walks the whole schedule from the first stage. The design is in
`docs/design/start-build-check.md`. The mod's version stays 0.1.0; the new mod jar must be
installed with Minecraft closed.

### Mod

- Every Start first checks what is already built: a `CHECKING` state reads the received build
  chunks in a few seconds without moving the player, then construction starts at the first
  unfinished piece and skips pieces that are already finished. Previously a Start of a mostly built
  farm spent about 6.5 minutes re-checking finished stages and could pause for a hoe at a till stage
  that was already done.
- Progress counts the pieces the check found finished, so the percentage is right from the start.
- The check reports work left (blocks to place, soil to till, crops to plant), wrong blocks, and
  extra blocks. Blocks the builder clears by itself (moss, stems, a jack o'lantern at a planned
  Glowstone cell) are counted separately. The summary is posted in chat and in the new observation
  field `build_check`, with up to ten blocks that need attention.
- Unreceived chunks are left unchecked and handled as before when the build reaches them. Pause or
  Stop cancels the check. If the check fails, construction starts from the first stage as before.
- Checked pieces are saved in the checkpoint (`checked_pieces`), so Resume and client restarts keep
  skipping them. Older checkpoints load unchanged, and an older mod ignores the field.
- The Glowstone restock lookahead no longer counts pieces the builder will skip.

### Monitor

- Shows the `CHECKING` state as "Checking n%", records each finished check in the Activity tab,
  lists wrong and extra blocks the builder won't fix under attention in the full window (the
  Status details tab lists up to ten), and notes chunks that were out of range.
- The speed and time-left estimate starts a new baseline during the check, so pieces found finished
  are not counted as build speed.
- The Stop confirmation describes the new Start.
- The AI runner treats `CHECKING` as mod work, like loading, with no model call and no second Start.

## 2026-09-28: Dashboard fixes (monitor 0.3.1)

- Reset speed and time-left samples when telemetry is unavailable, so reconnecting cannot count
  work completed during the outage as instant progress. Normal pauses still preserve the rate.
- Hide the attention panel when its problems clear, returning the space to the chunk map.
- Fit every control, including More, within the full window's minimum width.
- Restore the intended percentage and heading fonts. Keep the compact card's stale-data label
  readable with the larger percentage text.

## 2026-09-27: Live dashboard (monitor 0.3.0)

This release has a new desktop monitor, `SchematicSupervisor.exe` 0.3.0, and a mod update. The mod's
version stays 0.1.0. The new monitor also works with the previous mod, with less detail.

### Monitor (SchematicSupervisor.exe 0.3.0)

Added:
- A compact card that stays on top of other windows. It shows the build state, the exact % built,
  "Verify n/2", a stage timeline, and a "Now:" line (or the current problem and how to fix it). It
  has Pause/Continue, Scan depots, and Expand buttons. Unpin turns always-on-top off.
- A full window with:
  - the stage timeline and a per-chunk map, for one stage or all stages;
  - build speed and time left;
  - where the last control came from (this monitor, in game, or the AI runner);
  - every current problem with a fix hint;
  - all controls, including Start and Stop (Stop asks for confirmation);
  - Status details, Materials, Activity, and Raw data tabs.
- Taskbar alerts when the build pauses, reports an error, finishes, or loses its connection for
  15 s during work. A beep is optional (Sound on attention). A Pause sent from the monitor doesn't
  alert.
- Fix hints for 15 known mod messages.
- Named connection problems instead of a plain "Offline": Not running, Not responding, Connection
  dropped, Starting, Pairing needed, Pairing rejected, Pairing invalid, and Version mismatch.
- A debug log, `logs\monitor.log`, next to the exe (falling back to
  `%LOCALAPPDATA%\SchematicSupervisor\logs`). It rotates at 1 MB with 4 backups, hides pairing
  tokens, and records crashes.
- Remembered layout, card position, window size, pin, and sound settings (`monitor-state.json`).
- A light or dark theme that follows Windows, sharp text at high DPI, and one monitor at a time.

Changed:
- The display keeps refreshing after an error and shows "Display error; details are in the log".
- Pairing messages now say plainly that without the pairing file the monitor can neither read
  status nor send controls.

### Mod (version unchanged at 0.1.0)

Added:
- `GET /v1/progress` on `127.0.0.1:8765`. It uses the same pairing token as `/v1/observation` and
  reports totals, per-stage progress, and per-chunk progress.
- Two observation fields, `progress_revision` and `last_progress_at`. The protocol version stays 1,
  and every change is additive.

### Upgrading

1. Close Minecraft, then install the new mod jar, as described under "Update the installed mod"
   in `docs/profile.md`. Until you do, the monitor shows an approximate "≈ %" and "Update the mod
   for exact progress".
2. Use the new `companion\dist\SchematicSupervisor.exe`. The previous version is kept as
   `SchematicSupervisor.previous.exe`.
3. The card shows over Minecraft only when Minecraft runs windowed or borderless.

### Known limitations

- After Stop and Start, progress restarts at 0% and climbs quickly while placed blocks are
  re-checked.
- If a plan's lighting or planting order changes when it loads, the % can dip until the build
  reaches those pieces again.
- Time left doesn't include final verification.

All 524 Python tests and 1,301 Java tests pass.
