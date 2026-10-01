package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Minimal independent NBT reader for the checked-in farm regression fixture. */
final class LitematicSourceFixture {
    record Source(int width, int height, int depth, BlockPosition regionPosition,
                  BlockPosition signedSize, long expectedNonAir, List<BlockState> palette,
                  long[] words, int bits) {
        BlockState get(int x, int y, int z) {
            long index = ((long) y * depth + z) * width + x;
            long bitIndex = index * bits;
            int word = Math.toIntExact(bitIndex >>> 6);
            int offset = (int) (bitIndex & 63);
            long value = words[word] >>> offset;
            if (offset + bits > 64) { value |= words[word + 1] << (64 - offset); }
            return palette.get((int) (value & ((1L << bits) - 1)));
        }
    }

    private LitematicSourceFixture() { }

    static Source read() throws IOException {
        Map<String, Object> root;
        try (DataInputStream input = new DataInputStream(new GZIPInputStream(
                Files.newInputStream(Path.of("schematics", "wheatfarm_v2.litematic"))))) {
            if (input.readUnsignedByte() != 10) { throw new IOException("expected compound root"); }
            string(input);
            root = compound(payload(input, 10));
        }
        Map<String, Object> regions = compound(root.get("Regions"));
        if (regions.size() != 1) { throw new IOException("regression fixture must contain one region"); }
        Map<String, Object> region = compound(regions.values().iterator().next());
        BlockPosition signedSize = vector(region.get("Size"));
        List<BlockState> palette = new ArrayList<>();
        for (Object raw : (List<?>) region.get("BlockStatePalette")) {
            Map<String, Object> state = compound(raw);
            Map<String, String> properties = new LinkedHashMap<>();
            if (state.containsKey("Properties")) {
                compound(state.get("Properties")).forEach((key, value) -> properties.put(key, (String) value));
            }
            palette.add(new BlockState((String) state.get("Name"), properties));
        }
        return new Source(Math.abs(signedSize.x()), Math.abs(signedSize.y()), Math.abs(signedSize.z()),
                vector(region.get("Position")), signedSize,
                ((Number) compound(root.get("Metadata")).get("TotalBlocks")).longValue(),
                List.copyOf(palette), (long[]) region.get("BlockStates"),
                Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1)));
    }

    private static BlockPosition vector(Object raw) {
        Map<String, Object> values = compound(raw);
        return new BlockPosition(((Number) values.get("x")).intValue(),
                ((Number) values.get("y")).intValue(), ((Number) values.get("z")).intValue());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> compound(Object value) {
        return (Map<String, Object>) value;
    }

    private static String string(DataInputStream input) throws IOException {
        return new String(input.readNBytes(input.readUnsignedShort()), StandardCharsets.UTF_8);
    }

    private static Object payload(DataInputStream input, int type) throws IOException {
        return switch (type) {
            case 1 -> input.readByte();
            case 2 -> input.readShort();
            case 3 -> input.readInt();
            case 4 -> input.readLong();
            case 5 -> input.readFloat();
            case 6 -> input.readDouble();
            case 7 -> input.readNBytes(input.readInt());
            case 8 -> string(input);
            case 9 -> {
                int childType = input.readUnsignedByte();
                int count = input.readInt();
                List<Object> values = new ArrayList<>(count);
                for (int index = 0; index < count; index++) { values.add(payload(input, childType)); }
                yield values;
            }
            case 10 -> {
                Map<String, Object> values = new LinkedHashMap<>();
                int childType;
                while ((childType = input.readUnsignedByte()) != 0) {
                    values.put(string(input), payload(input, childType));
                }
                yield values;
            }
            case 11 -> {
                int[] values = new int[input.readInt()];
                for (int index = 0; index < values.length; index++) { values[index] = input.readInt(); }
                yield values;
            }
            case 12 -> {
                long[] values = new long[input.readInt()];
                for (int index = 0; index < values.length; index++) { values[index] = input.readLong(); }
                yield values;
            }
            default -> throw new IOException("unsupported fixture tag " + type);
        };
    }
}
