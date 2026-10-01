# Tilling target selection and timing

Tilling now finishes reachable planned soil before moving to the next patch. Candidate selection uses the existing flight arrival and exact top-face ray checks. If no unfinished target is reachable, a serpentine sweep alternates direction between rows. The layer and chunk schedule stays the same.

Selection retains the original target indices and completion bits. It scans at most 256 candidates per execution tick, also respecting a smaller configured scan budget. Partially scanned selections finish before choosing a fallback, and lower floors wait for higher unfinished floors. A target already within clear interaction range enters the ready state immediately.

The tilling interval starts when a click is dispatched. It advances during subsequent execution ticks, including movement and server acknowledgement waits. Confirmation no longer adds another interval. The optional `tillInteractionCooldownTicks` setting now supplies the tilling interval and defaults to two ticks. Existing settings files without that field use the new default. The shared `interactionCooldownTicks` setting remains four ticks in this profile and still governs ordinary placement, planting, and bounded retry backoff.

Tilling aims just inside the full dirt block's upper surface. The same exact ray is used for nearby target selection, flight arrival, alternate approaches, and the final click. This avoids treating adjacent soil as an obstruction to a ray aimed through the block's centre. Actual obstructions, unloaded chunks, wrong faces, and hits outside the existing reach limit are still rejected. Planting keeps its existing aim at the lower farmland shape.

There is still one outstanding interaction. Pending predictions prevent completion credit, and existing receipt, pause, screen, flight, reach, repair, and verification checks remain in place. Planting and ordinary placement keep their previous ordering and cooldown behavior. Multi-target batching is not included.

## Validation

On 23 September 2026, the complete Java check, build, and packaging run passed **1,232 tests**, including **15 new tilling tests**, with no failures, errors, or skipped tests.

The added coverage exercises stable completion indices, completing the final index before earlier targets, nearest reachable preference, obstructed top-face rays, alternating negative-coordinate rows, floor ordering, bounded scans, changed reachability, and cooldown overlap with short or delayed receipts. Existing confirmation, flight geometry, screen, and ordering tests also passed.

The packaged update's SHA-256 is `cfe429cdd56fd30b1152477587f488b454eae0ed04428a62cd7fe983e4f0a21c`.

The update was installed in the Schematic Supervisor profile on 23 September 2026, with its checkpoint and settings preserved. After restarting the launcher and game, the saved plan was restored and resumed on tilling layer y=0, stage 55/76, chunk 25/49. A brief live check observed 32 new server-confirmed farmland targets over 14.85 seconds, with no new execution errors or pending inventory transactions. The build continues running.

This confirms productive progress after restart. It is a short check on a different chunk, not a matched before/after speed benchmark; the prior 1.17 blocks/second observation remains the baseline.

Evidence, kept locally and not in the repository: the live resumption
`data/tilling-resume-live-20260923.json`, the investigation and live baseline
`data/reports/tilling-performance-20260923.md`, and the build validation record
`data/tilling-performance-validation-20260923.json`.

## Surface aiming and separate till interval

On 24 September 2026, the complete Java check, build, and packaging run passed **1,244 tests**, including **12 new tests**, with no failures, errors, or skipped tests. A filled-soil ray fixture reaches 45 targets from one hover position, compared with 25 using the centre ray, at the same 4.15-block reach. This is a geometry test, not measured multiplayer throughput.

The update was installed with SHA-256 `5936c966548eb17c420cd69964b9b6c36a9916ae0e094b9752c869ac6ba422df`. The guarded installer preserved the checkpoint and settings and backed up the previous artifact. After restart, the return flight from the island arrival point exceeded the existing travel limit. A bounded approach to the saved target resolved the positioning issue, and tilling resumed without editing the checkpoint or granting progress.

Two live samples on layer y=-12, chunks 41 and 42, each confirmed 111 blocks in about 40 seconds: **2.78 confirmed blocks/second**, compared with **2.19 blocks/second** in the 44.68-second sample immediately before this update, an observed increase of **27%**. Neither sample reported a new execution failure or unsettled farmland result. These are short samples on different chunks of the same floor; they exclude chunk transitions, repairs, and the restart return flight. They do not establish a whole-farm finish time or fix the existing long-return travel timeout.

Evidence, kept locally and not in the repository: the live results and confirmed targets
`data/tilling-surface-live-20260924.json`, the update validation
`data/tilling-surface-validation-20260924.json`, and the baseline before the update
`data/tilling-surface-baseline-20260924.json`.
