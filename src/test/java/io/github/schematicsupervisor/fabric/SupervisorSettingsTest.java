package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SupervisorSettingsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsSettings() throws IOException {
        Path path = temporaryDirectory.resolve("settings.json");
        SupervisorSettings expected = new SupervisorSettings(
                URI.create("http://127.0.0.1:9001"),
                9_002,
                321,
                654,
                7,
                2
        );

        SupervisorSettings.save(path, expected);

        assertEquals(expected, SupervisorSettings.loadOrCreate(path));
    }

    @Test
    void createsDefaultsWhenMissing() throws IOException {
        Path path = temporaryDirectory.resolve("nested").resolve("settings.json");

        assertEquals(SupervisorSettings.defaults(), SupervisorSettings.loadOrCreate(path));
        assertEquals(SupervisorSettings.defaults(), SupervisorSettings.loadOrCreate(path));
    }

    @Test
    void tillIntervalDefaultsToTwoWithoutChangingTheSharedInterval() throws IOException {
        SupervisorSettings defaults = SupervisorSettings.defaults();
        assertEquals(2, defaults.tillInteractionCooldownTicks());
        assertEquals(4, defaults.interactionCooldownTicks());

        Path path = temporaryDirectory.resolve("legacy-till.json");
        SupervisorSettings legacy = new SupervisorSettings(URI.create("http://127.0.0.1:8766"),
                8765, 20000, 20000, 7, 3, 0, true, true, true, true, true, true);
        SupervisorSettings.save(path, legacy);
        Files.writeString(path, Files.readString(path).replace(
                ",\n  \"tillInteractionCooldownTicks\": 2", ""));
        SupervisorSettings loaded = SupervisorSettings.loadOrCreate(path);
        assertEquals(legacy, loaded, "An omitted till interval preserves every existing preference");
        assertEquals(2, loaded.tillInteractionCooldownTicks());
        assertEquals(7, loaded.interactionCooldownTicks());
    }

    @Test
    void explicitTillIntervalsRoundTripIndependentlyIncludingBothBounds() throws IOException {
        Path path = temporaryDirectory.resolve("till-interval.json");
        for (int tillInterval : new int[] {1, 2, 6, 100}) {
            SupervisorSettings expected = new SupervisorSettings(URI.create("http://127.0.0.1:8766"),
                    8765, 20000, 20000, 4, 3, 0, true, true, true, true, true, true, tillInterval);
            SupervisorSettings.save(path, expected);
            SupervisorSettings loaded = SupervisorSettings.loadOrCreate(path);
            assertEquals(expected, loaded);
            assertEquals(tillInterval, loaded.tillInteractionCooldownTicks());
            assertEquals(4, loaded.interactionCooldownTicks());
        }
    }

    @Test
    void tillIntervalRejectsMalformedFractionalOverflowAndOutOfRangeValues() throws IOException {
        Path path = temporaryDirectory.resolve("invalid-till-interval.json");
        SupervisorSettings.save(path, SupervisorSettings.defaults());
        String baseline = Files.readString(path);
        for (String value : new String[] {"\"2\"", "true", "false", "null", "[]", "{}",
                "0", "-1", "101", "1.5", "2.0000000000000001", "4294967298", "1e100"}) {
            Files.writeString(path, baseline.replace("\"tillInteractionCooldownTicks\": 2",
                    "\"tillInteractionCooldownTicks\": " + value));
            assertThrows(IllegalArgumentException.class, () -> SupervisorSettings.loadOrCreate(path), value);
        }
        for (int value : new int[] {0, -1, 101}) {
            assertThrows(IllegalArgumentException.class, () -> new SupervisorSettings(
                    URI.create("http://127.0.0.1:8766"), 8765, 20000, 20000, 4, 3,
                    0, true, true, true, true, true, true, value));
        }
    }

    @Test
    void structureFirstOrderingRoundTripsDefaultsOffAndRejectsNonBooleans() throws IOException {
        Path path = temporaryDirectory.resolve("structure-first.json");
        SupervisorSettings settings = new SupervisorSettings(URI.create("http://127.0.0.1:8766"),
                8765, 20000, 20000, 4, 3, 0, true, true, false, true, true, true);
        SupervisorSettings.save(path, settings);
        String baseline = Files.readString(path);
        assertEquals(settings, SupervisorSettings.loadOrCreate(path));
        Files.writeString(path, baseline.replace(",\n  \"glowstoneAfterStructure\": true", ""));
        SupervisorSettings legacy = SupervisorSettings.loadOrCreate(path);
        assertFalse(legacy.glowstoneAfterStructure());
        assertEquals(settings.deferPlanting(), legacy.deferPlanting());
        assertEquals(settings.buyMaterialsInPlace(), legacy.buyMaterialsInPlace());
        assertEquals(settings.discardSurplusDirectly(), legacy.discardSurplusDirectly());
        for (String value : new String[] {"\"true\"", "1", "null", "[]", "{}"}) {
            Files.writeString(path, baseline.replace("\"glowstoneAfterStructure\": true",
                    "\"glowstoneAfterStructure\": " + value));
            assertThrows(IllegalArgumentException.class, () -> SupervisorSettings.loadOrCreate(path));
        }
    }

    @Test
    void deferredPlantingRoundTripsAndOldSettingsDefaultToFullPlanting() throws IOException {
        Path path = temporaryDirectory.resolve("deferred.json");
        SupervisorSettings settings = new SupervisorSettings(URI.create("http://127.0.0.1:8766"),
                8765, 20000, 20000, 4, 3, 0, true);
        SupervisorSettings.save(path, settings);
        assertEquals(settings, SupervisorSettings.loadOrCreate(path));
        Files.writeString(path, Files.readString(path).replace(
                ",\n  \"deferPlanting\": true", ""));
        assertEquals(false, SupervisorSettings.loadOrCreate(path).deferPlanting());
    }

    @Test
    void zeroFoodSettingRoundTripsAndReachesSupervisorConfiguration() throws IOException {
        Path path = temporaryDirectory.resolve("no-food.json");
        SupervisorSettings noFood = new SupervisorSettings(URI.create("http://127.0.0.1:8766"),
                8765, 20000, 20000, 4, 3, 0);
        SupervisorSettings.save(path, noFood);
        SupervisorSettings loaded = SupervisorSettings.loadOrCreate(path);
        assertEquals(noFood, loaded);
        assertEquals(0, loaded.supervisorConfig().minimumFood());
    }

    @Test
    void automaticHoeRepairIsOptInAndPreservesBuildPreferences() throws IOException {
        Path path = temporaryDirectory.resolve("repair.json");
        SupervisorSettings enabled = new SupervisorSettings(URI.create("http://127.0.0.1:8766"),
                8765, 20000, 20000, 4, 3, 0, true, true);
        SupervisorSettings.save(path, enabled);
        assertEquals(enabled, SupervisorSettings.loadOrCreate(path));
        Files.writeString(path, Files.readString(path).replace(
                ",\n  \"autoRepairHoes\": true", ""));
        SupervisorSettings old = SupervisorSettings.loadOrCreate(path);
        assertEquals(false, old.autoRepairHoes());
        assertEquals(true, old.deferPlanting());
        assertEquals(0, old.minimumFood());
        assertEquals(false, SupervisorSettings.defaults().autoRepairHoes());
    }

    @Test
    void existingSettingsKeepTheirFoodReserveAndNegativeValuesAreRejected() throws IOException {
        Path path = temporaryDirectory.resolve("existing.json");
        Files.writeString(path, """
                {"companionUri":"http://127.0.0.1:8766","controlPort":8765,
                 "placementBlocksPerTick":20000,"verificationBlocksPerTick":20000,
                 "interactionCooldownTicks":4,"pathGoalRadius":3}
                """);
        assertEquals(1, SupervisorSettings.loadOrCreate(path).supervisorConfig().minimumFood());
        assertThrows(IllegalArgumentException.class, () -> new SupervisorSettings(
                URI.create("http://127.0.0.1:8766"), 8765, 20000, 20000, 4, 3, -1));
    }

    @Test
    void surplusDisposalRequiresExplicitOptInAndRoundTripsWithoutChangingOtherPreferences() throws IOException {
        Path path = temporaryDirectory.resolve("surplus.json");
        SupervisorSettings enabled = new SupervisorSettings(URI.create("http://127.0.0.1:8766"),
                8765, 20000, 20000, 4, 3, 0, true, true, true);
        SupervisorSettings.save(path, enabled);
        assertEquals(enabled, SupervisorSettings.loadOrCreate(path));
        Files.writeString(path, Files.readString(path).replace(",\n  \"discardSurplusWhenStorageFull\": true", ""));
        SupervisorSettings old = SupervisorSettings.loadOrCreate(path);
        assertEquals(false, old.discardSurplusWhenStorageFull());
        assertEquals(true, old.autoRepairHoes());
        assertEquals(true, old.deferPlanting());
        assertEquals(0, old.minimumFood());
        assertEquals(false, SupervisorSettings.defaults().discardSurplusWhenStorageFull());
    }

    @Test
    void disposalOptInRejectsCoercedStringsNumbersNullAndContainers() throws IOException {
        Path path = temporaryDirectory.resolve("strict-surplus.json");
        SupervisorSettings.save(path, SupervisorSettings.defaults());
        String baseline = Files.readString(path);
        for (String value : new String[] {"\"true\"", "\"false\"", "1", "0", "null", "[]", "{}"}) {
            Files.writeString(path, baseline.replace("\"discardSurplusWhenStorageFull\": false",
                    "\"discardSurplusWhenStorageFull\": " + value));
            assertThrows(IllegalArgumentException.class, () -> SupervisorSettings.loadOrCreate(path));
        }
    }

    @Test void directDisposalIsIndependentExplicitOptInAndRejectsCoercion() throws IOException {
        Path path = temporaryDirectory.resolve("direct-surplus.json");
        var enabled = new SupervisorSettings(URI.create("http://127.0.0.1:8766"),
                8765, 20000, 20000, 4, 3, 0, true, true, false, true);
        SupervisorSettings.save(path, enabled);
        assertEquals(enabled, SupervisorSettings.loadOrCreate(path));
        assertFalse(SupervisorSettings.defaults().discardSurplusDirectly());
        String saved = Files.readString(path);
        for (String value : new String[] {"\"true\"", "1", "null", "[]", "{}"}) {
            Files.writeString(path, saved.replace("\"discardSurplusDirectly\": true", "\"discardSurplusDirectly\": " + value));
            assertThrows(IllegalArgumentException.class, () -> SupervisorSettings.loadOrCreate(path));
        }
        Files.writeString(path, saved.replace(",\n  \"discardSurplusDirectly\": true", ""));
        assertFalse(SupervisorSettings.loadOrCreate(path).discardSurplusDirectly());
    }

    @Test void inPlacePurchasingIsExplicitAndRejectsBooleanCoercion() throws IOException {
        Path path = temporaryDirectory.resolve("in-place-supply.json");
        var enabled = new SupervisorSettings(URI.create("http://127.0.0.1:8766"),
                8765, 20000, 20000, 4, 3, 0, true, true, false, true, true);
        SupervisorSettings.save(path, enabled);
        assertEquals(enabled, SupervisorSettings.loadOrCreate(path));
        assertFalse(SupervisorSettings.defaults().buyMaterialsInPlace());
        String saved = Files.readString(path);
        for (String value : new String[] {"\"true\"", "1", "null", "[]", "{}"}) {
            Files.writeString(path, saved.replace("\"buyMaterialsInPlace\": true", "\"buyMaterialsInPlace\": " + value));
            assertThrows(IllegalArgumentException.class, () -> SupervisorSettings.loadOrCreate(path));
        }
        Files.writeString(path, saved.replace(",\n  \"buyMaterialsInPlace\": true", ""));
        assertFalse(SupervisorSettings.loadOrCreate(path).buyMaterialsInPlace());
    }

    @Test
    void rejectsNonLoopbackCompanion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SupervisorSettings(
                        URI.create("https://example.com:8766"),
                        8_765,
                        1,
                        1,
                        1,
                        1
                )
        );
    }

    @Test
    void rejectsOutOfRangeBudgets() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SupervisorSettings(
                        URI.create("http://127.0.0.1:8766"),
                        8_765,
                        0,
                        1,
                        1,
                        1
                )
        );
    }
}
