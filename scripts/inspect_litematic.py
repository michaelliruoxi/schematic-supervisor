#!/usr/bin/env python3
"""Inspect gzip-compressed Litematica schematics using only the stdlib."""

from __future__ import annotations

import argparse
import gzip
import json
import math
import struct
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, BinaryIO


class NbtReader:
    def __init__(self, stream: BinaryIO) -> None:
        self.stream = stream

    def read_exact(self, size: int) -> bytes:
        data = self.stream.read(size)
        if len(data) != size:
            raise EOFError(f"expected {size} bytes, received {len(data)}")
        return data

    def unpack(self, fmt: str) -> Any:
        size = struct.calcsize(fmt)
        values = struct.unpack(fmt, self.read_exact(size))
        return values[0] if len(values) == 1 else values

    def string(self) -> str:
        size = self.unpack(">H")
        return self.read_exact(size).decode("utf-8")

    def array_length(self) -> int:
        size = self.unpack(">i")
        if size < 0:
            raise ValueError(f"negative NBT array length: {size}")
        return size

    def numeric_array(self, element_format: str) -> list[int]:
        size = self.array_length()
        fmt = f">{size}{element_format}"
        # Arrays must stay sequences even when they contain only one element.
        return list(struct.unpack(fmt, self.read_exact(struct.calcsize(fmt))))

    def payload(self, tag_type: int) -> Any:
        if tag_type == 0:
            return None
        if tag_type == 1:
            return self.unpack(">b")
        if tag_type == 2:
            return self.unpack(">h")
        if tag_type == 3:
            return self.unpack(">i")
        if tag_type == 4:
            return self.unpack(">q")
        if tag_type == 5:
            return self.unpack(">f")
        if tag_type == 6:
            return self.unpack(">d")
        if tag_type == 7:
            size = self.array_length()
            return self.read_exact(size)
        if tag_type == 8:
            return self.string()
        if tag_type == 9:
            child_type = self.unpack(">B")
            size = self.unpack(">i")
            return [self.payload(child_type) for _ in range(size)]
        if tag_type == 10:
            result: dict[str, Any] = {}
            while True:
                child_type = self.unpack(">B")
                if child_type == 0:
                    return result
                name = self.string()
                result[name] = self.payload(child_type)
        if tag_type == 11:
            return self.numeric_array("i")
        if tag_type == 12:
            return self.numeric_array("q")
        raise ValueError(f"unsupported NBT tag type {tag_type}")

    def root(self) -> tuple[str, dict[str, Any]]:
        tag_type = self.unpack(">B")
        if tag_type != 10:
            raise ValueError(f"expected a compound root, got tag type {tag_type}")
        name = self.string()
        return name, self.payload(tag_type)


def vector(value: Any) -> tuple[int, int, int]:
    if isinstance(value, list) and len(value) == 3:
        return int(value[0]), int(value[1]), int(value[2])
    if isinstance(value, dict):
        return int(value.get("x", 0)), int(value.get("y", 0)), int(value.get("z", 0))
    raise ValueError(f"unsupported vector value: {value!r}")


def palette_label(entry: dict[str, Any]) -> str:
    name = str(entry.get("Name", "unknown"))
    properties = entry.get("Properties") or {}
    if not properties:
        return name
    props = ",".join(f"{key}={properties[key]}" for key in sorted(properties))
    return f"{name}[{props}]"


def bits_per_entry(palette_size: int) -> int:
    return max(2, math.ceil(math.log2(max(1, palette_size))))


def decode_compact(longs: list[int], entry_count: int, bits: int) -> list[int]:
    words = [value & 0xFFFFFFFFFFFFFFFF for value in longs]
    mask = (1 << bits) - 1
    values: list[int] = []
    for index in range(entry_count):
        bit_index = index * bits
        word_index = bit_index >> 6
        offset = bit_index & 63
        value = words[word_index] >> offset
        if offset + bits > 64:
            value |= words[word_index + 1] << (64 - offset)
        values.append(value & mask)
    return values


def decode_padded(longs: list[int], entry_count: int, bits: int) -> list[int]:
    words = [value & 0xFFFFFFFFFFFFFFFF for value in longs]
    per_word = 64 // bits
    mask = (1 << bits) - 1
    return [
        (words[index // per_word] >> ((index % per_word) * bits)) & mask
        for index in range(entry_count)
    ]


def select_decoder(
    longs: list[int], entry_count: int, bits: int, palette_size: int, expected_non_air: int | None
) -> tuple[str, list[int]]:
    candidates: list[tuple[int, int, str, list[int]]] = []
    for name, decoder in (("compact", decode_compact), ("padded", decode_padded)):
        try:
            values = decoder(longs, entry_count, bits)
        except IndexError:
            continue
        invalid = sum(value >= palette_size for value in values)
        non_air = sum(value != 0 for value in values)
        delta = abs(non_air - expected_non_air) if expected_non_air is not None else 0
        candidates.append((invalid, delta, name, values))
    if not candidates:
        raise ValueError("block-state array is too short for either supported packing")
    invalid, _, name, values = min(candidates, key=lambda item: (item[0], item[1]))
    if invalid:
        raise ValueError(f"decoded {invalid} palette indexes outside a palette of {palette_size}")
    return name, values


def summarize_region(name: str, region: dict[str, Any], expected_non_air: int | None) -> dict[str, Any]:
    position = vector(region.get("Position", [0, 0, 0]))
    raw_size = vector(region["Size"])
    size = tuple(abs(value) for value in raw_size)
    volume = size[0] * size[1] * size[2]
    palette = region["BlockStatePalette"]
    labels = [palette_label(entry) for entry in palette]
    names = [str(entry.get("Name", "unknown")) for entry in palette]
    bits = bits_per_entry(len(palette))
    packing, indexes = select_decoder(
        region["BlockStates"], volume, bits, len(palette), expected_non_air
    )

    state_counts: Counter[str] = Counter()
    block_counts: Counter[str] = Counter()
    y_counts: dict[int, Counter[str]] = defaultdict(Counter)
    chunk_counts: dict[tuple[int, int], Counter[str]] = defaultdict(Counter)
    layer_positions: dict[str, dict[int, set[tuple[int, int]]]] = defaultdict(lambda: defaultdict(set))
    min_x = min(position[0], position[0] + (size[0] - 1) * (1 if raw_size[0] >= 0 else -1))
    min_y = min(position[1], position[1] + (size[1] - 1) * (1 if raw_size[1] >= 0 else -1))
    min_z = min(position[2], position[2] + (size[2] - 1) * (1 if raw_size[2] >= 0 else -1))
    invalid_indexes = 0
    for index, palette_index in enumerate(indexes):
        if palette_index >= len(palette):
            invalid_indexes += 1
            continue
        state = labels[palette_index]
        block = names[palette_index]
        state_counts[state] += 1
        block_counts[block] += 1
        y = index // (size[0] * size[2])
        plane_index = index % (size[0] * size[2])
        z = plane_index // size[0]
        x = plane_index % size[0]
        # Container indexes advance from the minimum corner even for negative area sizes.
        offset_x = min_x + x
        offset_z = min_z + z
        norm_x = offset_x - min_x
        norm_z = offset_z - min_z
        y_counts[y][block] += 1
        if block not in {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}:
            chunk_counts[(norm_x // 16, norm_z // 16)][block] += 1
            layer_positions[block][y].add((norm_x, norm_z))

    air_names = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
    non_air = sum(count for block, count in block_counts.items() if block not in air_names)
    per_y = []
    for y in range(size[1]):
        counts = y_counts[y]
        non_air_y = sum(count for block, count in counts.items() if block not in air_names)
        if non_air_y:
            per_y.append(
                {
                    "local_y": y,
                    "world_offset_y": min_y + y,
                    "non_air": non_air_y,
                    "blocks": dict(sorted((k, v) for k, v in counts.items() if k not in air_names)),
                }
            )

    deviations: dict[str, list[dict[str, Any]]] = {}
    for block, layers in layer_positions.items():
        if len(layers) < 2:
            continue
        pattern_counts = Counter(frozenset(points) for points in layers.values())
        modal_pattern, occurrences = pattern_counts.most_common(1)[0]
        if occurrences < 2:
            continue
        block_deviations = []
        for y, points in sorted(layers.items()):
            if frozenset(points) == modal_pattern:
                continue
            block_deviations.append(
                {
                    "local_y": y,
                    "missing_from_modal": sorted([list(point) for point in modal_pattern - points]),
                    "extra_vs_modal": sorted([list(point) for point in points - modal_pattern]),
                }
            )
        if block_deviations:
            deviations[block] = block_deviations

    full_plane_missing: dict[str, list[dict[str, Any]]] = {}
    full_plane = {(x, z) for z in range(size[2]) for x in range(size[0])}
    for block, layers in layer_positions.items():
        entries = []
        for y, points in sorted(layers.items()):
            missing = full_plane - points
            if 0 < len(missing) <= 16:
                entries.append({"local_y": y, "missing": sorted([list(point) for point in missing])})
        if entries:
            full_plane_missing[block] = entries

    return {
        "name": name,
        "position": position,
        "raw_size": raw_size,
        "absolute_size": size,
        "volume": volume,
        "palette_size": len(palette),
        "bits_per_entry": bits,
        "packing": packing,
        "long_count": len(region["BlockStates"]),
        "invalid_palette_indexes": invalid_indexes,
        "non_air_blocks": non_air,
        "block_counts": dict(sorted(block_counts.items(), key=lambda item: (-item[1], item[0]))),
        "state_counts": dict(sorted(state_counts.items(), key=lambda item: (-item[1], item[0]))),
        "non_air_layers": per_y,
        "chunk_grid": [math.ceil(size[0] / 16), math.ceil(size[2] / 16)],
        "per_chunk_block_counts": {
            f"{chunk_x},{chunk_z}": dict(sorted(counts.items()))
            for (chunk_x, chunk_z), counts in sorted(chunk_counts.items())
        },
        "repeated_layer_deviations": deviations,
        "nearly_full_plane_missing": full_plane_missing,
        "tile_entity_count": len(region.get("TileEntities", [])),
        "entity_count": len(region.get("Entities", [])),
    }


def inspect(path: Path) -> dict[str, Any]:
    with gzip.open(path, "rb") as stream:
        root_name, root = NbtReader(stream).root()

    metadata = root.get("Metadata", {})
    total_blocks = metadata.get("TotalBlocks")
    regions = root.get("Regions", {})
    summaries = []
    for region_name, region in regions.items():
        expected = int(total_blocks) if len(regions) == 1 and total_blocks is not None else None
        summaries.append(summarize_region(region_name, region, expected))

    aggregate: Counter[str] = Counter()
    for region in summaries:
        aggregate.update(region["block_counts"])
    air_names = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
    decoded_non_air = sum(count for name, count in aggregate.items() if name not in air_names)

    return {
        "file": str(path.resolve()),
        "file_size_bytes": path.stat().st_size,
        "root_name": root_name,
        "version": root.get("Version"),
        "sub_version": root.get("SubVersion"),
        "minecraft_data_version": root.get("MinecraftDataVersion"),
        "metadata": metadata,
        "region_count": len(summaries),
        "regions": summaries,
        "aggregate_block_counts": dict(sorted(aggregate.items(), key=lambda item: (-item[1], item[0]))),
        "decoded_non_air_blocks": decoded_non_air,
        "metadata_total_blocks_match": total_blocks is None or int(total_blocks) == decoded_non_air,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("schematic", type=Path)
    parser.add_argument("--compact", action="store_true", help="omit per-state and per-layer detail")
    args = parser.parse_args()
    result = inspect(args.schematic)
    if args.compact:
        for region in result["regions"]:
            region.pop("state_counts", None)
            region.pop("non_air_layers", None)
    print(json.dumps(result, indent=2, sort_keys=False, default=str))


if __name__ == "__main__":
    main()
