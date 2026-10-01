package io.github.schematicsupervisor.fabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Local pairing credential; never included in observations or logs. */
final class LocalProtocolToken {
    private LocalProtocolToken() { }

    static String resolve(Path path, String environmentValue) throws IOException {
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue;
        }
        if (!Files.exists(path)) {
            Files.createDirectories(path.toAbsolutePath().getParent());
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            String generated = HexFormat.of().formatHex(random);
            try {
                Files.writeString(path, generated + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException exception) {
                // Another initialization won; use its credential below.
            }
        }
        if (Files.size(path) > 256) {
            throw new IOException("Local pairing credential has an invalid size");
        }
        String token = Files.readString(path, StandardCharsets.UTF_8).strip();
        if (!token.matches("[0-9a-f]{64}")) {
            throw new IOException("Local pairing credential is invalid; restore the original file");
        }
        return token;
    }
}
