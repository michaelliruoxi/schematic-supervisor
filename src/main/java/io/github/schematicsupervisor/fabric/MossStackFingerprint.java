package io.github.schematicsupervisor.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Optional;

/** Opaque hashes only: bounded canonical codec trees are never returned or included in errors. */
final class MossStackFingerprint {
    static final int MAX_CANONICAL_BYTES = 65_536;
    static final int MAX_CAPTURE_BYTES = 524_288;
    static final int MAX_NODES = 4_096;
    static final int MAX_DEPTH = 32;
    private MossStackFingerprint() { }

    static final class Budget {
        private int remaining = MAX_CAPTURE_BYTES;
        private void consume(int bytes) {
            if (bytes < 0 || bytes > remaining) { throw new Unavailable(); }
            remaining -= bytes;
        }
    }

    static Optional<String> fingerprint(String itemId, int count, JsonElement encodedStack, Budget budget) {
        try {
            if (itemId == null || itemId.length() > 128 || count < 0 || encodedStack == null || budget == null) {
                return Optional.empty();
            }
            Canonical canonical = new Canonical();
            canonical.string(itemId);
            canonical.append("|");
            canonical.append(Integer.toString(count));
            canonical.append("|");
            canonical.value(encodedStack, 0);
            byte[] bytes = canonical.output.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_CANONICAL_BYTES) { return Optional.empty(); }
            budget.consume(bytes.length);
            return Optional.of(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (RuntimeException | NoSuchAlgorithmException unavailable) {
            return Optional.empty();
        }
    }

    private static final class Canonical {
        private final StringBuilder output = new StringBuilder();
        private int nodes;

        private void append(String text) {
            if (text.length() > MAX_CANONICAL_BYTES - output.length()) { throw new Unavailable(); }
            output.append(text);
        }

        private void character(char value) {
            if (output.length() == MAX_CANONICAL_BYTES) { throw new Unavailable(); }
            output.append(value);
        }

        private void string(String value) {
            if (value.length() > MAX_CANONICAL_BYTES - output.length()) { throw new Unavailable(); }
            character('"');
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                // Escape every UTF-16 unit outside ASCII, including isolated surrogates, without lossy UTF-8 replacement.
                if (current < 0x20 || current > 0x7e) {
                    append("\\u");
                    append(String.format(java.util.Locale.ROOT, "%04x", (int) current));
                } else {
                    if (current == '"' || current == '\\') { character('\\'); }
                    character(current);
                }
            }
            character('"');
        }

        private void value(JsonElement value, int depth) {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES || value == null) { throw new Unavailable(); }
            if (value.isJsonNull()) { append("null"); }
            else if (value.isJsonObject()) {
                JsonObject object = value.getAsJsonObject();
                if (object.size() > MAX_NODES - nodes) { throw new Unavailable(); }
                ArrayList<String> keys = new ArrayList<>(object.size());
                int keyCharacters = 0;
                for (String key : object.keySet()) {
                    if (key.length() > MAX_CANONICAL_BYTES - keyCharacters) { throw new Unavailable(); }
                    keyCharacters += key.length();
                    keys.add(key);
                }
                Collections.sort(keys);
                character('{');
                boolean first = true;
                for (String key : keys) {
                    if (!first) { character(','); }
                    first = false;
                    string(key);
                    character(':');
                    value(object.get(key), depth + 1);
                }
                character('}');
            } else if (value.isJsonArray()) {
                if (value.getAsJsonArray().size() > MAX_NODES - nodes) { throw new Unavailable(); }
                character('[');
                boolean first = true;
                for (JsonElement element : value.getAsJsonArray()) {
                    if (!first) { character(','); }
                    first = false;
                    value(element, depth + 1);
                }
                character(']');
            } else {
                JsonPrimitive primitive = value.getAsJsonPrimitive();
                if (primitive.isString()) { string(primitive.getAsString()); }
                else if (primitive.isBoolean()) { append(primitive.getAsBoolean() ? "true" : "false"); }
                else if (primitive.isNumber()) {
                    String number = primitive.getAsString();
                    if (number.length() > 128 || !number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
                        throw new Unavailable();
                    }
                    append(number);
                } else { throw new Unavailable(); }
            }
        }
    }

    private static final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private Unavailable() { super("Bounded stack fingerprint unavailable"); }
    }
}
