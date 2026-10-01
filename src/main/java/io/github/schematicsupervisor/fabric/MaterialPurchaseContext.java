package io.github.schematicsupervisor.fabric;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Purchase recovery also binds the player and profile, without persisting either identity in plain text. */
final class MaterialPurchaseContext {
    private MaterialPurchaseContext() { }

    static RunContext bind(RunContext world, UUID player, Path journalPath) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(player, "player");
        Path profile = Objects.requireNonNull(journalPath, "journalPath").toAbsolutePath().normalize().getParent();
        if (profile == null) { throw new IllegalArgumentException("Purchase journal requires a profile directory"); }
        String value = world.worldIdentityHash() + "\n" + player + "\n"
                + profile.toString().toLowerCase(Locale.ROOT);
        try {
            return new RunContext("sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))), world.dimension());
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }
}
