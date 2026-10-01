# The automation profile

This project installs Minecraft into a separate launcher profile, **Schematic Supervisor**, whose
game directory is `runtime\game`. The profile includes the bundled farm schematic. The scripts in
`scripts\` install that profile, add another schematic, update the installed mod, and recover
interrupted operations. The scripts that change the profile refuse to run while Minecraft is
open. `runtime\installation.json` records what was installed, and each change keeps a backup
under `runtime\installation-backups`.

- [Install the profile](#install-the-profile)
- [Build another schematic](#build-another-schematic)
- [Capture a build manifest](#capture-a-build-manifest)
- [Update the installed mod](#update-the-installed-mod)
- [Offline recovery](#offline-recovery)

## Install the profile

Close Minecraft and its launcher first. Build and stage the pinned mod bundle, install the
profile, then register the agent connection:

```powershell
.\gradlew.bat --offline check build stageAutomationMods
python scripts\install_automation_profile.py --minecraft-dir "$env:APPDATA\.minecraft" `
  --python-exe (Get-Command python).Source --agent-executable <local-agent-cli-path>
.\scripts\register-agent-link.ps1
```

The installer updates the isolated game directory and preserves existing launcher entries. The
launcher downloads any missing pinned loader libraries when the profile is first started. The
Python and agent paths are saved in the ignored `companion\agent.local.json` for the optional
runner; see [`agent-supervision.md`](agent-supervision.md).

The installer checks that the input is an existing `.litematic` file before writing anything. Its process check requires Minecraft and both launcher variants (`Minecraft.exe` and `MinecraftLauncher.exe`) to be closed before changing the profile or files. It installs the file under its source name in `runtime/game/schematics`. A different existing file with the same name is backed up under the installation backup's `schematics` directory. Identical files and a source already at its destination are left untouched. Omitting `--schematic` keeps the bundled farm as the default.

These options write the matching [settings](operating.md#settings); each has a `--no-` form, and
omitting one keeps the existing value: `--defer-planting`, `--glowstone-after-structure`,
`--auto-repair-hoes`, `--discard-surplus-when-storage-full`, and `--discard-surplus-directly`.
`--shop-purchases` sets `enabled` in `shop.json` and keeps any shop layout already in it; a new
profile has no `shop.json` and buys nothing (see [Shop purchases](operating.md#shop-purchases)).

The saved mod setting is `deferPlanting` in `runtime/game/config/schematic-supervisor/settings.json`. The installer preserves an existing value when the option is omitted; a new profile defaults to `false`. Use `--defer-planting` to enable it or `--no-defer-planting` to enable planting later. Pause the build, let pending interactions settle, and close the game before changing this setting. An unsettled transfer or active chunk repair blocks a mode change; finish that recovery in the previous mode first.

On the next load, a settled checkpoint is converted explicitly and saved paused. Its source plan identity and material ledger remain intact. Shared construction orders retain their cursor position, while enabling planting returns to the earliest deferred planting step that precedes the saved cursor. Old completion fingerprints are invalidated, so both modes must pass fresh verification. This transition does not reset or delete the checkpoint. Resume continues in the selected mode. Deferred checkpoints use the distinct schedule identity `layers-v2-deferred-planting`, so older mods reject them instead of silently restoring planting.

Schedule identities starting with `layers-v2` visit each stage's chunks along a tour that steps only between neighbouring chunks and ends beside the first chunk. The earlier `layers-v1` identities visited them row by row. When a `layers-v1` checkpoint loads, the mod converts it to the matching `layers-v2` schedule and saves it before anything resumes: finished pieces stay finished, the cursor moves to the first unfinished piece in the tour, and the state, ledger, and any pending settlement are kept. A mod older than this change rejects the converted checkpoint, so keep the matching jar installed.

`runtime/installation.json` records the source name, installed file SHA-256, destination, backup
location, and pinned mod hashes. Keep this report with the original schematic when repeating a
build. Existing runner preferences in `companion\agent.local.json` are retained.

Set `pauseOnLostFocus:false` in the profile's `runtime\game\options.txt` so depot scans at startup
continue while Minecraft is unfocused.

## Build another schematic

First create an offline material and layer summary for the chosen file:

```powershell
python scripts/generate_work_order.py "C:\builds\workshop.litematic" --output "data\workshop_work_order.json"
```

The offline planner accepts one region with positive dimensions, at most 2,000,000 cells, 1,000,000 non-air blocks, and 1,024 source subdivisions. Its chunk counts divide the untransformed source into 16-by-16 columns from the region minimum, assuming that minimum is aligned to a chunk boundary. Rotation, mirror, or unaligned placement can change the actual world chunk assignments; the mod must still preflight the selected live placement and apply its limits there. Empty layers remain part of verification. An air-only region has no placement stages but still needs full verification.

The summary counts only materials and interactions implied by supported source blocks. It does not invent server-specific hopper work or include travel, restocking, retries, or temporary-support actions. The existing layer policy builds structure upward, handles hanging lights after their upper supports, and tills and plants downward; this is not a general dependency solver for arbitrary layouts.

The summary still knows only the bundled farm's palette (dirt, farmland, wheat, glowstone, birch
planks) and rejects other blocks. The mod itself also accepts any full block without properties
(see [Build order](operating.md#build-order)) and reports any block it refuses, with the reason,
when the placement loads; use that check for other schematics.

Read the output's `support_capabilities` before treating a supported palette as a buildable layout. Its `basis: source_policy_only` and `constructability: not_assessed` describe the implementation limits, not the installed jar or the current world; the compact command output also reports `constructability`. An ordinary target can use an existing received adjacent world face when the live reach, collision and interaction guards permit it. Otherwise, the temporary-support policy can place exactly two owned Dirt cells at the target's same X/Z and Y offsets -2 and -1, resting on a received matching planned structural anchor at offset -3. Both temporary cells must be AIR in the original source and the received world, within the current ordinary slice's permitted column. Deferred Wheat is not source AIR. Owned supports must be cleaned up, and this metadata does not include their extra material demand in the nominal totals.

For example, a source containing only Dirt at Y=0 and Y=4 passes palette and size checks. If only the lower block exists and every face beside the upper target is AIR, the two-cell support policy cannot bridge from Y=0: its required anchor at Y=1 is absent. A suitable existing adjacent world face can make the same source buildable, so four-block spacing is not rejected categorically. Check those world attachment conditions for a new site; successful planning alone is not constructability proof. Retain the full selected-volume AIR, tilling, lighting, roof and two-pass verification requirements.

To finish construction without planting yet, add `--defer-planting` to both the offline summary command and the installer command below. This keeps the full structural footprint, dirt, roof, hanging lights, and tilling. It removes only seed demand and planting actions. The bundled farm then has 76 layer stages and 651,993 nominal interactions; its 156,800 seed cells are explicitly deferred.

Build and stage the pinned mod bundle with `.\gradlew.bat check build stageAutomationMods`, then save and close Minecraft and its launcher. From the project directory, run the installer with your existing runtime paths and the chosen file:

```powershell
python scripts/install_automation_profile.py `
  --minecraft-dir "$env:APPDATA\.minecraft" `
  --python-exe "<existing Python executable>" `
  --agent-executable "<existing agent executable>" `
  --schematic "C:\builds\workshop.litematic"
```

Open the Schematic Supervisor profile, load the installed file in Litematica, and select its placement. The mod currently requires exactly one enabled sub-region and supports dirt, farmland, wheat, glowstone, and birch planks. The placement must also pass the mod's source, transform and size preflight; those checks do not prove that every target has an executable attachment path. Installing another file does not add support for arbitrary blocks, block entities or a general support planner.

Before changing a build, run `/schematic-supervisor pause`, wait for pending interactions to settle, then run `/schematic-supervisor unload`. Unload preserves the saved checkpoint; Reset deletes the selected checkpoint and is not the command for switching builds. Select the next placement and use Start. A saved unfinished build loads paused and continues with Resume.

New checkpoints are stored under `config/schematic-supervisor/builds/<build-identity>` using the plan, world identity, and dimension. An existing root checkpoint/context pair stays at its original paths and is reused only for its matching build. Unsettled outcomes or failed unload cleanup prevent switching; retry Unload after resolving the reported cause. Keep the checkpoint and run-context files with the installation report.

After a restart, the saved-build guard checks the other namespaced checkpoints. Original
unresolved builds remain loadable for recovery; fresh or settled builds cannot bypass them. Keep
namespace directories intact: malformed or incomplete state blocks switching, and inspection is
bounded to 128 saved namespace entries. Resolution never deletes old builds.

Register and scan the new build's real supply depots, verify flight readiness, then start the plan through the supervisor. The runner's default objective follows the selected schematic; use `-Objective` for any additional build-specific instructions. The runner exits when you unload a build, so restart it after selecting the next one.

## Capture a build manifest

Save the intended selected placement, then exit Minecraft before capturing its manifest. Locate
the saved state for that world and dimension under `runtime/game/config/litematica`. Its file name
combines the server and the dimension, in the form
`litematica_<server>_dim_minecraft_overworld.json` for an Overworld build.

The required JSON has a root `placements` object containing a `placements` array and an integer `selected` index. Inspect the array entry at that zero-based index: its `schematic` path must identify the intended new file, and its `origin`, `rotation`, `mirror` and `enabled` must match the intended placement. Relative schematic paths are resolved from `runtime/game`. Check each nested `placements` entry's `name` and `placement.pos`, `placement.rotation`, `placement.mirror` and `placement.enabled`; exactly one sub-region must be enabled. A server-wide settings JSON or standalone placement export does not satisfy this selection schema.

Pass that world/dimension state file to the command below. Choose a distinct output filename for each build so the previous build's manifest remains available:

```powershell
python scripts/capture_build_manifest.py `
  --placement-state "runtime/game/config/litematica/<world-and-dimension-state>.json" `
  --game-directory "runtime/game" `
  --output "data/build-manifests/workshop.json"
```

The manifest records the saved origin, rotations, mirrors, enabled region, source hash, installed mod hashes, and relevant settings. It omits account, token, and connection details. It reads saved state, so capture it again after saving placement changes or updating the installed bundle.

Temporary-support availability uses the installation report beside the game directory, or an explicit `--installation-report` path. Its game directory, recorded jar name, bundle hash, and last-update hash must match the installed supervisor jar before the report's boolean integration claim is accepted. The existing `temporary_support_execution_available` field is `null` when evidence is missing, stale, or invalid; `null` means unknown, not unsupported. The additive `temporary_support_execution_evidence` field records the basis and reason without private paths. Schema version 1 is retained. This is installed-feature evidence only: manifest capture does not assess live support placement, starter credit, or cleanup.

## Update the installed mod

For a future tested jar update, pause and settle the build, exit Minecraft cleanly, and stop the runner. Build/stage the candidate and retain a JSON validation report containing its `sha256`, a positive integer `java_tests`, and integer zero values for `failures`, `errors` and `skipped`. From the workspace, use the existing profile updater:

```powershell
python scripts/update_automation_mod.py `
  --artifact build/automation-mods/schematic-supervisor-0.2.0.jar `
  --expected-sha256 "<verified 64-character artifact SHA-256>" `
  --test-report "data/<matching-validation-report>.json"
```

The helper requires the staged supervisor jar and a matching installed jar/metadata pair, verifies absent game/runner processes, and backs up the prior jar, metadata and saved build state before atomic replacement. When the staged filename carries a new version, the new jar replaces the installed one under its new name and the metadata follows; a failure restores the old jar under its old name. A profile without `shop.json`, installed when every mod bought from the shop, gets `{"enabled": true}` so its shop purchases stay on; the update records this as `shop_settings_created`. It checks that saved checkpoints, context, depots, settings and transaction journals remain unchanged, retains prior update metadata in history, and records `update_live_verified: false`. The report may include an `integrated_features` map containing only recognized implementation-feature names with boolean values; these are hash-bound implementation claims, not live-verification results. Keep the validation report immutable and save installation/live results separately.

The helper neither launches Minecraft nor proves in-world behavior. It detects supervision
runners with quoted or unquoted command-line arguments; a live runner blocks the update even after
the game closes. Launchers may remain open for this jar-only update. Check the resulting hashes
after an authorized update, restart the dedicated profile, and validate the restored checkpoint
and intended feature before claiming live success.

## Offline recovery

Two tools settle one interrupted operation from preserved evidence while Minecraft and the runner
are closed. Both run as a dry run unless given `--apply`, and neither retries an action, resets
progress, or advances the schedule.

### Recover an interrupted clearing

`scripts/reconcile_clearing.py` clears the reconciliation hold left by one interrupted clearing
attempt, with zero material credit. It accepts a disconnected light removal and these immutable
incident kinds: `light_clearing_acknowledgement_timeout`, `moss_clearing_acknowledgement_timeout`,
`stem_sweep_acknowledgement_timeout`, and `moss_clearing_disconnect_unchanged`. The light-removal
timeout is described below; the others follow the same probe, dry-run, and apply steps.

A `light_clearing_acknowledgement_timeout` incident is a retained, zero-spend, 240-tick diamond-axe clearing failure followed by a disconnect and a read-only rejoin. It requires the identical failure and automatic safety-pause control in both observations, the same lighting slice and ledgers, the received Jack o'Lantern at the planned Glowstone cell, and an unchanged plain replacement stack. Withdrawn minus consumed Glowstone must independently equal that stack. An operator pause, other pending operation, unrelated error or mismatched evidence is refused. This case does not invent a missing pre-disconnect mining sample.

Create a new clearing probe request with the exact checkpoint hash after preserving the incident, while game and runner are stopped. The selected slot identifies the fresh replacement stack, not the historical axe slot. Start a fresh client, rejoin, let depot scans settle, then load the selected placement with START only after confirming that the exact saved checkpoint remains PAUSED with reconciliation required. The loader retains that lock and does not start construction. Select the requested hotbar stack without moving inventory. Never Resume or Reset during the probe. The existing passive probe must confirm the loaded plan and current scheduled target, all 36 applied server inventory receipts, no prediction, no pending operation, and the unchanged plain Glowstone total. Keep the runner stopped throughout.

With the client stopped again, run the helper with `--request`, `--evidence`, the reviewed
`--expected-evidence-sha256`, and `--probe`; default behavior is a dry run. `--apply` commits only
the two reconciliation fields with zero material credit. Its five-minute freshness gate,
absent-process guard, auxiliary-journal checks and immutable prepared/committed transaction remain
mandatory. Archive the consumed profile request beside its transaction before restarting. All
schedules, ledgers, saved errors and other state remain preserved; normal resumed execution must
separately confirm removal and placement. Committed transactions are kept under
`runtime/clearing-reconciliations/<request_id>/`.

### Reconcile one uncertain ordinary placement

`scripts/reconcile_placement.py` handles only one accepted ordinary Dirt, Glowstone or Birch
Planks placement whose original receipt was UNCERTAIN with a pending prediction, whose target now
contains the expected received block, and whose inventory proves exactly one item was spent. It
supports the existing root-profile `runtime/game/config/schematic-supervisor/checkpoint.json`, not
arbitrary build namespaces. It does not retry a click, reset progress, advance the schedule,
reconcile mining/support/depot/shop operations or accept a rejected/no-spend outcome.

1. Preserve the original fresh, connected uncertainty observation as immutable JSON and record its reviewed lowercase SHA-256. Keep its exact PAUSED checkpoint, run context and all transaction journals. With both game and runner stopped, write UTF-8 JSON to `runtime/game/config/schematic-supervisor/placement-reconciliation-probe.json`: `version: 1`, a new canonical UUID `request_id`, UTC `created_at` after the incident, the exact lowercase `checkpoint_sha256`, `plan_id`, `saved_run_context` containing `world_identity_hash` and `dimension`, the actual `player_uuid`, integer `target` coordinates, `expected_block`, `material`, `inventory_before` and `expected_inventory_after` equal to one fewer. An optional `old_session_id` must also be a canonical UUID. Bind these values to the preserved incident and original context; do not guess them or reuse a request for another outcome.
2. Start a fresh client process after creating that request, join the original world and keep the runner stopped. Do not Start or Resume the build. The optional probe runs read-only while automation is idle in IDLE or PAUSED and writes `runtime/game/config/schematic-supervisor/placement-reconciliation-observation.json`. Wait for a positive `available: true` response: it must bind the request/checkpoint/plan/world/player, show the received target without pending prediction, and match applied server receipts for all 36 main inventory slots. Pending transactions, an occupied cursor, mismatched context or incomplete evidence block acceptance.
3. Copy that positive response to a separate immutable evidence file, preserving the original request bytes. Exit the game cleanly and verify both game and runner are stopped. The helper requires the probe capture to be no more than five minutes old when it actually changes the checkpoint; the historical incident has no equivalent five-minute limit. Do not change inventory, checkpoint, context or journals between the probe and review.
4. Run the default dry run from the workspace, supplying the previously reviewed incident hash explicitly:

   ```powershell
   python scripts/reconcile_placement.py `
     --request runtime/game/config/schematic-supervisor/placement-reconciliation-probe.json `
     --evidence "data/<immutable-incident>.json" `
     --expected-evidence-sha256 "<reviewed lowercase 64-character incident SHA-256>" `
     --probe "data/<fresh-positive-probe>.json"
   ```

Review the reported before/after checkpoint hashes and single-item consumption credit. To commit that exact reviewed result while its fresh-evidence gate still passes, repeat the same command with `--apply`. The helper checks absent game/runner processes, exact checkpoint and other saved-state bytes, unchanged inputs and settled auxiliary journals. It leaves the checkpoint PAUSED, preserves withdrawals, schedule and all other progress, adds only the evidenced item to consumption, and clears that placement's reconciliation/error fields.

Keep `runtime/placement-reconciliations/<request_id>/` intact. It holds exact before/after checkpoints, bound input copies and a transaction written as `prepared` before the atomic checkpoint replacement. Repeating the same invocation recognizes the exact already-applied checkpoint and finishes transaction metadata without adding another credit; this metadata-only replay can use the original aged probe. If the checkpoint was never changed, the five-minute gate still applies. A changed or rolled-back checkpoint, altered evidence, an unfinished competing transaction or stale unapplied evidence is refused. Preserve those records for review instead of deleting them, substituting evidence into a prepared transaction, retrying the placement or using Reset. A successful reconciliation still requires fresh startup and normal build verification; it is not completion evidence.
