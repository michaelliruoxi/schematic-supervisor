# Planting and navigation performance

Installed and live-verified: the final build confirmed 255 seeds in 89.939 seconds
(2.835 seeds/second), compared with the earlier 0.990 seeds/second baseline: **2.86 times
the observed planting speed**. This is a short active-planting comparison, not a whole-farm
completion-time estimate.

Planting now scans up to 256 planned cells per execution tick, respecting a smaller configured
scan budget, and finishes the visible patch before moving. It plants the outside of that patch
first so newly placed crops are less likely to obstruct farther targets. Selection preserves
original completion indices and finishes the highest unfinished floor first.

The movement fallback alternates direction by the ordinal of each distinct planting row.
Unlike coordinate parity, this works for alternating crop rows, sparse rows, and negative
coordinates. Pending predictions cannot become completed cells or allow a lower floor to start.
Unreceived targets remain eligible for the existing bounded chunk approach; invalid support
still stops execution.

Upper-face interactions with farmland aim just inside the actual outline height. Candidate
selection, route arrival, alternate approach, and final dispatch use the same exact ray.
Other faces retain their existing endpoints, and tilling retains its separate full-dirt aim.

Planting's configured interaction interval begins at dispatch and elapses during confirmation
and travel. The four-tick default minimum between clicks is preserved. Arrival at an already
reachable target no longer requires an extra navigation tick. Retry backoff remains separate.

Flight following can look ahead up to eight grid waypoints. A shortcut is accepted only when
its entire swept player box is currently loaded, within the permitted movement space, and
collision-free. Clearance is rechecked every tick and again for the next velocity segment.
Route duration, search budgets, identity checks, and server-displacement guards retain
their existing bounds. This removes grid zigzags and unnecessary returns to cell centers.
The installed movement update increases the maximum flight step from 0.15 to 0.25 blocks per
tick. The final approach slows to the remaining distance, and the complete next swept box
must still be clear before any velocity is sent.

There is still one outstanding interaction. Exact reach and line of sight, received crop/support
state, the expected wheat block, server prediction settlement, and the one-seed inventory
change remain necessary before another placement. Full-inventory seed refills are preserved.
Plain wheat seeds are also permitted for the exact registered-chest interaction. This fixes
an older pause when a refill filled the hotbar before moving to the next chest. Custom seeds,
keys, books, tools, offhand use, and bypassing the chest action remain rejected. The change
does not expand stem-clearing or inventory-displacement item permissions.

## Validation

The complete offline check, build, and packaging run passed 1,279 Java tests with no failures,
errors, or skipped tests on 25 September 2026. Twenty-three new tests cover sparse and negative rows,
completion index preservation, pending higher floors, invalid and unloaded support, partial
scan budgets, farmland surface geometry, obstacles, received boundaries, bounded lookahead,
fresh clearance checks, delayed planting receipts, combined diagonal/vertical speed limits,
slowing the final approach without overshooting, and seed-filled hotbar chest access without
selecting keys or books.

The exact ray fixture reaches 45 bare farmland cells from one hover, compared with 25 using
the old center ray, at the same 4.15-block reach. This is a geometry result, not a multiplayer
speed measurement. Existing crops and other obstacles can reduce that visible patch.

The pre-update live sample confirmed 89 seeds in 89.908 seconds (0.990 seeds/second), with
58.1% of sampled time in navigation states. It includes a chunk transition and no restock.
The first installed update confirmed 417 seeds in 179.815 seconds across chunks 11 through 14
of the same top floor: 2.319 seeds/second, or 2.34 times the baseline. The two consecutive
samples measured 2.295 and 2.343 seeds/second, with no new execution errors or blockers.
Estimated travel per seed fell from 1.471 to 0.613 blocks. These are sampled travel lower bounds.
With the faster flight step, a further sample confirmed 175 seeds in 60.791 seconds:
2.879 seeds/second, or 2.91 times the baseline, across chunks 18 and 19. The subsequent refill
exposed the older wheat-seed chest-hand restriction described above; its pause is retained
in the evidence and excluded from the active-planting rate.

After installing the chest-hand correction, all 13 depots were successfully scanned with a
seed-filled hotbar. Restoring the paused refill added the remaining 1,025 seeds, reaching
2,176 seeds (34 full stacks) with zero empty main-inventory slots. The two keys and book
remained in their original slots. The saved checkpoint, chest registry, settings, and world
context were preserved. No transfer or reconciliation was pending before Resume.

The final build then confirmed 255 placements in 89.939 seconds across chunks 19 through 21
on the same floor, measuring 2.835 seeds/second (2.86 times baseline). All 163 observations
were fresh and BUILDING, with no execution errors or blockers. The rate includes ordinary
chunk transitions but excludes startup, chest scans, refill, return travel, and final plan
verification. The sequential samples use different chunks and are not a controlled farm-wide
benchmark. Planting was left running after validation.

Evidence, kept locally and not in the repository: the investigation
`data/reports/planting-performance-20260925.md`, baseline observations
`data/planting-performance-baseline-20260925.json`, initial optimized live samples
`data/planting-speed-live-20260925.json`, the faster flight sample and refill diagnosis
`data/planting-cruise-live-20260925.json`, and the final installed build, full refill, and live
planting `data/planting-final-live-20260925.json`.
