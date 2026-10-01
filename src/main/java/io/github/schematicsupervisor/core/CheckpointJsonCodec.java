package io.github.schematicsupervisor.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small checkpoint-only JSON codec, avoiding a runtime JSON dependency in the core.
 */
public final class CheckpointJsonCodec {
    private CheckpointJsonCodec() {
    }

    public static String toJson(SupervisorCheckpoint checkpoint) {
        StringBuilder json = new StringBuilder(768);
        json.append("{\n");
        appendNumber(json, "version", checkpoint.version(), true);
        appendString(json, "plan_id", checkpoint.planId(), true);
        appendString(json, "state", checkpoint.state().name(), true);
        appendString(json, "resume_state", checkpoint.resumeState().name(), true);
        appendString(json, "restock_resume_state", checkpoint.restockResumeState().name(), true);
        appendNumber(json, "current_chunk_index", checkpoint.currentChunkIndex(), true);
        appendNumber(json, "chunk_count", checkpoint.chunkCount(), true);
        appendString(json, "schedule_id", checkpoint.scheduleId(), true);
        appendBoolean(json, "planting_deferred", checkpoint.plantingDeferred(), true);
        appendNumber(json, "schedule_cursor", checkpoint.scheduleCursor(), true);
        appendNumber(json, "repair_chunk_index", checkpoint.repairChunkIndex(), true);
        appendString(json, "checked_pieces", checkpoint.checkedPieces().encode(), true);
        appendString(json, "phase", checkpoint.phase().name(), true);
        appendString(json, "verification_stage", checkpoint.verificationStage().name(), true);
        appendString(json, "recovery_stage", checkpoint.recoveryStage().name(), true);
        appendNumber(json, "stable_verification_passes", checkpoint.stableVerificationPasses(), true);
        appendString(json, "last_verification_fingerprint", checkpoint.lastVerificationFingerprint(), true);
        appendMaterials(json, "consumed_materials", checkpoint.consumedMaterials(), true);
        appendCredit(json, checkpoint.lastAppliedPlannedCredit());
        appendMaterials(json, "withdrawn_materials", checkpoint.withdrawnMaterials(), true);
        appendMaterials(json, "missing_materials", checkpoint.missingMaterials(), true);
        appendMaterials(json, "restock_requirement", checkpoint.restockRequirement(), true);
        appendString(json, "last_error", checkpoint.lastError(), true);
        appendNumber(json, "verification_retries", checkpoint.verificationRetries(), true);
        appendBoolean(json, "repath_attempted", checkpoint.repathAttempted(), true);
        appendBoolean(json, "safe_return_attempted", checkpoint.safeReturnAttempted(), true);
        appendBoolean(json, "advisor_attempted", checkpoint.advisorAttempted(), true);
        appendBoolean(json, "withdrawal_in_flight", checkpoint.withdrawalInFlight(), true);
        appendBoolean(
                json,
                "reconciliation_required",
                checkpoint.reconciliationRequired(),
                true
        );
        appendString(
                json,
                "reconciliation_detail",
                checkpoint.reconciliationDetail(),
                false
        );
        json.append("}\n");
        return json.toString();
    }

    public static SupervisorCheckpoint fromJson(String json) {
        Object parsed = new Parser(json).parse();
        Map<String, Object> root = object(parsed, "checkpoint");
        int encodedVersion = Math.toIntExact(number(root, "version"));
        if (encodedVersion != 3 && encodedVersion != SupervisorCheckpoint.CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "Checkpoint version " + encodedVersion + " uses an incompatible build order. "
                            + "Reconcile any unsettled depot transfer, then reset and restart "
                            + "for layer building. The existing checkpoint has not been changed."
            );
        }
        return new SupervisorCheckpoint(
                SupervisorCheckpoint.CURRENT_VERSION,
                string(root, "plan_id"),
                enumValue(SupervisorState.class, string(root, "state"), "state"),
                enumValue(SupervisorState.class, string(root, "resume_state"), "resume_state"),
                enumValue(SupervisorState.class, string(root, "restock_resume_state"), "restock_resume_state"),
                Math.toIntExact(number(root, "current_chunk_index")),
                enumValue(BuildPhase.class, string(root, "phase"), "phase"),
                enumValue(VerificationStage.class, string(root, "verification_stage"), "verification_stage"),
                enumValue(RecoveryStage.class, string(root, "recovery_stage"), "recovery_stage"),
                Math.toIntExact(number(root, "stable_verification_passes")),
                string(root, "last_verification_fingerprint"),
                materials(root, "consumed_materials"),
                materials(root, "withdrawn_materials"),
                materials(root, "missing_materials"),
                materials(root, "restock_requirement"),
                string(root, "last_error"),
                Math.toIntExact(number(root, "verification_retries")),
                bool(root, "repath_attempted"),
                bool(root, "safe_return_attempted"),
                bool(root, "advisor_attempted"),
                bool(root, "withdrawal_in_flight"),
                bool(root, "reconciliation_required"),
                string(root, "reconciliation_detail"),
                string(root, "schedule_id"),
                Math.toIntExact(number(root, "schedule_cursor")),
                Math.toIntExact(number(root, "repair_chunk_index")),
                root.containsKey("chunk_count") ? Math.toIntExact(number(root, "chunk_count"))
                        : SchematicPlan.CHUNK_COUNT,
                root.containsKey("planting_deferred") && bool(root, "planting_deferred"),
                credit(root, encodedVersion),
                // Absent in checkpoints written before the start build check; older mods ignore it.
                root.containsKey("checked_pieces")
                        ? CompletedPieces.decode(string(root, "checked_pieces")) : CompletedPieces.none()
        );
    }

    private static void appendCredit(StringBuilder json, PlannedConsumptionCredit credit) {
        json.append("  \"last_applied_planned_credit\": ");
        if (credit == null) {
            json.append("null,\n");
            return;
        }
        json.append("{\"id\": \"").append(escape(credit.id()))
                .append("\", \"plan_id\": \"").append(escape(credit.planId()))
                .append("\", \"material\": \"").append(credit.material().jsonName())
                .append("\", \"quantity\": ").append(credit.quantity()).append("},\n");
    }

    private static PlannedConsumptionCredit credit(Map<String, Object> root, int version) {
        if (version == 3) {
            if (root.get("last_applied_planned_credit") != null) {
                throw new IllegalArgumentException("Legacy checkpoint cannot contain durable planned credit metadata");
            }
            return null;
        }
        Object value = required(root, "last_applied_planned_credit");
        if (value == null) { return null; }
        Map<String, Object> credit = object(value, "last_applied_planned_credit");
        if (!credit.keySet().equals(java.util.Set.of("id", "plan_id", "material", "quantity"))) {
            throw new IllegalArgumentException("planned credit fields are invalid");
        }
        return new PlannedConsumptionCredit(string(credit, "id"), string(credit, "plan_id"),
                Material.fromJsonName(string(credit, "material")), number(credit, "quantity"));
    }

    private static void appendString(StringBuilder json, String key, String value, boolean comma) {
        json.append("  \"").append(escape(key)).append("\": \"")
                .append(escape(value)).append('"');
        appendLineEnd(json, comma);
    }

    private static void appendNumber(StringBuilder json, String key, long value, boolean comma) {
        json.append("  \"").append(escape(key)).append("\": ").append(value);
        appendLineEnd(json, comma);
    }

    private static void appendBoolean(StringBuilder json, String key, boolean value, boolean comma) {
        json.append("  \"").append(escape(key)).append("\": ").append(value);
        appendLineEnd(json, comma);
    }

    private static void appendMaterials(
            StringBuilder json,
            String key,
            MaterialQuantities quantities,
            boolean comma
    ) {
        json.append("  \"").append(escape(key)).append("\": {");
        boolean first = true;
        for (Map.Entry<String, Long> entry : quantities.asJsonMap().entrySet()) {
            if (!first) {
                json.append(", ");
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append("\": ").append(entry.getValue());
        }
        json.append('}');
        appendLineEnd(json, comma);
    }

    private static void appendLineEnd(StringBuilder json, boolean comma) {
        if (comma) {
            json.append(',');
        }
        json.append('\n');
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static MaterialQuantities materials(Map<String, Object> root, String key) {
        Map<String, Object> raw = object(required(root, key), key);
        LinkedHashMap<String, Number> values = new LinkedHashMap<>();
        raw.forEach((material, amount) -> {
            if (!(amount instanceof Long number)) {
                throw new IllegalArgumentException(key + "." + material + " must be an integer");
            }
            values.put(material, number);
        });
        return MaterialQuantities.fromJsonMap(values);
    }

    private static String string(Map<String, Object> root, String key) {
        Object value = required(root, key);
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return string;
    }

    private static long number(Map<String, Object> root, String key) {
        Object value = required(root, key);
        if (!(value instanceof Long number)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return number;
    }

    private static boolean bool(Map<String, Object> root, String key) {
        Object value = required(root, key);
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
        return bool;
    }

    private static Object required(Map<String, Object> root, String key) {
        if (!root.containsKey(key)) {
            throw new IllegalArgumentException("missing checkpoint field " + key);
        }
        return root.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String description) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(description + " must be a JSON object");
        }
        return (Map<String, Object>) map;
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid " + field + " value " + value, exception);
        }
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            if (input == null) {
                throw new NullPointerException("json");
            }
            this.input = input;
        }

        private Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (index != input.length()) {
                throw error("unexpected trailing content");
            }
            return value;
        }

        private Object parseValue() {
            if (index >= input.length()) {
                throw error("unexpected end of input");
            }
            return switch (input.charAt(index)) {
                case '{' -> parseObject();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseInteger();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            skipWhitespace();
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            if (take('}')) {
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = parseValue();
                if (result.containsKey(key)) {
                    throw error("duplicate key " + key);
                }
                result.put(key, value);
                skipWhitespace();
                if (take('}')) {
                    return result;
                }
                expect(',');
                skipWhitespace();
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (index < input.length()) {
                char character = input.charAt(index++);
                if (character == '"') {
                    return result.toString();
                }
                if (character != '\\') {
                    if (character < 0x20) {
                        throw error("unescaped control character");
                    }
                    result.append(character);
                    continue;
                }
                if (index >= input.length()) {
                    throw error("unterminated escape");
                }
                char escaped = input.charAt(index++);
                switch (escaped) {
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case '/' -> result.append('/');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(parseUnicodeEscape());
                    default -> throw error("invalid escape \\" + escaped);
                }
            }
            throw error("unterminated string");
        }

        private char parseUnicodeEscape() {
            if (index + 4 > input.length()) {
                throw error("incomplete unicode escape");
            }
            String digits = input.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(digits, 16);
            } catch (NumberFormatException exception) {
                throw error("invalid unicode escape");
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (!input.startsWith(literal, index)) {
                throw error("expected " + literal);
            }
            index += literal.length();
            return value;
        }

        private long parseInteger() {
            int start = index;
            if (take('-') && index >= input.length()) {
                throw error("incomplete integer");
            }
            int digits = index;
            while (index < input.length() && Character.isDigit(input.charAt(index))) {
                index++;
            }
            if (digits == index) {
                throw error("expected JSON value");
            }
            try {
                return Long.parseLong(input.substring(start, index));
            } catch (NumberFormatException exception) {
                throw error("integer is out of range");
            }
        }

        private boolean take(char expected) {
            if (index < input.length() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) {
                throw error("expected '" + expected + "'");
            }
        }

        private void skipWhitespace() {
            while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at JSON offset " + index);
        }
    }
}
