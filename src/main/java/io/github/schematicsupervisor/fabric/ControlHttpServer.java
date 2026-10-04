package io.github.schematicsupervisor.fabric;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Loopback-only observations and bounded operator controls.
 */
final class ControlHttpServer implements AutoCloseable {
    private static final Duration DEFAULT_CALLBACK_TIMEOUT = Duration.ofSeconds(3);
    // Time beyond the callback timeout for a client to send its request and read the response.
    private static final Duration REQUEST_ALLOWANCE = Duration.ofSeconds(5);
    private static final int BACKLOG = 16;
    private static final int HTTP_OK = 200;
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_CONFLICT = 409;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_METHOD_NOT_ALLOWED = 405;
    private static final int HTTP_LENGTH_REQUIRED = 411;
    private static final int HTTP_PAYLOAD_TOO_LARGE = 413;
    private static final int HTTP_UNSUPPORTED_MEDIA_TYPE = 415;
    private static final int HTTP_GATEWAY_TIMEOUT = 504;
    private static final int HTTP_INTERNAL_ERROR = 500;
    private static final Duration MAX_REQUEST_AGE = Duration.ofSeconds(30);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofSeconds(5);
    private static final int RECENT_REQUEST_LIMIT = 256;

    private final HttpServer server;
    private final ExecutorService executor;
    private final ScheduledThreadPoolExecutor deadlines;
    private final long exchangeTimeoutNanos;
    private final byte[] token;
    private final Duration callbackTimeout;
    private final ClientThreadControl callback;
    private final Supplier<byte[]> observation;
    private final Supplier<byte[]> progress;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private final Set<ControlInvocation> pendingControls = new HashSet<>();
    private final LinkedHashMap<String, Instant> recentRequestIds = new LinkedHashMap<>();

    ControlHttpServer(
            SupervisorSettings settings,
            ClientThreadControl callback
    ) throws IOException {
        this(settings, callback, null);
    }

    ControlHttpServer(
            SupervisorSettings settings,
            ClientThreadControl callback,
            Supplier<byte[]> observation
    ) throws IOException {
        this(settings, callback, observation, null);
    }

    ControlHttpServer(
            SupervisorSettings settings,
            ClientThreadControl callback,
            Supplier<byte[]> observation,
            Supplier<byte[]> progress
    ) throws IOException {
        this(
                settings.controlPort(),
                settings.token(),
                DEFAULT_CALLBACK_TIMEOUT,
                callback,
                observation,
                progress
        );
    }

    ControlHttpServer(
            int port,
            String token,
            Duration callbackTimeout,
            ClientThreadControl callback
    ) throws IOException {
        this(port, token, callbackTimeout, callback, null);
    }

    ControlHttpServer(
            int port,
            String token,
            Duration callbackTimeout,
            ClientThreadControl callback,
            Supplier<byte[]> observation
    ) throws IOException {
        this(port, token, callbackTimeout, callback, observation, null);
    }

    ControlHttpServer(
            int port,
            String token,
            Duration callbackTimeout,
            ClientThreadControl callback,
            Supplier<byte[]> observation,
            Supplier<byte[]> progress
    ) throws IOException {
        this(
                port,
                token,
                callbackTimeout,
                Objects.requireNonNull(callbackTimeout, "callbackTimeout").plus(REQUEST_ALLOWANCE),
                callback,
                observation,
                progress
        );
    }

    /**
     * {@code exchangeTimeout} bounds each whole exchange, from its request line to its response,
     * so it must exceed {@code callbackTimeout}.
     */
    ControlHttpServer(
            int port,
            String token,
            Duration callbackTimeout,
            Duration exchangeTimeout,
            ClientThreadControl callback,
            Supplier<byte[]> observation,
            Supplier<byte[]> progress
    ) throws IOException {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        this.callbackTimeout = Objects.requireNonNull(callbackTimeout, "callbackTimeout");
        if (callbackTimeout.isZero() || callbackTimeout.isNegative()) {
            throw new IllegalArgumentException("callbackTimeout must be positive");
        }
        if (Objects.requireNonNull(exchangeTimeout, "exchangeTimeout").compareTo(callbackTimeout) <= 0) {
            throw new IllegalArgumentException("exchangeTimeout must exceed callbackTimeout");
        }
        this.exchangeTimeoutNanos = exchangeTimeout.toNanos();
        this.callback = Objects.requireNonNull(callback, "callback");
        this.observation = observation;
        this.progress = progress;
        this.token = tokenBytes(token);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        this.server = HttpServer.create(new InetSocketAddress(loopback, port), BACKLOG);
        this.executor = Executors.newFixedThreadPool(
                2,
                Thread.ofPlatform()
                        .daemon()
                        .name("schematic-supervisor-control-", 0)
                        .factory()
        );
        this.deadlines = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform()
                        .daemon()
                        .name("schematic-supervisor-control-deadline")
                        .factory()
        );
        deadlines.setRemoveOnCancelPolicy(true);
        server.setExecutor(this::runWithDeadline);
        server.createContext("/", this::handle);
    }

    void start() {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("control server is closed");
            }
            if (started.compareAndSet(false, true)) {
                server.start();
            }
        }
    }

    int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            for (ControlInvocation invocation : pendingControls) {
                invocation.cancelPending();
            }
        }
        server.stop(0);
        executor.shutdownNow();
        deadlines.shutdownNow();
    }

    /**
     * Runs one exchange on a worker under a deadline. The JDK server reads the request line,
     * headers and body on these two workers, before and inside {@link #handle}, so a client that
     * stops sending would otherwise hold a worker indefinitely and block observations and
     * Pause/Stop, with or without a token. The blocking channel read is interruptible, so the
     * interrupt closes that connection.
     */
    private void runWithDeadline(Runnable exchange) {
        executor.execute(() -> {
            ExchangeDeadline deadline = new ExchangeDeadline(Thread.currentThread());
            ScheduledFuture<?> timer;
            try {
                timer = deadlines.schedule(deadline, exchangeTimeoutNanos, TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException closing) {
                // close() already stopped the server, which closes this connection.
                return;
            }
            try {
                exchange.run();
            } finally {
                deadline.finish();
                timer.cancel(false);
            }
        });
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                send(exchange, HTTP_UNAUTHORIZED, rejected("Loopback clients only."));
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            if (exchange.getRequestURI().getRawQuery() != null
                    || (!"/v1/control".equals(path) && !"/v1/observation".equals(path)
                    && !"/v1/progress".equals(path))) {
                send(exchange, HTTP_NOT_FOUND, rejected("Not found."));
                return;
            }
            if ("/v1/observation".equals(path)) {
                handleSnapshot(exchange, observation, "Observation is not ready; wait for a client tick.");
                return;
            }
            if ("/v1/progress".equals(path)) {
                handleSnapshot(exchange, progress, "Progress is not ready; wait for a client tick.");
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                send(exchange, HTTP_METHOD_NOT_ALLOWED, rejected("Method not allowed."));
                return;
            }
            if (!authorized(exchange.getRequestHeaders())) {
                send(exchange, HTTP_UNAUTHORIZED, rejected("Unauthorized."));
                return;
            }
            if (!hasJsonContentType(exchange.getRequestHeaders())) {
                send(
                        exchange,
                        HTTP_UNSUPPORTED_MEDIA_TYPE,
                        rejected("Content-Type must be application/json.")
                );
                return;
            }
            Integer contentLength = contentLength(exchange.getRequestHeaders());
            if (contentLength == null) {
                send(
                        exchange,
                        HTTP_LENGTH_REQUIRED,
                        rejected("Content-Length is required.")
                );
                return;
            }
            if (contentLength > SupervisorProtocolJson.MAX_CONTROL_REQUEST_BYTES) {
                send(
                        exchange,
                        HTTP_PAYLOAD_TOO_LARGE,
                        rejected("Request body exceeds the size limit.")
                );
                return;
            }
            byte[] body;
            try (InputStream requestBody = exchange.getRequestBody()) {
                body = SupervisorProtocolJson.readBounded(
                        requestBody,
                        SupervisorProtocolJson.MAX_CONTROL_REQUEST_BYTES
                );
            } catch (SupervisorProtocolJson.ProtocolException exception) {
                send(
                        exchange,
                        HTTP_PAYLOAD_TOO_LARGE,
                        rejected("Request body exceeds the size limit.")
                );
                return;
            }
            if (body.length != contentLength) {
                send(exchange, HTTP_BAD_REQUEST, rejected("Content-Length is incorrect."));
                return;
            }

            ControlRequest request;
            try {
                request = SupervisorProtocolJson.decodeControlRequest(body);
            } catch (SupervisorProtocolJson.ProtocolException exception) {
                send(exchange, HTTP_BAD_REQUEST, rejected(exception.getMessage()));
                return;
            }
            if (token == null
                    && (request.action() == ControlAction.START
                    || request.action() == ControlAction.RESUME
                    || request.action() == ControlAction.SCAN_DEPOTS)) {
                send(
                        exchange,
                        HTTP_UNAUTHORIZED,
                        rejected("START, RESUME and SCAN_DEPOTS require SCHEMATIC_PROTOCOL_TOKEN.")
                );
                return;
            }
            String freshnessError = registerFreshRequest(request);
            if (freshnessError != null) {
                send(
                        exchange,
                        freshnessError.startsWith("Duplicate")
                                ? HTTP_CONFLICT
                                : HTTP_BAD_REQUEST,
                        rejected(freshnessError)
                );
                return;
            }

            ControlInvocation invocation = new ControlInvocation();
            synchronized (lifecycleLock) {
                if (closed.get()) {
                    invocation.cancelPending();
                } else {
                    pendingControls.add(invocation);
                }
            }
            try {
                try {
                    callback.schedule(request, invocation);
                } catch (RuntimeException exception) {
                    invocation.cancelPending();
                    send(
                            exchange,
                            HTTP_INTERNAL_ERROR,
                            rejected("Control could not be scheduled.")
                    );
                    return;
                }

                ControlResult result;
                try {
                    result = invocation.completion.get(
                            invocation.remainingNanos(),
                            TimeUnit.NANOSECONDS
                    );
                    if (result == null) {
                        result = rejected("Control returned no result.");
                    }
                } catch (TimeoutException exception) {
                    boolean cancelled = invocation.cancelPending();
                    send(
                            exchange,
                            HTTP_GATEWAY_TIMEOUT,
                            rejected(cancelled
                                    ? "Control timed out before execution and was cancelled."
                                    : "Control execution timed out; outcome is unknown. "
                                            + "Check status before retrying.")
                    );
                    return;
                } catch (InterruptedException exception) {
                    invocation.cancelPending();
                    Thread.currentThread().interrupt();
                    send(
                            exchange,
                            HTTP_INTERNAL_ERROR,
                            rejected("Control request was interrupted.")
                    );
                    return;
                } catch (ExecutionException exception) {
                    invocation.cancelPending();
                    send(
                            exchange,
                            HTTP_INTERNAL_ERROR,
                            rejected("Control failed on the client thread.")
                    );
                    return;
                }
                send(exchange, HTTP_OK, result);
            } finally {
                synchronized (lifecycleLock) {
                    pendingControls.remove(invocation);
                }
            }
        } catch (RuntimeException exception) {
            if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
                send(exchange, HTTP_INTERNAL_ERROR, rejected("Internal control error."));
            }
        }
    }

    private void handleSnapshot(HttpExchange exchange, Supplier<byte[]> source, String notReady)
            throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            send(exchange, HTTP_METHOD_NOT_ALLOWED, rejected("Method not allowed."));
            return;
        }
        if (!authorized(exchange.getRequestHeaders())) {
            send(exchange, HTTP_UNAUTHORIZED, rejected("Unauthorized."));
            return;
        }
        byte[] snapshot = source == null ? null : source.get();
        if (snapshot == null) {
            send(exchange, 503, rejected(notReady));
            return;
        }
        sendBytes(exchange, HTTP_OK, snapshot);
    }

    private boolean authorized(Headers headers) {
        if (token == null) {
            return true;
        }
        List<String> values = headers.get("X-Supervisor-Token");
        if (values == null || values.size() != 1) {
            return false;
        }
        return MessageDigest.isEqual(
                token,
                values.getFirst().getBytes(StandardCharsets.UTF_8)
        );
    }

    private synchronized String registerFreshRequest(ControlRequest request) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant sent = request.sentAt().toInstant();
        if (sent.isBefore(now.minus(MAX_REQUEST_AGE))) {
            return "Control request is stale.";
        }
        if (sent.isAfter(now.plus(MAX_FUTURE_SKEW))) {
            return "Control request timestamp is in the future.";
        }
        if (recentRequestIds.containsKey(request.requestId())) {
            return "Duplicate control request_id.";
        }
        recentRequestIds.put(request.requestId(), sent);
        while (recentRequestIds.size() > RECENT_REQUEST_LIMIT) {
            String oldest = recentRequestIds.keySet().iterator().next();
            recentRequestIds.remove(oldest);
        }
        return null;
    }

    private static boolean hasJsonContentType(Headers headers) {
        List<String> values = headers.get("Content-Type");
        if (values == null || values.size() != 1) {
            return false;
        }
        String mediaType = values.getFirst().split(";", 2)[0].strip()
                .toLowerCase(Locale.ROOT);
        return "application/json".equals(mediaType);
    }

    private static Integer contentLength(Headers headers) {
        List<String> values = headers.get("Content-Length");
        if (values == null || values.size() != 1) {
            return null;
        }
        String value = values.getFirst();
        if (value.isEmpty()) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return null;
            }
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            return (int) parsed;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static void send(
            HttpExchange exchange,
            int status,
            ControlResult result
    ) throws IOException {
        sendBytes(exchange, status, SupervisorProtocolJson.encodeControlResult(result));
    }

    private static void sendBytes(HttpExchange exchange, int status, byte[] body)
            throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(body);
        }
    }

    private static ControlResult rejected(String message) {
        return new ControlResult(false, message, "UNKNOWN");
    }

    private static byte[] tokenBytes(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.length() > 4_096) {
            throw new IllegalArgumentException("token exceeds its length limit");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException("token contains a control character");
            }
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    enum ControlAction {
        START,
        PAUSE,
        RESUME,
        STOP,
        SCAN_DEPOTS
    }

    record ControlRequest(
            ControlAction action,
            String requestId,
            OffsetDateTime sentAt,
            String expectedRunId,
            String expectedState,
            Long expectedControlSequence
    ) {
        ControlRequest(ControlAction action, String requestId, OffsetDateTime sentAt) {
            this(action, requestId, sentAt, null, null, null);
        }

        ControlRequest {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(sentAt, "sentAt");
            if ((expectedRunId == null) != (expectedState == null)) {
                throw new IllegalArgumentException("both control preconditions must be supplied");
            }
            if (expectedControlSequence != null && expectedControlSequence < 0) {
                throw new IllegalArgumentException("expected control sequence must not be negative");
            }
        }

        boolean matches(String runId, String state, long controlSequence) {
            return (expectedRunId == null
                    || (expectedRunId.equals(runId) && expectedState.equals(state)))
                    && (expectedControlSequence == null
                    || expectedControlSequence == controlSequence);
        }
    }

    record ControlResult(boolean accepted, String message, String state) {
        ControlResult {
            message = normalize(message, accepted ? "Accepted." : "Rejected.", 1_000);
            state = normalize(state, "UNKNOWN", 128);
        }

        private static String normalize(String value, String fallback, int maximumLength) {
            String selected = value == null || value.isBlank() ? fallback : value.strip();
            if (selected.length() > maximumLength) {
                selected = selected.substring(0, maximumLength);
            }
            StringBuilder safe = new StringBuilder(selected.length());
            for (int index = 0; index < selected.length(); index++) {
                char character = selected.charAt(index);
                safe.append(Character.isISOControl(character) ? ' ' : character);
            }
            return safe.toString();
        }
    }

    /** Interrupts its worker once the exchange outlives the deadline, and never after it finished. */
    private static final class ExchangeDeadline implements Runnable {
        private final Thread worker;
        private boolean finished;

        ExchangeDeadline(Thread worker) {
            this.worker = worker;
        }

        @Override
        public synchronized void run() {
            if (!finished) {
                worker.interrupt();
            }
        }

        /** Called on the worker; a deadline that fired during this exchange must not reach its next one. */
        synchronized void finish() {
            finished = true;
            Thread.interrupted();
        }
    }

    @FunctionalInterface
    interface ClientThreadControl {
        /**
         * Schedules the request onto the client thread. The runnable must claim the invocation
         * with {@code tryStart()} immediately before applying the control, then complete it.
         */
        void schedule(ControlRequest request, ControlInvocation invocation);
    }

    final class ControlInvocation {
        private final CompletableFuture<ControlResult> completion = new CompletableFuture<>();
        private final long deadlineNanos = System.nanoTime() + callbackTimeout.toNanos();
        private boolean running;
        private boolean cancelled;

        boolean tryStart() {
            synchronized (lifecycleLock) {
                if (running || cancelled) {
                    return false;
                }
                if (closed.get() || remainingNanos() == 0) {
                    cancelled = true;
                    return false;
                }
                running = true;
                return true;
            }
        }

        void complete(ControlResult result) {
            synchronized (lifecycleLock) {
                if (running) {
                    completion.complete(result);
                }
            }
        }

        private boolean cancelPending() {
            synchronized (lifecycleLock) {
                if (!running) {
                    cancelled = true;
                }
                return cancelled;
            }
        }

        private long remainingNanos() {
            return Math.max(0L, deadlineNanos - System.nanoTime());
        }
    }
}
