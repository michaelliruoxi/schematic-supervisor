package io.github.schematicsupervisor.fabric;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

final class MinecraftRunContext {
    private MinecraftRunContext() {
    }

    static RunContext capture(MinecraftClient client) {
        Objects.requireNonNull(client, "client");
        if (client.world == null) {
            throw new IllegalStateException("client world is unavailable");
        }
        String identity;
        ServerInfo server = client.getCurrentServerEntry();
        if (server != null) {
            identity = "remote:" + server.address.strip().toLowerCase(Locale.ROOT);
        } else if (client.getServer() != null) {
            identity = "local:" + client.getServer()
                    .getSavePath(net.minecraft.util.WorldSavePath.ROOT)
                    .toAbsolutePath()
                    .normalize()
                    .toString()
                    .toLowerCase(Locale.ROOT);
        } else if (client.getNetworkHandler() != null) {
            identity = "connection:"
                    + client.getNetworkHandler().getConnection().getAddress().toString();
        } else {
            throw new IllegalStateException("world identity is unavailable");
        }
        String dimension = client.world.getRegistryKey().getValue().toString();
        return new RunContext("sha256:" + hash(identity), dimension);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
