# Operating guide

How a build runs and how the mod behaves while it works. The [README](../README.md) has the
installation steps, the start checklist, and the command list.

- [Build order](#build-order)
- [Build check on Start](#build-check-on-start)
- [Depots, seeds, and planting](#depots-seeds-and-planting)
- [Shop purchases](#shop-purchases)
- [Dirt restocking](#dirt-restocking)
- [Pause, Stop, Unload, and Reset](#pause-stop-unload-and-reset)
- [Completion and recovery](#completion-and-recovery)
- [Settings](#settings)
- [Files the mod keeps](#files-the-mod-keeps)
- [Local connection and pairing](#local-connection-and-pairing)
- [Behavior reference](#behavior-reference)

## Build order

For the bundled farm, the default order completes each horizontal work stage across all 49 chunks:

1. Build the lowest dirt plane, then work upward through the structural planes.
2. After each higher dirt plane is complete, place the glowstone directly below
   it. Finish with the birch roof and its underside lighting.
3. Till and plant each farm floor from the top down, completing the entire floor
   before moving lower. This protects completed crops from later construction.
4. Verify all chunks, then require two matching full-volume verification passes.

Chunks remain the movement and material batching units within a layer. Each
stage visits its chunks along one tour that steps only between neighbouring
chunks and ends beside its first chunk, so the next stage starts close by. Status
shows the current layer stage, height, and chunk progress. An interrupted slice
is rescanned when resumed, and already-correct blocks are preserved.

Checkpoint version 4 stores the layer schedule identity, cursor, and the last
acknowledged material credit from a temporary support operation. Version 3
layer checkpoints migrate without resetting progress or material totals. A
checkpoint from the earlier row-by-row schedule (`layers-v1…`) is converted to
the chunk tour when it loads, keeping its finished pieces, state, and ledger. Older
chunk-first checkpoints remain rejected; existing files are preserved.
Registered material depots remain intact.

For another schematic, follow [`profile.md`](profile.md#build-another-schematic).
A schematic may use farmland, wheat, and any full block the builder can place the way it places
dirt: a full cube with no properties to orient or set, no block entity, no gravity, and an item that
places exactly that block (stone, planks, wool, concrete, glass, glowstone, and so on). Logs, stairs,
slabs, sand, gravel, chests, and other such blocks are refused when the placement loads, with the
block, its position, and the reason. Source and live-placement preflight also enforce resource
limits, and other enabled placements may not overlap the selected one in one enabled region.

Every other full block is built in the structural stages and supplied from registered chests: put
it in a chest, register the chest, and scan it, as for dirt. The inventory, chest scans, and
material counts track only the blocks the loaded plan uses. Only dirt, glowstone, and birch planks
can be bought (see [Shop purchases](#shop-purchases)).

The `glowstoneAfterStructure` setting finishes every structural layer before any Glowstone; see
[`design/structure-first-order.md`](design/structure-first-order.md).

## Build check on Start

Every Start (in game, from the desktop monitor, or from the runner) first checks what is already
built. The state is `CHECKING` while the mod reads the received build chunks from the client world,
about 20,000 blocks per tick (`verificationBlocksPerTick`), so the 112 × 76 × 112 farm takes a few
seconds. The check never moves the player. Chunks the client has not received are left unchecked;
the builder checks their pieces when the schedule reaches them.

The check compares every piece (one stage in one chunk) with the plan, using the same rules as the
builder: planned blocks present, soil tilled, crops planted. Construction then starts at the first
unfinished piece and skips every piece the check found finished, so the percentage is right from the
start. It also counts:

- work left: blocks to place, soil to till, and crops to plant;
- wrong blocks: a planned cell holding a different block;
- extra blocks: a block where the plan has air.

The builder clears moss, stems, and a jack o'lantern in a planned Glowstone cell by itself. It does
not remove other wrong or extra blocks, and final verification fails while they remain, so the check
reports them for you to fix. The result is posted in chat, shown in the monitor's full window, and
published in the observation's `build_check` field; the first ten blocks that need attention are
listed with their coordinates.

Pause or Stop during the check cancels it without starting construction. If the check itself fails,
construction starts from the first stage and re-checks each piece, as it did before the check
existed. Final verification still checks every chunk before the build can finish. The design is in
[`design/start-build-check.md`](design/start-build-check.md).

## Depots, seeds, and planting

`depot add-nearby` registers received chest blocks within an eight-block cube around the
player (optional radius 1–16), combines both halves of double chests, and queues real
container scans. It requires inactive, settled supervision and rejects more than 64
chests without changing the registry. Existing depots remain registered.

Registered material chests are automatically removed when their saved block position is
confirmed to no longer contain a chest. This check runs about once a second while depot
operations are idle, including while supervision is paused. Unloaded chunks, pending
local block changes, and chests in other worlds or dimensions are left registered.
Removals are saved to the depot registry and reported in chat; active chest operations
finish their normal cleanup first. For a double chest, this checks its registered half;
if that half was removed, register the remaining half again with `depot add`.

When planting runs out of wheat seeds, it withdraws enough to fill available main-inventory
space, limited by scanned chest stock. Seeds carry across planting sections; refills are
not capped by the current chunk's planting targets. Existing unrelated items stay in place.

A route to a chest that is at least 32 blocks higher or lower first travels at the current height
to the chest's column, then climbs or descends. That staging leg may use a third of the route's
search nodes. When the column is closed at the current height, as it is below a chest standing on
the roof above the stacked farm planes, the route stages at the nearest cell it reached within four
blocks of the column, or otherwise plans the rest directly from where it is.

If the searches still find no route to a chest during a restock, that chest is skipped. Nothing
was opened or moved there, its stock stops counting until it is scanned again, and the shortage is
planned again from the other scanned chests. The status's last error names the skipped chest. A
restock pauses after it has skipped three chests. Movement input, lost flight, server corrections
and other interference still pause straight away.

Planting finishes visible cells from each hover position. Rows up to three blocks apart are
planted together as one strip, strips alternate direction, and a move aims up to four blocks
past the first unfinished cell of its strip so several new cells come into reach at once.
Upper-face farmland clicks use the lower soil outline, and the configured interaction interval
elapses during movement and acknowledgement, for block placement as well as tilling and
planting. Flight moves at up to 0.5 blocks per tick horizontally and 0.375 vertically, the
vanilla flying speeds, and routes can skip up to eight grid waypoints when the complete shortcut
is freshly clear and loaded. Exact reach, collision, and single-placement confirmation checks
remain in place.
See [`design/planting-performance.md`](design/planting-performance.md) and
[`design/tilling-performance.md`](design/tilling-performance.md) for measurements and limits.

## Shop purchases

The mod buys nothing unless `config\schematic-supervisor\shop.json` says `"enabled": true`.
Without it, every material comes from registered chests, a shortage pauses with "Registered depots
cannot satisfy the exact material shortage", `/schematic-supervisor buy-dirt` is refused, and
`buyMaterialsInPlace` has no effect. Change the file only while Minecraft is closed. The profile
installer writes it with `--shop-purchases`, and the mod updater adds `{"enabled": true}` to a
profile installed before the file existed, so that profile keeps buying as before.

With purchases on, dirt uses the fixed flow below, which finds the shop's `Blocks`, `Dirt`, and
`Buy Stacks` buttons by their words. Glowstone and birch planks use exact routes whose menus
default to the layout they were captured from. Another server's shop can be described with these
keys; any key left out keeps the captured value:

```json
{
  "enabled": true,
  "command": "shop",
  "mainTitle": "Shop | Economy",
  "category": {"label": "Blocks", "item": "minecraft:grass_block", "slot": 11},
  "pageTitle": "Blocks (Page {page}/{pages})",
  "pages": 5,
  "nextPage": {"label": "Next page →", "item": "minecraft:paper", "slot": 50},
  "mainMenu": {"label": "Main Menu", "item": "minecraft:birch_door", "slot": 45},
  "buyingTitle": "Buying {name}",
  "buyingProductSlot": 22,
  "buyStacks": {"label": "Buy stacks", "item": "minecraft:yellow_stained_glass_pane", "slot": 35},
  "stacksTitle": "Buying stacks of {name}",
  "products": {
    "minecraft:dirt": {"name": "Dirt", "page": 1, "slot": 11},
    "minecraft:birch_planks": {"name": "Birch Planks", "page": 2, "slot": 20},
    "minecraft:glowstone": {"name": "Glowstone", "page": 4, "slot": 10}
  }
}
```

`command` is sent without the slash. Titles, labels, items, and slots must match the shop exactly;
slots count from 0 in the top-left corner. `products` lists where each product sits; a product on
the first page also identifies that page when the shop returns there after a purchase. The stack
menu must offer `Buy N stack(s)` options priced at the unit price times 64 times N. Anything else
in a menu blocks the purchase, which pauses the build with the reason. An invalid file stops the
mod from starting, with the problem in the log.

## Dirt restocking

Dirt restocking runs automatically when construction needs dirt and registered chests can't supply
it, while shop purchases are on. It opens
`/shop`, clicks `Blocks`, then `Dirt`, then `Buy Stacks`, and buys the largest
available stack quantity that fits the currently empty main-inventory slots.
It rechecks capacity before every purchase and waits for the exact inventory
increase before another click. Existing items are preserved; partial dirt
stacks, armor, and offhand slots are not counted as empty slots. Glowstone and birch planks come
from registered chests first and from the shop only when scanned stock runs out (see
[Material purchases](#material-purchases)). With `buyMaterialsInPlace` enabled, all three are
bought where the player is (see [Inventory and restocking](#inventory-and-restocking)).

Use `/schematic-supervisor buy-dirt` to refill separately while supervision is
paused or stopped, including before a plan is loaded. Close other inventory
menus and wait for depot scans before starting. **Pause** and **Stop** cancel
shopping. If a purchase was already sent, cancellation waits for its inventory
acknowledgement and sends no further purchases. Missing or changed shop buttons,
insufficient funds, and unacknowledged purchases stop the sequence and report
an error; a failed automatic refill pauses construction. Inspect the reported
issue before explicitly resuming. An uncertain purchase blocks new automation
and Reset while the mod watches for a matching late inventory acknowledgement;
it never retries the purchase. If no acknowledgement arrives, inspect the
inventory and restart the client before continuing.

## Pause, Stop, Unload, and Reset

Use **Pause** for a resumable interruption. **Stop** ends the current run; the
next Start runs the build check and continues from the first unfinished piece. **Unload** releases
an inactive, settled build and preserves its checkpoint for later selection. **Reset** discards the
checkpoint and loaded plan but preserves registered depots. Disconnecting
or closing the client automatically saves a paused checkpoint. Resume is
rejected unless the opaque server/save identity and dimension still match.
If the client closes while a cancelled depot transfer is still awaiting its
final inventory acknowledgement, the checkpoint is marked for reconciliation
and Start/Resume remain blocked. Use **Reset** after inspecting the inventory;
the next run rescans the selected placement and registered depot stock instead
of trusting the interrupted transfer ledger.

If Baritone control release cannot be proven, all automation stays frozen and
the safety settings remain active. **Reset** retries every stop action and is
rejected unless all of them succeed. If an accepted depot screen response times
out or its context or identity becomes uncertain, Reset cannot discard that
pending response. Wait for the exact response to be observed and safely closed,
or restart the client before continuing.

If Reset reports an incomplete teardown, the loaded plan has already been
detached and all run or depot-mutation work remains blocked. Correct the
reported adapter, settings, or file-cleanup problem and run **Reset** again;
cleanup is idempotent and registered depots remain preserved.

## Completion and recovery

A run reaches `DONE` only after:

- every chunk intersecting the selected build volume is loaded and checked;
- no required block is missing or incorrect;
- no unexpected block remains inside the bounded build volume;
- no temporary scaffolding remains;
- farmland moisture and wheat age are the only ignored properties; and
- two consecutive full-volume passes produce the same normalized fingerprint.

Pausing, failing a verification pass, or reopening an unfinished run discards
earlier final-verification evidence. Resume requires two fresh consecutive
passes before the run can reach `DONE`.

If progress stalls, the core stops movement, checks lag/material state, retries
the current path once, attempts a confirmed return to the last safe position,
and only then asks the optional companion. An unavailable or invalid companion
response results in a safe pause and operator notification, with two exceptions:

- **Lost flight.** When flight the build relies on switches off while the server still allows it
  (for example after landing on the roof), recovery first takes off again, the same way
  `/schematic-supervisor takeoff` does, and then restarts the current piece from where the player
  hovers. This happens before any lag wait, so a player whose flight dropped mid-air stops falling.
  It is tried once per incident: confirmed placing or planting renews it, a second landing before
  that does not. If the takeoff fails or the server no longer allows flight, recovery continues as
  above and pauses. The status shows "Flight was restored automatically" after the original cause.
- **Transient causes.** A cause that comes from lag, slow chunk delivery, or a moving entity (the
  server not confirming a change in time, a route segment obstructed or unloaded, no confirmed
  progress, a chunk that did not arrive, an entity standing on a target) is retried after 30, 90,
  and then 270 seconds before the build pauses. Each retry starts the current piece again with fresh
  recovery attempts, and the state stays `STUCK` while it waits; the status ends with "retrying
  automatically in N s (k of 3)". Confirmed placing or planting resets the count. Causes that need
  you, such as movement keys, a server correction, an unplanned block on a target, missing
  materials, or reconciliation, still pause straight away, and an advisor's advice to pause is
  always followed.

Start and Resume also take off by themselves when the player is standing and the server allows
flight, then start or resume once the player is airborne. Pause or Stop during that takeoff cancels
it. When the server has not granted flight, Start and Resume stay unavailable and the status says so.

Checkpoints save the takeoff and retry stages as `STOP_MOVEMENT` and `ADVISOR_WAIT`, which older
mods can read. Like every recovery stage, they are not resumed after a restart: the build loads paused.

When flight is active, chest access and construction use bounded routes around
loaded obstacles. They retain flight, stop when it is lost, and
confirm block changes before recording consumption. Each leg ends with the player at rest:
what vanilla flight leaves of the last step is cancelled, so a descent that stops just above a
floor, such as the roof, hovers there instead of landing, which would switch flight off. A
missing placement anchor pauses construction; the builder does not create unplanned scaffolding.

Shopping, when [turned on](#shop-purchases), is limited to the dirt flow above and the Birch
Planks and Glowstone routes described under [Material purchases](#material-purchases). Crafting,
selling, public warps, granting flight permission, general scaffolding, and other server
commands are outside the current automation.

## Settings

`config\schematic-supervisor\settings.json` is created with defaults on first launch. Change it
only while Minecraft is closed. The profile installer sets several of these options for you; see
[`profile.md`](profile.md#install-the-profile).

| Setting | Default | Effect |
| --- | --- | --- |
| `minimumFood` | `1` | Food items kept in reserve. Set `0` on a server where food is unnecessary. |
| `deferPlanting` | `false` | Build, light, and till, but leave the wheat unplanted. |
| `glowstoneAfterStructure` | `false` | Finish every structural layer before the Glowstone layers. |
| `buyMaterialsInPlace` | `false` | Buy missing Dirt, Glowstone, and Birch Planks from the shop where the player is, and pause automatic depot scans and withdrawals. Needs [shop purchases](#shop-purchases) on. |
| `autoRepairHoes` | `false` | Repair worn hoes, and admitted axes and shovels, with the server's `/fix` command. |
| `discardSurplusDirectly` | `false` | Discard plain pickups instead of storing them in registered chests. |
| `discardSurplusWhenStorageFull` | `false` | Allow discarding plain pickups once every registered chest is full. |
| `interactionCooldownTicks` | `4` | Minimum ticks between placement and planting clicks; also the retry backoff. |
| `tillInteractionCooldownTicks` | `2` | Minimum ticks between tilling clicks. |
| `verificationBlocksPerTick` | `20000` | Blocks read per tick by the build check and final verification. |
| `placementBlocksPerTick` | `20000` | Blocks processed per tick while loading the placement and scanning build slices. |
| `pathGoalRadius` | `3` | Navigation goal radius in blocks (1 to 8), for chest approaches and build movement. |
| `controlPort` | `8765` | Loopback port of the control and observation API. |
| `companionUri` | `http://127.0.0.1:8766` | Loopback address of the optional incident companion. |

When `minimumFood` changes to `0`, the loaded checkpoint keeps its layer progress and removes
only the obsolete food reserve.

## Files the mod keeps

On first launch the mod creates:

- `config\schematic-supervisor\settings.json`
- `config\schematic-supervisor\depots.json` with opaque server/save and dimension
  binding for every registered chest
- `config\schematic-supervisor\builds\<build-identity>\checkpoint.json` after a new build begins
- the matching `run-context.json` with an opaque server/save hash and dimension
  binding; existing root checkpoint/context pairs are preserved for their original build

The depot registry uses schema version 2. A legacy schema-version-1 registry has
no safe world identity, so it is rejected rather than migrated. Back up and
remove that old `depots.json`, then register the intended chests again.

Operations that must survive a restart keep their own journals beside the checkpoint:
`material-purchase.json`, `moss-deposit.json`, `surplus-disposal.json`,
`temporary-support.json`, and `hoe-repair.json`. Keep them with the checkpoint, run context,
and depot registry. An unresolved journal blocks new work until its original operation is
reconciled; never delete one to retry.

## Local connection and pairing

The companion and control services bind to `127.0.0.1` only. On first launch
the mod generates `config\schematic-supervisor\protocol-token.txt`; the agent
bridge reads this local pairing file without putting the secret into connection
configuration or logs. `SCHEMATIC_PROTOCOL_TOKEN` remains an environment override.
Launch the optional desktop companion with `--token-file PATH` to use the same
pairing file. Loopback Pause and Stop remain available without authentication;
observations and expanding controls require the configured token. Control request
IDs are one-use and timestamps expire after 30 seconds.

Controls waiting on the client thread also expire if they cannot start within
three seconds. Expired queued controls are cancelled before they can execute.
If a control has already started when its response times out, its outcome is
unknown; check the current status before retrying Start or Resume.

## Behavior reference

These notes describe the mod's mechanics in detail, for diagnosing a build and for development.
The code and its tests are authoritative where the two differ.

### Building while a menu is open

Every step continues while viewing the player inventory, the game (Esc) menu, supported settings
pages or chat, and while Minecraft is unfocused, provided the player inventory handler remains
active and the cursor holds no item. This includes depot scans and routes to registered chests, so
the scans queued after joining no longer hold Start and Resume until the page closes. Merely opening
one of these pages does not issue a supervisor Pause. Containers, unknown screens or an item on the
cursor stop movement and interactions until they close; takeoff and paused approaches stop on them
too.

Inventory viewing allows work from the available hotbar, and refills behave as they do in gameplay.
A refill rechecks the player handler, empty cursor, exact source/destination slots, take/insert
permissions and capacity. It prefers an empty hotbar slot, then swaps out a plain build supply or
pickup; tools and special items stay in place, and the refill waits if no permitted destination
exists.

A chest, shop menu or disposal inventory replaces the current page, so the mod first leaves an
unchanged idle Inventory or Settings page: when required active restocking starts, when a route
reaches its registered chest, and before a `/shop` command. It rechecks the connected context,
player handler, empty cursor/crafting slots, released mouse buttons and, for restocking, the absence
of pending receipts or competing operations. Mouse buttons are read from the window's physical
state. Vanilla's own click flags change only while no screen is open, so a click that opens a
chest would otherwise count as held until the next click in gameplay. It does not close Chat,
containers or unknown pages; a route that reaches its chest while one is open waits there without
using route time. Blocked screens continue waiting deterministically without a model call, and the
restock and depot status name the condition that holds the page.

The mod remembers the page it left. Once no restock, shop, pickup, disposal or depot work remains,
it reopens that page: a new Inventory, as the inventory key opens one, or the same game-menu or
settings screen, as a parent screen returns. It forgets the page instead after a world or player
change, movement, attack or use input in gameplay, or when another screen is open by then.

A narrow render hook suppresses only the automatic game menu caused by losing focus during active
work, depot scans, takeoff or approaches, allowing later restocking to proceed while unfocused.
Explicit Escape and settings pages still open normally; the mod may later leave an eligible idle
page only through the guarded path above. Supervisor Pause or Stop prevents the active-restock
transition.

While the active builder is eligible to work, and while depot scans, takeoff or approaches run, the
mod bypasses vanilla singleplayer menu pause and uses an inactive/minimized frame-limit floor of 20
FPS to keep client ticks moving. This is a runtime adjustment, not an edit to saved game options.
Use supervisor Pause or Stop to stop building and restore vanilla pause and frame-limit behavior.
Rendering or system load may still reduce actual frame rate below the configured floor.

### Placement anchors

Placement prefers exact faces reachable from the current pose before choosing by distance. It
keeps the full candidate scan and nearest-distance fallback, shares read-only interaction-arrival
predicates with navigation, and retains final placement checks, cooldowns, flight bounds and
receipts. The anchor policy accepts horizontal faces of exact Farmland blocks while rejecting their
top and bottom faces; exact outline-ray, player-body collision and placement-context checks still
apply. Cached failed faces are invalidated only after a confirmed Moss-to-AIR change.
Unsupported-target diagnostics report the target coordinates, six received neighbor block IDs,
bounded failure counts, and the last rejection.

### Clearing tools and hoe repair

At an exact planned Glowstone cell, ordinary flight execution may replace an existing jack
o'lantern after acquiring the replacement. It freezes the target's complete state, including
facing. It can select a safe existing axe; otherwise it uses a harmless plain replacement or empty
hand. The selected-volume, current-order, received-chunk, entity, fluid, block-entity, ray and
input guards still apply. Jack o'lantern removal has a 240-tick deadline; Moss retains 100 ticks.
Only acknowledged AIR with no pending prediction completes removal, with zero Glowstone credit; the
following placement needs its own world/inventory receipt.

The current clearing selector ranks eligible existing hoes for Moss, axes for authorized light replacement and shovels for journal-owned Dirt support cleanup. Eligibility requires a comparable exact tool identity, mining multiplier greater than 1 and one durability per block. Effective mining attributes determine ranking when all eligible candidates have current values; if any are unavailable or stale, every candidate uses its base multiplier for that selection. Tool suitability never grants permission to remove another block. Main-inventory recovery remains optional and uses only an empty hotbar destination; no clearing tools are purchased. Ordinary refills prefer empty slots, then exact plain supplies or pickups, including while the Inventory is visible. Tools and special items are not displaced; if no permitted destination exists, the refill waits.

Before each selected work order, a bounded stem sweep covers its chunk from the selected volume's
minimum Y through one cell above the highest order target, clipped to the volume. Only
pumpkin/melon stems and their two attached variants may be removed from source AIR or Wheat cells.
These four stems may also be cleared at exact ordinary replacement targets under the existing
material guards. The attempt freezes the full observed state and requires received data, an exact
ray, a harmless hand and acknowledged AIR with zero material credit. Short or multipart stems use an
endpoint inside an actual outline cuboid while retaining first-hit and reach checks. Navigation may
first move clear of an overlapping stem; full occupancy guards apply at arrival, before START and
during mining. The stem-only hand allowlist accepts empty or plain Dirt, Glowstone, Birch Planks,
pumpkin seeds, melon seeds, Moss and jack o'lantern; tools and custom-component items remain
excluded. This adds no inventory shuffle, right-click, planting or general vegetation clearing.

Recovery reuses the exact-source SWAP with a player inventory handler, empty cursor and an allowed
screen. It is unavailable during a pending repair or owned mining/interaction receipt. Source and
destination must still belong to the player inventory, the source must be unchanged and removable,
and the empty destination must accept its exact stack within capacity. An unavailable optional
recovery keeps the harmless-hand fallback before dispatch; an unexpected post-transfer identity
mismatch stops safely. Identity, wear allowance and repair binding remain attached to the recovered
tool. The transfer uses existing client prediction and adds no durable server receipt protocol.

Mining and repair identities share one narrow normalization: only an exact BYTE 1 legacy marker
plus matching nonnegative INT `Damage`, after a complete bounded traversal, permits changing that
duplicate to INT 0 on a comparison copy. Every other NBT tag, including empty containers, and every
other item component remains part of exact identity. Missing zero damage is not inferred.
Recognized inconsistent or unsupported metadata cannot be admitted as a new mining/repair identity;
before a repair command it produces WAITING under the ordinary finite progress deadline. Ordinary
unrecognized metadata retains exact comparison. Tracking is capped at 36 identities.

A conservative allowance is charged before every attempted owned clearing-tool break, including
rejected attempts; local or delayed damage updates cannot replenish it. Identical tools share the
allowance across slot changes, retaining reserve `min(64, max(1, maximum / 10))`. During an owned
break, the same slot and normalized item components must remain, accepting only a damage delta of
0 or 1. When the allowance is exhausted, selection falls back to plain-hand clearing.

The wear guard attaches each START charge to its interaction receipt. Successful AIR with no pending prediction settles that exact token once, without an immediate refund. Pending charges block refresh; unknown, failed or aborted charges permanently disable refresh for that identity. Only an already planned depot opening can establish a new allowance baseline: execution freezes a clean complete cohort ticket before opening, then requires the exact new handler's applied full contents, empty cursor, unchanged cohort and no intervening mining or invalidating action. An ordinary scan or withdrawal uses its initial receipt before transfer. The current pickup-storage integration instead uses the exact durably confirmed transfer's receipt reopen, before any next transfer. Allowance becomes at most the minimum remaining durability across all matching main-inventory hoes. Replayed openings, incomplete coverage and merely newer slot packets grant no credit. No extra chest openings or repair commands are added.

Pickup storage captures its ticket only before a fresh opening with no pending deposit, then binds
it to the exact operation, original observation, plan, confirmed receipt and current inventory
baseline. Initial unconfirmed windows, restart-loaded pending deposits, disposal reopens,
reconciliation barriers and uncertain confirmation writes cannot replenish allowance. Invalid
custody and refresh-disabled identities remain ineligible. This closes the missing path when
pickups go to a depot but replacement Dirt comes from the shop; the shop is not a trusted depot
boundary.

The custody owner survives execution-object replacement. Narrow scopes authorize only the exact
automatic click, block interaction or mining packet during its synchronous call. Unowned actions,
unexplained tool-cohort changes, unavailable established facts or context changes permanently
disable optional hoe STARTs; Resume and reconnect do not rearm it. Diamond hoes in crafting,
cursor, armor, offhand or other uncovered equipment positions also disable the optimization. An
exact automatic main-inventory SWAP keeps optional hoe use and refresh pending until matching
applied slot receipts or an already-owned full depot receipt confirms the outcome. That resolving
opening cannot grant allowance if it had no clean ticket before opening. This receipt check adds no
inventory actions or durable transfer journal.

At actual reserve wear with repair enabled, the mod retains the existing durable `/fix`
confirmation path and navigation `ARRIVED` while waiting. The original matching confirmed repair
settles its optional binding once but grants no Moss allowance, so repairing one hoe cannot erase an
identical spare's unknown wear debt. Only a later eligible full-cohort depot baseline can restore
credit. There is no repair trigger solely for conservative allowance exhaustion. Tilling keeps its
actual-wear repair rule.

Hoe upkeep uses the server's `/fix` command on existing hoes; the mod never buys replacement hoes.
The `autoRepairHoes` setting enables it. New profiles default to false because `/fix` is
server-specific. Use `--auto-repair-hoes` with the profile installer for a server where the command
is supported; `--no-auto-repair-hoes` disables it, and omission preserves an existing setting.
Change settings only with the client closed.

Before tilling, the mod checks the selected usable hoe. For a damageable, non-unbreakable hoe it starts repair when remaining durability is at most `min(64, max(1, maximum / 10))`. It synchronizes the selected hand, saves intent before one `/fix`, and waits for a later applied server inventory packet proving the same tool in the same slot has damage 0. It does not repair by altering local item damage. Other item components remain part of the tool identity. The same durable repair path now covers admitted existing axes and shovels used by the clearing selector, under the existing `autoRepairHoes` setting; it does not buy replacement tools.

Preserve `config/schematic-supervisor/hoe-repair.json` with the checkpoint, context and other
transaction journals. An active repair wait has a 100-tick receipt deadline. Missing permission,
cooldown, changed tool/slot, disconnect or uncertain dispatch retains the pending intent and
prevents automatic command replay; inspect the original receipt instead of deleting the journal to
retry. This upkeep wait does not itself count as stalled construction.

### Inventory and restocking

With `buyMaterialsInPlace` enabled, missing Dirt, Glowstone and Birch Planks are bought through the
bounded shop routes at the player's current position; automatic depot scans and supply withdrawals
are paused, while the registry and saved build records stay intact. Approved plain surplus is then
cleared after about 2,400 active building ticks (roughly two minutes) or when fewer than eight main
inventory slots are free, and refills reserve up to 12 slots for pickups where possible.

Glowstone requests up to 64 when its usable inventory is exhausted. The reserve combines the
current slice's scanned missing cells with upcoming plain Glowstone cells in the same exact
lighting stage, capped by remaining planned demand, observed depot stock and compatible inventory
capacity after other required slots. It never reserves from later lighting layers. Existing exact
withdrawal and placement accounting remains unchanged; future scheduled cells are a demand bound,
not observed missing blocks.

The registered-chest hand allowlist accepts the seven exact plain build supplies/pickups only with
the default main-hand action on a received registered vanilla chest, no sneaking or block-use
bypass, the player handler and an empty cursor. Plain wheat seeds are also permitted for this
interaction. Full-hotbar material selection prefers empty space, then the selected plain supply,
then another plain supply; tools, keys and custom items stay in place. Visible Inventory screens and
optional tool recovery retain empty-slot-only destinations, and the SWAP rechecks both slots and
capacities. A saved one-unit Glowstone fallback may be refreshed once after complete idle depot
scans, at zero Glowstone inventory and before withdrawal, with the same stage/plan/stock/capacity
caps. An already refreshed request cannot grow again, before or after a reload.

Initial registered-depot scans and withdrawals wait for an applied full server-content packet
matching the exact owned chest handler, sync ID, expected row count, all chest/player slots and
empty cursor. The same world, player, connection and accepted window remain bound while waiting or
cancelling, within the existing 60-tick deadline. The mod counts stock only after that gate
succeeds; a missing or mismatched packet does not establish empty stock.

Depot transfers read the cursor count before `clickSlot` synchronously changes the stack.
`DepotTransferPolicy` also settles pending input and returns a known unused cursor before scanning,
closing or completing an allocation. Unknown cursor ownership blocks completion. Acknowledgements
can include local prediction; depot transfers have no durable server receipt protocol. A clean
capacity rejection before any transfer click returns to bounded inventory recovery instead of
pausing the build; an uncertain transfer still pauses.

The mod reports all 36 main inventory slots, offhand, armor, general item durability, purchase and moss-deposit receipts, depot stock, and execution target/acknowledgement facts. Armor uses player-inventory indices 36 feet, 37 legs, 38 chest and 39 head; these are not GUI slot numbers. General `durability` retains the existing `hoe_durability` field for hoe consumers. The `normal_material_capacity` map independently measures compatible main-inventory capacity for Dirt, Glowstone, Birch Planks and Wheat Seeds using handler insertion permissions, limits and component equality. Each estimate can use the same empty slots, so do not sum the values. Unavailable capacity evidence is `null`, not zero, and observing seed capacity does not authorize seed purchases or planting.

Automatic Dirt restocking now chooses feasible scanned depot stock before the shop, including partial depot supply. Incomplete depot scans cannot authorize shopping. If a shop refill is needed, its target is capped by remaining planned Dirt demand and explicit current temporary-support needs, with whole-stack rounding and room reserved for other current shortages. Every purchase batch rechecks its space and remaining target. `shop.target_stacks` and `shop.reserved_empty_slots` expose these limits. The explicit manual `/schematic-supervisor buy-dirt` command continues to fill available main slots; it does not use the automatic plan-demand cap. Dirt's pending purchase receipt remains process-local, so preserve unresolved state and do not infer restart-safe Dirt delivery from the separate Birch/Glowstone journal.

An exactly acknowledged Dirt purchase completes before another purchase route is evaluated, and
delayed contents of the same returned Blocks menu receive a bounded wait and verified cleanup.

### Pickup storage and optional overflow

`discardSurplusDirectly` temporarily bypasses pickup deposits and storage-capacity checks. It defaults to `false`; the profile installer accepts `--discard-surplus-directly` and `--no-discard-surplus-directly`. When enabled, a refill can discard the largest exact plain Moss Block, Pumpkin Seed, Melon Seed or Jack o'Lantern stack. Tools, keys, custom items and construction materials remain protected. One registered chest is opened for inventory evidence before disposal and again for confirmation afterward; it receives no pickup deposit. Material withdrawals still use registered depots. Each stack requires its own durable intent, verified drop location and later inventory receipt. Existing pending deposits or discards must settle first. Setting the option back to `false` restores storage-first behavior and leaves the separate overflow preference unchanged. Version-2 disposal journals explicitly record `DIRECT` or `STORAGE_FULL`; legacy version-1 receipts remain supported.

With `buyMaterialsInPlace` also enabled, disposal needs no chest: the mod opens `/shop` and the
first Blocks page only to obtain a full inventory snapshot, never selecting a product there, then
closes the shop, opens the player inventory, permits one guarded discard, and waits before
requesting the next server receipt. A cleanup batch has at most 36 single-stack operations, each
with a durable intent and an exact full inventory receipt before the next. When the evidence expires
or the inventory changes before dispatch, the mod takes at most two fresh full inventory receipts;
stale full inventories get two bounded read refreshes without replaying a throw. Idle Inventory and
Settings pages can be left for these receipts. An interrupted cleanup resumes through two stable
server inventories, keeping the original unverified discard with zero credit and no replay.
Automatic recovery admits only plain surplus drift, and recovered intents are archived before later
cleanup. Pending intents still require reconciliation.

Pickup storage stores only exact default-component Moss Blocks, Pumpkin Seeds, Melon Seeds and
Jack o'Lanterns. During an active Dirt shortage, after existing build/shop/depot receipts settle,
it tries registered chests first, with at most eight confirmed transfers per restock attempt. It
uses compatible partial-stack room as well as empty slots; one remaining compatible space prevents a
chest from being treated as full. Tools, keys, named or other custom-component items, Dirt,
Glowstone, Birch Planks and Wheat Seeds are protected. Keep registered depots accessible. Aggregate
material stock does not prove storage capacity.

Each storage transfer saves one durable intent before one click, then closes and reopens the same chest. A later full server inventory packet must prove the exact same-item decrease in the source, any exact source remainder, the equal chest increase, an empty cursor and unchanged protected slots. The server receipt covers chest slots, all 36 main slots and cursor; offhand and armor remain additional local unchanged guards. Storage never changes construction-material consumption or withdrawal totals. A timeout, unknown chest, missing receipt or the eight-transfer limit is not proof that storage is full.

Overflow disposal is a separate explicit opt-in: `discardSurplusWhenStorageFull` defaults to `false`. The profile installer accepts `--discard-surplus-when-storage-full` to enable it and `--no-discard-surplus-when-storage-full` to disable it. With the option enabled, only the same four plain pickup types may be discarded, after fresh complete evidence shows zero compatible space across every registered chest and inventory capacity still prevents the Dirt refill. It never authorizes selling or discarding protected items. The route may pause if no received, clear drop location can be verified. With the option disabled, surplus stays in inventory and an unresolved capacity shortage pauses the build.

Preserve `config/schematic-supervisor/surplus-disposal.json` when present. It binds a single discard to the original player UUID, world, plan, registered depot, exact inventory and fresh storage proof. Pending receipt travel stays above the verified falling-item column until outside it, including intermediate flight segments. A later full chest receipt confirms inventory removal; it does not claim that item destruction was observed. Updates and both reconciliation utilities preserve this journal and refuse unresolved or malformed state. Turning the preference off cannot erase a pending outcome or authorize another discard.

Keep `config/schematic-supervisor/moss-deposit.json` with the original checkpoint, run context and depot registry. Existing version-1 Moss intents remain readable without changing their original item evidence or packet stamps. A pending intent takes priority even if Dirt is replenished or the source slot changes; it blocks new transfers, purchases, Reset and Unload until reconciled. Pause and Stop cancel input and preserve pending settlement, including the original paused restock checkpoint. After a process or connection change, a fresh observation establishes a barrier and another full reopen must satisfy the original receipt. An uncertain storage or discard operation is never repeated automatically. Preserve its journal and reconcile the original outcome instead of deleting it to retry.

### Material purchases

Birch Planks and Glowstone have deterministic automatic shop routes taken from one server's shop
catalog: `/shop`, Blocks, page 2 for Birch Planks or page 4 for Glowstone, then the product's Buy
stacks menu. The mod still validates every current menu, exact product identity, explicit quantity
and price; a changed route pauses instead of guessing. Registered-depot stock is used first. An
actual restock shortage with no scanned stock can buy at most nine whole stacks per click, bounded
by remaining planned material demand and observed compatible main-inventory space. Whole-stack
rounding may add at most 63 units beyond remaining demand. Space needed by other current material
requirements is reserved. This does not introduce future seed, food or tool demand. Hoe purchases
are excluded; hoes are repaired with `/fix` instead.

For these two products, keep `config/schematic-supervisor/material-purchase.json`, when present, with the original checkpoint and run context. The mod forces one purchase intent to disk before clicking a quantity. It waits for applied server evidence that the owned cursor is empty before closing its menu, then reopens the shop and requires a later full server inventory packet with exactly the quoted plain-product increase and unchanged protected main slots. Offhand and armor are separate local guards. Cursor acknowledgement alone is not a delivery receipt, and item delivery does not prove a currency debit. Pending or uncertain purchases block new purchasing, building, Reset and Unload; Pause preserves the intent, and Stop retains the original paused restock checkpoint while a receipt is pending. Restore the same world and plan and Resume to reconcile without repeating the purchase. A new process or connection first records a reconciliation barrier, then requires another later full reopen. Preserve unreadable or unresolved journals rather than deleting them to retry.

If the server returns to the first Blocks menu after a purchase, the receipt reopen is permitted
only after exact inventory and menu validation. Dirt uses its own separate purchase implementation;
the durable purchase journal does not cover Dirt transactions.

### Temporary supports and deferred planting

Keep the profile-wide `config/schematic-supervisor/temporary-support.json`, when present, with the checkpoint and run context. It records ownership of the two temporary dirt blocks used to start a separated structural plane. The mod removes those supports from top to bottom and records the planned structural block exactly once through the checkpoint. Checkpoint version 4 adds that material-credit record; version 3 layer checkpoints migrate without losing progress. Do not restore an older jar over a version 4 checkpoint or restore only one file from an interrupted support operation.

An unfinished support journal blocks Reset, Unload, and selecting another build until its original work is settled. Pausing preserves the journal. Restarting may clean up confirmed owned supports, but a placement whose receipt was lost remains uncertain; the mod will not infer permission to remove a block from its appearance alone. Temporary supports are restricted to cells that are AIR in the original schematic, including when planting is deferred.

The current completion rule still requires the complete selected footprint to pass two fresh matching verification scans, including air and temporary-support cleanup. Verification waits up to 15 seconds for a locally predicted block to receive server acknowledgement before reading it; an unresolved prediction cannot count as a completed scan. A copied file or successful installation is not proof of a completed build.

With deferred planting enabled, source wheat cells may remain air or contain existing correct wheat, with crop age normalized as usual. Other obstructions still fail, and unrelated source air cells must remain air. The mod places no new seeds and does not remove existing crops merely because planting is deferred. Status and completion explicitly report `planting_deferred` and the number of deferred seed cells; `DONE` in this mode means construction and tilling passed verification with planting deferred, not that the crops were planted. The saved build manifest includes the mode setting.

### Diagnostics

Failed moss-clearing checks retain `execution.last_obstruction`: the capture time, exact failed target, received-block state, player overlap, and up to eight nearby entity types with collision facts and dropped-item IDs/counts. These are historical observations, separate from block-interaction receipts. `source_world_matches` describes the capture, while `current_world_matches` indicates whether it belongs to the currently connected world/player instance. Missing or unreceived facts remain null. Recovery does not erase the failed target.

During normal Dirt restocking, `shop.menu_history` retains the last four accepted menus with capture times and up to 54 slot/item/count entries each. Capturing history performs no extra clicks and is not authority for a later purchase. The fixed Dirt route still requires current menu validation, capacity, and the exact inventory increase before another purchase. Routine disconnect/context-change cleanup clears this history; oversized telemetry trims it before obstruction evidence.

`material_shop` observations expose bounded stage, product and receipt state.

`moss_deposit` telemetry retains `confirmed_session_items` as the Moss-only count;
`confirmed_session_pickup_items` counts all four allowed pickup types, and optional `item_id`
identifies the saved operation. Destination, quantity and original/receipt stamps remain receipt
evidence, not permission for another click.

Optional `soil_watchpoints` telemetry samples up to four fixed, received source-farmland cells once per second. It includes exact soil/above states, moisture, bounded nearby water and rain facts, and a short change history. Unloaded cells, pending predictions, gaps and reconnects break continuity. Samples are passive client observations, never proof of server random ticks or full-farm soil stability; they do not alter completion checks or plant seeds.

`execution.last_moss_tool_selection` is a read-only historical snapshot of a selection decision, with its own `captured_at`, target, outcome and `current_context_matches`. It does not change tool selection or wear accounting. `candidate_scope: HOTBAR` limits details to at most nine hotbar clearing tools; main-inventory recovery is not fully enumerated. Only candidates marked `evaluated: true` reached the selector's checks. Their guard facts describe the moment before identity admission, including the rejection reason, tracked identity count/cap, conservative allowance and current durability. Unevaluated candidates are not confirmed rejections, and null facts mean unavailable rather than false.

Compare an early `HOE` snapshot with a later `PLAIN_HAND` snapshot in the same client context: check the evaluated candidates' guard reasons, identity counts and allowance, then compare component IDs and SHA-256 hashes. Fingerprints normalize damage on a copied identity and expose no raw component values. Unsupported or oversized values have null hashes; incomplete fingerprints cannot establish whole-identity equality. Candidate/hash data are bounded and may be truncated or omitted before receipt telemetry under payload pressure. Pair the selection timestamp and target with a current `FLIGHT_CLEARING_MOSS` receipt showing owned mining, selected item and breaking delta; a later observation's timestamp or `last_receipt` alone does not establish the current clearing speed. These diagnostics do not constitute installation or live-validation evidence.

`custom_data_evidence` separates the current local metadata probe from `latest_applied_slot_receipt`. Leaf records contain bounded metadata paths, NBT types and hashes, with a damage-equality flag only for integral values; they expose no raw values. Complete leaf coverage is not a whole-NBT identity proof: empty container structure is not represented. A packet stamp proves past application. It represents the current stack only when `exact_current_match` is true. Compare later sequences within the same epoch and client context, and retain the earlier probe with the exact stamp named by `previous_applied_slot_receipt`. Its `other_components_equal` fact compares all remaining components and count exactly after excluding DAMAGE and CUSTOM_DATA on copies; this is observational and does not change identity rules. Null or incomplete facts cannot establish a wear-only transition. Before normalizing a proven field, compare all remaining NBT exactly. A `/fix` transition needs its own later applied receipt, not an inference from ordinary wear.

The legacy-damage identity normalization above uses the proven marker, path and type, while the
leaf probe remains observational. It preserves the complete remaining NBT on the comparison copy
rather than treating matching leaf lists as whole-tree equality, and it does not reset allowance or
enlarge the identity cap. `ALLOWANCE_RESERVE` alone does not prove or exclude unknown debt or
`refreshDisabled`.
