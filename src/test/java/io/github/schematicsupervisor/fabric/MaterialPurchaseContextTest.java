package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MaterialPurchaseContextTest {
    private static final RunContext WORLD = new RunContext("sha256:" + "a".repeat(64), "minecraft:overworld");
    private static final UUID PLAYER = UUID.fromString("4c21ab40-1a37-4af9-8a5c-5547bd5d8a12");
    private static final Path PROFILE = Path.of("profile-a", "material-purchase.json");

    @Test void sameProfileAndPlayerResumeWithOpaqueStableIdentity() {
        var first = MaterialPurchaseContext.bind(WORLD, PLAYER, PROFILE);
        assertEquals(first, MaterialPurchaseContext.bind(WORLD, PLAYER, Path.of("profile-a", ".", "material-purchase.json")));
        assertTrue(first.worldIdentityHash().matches("sha256:[a-f0-9]{64}"));
        assertFalse(first.toString().contains(PLAYER.toString()));
        assertFalse(first.toString().contains("profile-a"));
        assertEquals(WORLD.dimension(), first.dimension());
    }

    @Test void anotherAccountProfileWorldOrDimensionCannotClaimTheReceipt() {
        var first = MaterialPurchaseContext.bind(WORLD, PLAYER, PROFILE);
        assertNotEquals(first, MaterialPurchaseContext.bind(WORLD,
                UUID.fromString("801144ae-64b7-4711-9c69-820755aa0f27"), PROFILE));
        assertNotEquals(first, MaterialPurchaseContext.bind(WORLD, PLAYER, Path.of("profile-b", "material-purchase.json")));
        assertNotEquals(first, MaterialPurchaseContext.bind(new RunContext("sha256:" + "b".repeat(64), WORLD.dimension()), PLAYER, PROFILE));
        assertNotEquals(first, MaterialPurchaseContext.bind(new RunContext(WORLD.worldIdentityHash(), "minecraft:the_nether"), PLAYER, PROFILE));
    }
}
