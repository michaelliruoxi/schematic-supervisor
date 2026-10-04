package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class ControlHttpServerTest {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Test
    void observationIsAuthenticatedAndNeverSchedulesGameControls() throws Exception {
        AtomicInteger scheduled = new AtomicInteger();
        try (ControlHttpServer server = new ControlHttpServer(
                0, "shared-token", Duration.ofSeconds(1),
                (request, completion) -> scheduled.incrementAndGet(),
                () -> "{\"state\":\"IDLE\",\"run_id\":\"run-1\"}"
                        .getBytes(StandardCharsets.UTF_8))) {
            server.start();
            URI uri = URI.create("http://127.0.0.1:" + server.port() + "/v1/observation");
            HttpResponse<String> missing = CLIENT.send(HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> accepted = CLIENT.send(HttpRequest.newBuilder(uri)
                            .header("X-Supervisor-Token", "shared-token").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, missing.statusCode());
            assertEquals(200, accepted.statusCode());
            assertEquals("run-1", JsonParser.parseString(accepted.body())
                    .getAsJsonObject().get("run_id").getAsString());
            assertEquals("no-store", accepted.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(0, scheduled.get());
        }
    }

    @Test
    void observationRequiresAClientSnapshotAndGetWithoutQuery() throws Exception {
        try (ControlHttpServer server = new ControlHttpServer(
                0, null, Duration.ofSeconds(1), (request, completion) -> { }, () -> null)) {
            server.start();
            String base = "http://127.0.0.1:" + server.port() + "/v1/observation";
            assertEquals(503, CLIENT.send(HttpRequest.newBuilder(URI.create(base)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404, CLIENT.send(HttpRequest.newBuilder(URI.create(base + "?token=x"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpResponse<String> wrongMethod = CLIENT.send(HttpRequest.newBuilder(URI.create(base))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, wrongMethod.statusCode());
            assertEquals("GET", wrongMethod.headers().firstValue("Allow").orElseThrow());
        }
    }

    @Test
    void progressIsAuthenticatedGetOnlyAndWaitsForTheFirstSnapshot() throws Exception {
        AtomicReference<byte[]> body = new AtomicReference<>();
        try (ControlHttpServer server = new ControlHttpServer(
                0, "shared-token", Duration.ofSeconds(1), (request, completion) -> { },
                () -> null, body::get)) {
            server.start();
            URI uri = URI.create("http://127.0.0.1:" + server.port() + "/v1/progress");
            HttpRequest authorized = HttpRequest.newBuilder(uri)
                    .header("X-Supervisor-Token", "shared-token").GET().build();
            assertEquals(503, CLIENT.send(authorized, HttpResponse.BodyHandlers.ofString()).statusCode());
            body.set("{\"available\":false}".getBytes(StandardCharsets.UTF_8));
            HttpResponse<String> ready = CLIENT.send(authorized, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ready.statusCode());
            assertFalse(JsonParser.parseString(ready.body()).getAsJsonObject().get("available").getAsBoolean());
            assertEquals(401, CLIENT.send(HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpResponse<String> post = CLIENT.send(HttpRequest.newBuilder(uri)
                            .header("X-Supervisor-Token", "shared-token")
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(405, post.statusCode());
            assertEquals(404, CLIENT.send(HttpRequest.newBuilder(URI.create(uri + "?stage=1"))
                            .header("X-Supervisor-Token", "shared-token").GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404, CLIENT.send(HttpRequest.newBuilder(uri.resolve("/v1/progress/"))
                            .header("X-Supervisor-Token", "shared-token").GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404, CLIENT.send(HttpRequest.newBuilder(uri.resolve("/v1/unknown"))
                            .header("X-Supervisor-Token", "shared-token").GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test
    void serverWithoutProgressSourceReportsProgressNotReady() throws Exception {
        try (ControlHttpServer server = new ControlHttpServer(
                0, null, Duration.ofSeconds(1), (request, completion) -> { },
                () -> "{}".getBytes(StandardCharsets.UTF_8))) {
            server.start();
            URI uri = URI.create("http://127.0.0.1:" + server.port() + "/v1/progress");
            assertEquals(503, CLIENT.send(HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test
    void depotScanRequiresConfiguredToken() throws Exception {
        AtomicInteger scheduled = new AtomicInteger();
        try (ControlHttpServer server = new ControlHttpServer(
                0, null, Duration.ofSeconds(1),
                (request, completion) -> scheduled.incrementAndGet())) {
            server.start();
            assertEquals(401, send(server, controlBody("SCAN_DEPOTS"), null).statusCode());
            assertEquals(0, scheduled.get());
        }
    }

    @ParameterizedTest
    @EnumSource(ControlHttpServer.ControlAction.class)
    void allowlistedControlsAreScheduledAndExplicitlyAcknowledged(
            ControlHttpServer.ControlAction action
    ) throws Exception {
        AtomicReference<String> callbackThread = new AtomicReference<>();
        ExecutorService clientThread = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("simulated-client").factory()
        );
        try (clientThread;
             ControlHttpServer server = new ControlHttpServer(
                     0,
                     "shared-token",
                     Duration.ofSeconds(2),
                     (request, completion) -> clientThread.execute(() -> {
                         if (!completion.tryStart()) {
                             return;
                         }
                         callbackThread.set(Thread.currentThread().getName());
                         completion.complete(new ControlHttpServer.ControlResult(
                                 true,
                                 request.action().name() + " accepted.",
                                 "PAUSED"
                         ));
                     })
             )) {
            server.start();

            HttpResponse<String> response = send(
                    server,
                    controlBody(action.name()),
                    "shared-token"
            );
            JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();

            assertEquals(200, response.statusCode());
            assertTrue(result.get("accepted").getAsBoolean());
            assertTrue(result.get("message").getAsString().contains(action.name()));
            assertEquals("PAUSED", result.get("state").getAsString());
            assertEquals("simulated-client", callbackThread.get());
            assertNotEquals(
                    Thread.currentThread().getName(),
                    callbackThread.get()
            );
        }
    }

    @Test
    void tokenIsRequiredAndComparedBeforeScheduling() throws Exception {
        AtomicInteger scheduled = new AtomicInteger();
        try (ControlHttpServer server = new ControlHttpServer(
                0,
                "shared-token",
                Duration.ofSeconds(1),
                (request, completion) -> {
                    if (!completion.tryStart()) {
                        return;
                    }
                    scheduled.incrementAndGet();
                    completion.complete(new ControlHttpServer.ControlResult(
                            true,
                            "Accepted.",
                            "BUILDING"
                    ));
                }
        )) {
            server.start();

            HttpResponse<String> missing = send(server, controlBody("START"), null);
            HttpResponse<String> wrong = send(server, controlBody("START"), "wrong");
            HttpResponse<String> accepted =
                    send(server, controlBody("START"), "shared-token");

            assertEquals(401, missing.statusCode());
            assertEquals(401, wrong.statusCode());
            assertEquals(200, accepted.statusCode());
            assertEquals(1, scheduled.get());
            assertFalse(JsonParser.parseString(missing.body()).getAsJsonObject()
                    .get("accepted").getAsBoolean());
        }
    }

    @Test
    void malformedAndOversizedControlsFailBeforeScheduling() throws Exception {
        AtomicInteger scheduled = new AtomicInteger();
        try (ControlHttpServer server = new ControlHttpServer(
                0,
                null,
                Duration.ofSeconds(1),
                (request, completion) -> scheduled.incrementAndGet()
        )) {
            server.start();

            HttpResponse<String> disallowed = send(server, controlBody("FLY"), null);
            String oversized = "{\"action\":\"START\",\"padding\":\""
                    + "a".repeat(SupervisorProtocolJson.MAX_CONTROL_REQUEST_BYTES)
                    + "\"}";
            HttpResponse<String> tooLarge = send(server, oversized, null);

            assertEquals(400, disallowed.statusCode());
            assertEquals(413, tooLarge.statusCode());
            assertEquals(0, scheduled.get());
            assertFalse(JsonParser.parseString(disallowed.body()).getAsJsonObject()
                    .get("accepted").getAsBoolean());
        }
    }

    @Test
    void callbackTimeoutFailsClosedWithExplicitState() throws Exception {
        try (ControlHttpServer server = new ControlHttpServer(
                0,
                null,
                Duration.ofMillis(25),
                (request, completion) -> {
                    // Deliberately do not complete the callback.
                }
        )) {
            server.start();

            HttpResponse<String> response = send(server, controlBody("STOP"), null);
            JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();

            assertEquals(504, response.statusCode());
            assertFalse(result.get("accepted").getAsBoolean());
            assertEquals("UNKNOWN", result.get("state").getAsString());
        }
    }

    @ParameterizedTest
    @EnumSource(ControlHttpServer.ControlAction.class)
    void controlsQueuedUntilAfterTimeoutCannotExecute(ControlHttpServer.ControlAction action)
            throws Exception {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicInteger applied = new AtomicInteger();
        try (ControlHttpServer server = new ControlHttpServer(
                0, "shared-token", Duration.ofMillis(25),
                (request, invocation) -> queued.set(() -> {
                    if (invocation.tryStart()) {
                        applied.incrementAndGet();
                        invocation.complete(new ControlHttpServer.ControlResult(
                                true, "Accepted.", "BUILDING"
                        ));
                    }
                })
        )) {
            server.start();
            HttpResponse<String> response = send(server, controlBody(action.name()), "shared-token");

            assertEquals(504, response.statusCode());
            assertTrue(response.body().contains("before execution and was cancelled"));
            queued.get().run();
            assertEquals(0, applied.get());
        }
    }

    @Test
    void timeoutOfRunningControlReportsUnknownOutcomeAndCannotRunAgain() throws Exception {
        AtomicReference<ControlHttpServer.ControlInvocation> running = new AtomicReference<>();
        AtomicInteger applied = new AtomicInteger();
        try (ControlHttpServer server = new ControlHttpServer(
                0, "shared-token", Duration.ofMillis(100),
                (request, invocation) -> {
                    if (invocation.tryStart()) {
                        applied.incrementAndGet();
                        running.set(invocation);
                    }
                }
        )) {
            server.start();
            HttpResponse<String> response = send(server, controlBody("START"), "shared-token");

            assertEquals(504, response.statusCode());
            assertTrue(response.body().contains("outcome is unknown"));
            assertFalse(response.body().contains("was cancelled"));
            assertEquals(1, applied.get());
            running.get().complete(new ControlHttpServer.ControlResult(
                    true, "Accepted.", "BUILDING"
            ));
            assertFalse(running.get().tryStart());
        }
    }

    @Test
    void closingServerCancelsQueuedControlsBeforeClientExecution() throws Exception {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        CountDownLatch scheduled = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        ControlHttpServer server = new ControlHttpServer(
                0, "shared-token", Duration.ofSeconds(2),
                (request, invocation) -> {
                    queued.set(() -> {
                        if (invocation.tryStart()) {
                            applied.incrementAndGet();
                            invocation.complete(new ControlHttpServer.ControlResult(
                                    true, "Accepted.", "BUILDING"
                            ));
                        }
                    });
                    scheduled.countDown();
                }
        );
        try {
            server.start();
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.port() + "/v1/control")
                    )
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .header("X-Supervisor-Token", "shared-token")
                    .POST(HttpRequest.BodyPublishers.ofString(controlBody("RESUME")))
                    .build();
            var response = CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(scheduled.await(2, TimeUnit.SECONDS));
                server.close();

                queued.get().run();
                assertEquals(0, applied.get());
            } finally {
                response.cancel(true);
            }
        } finally {
            server.close();
        }
    }

    @Test
    void unauthenticatedLoopbackMayOnlyReduceActivity() throws Exception {
        AtomicInteger scheduled = new AtomicInteger();
        try (ControlHttpServer server = new ControlHttpServer(
                0,
                null,
                Duration.ofSeconds(1),
                (request, completion) -> {
                    if (!completion.tryStart()) {
                        return;
                    }
                    scheduled.incrementAndGet();
                    completion.complete(new ControlHttpServer.ControlResult(
                            true,
                            "Accepted.",
                            "PAUSED"
                    ));
                }
        )) {
            server.start();

            HttpResponse<String> start = send(
                    server,
                    controlBody("START", "expand-1", OffsetDateTime.now(ZoneOffset.UTC)),
                    null
            );
            HttpResponse<String> pause = send(
                    server,
                    controlBody("PAUSE", "reduce-1", OffsetDateTime.now(ZoneOffset.UTC)),
                    null
            );

            assertEquals(401, start.statusCode());
            assertEquals(200, pause.statusCode());
            assertEquals(1, scheduled.get());
        }
    }

    @Test
    void rejectsStaleAndReplayedRequests() throws Exception {
        AtomicInteger scheduled = new AtomicInteger();
        try (ControlHttpServer server = new ControlHttpServer(
                0,
                "shared-token",
                Duration.ofSeconds(1),
                (request, completion) -> {
                    if (!completion.tryStart()) {
                        return;
                    }
                    scheduled.incrementAndGet();
                    completion.complete(new ControlHttpServer.ControlResult(
                            true,
                            "Accepted.",
                            "PAUSED"
                    ));
                }
        )) {
            server.start();
            String fresh = controlBody(
                    "PAUSE",
                    "one-time",
                    OffsetDateTime.now(ZoneOffset.UTC)
            );

            assertEquals(200, send(server, fresh, "shared-token").statusCode());
            assertEquals(409, send(server, fresh, "shared-token").statusCode());
            assertEquals(
                    400,
                    send(
                            server,
                            controlBody(
                                    "PAUSE",
                                    "stale",
                                    OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2)
                            ),
                            "shared-token"
                    ).statusCode()
            );
            assertEquals(1, scheduled.get());
        }
    }

    @Test
    void onlyExactPostPathAndJsonMediaTypeAreAccepted() throws Exception {
        try (ControlHttpServer server = new ControlHttpServer(
                0,
                null,
                Duration.ofSeconds(1),
                (request, completion) -> {
                    if (completion.tryStart()) {
                        completion.complete(new ControlHttpServer.ControlResult(
                                true, "Accepted.", "BUILDING"
                        ));
                    }
                }
        )) {
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.port());
            HttpRequest get = HttpRequest.newBuilder(base.resolve("/v1/control"))
                    .GET()
                    .build();
            HttpRequest query = HttpRequest.newBuilder(
                            base.resolve("/v1/control?ignored=true")
                    )
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(controlBody("START")))
                    .build();
            HttpRequest text = HttpRequest.newBuilder(base.resolve("/v1/control"))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(controlBody("START")))
                    .build();

            assertEquals(405, CLIENT.send(get, HttpResponse.BodyHandlers.ofString())
                    .statusCode());
            assertEquals(404, CLIENT.send(query, HttpResponse.BodyHandlers.ofString())
                    .statusCode());
            assertEquals(415, CLIENT.send(text, HttpResponse.BodyHandlers.ofString())
                    .statusCode());
        }
    }

    @Test
    void clientsThatStopSendingAreDisconnectedAndCannotHoldBothWorkers() throws Exception {
        try (ControlHttpServer server = new ControlHttpServer(
                0, "shared-token", Duration.ofMillis(100), Duration.ofMillis(500),
                (request, completion) -> { },
                () -> "{\"run_id\":\"run-1\"}".getBytes(StandardCharsets.UTF_8), () -> null)) {
            server.start();
            // One stalls inside an authenticated body, the other before its request line ends,
            // where no handler or token check has run yet.
            try (Socket unfinishedBody = stalledRequest(server,
                         "POST /v1/control HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Supervisor-Token: shared-token\r\n"
                                 + "Content-Type: application/json\r\nContent-Length: 64\r\n\r\n{\"action\"");
                 Socket unfinishedRequestLine = stalledRequest(server, "GET /v1/obs")) {
                HttpRequest observation = HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + server.port() + "/v1/observation"))
                        .timeout(Duration.ofSeconds(5))
                        .header("X-Supervisor-Token", "shared-token")
                        .GET()
                        .build();

                assertEquals(200, CLIENT.send(observation, HttpResponse.BodyHandlers.ofString()).statusCode());
                assertClosedByServer(unfinishedBody);
                assertClosedByServer(unfinishedRequestLine);
                // Interrupted workers serve later exchanges normally.
                for (int attempt = 0; attempt < 4; attempt++) {
                    assertEquals(200, CLIENT.send(observation, HttpResponse.BodyHandlers.ofString())
                            .statusCode());
                }
            }
        }
    }

    @Test
    void exchangeTimeoutMustExceedCallbackTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new ControlHttpServer(
                0, null, Duration.ofSeconds(1), Duration.ofSeconds(1),
                (request, completion) -> { }, () -> null, () -> null));
    }

    private static Socket stalledRequest(ControlHttpServer server, String partialRequest)
            throws IOException {
        Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), server.port());
        socket.getOutputStream().write(partialRequest.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    private static void assertClosedByServer(Socket socket) throws IOException {
        // Without a deadline the server never closes it and this read times out.
        socket.setSoTimeout(5_000);
        try {
            assertEquals(-1, socket.getInputStream().read());
        } catch (SocketException reset) {
            // A reset also shows that the server dropped the connection.
        }
    }

    private static HttpResponse<String> send(
            ControlHttpServer server,
            String body,
            String token
    ) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/v1/control")
                )
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        body,
                        StandardCharsets.UTF_8
                ));
        if (token != null) {
            request.header("X-Supervisor-Token", token);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String controlBody(String action) {
        return controlBody(
                action,
                "request-1",
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    private static String controlBody(
            String action,
            String requestId,
            OffsetDateTime sentAt
    ) {
        return """
                {
                  "action": "%s",
                  "request_id": "%s",
                  "sent_at": "%s"
                }
                """.formatted(action, requestId, sentAt);
    }
}
