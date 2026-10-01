# Build check on Start

- Date: 2026-09-28
- Status: implemented
- Replaces: the "Stop, then Start" rule in `2026-09-26-live-dashboard-design.md` §6.3

## 1. Problem

Start reset the schedule cursor to the first piece. The mod then re-walked every piece, and each
already-finished piece still cost an executor start, a world scan, a success poll, and two
checkpoint writes. On 2026-09-28 a Start at 12:17:36 spent about 6.5 minutes (about 7 seconds per
stage of 49 chunks) re-walking 51 finished stages. It also paused for a hoe at a till stage that
was already done. The percentage restarted at 0% and only reflected the cursor, and blocks that did
not belong to the plan were found only by final verification at the very end.

## 2. Behavior

Every Start of a stopped plan (the command, the monitor, the runner, and the Start that follows
placement loading) runs a build check first:

1. The runtime state is `CHECKING`. Pause and Stop cancel the check; the supervisor stays stopped.
2. `BuildCheckSession` reads the build volume from the client world on the client thread, chunk
   by chunk, at up to `verificationBlocksPerTick` cells per tick and at most 4 ms per tick. It never
   moves the player or waits for chunks: an unreceived chunk is skipped and stays unchecked. A
   chunk that unloads part-way is dropped. A cell with an unconfirmed block prediction is unknown.
3. `BuildCheck.evaluate` compares the snapshot with the plan on a worker thread.
4. `SchematicSupervisor.start(result)` starts at the first unfinished piece. The cursor skips every
   later piece the check found finished (`CompletedPieces`); those pieces count as done in
   progress. The set is saved in the checkpoint (`checked_pieces`, Base64 of the bit set) so Pause,
   Resume, and a client restart keep skipping them. It is cleared when every piece is done, so a
   final-verification chunk repair re-walks its whole chunk.
5. The summary goes to chat, `last_message`, and the observation's `build_check` field.

If the check fails, construction starts from the first stage the old way. A plain `start()`
without a check keeps the old behavior.

## 3. Rules

A piece is finished when every target is known and satisfied by the rule its executor uses:

| Order | Finished when |
|---|---|
| Ordinary placement | `PlacementAcceptance.satisfied`: the planned state, ignoring farmland moisture and wheat age; planned dirt at a till target may already be farmland. The executor's `OrdinaryPlacementAcceptance` delegates to the same method. |
| Till | Farmland. |
| Plant | Wheat of any age. |

Cells in received chunks are classified against the plan's final states:

| Cell | Result |
|---|---|
| Planned block present, or air/dirt/unplanted crop still to do | Not a problem; work left counts per target. |
| Planned cell with another block | Wrong block. |
| Cell planned as air with a block | Extra block, unless it is a tracked temporary support. |

Wrong and extra blocks the builder clears by itself are counted separately and are not reported
for attention: moss, stems, and a jack o'lantern in a planned Glowstone cell (`MossClearingPolicy`),
and stems in air or crop cells (`StemClearingSweep`). A stem sweep runs only before an order in its
chunk, so for each such stem the check reopens the first piece in that chunk whose sweep reaches
its height. A stem above every sweep is reported for attention. The slice that owns unfinished
temporary supports is never skipped, because supports must settle in that slice first.

## 4. Observation contract

`build_check` is a top-level observation field: `null` until the first check after a plan load,
then the running check or the last result. Unload and Reset clear it.

| Field | Meaning |
|---|---|
| `status` | `RUNNING`, `COMPLETE`, or `FAILED`. |
| `progress` | 0–1 share of cells read while running; 1 afterwards. |
| `started_at`, `finished_at` | ISO-8601 UTC; `finished_at` is null while running. |
| `duration_ms` | Time from Start to the result. |
| `summary` | One line, as posted in chat. |
| `chunks_checked`, `chunks_total` | Received chunks compared, and all chunks in the layout. |
| `actions_total`, `actions_done` | Same counting as `/v1/progress`; done counts finished pieces. |
| `pieces_total`, `pieces_done` | Schedule pieces, and those found finished. |
| `work_left` | `place`, `till`, `plant`: targets still to do in checked chunks; `unchecked`: targets in unchecked chunks or unknown cells. |
| `wrong_blocks`, `wrong_blocks_cleared` | Wrong blocks, and how many the builder clears itself. |
| `extra_blocks`, `extra_blocks_cleared` | Extra blocks, and how many the builder clears itself. |
| `temporary_supports` | The mod's own support blocks in cells planned as air. |
| `problems` | Up to 10 blocks that need attention, lowest Y first: `kind` (`WRONG`/`EXTRA`), `x`, `y`, `z`, `chunk` (1-based), `expected`, `actual` (block IDs). |

The result fields are present only when `status` is `COMPLETE`. `/v1/progress` keeps its format:
checked pieces after the cursor are reported as `D` and counted in `done`.

## 5. Clients

- Monitor: state label "Checking" with "Checking n%"; the card line says it is checking what is
  already built. The full window lists wrong and extra blocks the builder won't fix as an attention
  item with the first block's coordinates, notes unchecked chunks, and shows the summary and up to
  ten blocks in Status details. The Activity tab records each finished check once. The pace
  tracker starts a new baseline on `CHECKING`, so pieces the check marks done are not build speed.
  The Stop confirmation describes the new Start.
- Runner: `CHECKING` is mod work, like `LOADING`; it needs no model call and no second Start.
- Alerts: losing the connection for 15 s during `CHECKING` alerts like other work.
- The mod keeps the world ticking in the background during `CHECKING`.

## 6. Limits

- Unreceived chunks are not checked. Their pieces are re-checked the old way when reached.
- The check reports wrong and extra blocks; it does not remove them.
- Skipped pieces rely on the check's snapshot. Final verification still checks every chunk twice
  before `DONE`, and a dirty chunk is repaired by re-walking all of its pieces.
- A schedule conversion on load (changing `deferPlanting` or `glowstoneAfterStructure`) drops the
  checked pieces; the next Start checks again.
