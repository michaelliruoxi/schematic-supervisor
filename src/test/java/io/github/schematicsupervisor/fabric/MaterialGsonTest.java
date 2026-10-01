package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import io.github.schematicsupervisor.core.PlannedConsumptionCredit;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class MaterialGsonTest {
    private static final Gson GSON = MaterialGson.register(new GsonBuilder()).create();

    @Test
    void materialsAreWrittenAsTheirFormerEnumNames() {
        assertEquals("\"DIRT\"", GSON.toJson(Material.DIRT));
        assertEquals("\"minecraft:stone\"", GSON.toJson(Material.block("minecraft:stone")));
        assertSame(Material.GLOWSTONE, GSON.fromJson("\"GLOWSTONE\"", Material.class));
        assertSame(Material.block("minecraft:stone"), GSON.fromJson("\"minecraft:stone\"", Material.class));
    }

    @Test
    void aJournalCreditWrittenByAnOlderModReadsBack() {
        String older = "{\"id\":\"8f14e45f-ceea-467e-a4d0-55fb7f7a4b2c\",\"planId\":\"sha256:plan\","
                + "\"material\":\"DIRT\",\"quantity\":1}";
        PlannedConsumptionCredit credit = GSON.fromJson(older, PlannedConsumptionCredit.class);
        assertSame(Material.DIRT, credit.material());
        assertEquals(older, GSON.toJson(credit));
    }

    @Test
    void quantitiesKeepTheirKeys() {
        MaterialQuantities quantities = MaterialQuantities.of(Map.of(Material.DIRT, 2L, Material.block("minecraft:stone"), 1L));
        record Holder(MaterialQuantities stock) { }
        String json = GSON.toJson(new Holder(quantities));
        assertEquals(quantities, GSON.fromJson(json, Holder.class).stock());
    }

    @Test
    void anythingButANameIsRejected() {
        assertThrows(JsonParseException.class, () -> GSON.fromJson("{\"name\":\"DIRT\"}", Material.class));
        assertThrows(JsonParseException.class, () -> GSON.fromJson("\"granite\"", Material.class));
    }
}
