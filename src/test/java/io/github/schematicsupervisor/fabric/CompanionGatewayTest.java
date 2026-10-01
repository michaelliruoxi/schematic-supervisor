package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.schematicsupervisor.core.AdviceSnapshot;
import io.github.schematicsupervisor.core.AdviceStatus;
import io.github.schematicsupervisor.core.BuildPhase;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialLedgerSnapshot;
import io.github.schematicsupervisor.core.MaterialQuantities;
import io.github.schematicsupervisor.core.RecoveryAdvice;
import io.github.schematicsupervisor.core.RecoveryIncident;
import io.github.schematicsupervisor.core.RecoveryStage;
import io.github.schematicsupervisor.core.SupervisorState;
import io.github.schematicsupervisor.core.SupervisorStatus;
import io.github.schematicsupervisor.core.VerificationStage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class CompanionGatewayTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-23T12:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void advisorPostsSafeIncidentWithTokenAndAcceptsAllowlistedAction() throws Exception {
        AtomicReference<JsonObject> received = new AtomicReference<>();
        try (TestCompanion companion = new TestCompanion((exchange, request) -> {
            received.set(request);
            JsonObject response = new JsonObject();
            response.addProperty("incident_id", request.get("incident_id").getAsString());
            response.addProperty("action", "REPATH");
            response.addProperty("reason", "Fixed local reason.");
            response.addProperty("source", "test");
            response.addProperty("used_fallback", false);
            sendJson(exchange, 200, response);
        }, "shared-token");
             CompanionGateway gateway = new CompanionGateway(
                     companion.baseUri(),
                     "shared-token",
                     Duration.ofSeconds(2),
                     CLOCK
             )) {
            gateway.beginAdvice(incident());

            AdviceSnapshot snapshot = awaitAdvice(gateway);
            JsonObject payload = received.get();

            assertEquals(AdviceStatus.SUCCEEDED, snapshot.status());
            assertEquals(RecoveryAdvice.REPATH, snapshot.advice().orElseThrow());
            assertTrue(payload.get("recovery_exhausted").getAsBoolean());
            assertFalse(payload.getAsJsonArray("attempted_recovery").isEmpty());
            JsonObject chunk = payload.getAsJsonObject("status")
                    .getAsJsonObject("current_chunk");
            assertFalse(chunk.has("x"));
            assertFalse(chunk.has("z"));
            assertFalse(payload.has("work_order"));
            assertFalse(payload.has("plan_id"));
        }
    }

    @Test
    void malformedOrUnallowlistedAdviceFailsClosed() throws Exception {
        try (TestCompanion companion = new TestCompanion((exchange, request) -> {
            JsonObject response = new JsonObject();
            response.addProperty("incident_id", request.get("incident_id").getAsString());
            response.addProperty("action", "RUN_COMMAND");
            response.addProperty("reason", "Unsafe.");
            response.addProperty("source", "test");
            response.addProperty("used_fallback", false);
            sendJson(exchange, 200, response);
        }, null);
             CompanionGateway gateway = new CompanionGateway(
                     companion.baseUri(),
                     null,
                     Duration.ofSeconds(2),
                     CLOCK
             )) {
            gateway.beginAdvice(incident());

            AdviceSnapshot snapshot = awaitAdvice(gateway);

            assertEquals(AdviceStatus.FAILED, snapshot.status());
            assertTrue(snapshot.advice().isEmpty());
        }
    }

    @Test
    void statusPublisherOmitsCoordinatesAndRequiresExplicitAcknowledgement()
            throws Exception {
        AtomicReference<JsonObject> received = new AtomicReference<>();
        try (TestCompanion companion = new TestCompanion((exchange, request) -> {
            received.set(request);
            JsonObject response = new JsonObject();
            response.addProperty("accepted", true);
            response.addProperty("updated_at", "2026-07-23T12:00:00Z");
            sendJson(exchange, 202, response);
        }, null);
             CompanionGateway gateway = new CompanionGateway(
                     companion.baseUri(),
                     null,
                     Duration.ofSeconds(2),
                     CLOCK
             )) {
            boolean accepted = gateway.publishStatus(status(), "Walking")
                    .get(2, TimeUnit.SECONDS);
            JsonObject payload = received.get();

            assertTrue(accepted);
            JsonObject chunk = payload.getAsJsonObject("current_chunk");
            assertFalse(chunk.has("x"));
            assertFalse(chunk.has("z"));
            assertEquals("Walking", payload.get("baritone_status").getAsString());
            assertEquals(25, payload.getAsJsonObject("materials")
                    .getAsJsonObject("dirt")
                    .get("required")
                    .getAsLong());
        }
    }

    @Test
    void adviceBodyTimeoutAllowsAnotherRequestWhileTheOldServerResponseIsStalled()
            throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        try (TestCompanion companion = new TestCompanion((exchange, request) -> {
            if (requests.getAndIncrement() == 0) {
                holdJsonResponse(exchange, 200, bodyStarted, releaseBody);
            } else {
                sendAdvice(exchange, request);
            }
        }, null);
             CompanionGateway gateway = new CompanionGateway(
                     companion.baseUri(), null, Duration.ofMillis(500), CLOCK
             )) {
            gateway.beginAdvice(incident());
            assertTrue(bodyStarted.await(2, TimeUnit.SECONDS));

            assertEquals(AdviceStatus.UNAVAILABLE, awaitAdvice(gateway).status());
            assertEquals(1, releaseBody.getCount());

            gateway.beginAdvice(incident());
            assertEquals(AdviceStatus.SUCCEEDED, awaitAdvice(gateway).status());
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void statusBodyTimeoutAllowsAnotherPublicationWhileTheOldServerResponseIsStalled()
            throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        try (TestCompanion companion = new TestCompanion((exchange, request) -> {
            if (requests.getAndIncrement() == 0) {
                holdJsonResponse(exchange, 202, bodyStarted, releaseBody);
            } else {
                JsonObject response = new JsonObject();
                response.addProperty("accepted", true);
                response.addProperty("updated_at", "2026-07-23T12:00:00Z");
                sendJson(exchange, 202, response);
            }
        }, null);
             CompanionGateway gateway = new CompanionGateway(
                     companion.baseUri(), null, Duration.ofMillis(500), CLOCK
             )) {
            var pending = gateway.publishStatus(status(), "Idle");
            assertTrue(bodyStarted.await(2, TimeUnit.SECONDS));

            assertFalse(pending.get(2, TimeUnit.SECONDS));
            assertEquals(1, releaseBody.getCount());
            assertTrue(gateway.publishStatus(status(), "Idle").get(2, TimeUnit.SECONDS));
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void cancellingStalledAdviceAllowsTheNextRequestToFinish() throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        try (TestCompanion companion = new TestCompanion((exchange, request) -> {
            if (requests.getAndIncrement() == 0) {
                holdJsonResponse(exchange, 200, bodyStarted, releaseBody);
            } else {
                sendAdvice(exchange, request);
            }
        }, null);
             CompanionGateway gateway = new CompanionGateway(
                     companion.baseUri(), null, Duration.ofSeconds(5), CLOCK
             )) {
            gateway.beginAdvice(incident());
            assertTrue(bodyStarted.await(2, TimeUnit.SECONDS));

            gateway.cancelAdvice();
            assertEquals(AdviceStatus.IDLE, gateway.pollAdvice().status());
            gateway.beginAdvice(incident());
            assertEquals(AdviceStatus.SUCCEEDED, awaitAdvice(gateway).status());
            assertEquals(1, releaseBody.getCount());
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void cancellingStalledStatusAllowsTheNextPublicationToFinish() throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        try (TestCompanion companion = new TestCompanion((exchange, request) -> {
            if (requests.getAndIncrement() == 0) {
                holdJsonResponse(exchange, 202, bodyStarted, releaseBody);
            } else {
                JsonObject response = new JsonObject();
                response.addProperty("accepted", true);
                response.addProperty("updated_at", "2026-07-23T12:00:00Z");
                sendJson(exchange, 202, response);
            }
        }, null);
             CompanionGateway gateway = new CompanionGateway(
                     companion.baseUri(), null, Duration.ofSeconds(5), CLOCK
             )) {
            var pending = gateway.publishStatus(status(), "Idle");
            assertTrue(bodyStarted.await(2, TimeUnit.SECONDS));

            assertTrue(pending.cancel(true));
            assertTrue(gateway.publishStatus(status(), "Idle").get(2, TimeUnit.SECONDS));
            assertEquals(1, releaseBody.getCount());
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void oversizedAdviceIsRejectedBeforeTheResponseFinishes() throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        try (TestCompanion companion = new TestCompanion((exchange, request) -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                bodyStarted.countDown();
                output.write(new byte[SupervisorProtocolJson.MAX_RESPONSE_BYTES + 1]);
                output.flush();
                awaitBodyRelease(releaseBody);
            }
        }, null);
             CompanionGateway gateway = new CompanionGateway(
                     companion.baseUri(), null, Duration.ofSeconds(5), CLOCK
             )) {
            gateway.beginAdvice(incident());
            assertTrue(bodyStarted.await(2, TimeUnit.SECONDS));

            assertEquals(AdviceStatus.FAILED, awaitAdvice(gateway).status());
            assertEquals(1, releaseBody.getCount());
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void gatewayRejectsNonLoopbackAndOriginPaths() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionGateway(URI.create("http://192.0.2.1:8766"), null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionGateway(
                        URI.create("http://127.0.0.1:8766/unexpected"),
                        null
                )
        );
    }

    private static AdviceSnapshot awaitAdvice(CompanionGateway gateway)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        AdviceSnapshot snapshot;
        do {
            snapshot = gateway.pollAdvice();
            if (snapshot.status() != AdviceStatus.PENDING) {
                return snapshot;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        return snapshot;
    }

    private static RecoveryIncident incident() {
        return new RecoveryIncident(
                "private-plan",
                4,
                BuildPhase.TILL,
                45,
                false,
                MaterialQuantities.of(Material.DIRT, 8),
                "No progress",
                true,
                true
        );
    }

    private static SupervisorStatus status() {
        MaterialQuantities inventory = MaterialQuantities.of(Map.of(
                Material.DIRT, 20L,
                Material.FOOD, 4L
        ));
        return new SupervisorStatus(
                SupervisorState.BUILDING,
                BuildPhase.ORDINARY_BLOCKS,
                VerificationStage.CHUNK,
                3,
                new ChunkCoordinate(10, -8),
                RecoveryStage.NONE,
                0,
                MaterialLedgerSnapshot.calculate(
                        MaterialQuantities.of(Material.DIRT, 100),
                        MaterialQuantities.empty(),
                        MaterialQuantities.empty()
                ),
                inventory,
                MaterialQuantities.of(Material.DIRT, 5),
                ""
        );
    }

    private static void sendJson(HttpExchange exchange, int status, JsonObject body)
            throws IOException {
        byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json; charset=utf-8"
        );
        exchange.sendResponseHeaders(status, encoded.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(encoded);
        }
    }

    private static void sendAdvice(HttpExchange exchange, JsonObject request) throws IOException {
        JsonObject response = new JsonObject();
        response.addProperty("incident_id", request.get("incident_id").getAsString());
        response.addProperty("action", "REPATH");
        response.addProperty("reason", "Fixed local reason.");
        response.addProperty("source", "test");
        response.addProperty("used_fallback", false);
        sendJson(exchange, 200, response);
    }

    private static void holdJsonResponse(
            HttpExchange exchange,
            int status,
            CountDownLatch bodyStarted,
            CountDownLatch releaseBody
    ) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write('{');
            output.flush();
            bodyStarted.countDown();
            awaitBodyRelease(releaseBody);
        }
    }

    private static void awaitBodyRelease(CountDownLatch releaseBody) throws IOException {
        try {
            if (!releaseBody.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting to release test response body");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Test response interrupted", exception);
        }
    }

    @FunctionalInterface
    private interface RequestResponder {
        void respond(HttpExchange exchange, JsonObject request) throws IOException;
    }

    private static final class TestCompanion implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newFixedThreadPool(
                4,
                Thread.ofPlatform().daemon().name("test-companion-", 0).factory()
        );

        private TestCompanion(RequestResponder responder, String token) throws IOException {
            server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
                    0
            );
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try (exchange) {
                    String supplied = exchange.getRequestHeaders()
                            .getFirst("X-Supervisor-Token");
                    if (token != null && !token.equals(supplied)) {
                        sendJson(exchange, 401, new JsonObject());
                        return;
                    }
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    JsonObject request = JsonParser.parseString(
                            new String(body, StandardCharsets.UTF_8)
                    ).getAsJsonObject();
                    responder.respond(exchange, request);
                }
            });
            server.start();
        }

        private URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
