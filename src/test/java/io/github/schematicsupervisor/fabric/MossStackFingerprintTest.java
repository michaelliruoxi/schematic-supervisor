package io.github.schematicsupervisor.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MossStackFingerprintTest {
    @Test void canonicalObjectOrderProducesTheSameOpaqueHash() {
        String first = hash("{\"count\":4,\"components\":{\"b\":2,\"a\":1}}").orElseThrow();
        String second = hash("{\"components\":{\"a\":1,\"b\":2},\"count\":4}").orElseThrow();
        assertEquals(first, second);
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test void protectsItemCountComponentsAndArrayOrder() {
        JsonElement payload = JsonParser.parseString("{\"components\":{\"test:data\":[1,2]}}");
        String original = fingerprint("minecraft:tripwire_hook", 5, payload).orElseThrow();
        assertNotEquals(original, fingerprint("minecraft:dirt", 5, payload).orElseThrow());
        assertNotEquals(original, fingerprint("minecraft:tripwire_hook", 4, payload).orElseThrow());
        assertNotEquals(original, fingerprint("minecraft:tripwire_hook", 5,
                JsonParser.parseString("{\"components\":{\"test:data\":[2,1]}}")).orElseThrow());
    }

    @Test void stringsCannotAliasDelimitersOrInvalidUtf16() {
        assertNotEquals(fingerprint("minecraft:dirt", 1, new JsonPrimitive("\ud800")).orElseThrow(),
                fingerprint("minecraft:dirt", 1, new JsonPrimitive("?")).orElseThrow());
        assertNotEquals(hash("{\"a\":\"1,b:2\"}").orElseThrow(), hash("{\"a\":1,\"b\":2}").orElseThrow());
    }

    @Test void refusesOversizedDeepAndWideTreesWithoutReturningPayload() {
        assertTrue(fingerprint("minecraft:dirt", 1, new JsonPrimitive("x".repeat(65_537))).isEmpty());
        JsonObject deep = new JsonObject();
        JsonObject current = deep;
        for (int index = 0; index < 34; index++) { JsonObject child = new JsonObject(); current.add("child", child); current = child; }
        assertTrue(fingerprint("minecraft:dirt", 1, deep).isEmpty());
        JsonArray wide = new JsonArray();
        for (int index = 0; index < 4_096; index++) { wide.add(index); }
        assertTrue(fingerprint("minecraft:dirt", 1, wide).isEmpty());
    }

    @Test void aggregateCaptureBudgetCannotBeBypassedByManyIndividuallyBoundedStacks() {
        MossStackFingerprint.Budget budget = new MossStackFingerprint.Budget();
        int accepted = 0;
        for (int index = 0; index < 20; index++) {
            if (MossStackFingerprint.fingerprint("minecraft:dirt", 1, new JsonPrimitive("x".repeat(60_000)), budget).isPresent()) { accepted++; }
        }
        assertEquals(8, accepted);
    }

    @Test void nullAndNonJsonNumbersFailClosed() {
        assertTrue(fingerprint("minecraft:dirt", 1, null).isEmpty());
        assertTrue(fingerprint("minecraft:dirt", 1, new JsonPrimitive(Double.NaN)).isEmpty());
        assertTrue(fingerprint("minecraft:dirt", 1, new JsonPrimitive(Double.POSITIVE_INFINITY)).isEmpty());
    }

    private static Optional<String> hash(String json) { return fingerprint("minecraft:dirt", 4, JsonParser.parseString(json)); }
    private static Optional<String> fingerprint(String id, int count, JsonElement json) {
        return MossStackFingerprint.fingerprint(id, count, json, new MossStackFingerprint.Budget());
    }
}
