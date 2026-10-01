# Dirt before Glowstone

Set `glowstoneAfterStructure` to `true` in the automation profile's supervisor settings to use this order:

1. Complete every Dirt and Birch Plank structural layer, from bottom to top.
2. Automatically complete every Glowstone layer, from bottom to top.
3. Till the farming layers from top to bottom. Planting stays disabled when `deferPlanting` is `true`.

Every stage finishes all of its chunks before advancing. Glowstone remains part of the material plan and final verification. This setting does not remove existing lights or require an operator command to start the lighting stages.

Change the setting while Minecraft is closed. When the mod loads the same schematic, it converts a settled paused checkpoint to the earliest unfinished order in the new schedule. Material consumption, withdrawals, planned-placement credit and the source plan identity are preserved. Previously completed lights are checked again when the lighting stages are reached; existing matching blocks receive no new placement credit.

Pending material transactions, unresolved reconciliation and active chunk repair prevent schedule conversion. The same blocked work order retains its exhausted recovery attempts. Obstructions and temporary support ownership remain protected.

Completion still requires all structure, Glowstone and tilling, removal of owned temporary supports, and two clean full-volume verification passes. Planting preferences remain independent of Glowstone ordering. Profiles without the new setting retain their existing interleaved schedule.
