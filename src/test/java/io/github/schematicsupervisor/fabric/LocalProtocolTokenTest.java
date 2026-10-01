package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalProtocolTokenTest {
    @TempDir
    Path directory;

    @Test
    void createsStableRandomCredential() throws IOException {
        Path path = directory.resolve("nested/protocol-token.txt");
        String first = LocalProtocolToken.resolve(path, null);
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertEquals(first, LocalProtocolToken.resolve(path, ""));
    }

    @Test
    void environmentOverrideDoesNotWriteFile() throws IOException {
        Path path = directory.resolve("protocol-token.txt");
        assertEquals("existing-token", LocalProtocolToken.resolve(path, "existing-token"));
        assertFalse(Files.exists(path));
    }

    @Test
    void rejectsCorruptCredentialWithoutReplacingIt() throws IOException {
        Path path = directory.resolve("protocol-token.txt");
        Files.writeString(path, "broken");
        assertThrows(IOException.class, () -> LocalProtocolToken.resolve(path, null));
        assertEquals("broken", Files.readString(path));
    }
}
