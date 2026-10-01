# Wheat Farm v2

`wheatfarm_v2.litematic` is the farm the project builds by default. This page lists its measured
contents and the order the mod builds it in.

## Validated schematic facts

- Format: Litematica v7.1, gzip-compressed NBT
- Minecraft data version: 4440
- Regions: 1
- Dimensions: 112 x 76 x 112
- Footprint: exactly 7 x 7 chunks when the minimum X/Z corner is chunk-aligned
- Farm layers: 25 at a three-block vertical pitch
- Metadata total: 495,193 non-air blocks
- Decoded total: 495,193 non-air blocks (exact match)
- No containers, tile entities, water, or Crop Hoppers are included

The region spans relative X/Z coordinates 0 through 111. Its Litematica region starts at `(111, 0, 111)` and extends in the negative X/Z directions, so the placement must be checked with chunk borders before construction.

## Exact material and action conversion

| Schematic state | Count | Real build requirement |
|---|---:|---|
| Moist farmland | 313,600 | Place 313,600 dirt, then perform 313,600 hoe actions |
| Wheat, age 0 | 156,800 | Plant 156,800 wheat seeds |
| Glowstone | 12,250 | Place 12,250 glowstone blocks |
| Birch planks | 12,543 | Place 12,543 birch planks |
| Crop Hoppers | 0 | Add 49 Tier 3 Crop Hoppers separately, one per chunk |

Total planned interactions before movement and restocking: 808,793. Adding 49 Crop Hopper placements gives 808,842.

### Storage quantities

| Material | Total | Stacks | Approx. double chests |
|---|---:|---:|---:|
| Dirt | 313,600 | 4,900 exactly | 91 |
| Wheat seeds | 156,800 | 2,450 exactly | 46 |
| Glowstone | 12,250 | 191 stacks + 26 | 4 |
| Birch planks | 12,543 | 195 stacks + 63 | 4 |

If birch planks are crafted from logs, 3,136 logs produce 12,544 planks, leaving one spare.

## Per-chunk material accounting

The design is almost perfectly identical across all 49 chunks. These are material
accounting totals; execution crosses all chunks at each horizontal stage. Each
normal chunk requires:

- 6,400 dirt (100 stacks)
- 6,400 hoe actions
- 3,200 seeds (50 stacks)
- 250 glowstone (3 stacks + 58)
- 256 birch planks (4 stacks)
- 1 Tier 3 Crop Hopper, placed after validation

One one-block exception exists: central chunk `(3,3)` has one missing roof plank at relative
`(62, 75, 49)`. It should be confirmed visually; it may be an intentional access opening or an
accidental omission.

The original schematic also lacked one wheat plant in chunk `(0,5)` at relative `(0, 19, 90)`, the
only gap in that column's 25 wheat planes. On 2026-09-28 the cell was set to `wheat[age=0]` after
the build check found wheat already growing there, and the metadata total rose to 495,193. The
original file is SHA-256 `451fd8ab…b349`; the corrected one is `23c408f3…9ecd`.

## Layer pattern

For layer number `n = 0..24`:

- Farmland plane: `Y = 3n`
- Alternating-column wheat plane: `Y = 3n + 1`
- Glowstone lighting plane: `Y = 3n + 2`

The final birch roof is at relative `Y = 75`. Wheat occupies every other X column, giving 128 planted crops per chunk per farm layer. Each glowstone plane uses 10 glowstone per chunk.

## Design risks

1. Farmland cannot be placed as an inventory block. The executor must place dirt and then till it.
2. The schematic requests moisture level 7 but contains no water. A one-chunk server pilot must verify that the intended dry-farmland/Crop Hopper behavior remains effective.
3. Crop Hoppers are absent from the schematic. Their 49 final locations must be designed separately.
4. The stacked dirt planes float three blocks apart. Construction is practical with `/fly`; without it, temporary scaffolding and a different navigation plan are required.
5. The full farm needs 313,600 dirt. Check the server's shop price and rules on automated
   purchasing before budgeting or enabling automatic shopping.

## Layer build sequence

1. Position the schematic with its minimum X/Z corner on chunk boundaries.
2. Build the dirt plane at relative Y=0 across all 49 chunks.
3. Build the dirt plane at Y=3 across all chunks, then attach glowstone at Y=2
   to its underside. Repeat for dirt Y=6/glowstone Y=5 up to dirt Y=72/glowstone Y=71.
4. Build the birch roof at Y=75, followed by its glowstone at Y=74.
5. Till the full floor at Y=72, then plant its wheat at Y=73. Repeat top-down
   through every farm floor, ending with tilling Y=0 and planting Y=1.
6. Check all chunks and require two matching full-volume verification passes.
7. Crop Hoppers are outside the schematic and remain a separate placement task.

Each stage spans the whole farm before the next stage begins. Its internal
chunk slices keep movement, inventory batches, and resumable checkpoints bounded.

With `glowstoneAfterStructure` enabled, every structural layer is finished before the Glowstone
layers; see [`../docs/design/structure-first-order.md`](../docs/design/structure-first-order.md).
