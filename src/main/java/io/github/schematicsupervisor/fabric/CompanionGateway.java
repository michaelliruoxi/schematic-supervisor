package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.AdviceSnapshot;
import io.github.schematicsupervisor.core.RecoveryAdvice;
import io.github.schematicsupervisor.core.RecoveryIncident;
import io.github.schematicsupervisor.core.SupervisorPorts;
import io.github.schematicsupervisor.core.SupervisorStatus;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Serial;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Asynchronous loopback gateway for status publication and constrained recovery advice.
 */
final class CompanionGateway implements SupervisorPorts.Advisor, AutoCloseable {
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final AdviceSnapshot IDLE = new AdviceSnapshot(
            io.github.schematicsupervisor.core.AdviceStatus.IDLE,
            java.util.Optional.empty(),
            ""
    );

    private final URI incidentUri;
    private final URI statusUri;
    private final String token;
    private final Duration requestTimeout;
    private final Clock clock;
    private final ExecutorService executor;
    private final HttpClient client;
    private final Object adviceLock = new Object();
    private final Object statusLock = new Object();

    private AdviceSnapshot adviceSnapshot = IDLE;
    private AdviceExchange adviceExchange;
    private long adviceGeneration;
    private CompletableFuture<Boolean> statusPublication;
    private CompletableFuture<HttpResponse<byte[]>> statusRequest;
    private volatile boolean closed;

    CompanionGateway(SupervisorSettings settings) {
        this(settings.companionUri(), settings.token());
    }

    CompanionGateway(URI companionUri, String token) {
        this(companionUri, token, DEFAULT_REQUEST_TIMEOUT, Clock.systemUTC());
    }

    CompanionGateway(
            URI companionUri,
            String token,
            Duration requestTimeout,
            Clock clock
    ) {
        URI safeBase = requireLoopbackBase(companionUri);
        this.incidentUri = safeBase.resolve("/v1/incidents");
        this.statusUri = safeBase.resolve("/v1/status");
        this.token = validateToken(token);
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Executors.newFixedThreadPool(
                2,
                Thread.ofPlatform()
                        .daemon()
                        .name("schematic-supervisor-companion-", 0)
                        .factory()
        );
        this.client = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(executor)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public void beginAdvice(RecoveryIncident incident) {
        Objects.requireNonNull(incident, "incident");
        SupervisorProtocolJson.EncodedIncident encoded =
                SupervisorProtocolJson.encodeIncident(incident, clock.instant());
        synchronized (adviceLock) {
            requireOpen();
            cancelAdviceLocked();
            long generation = ++adviceGeneration;
            adviceSnapshot = AdviceSnapshot.pending();
            HttpRequest request = request(incidentUri, encoded.body());
            try {
                CompletableFuture<HttpResponse<byte[]>> raw = sendBounded(request);
                CompletableFuture<RecoveryAdvice> parsed = raw.thenApplyAsync(
                        response -> parseAdviceResponse(response, encoded.incidentId()),
                        executor
                );
                AdviceExchange exchange = new AdviceExchange(raw, parsed);
                adviceExchange = exchange;
                parsed.whenComplete((advice, failure) ->
                        completeAdvice(generation, exchange, advice, failure));
            } catch (RuntimeException exception) {
                adviceSnapshot = AdviceSnapshot.unavailable("Companion request unavailable");
                adviceExchange = null;
            }
        }
    }

    @Override
    public AdviceSnapshot pollAdvice() {
        synchronized (adviceLock) {
            return adviceSnapshot;
        }
    }

    @Override
    public void cancelAdvice() {
        synchronized (adviceLock) {
            adviceGeneration++;
            cancelAdviceLocked();
            adviceSnapshot = IDLE;
        }
    }

    /**
     * Publishes a coordinate-free status snapshot. A second publish while one is in flight
     * is skipped and completes with {@code false}.
     */
    CompletableFuture<Boolean> publishStatus(
            SupervisorStatus status,
            String baritoneStatus
    ) {
        Objects.requireNonNull(status, "status");
        byte[] body = SupervisorProtocolJson.encodeStatus(
                status,
                baritoneStatus,
                clock.instant()
        );
        synchronized (statusLock) {
            requireOpen();
            if (statusPublication != null && !statusPublication.isDone()) {
                return CompletableFuture.completedFuture(false);
            }
            HttpRequest request = request(statusUri, body);
            try {
                CompletableFuture<HttpResponse<byte[]>> raw = sendBounded(request);
                statusRequest = raw;
                statusPublication = raw.thenApplyAsync(this::parseStatusResponse, executor)
                        .exceptionally(ignored -> false);
                statusPublication.whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        raw.cancel(true);
                    }
                    synchronized (statusLock) {
                        if (statusRequest == raw) {
                            statusRequest = null;
                        }
                    }
                });
                return statusPublication;
            } catch (RuntimeException exception) {
                return CompletableFuture.completedFuture(false);
            }
        }
    }

    @Override
    public void close() {
        synchronized (adviceLock) {
            if (closed) {
                return;
            }
            closed = true;
            adviceGeneration++;
            cancelAdviceLocked();
            adviceSnapshot = IDLE;
        }
        synchronized (statusLock) {
            if (statusRequest != null) {
                CompletableFuture<HttpResponse<byte[]>> request = statusRequest;
                request.cancel(true);
                statusRequest = null;
            }
            if (statusPublication != null) {
                statusPublication.cancel(true);
                statusPublication = null;
            }
        }
        executor.shutdownNow();
    }

    private RecoveryAdvice parseAdviceResponse(
            HttpResponse<byte[]> response,
            String expectedIncidentId
    ) {
        try (ByteArrayInputStream body = new ByteArrayInputStream(response.body())) {
            requireSuccessfulJson(response, 200);
            return SupervisorProtocolJson.decodeAdvice(
                    body,
                    SupervisorProtocolJson.MAX_RESPONSE_BYTES,
                    expectedIncidentId
            );
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private boolean parseStatusResponse(HttpResponse<byte[]> response) {
        try (ByteArrayInputStream body = new ByteArrayInputStream(response.body())) {
            requireSuccessfulJson(response, 202);
            return SupervisorProtocolJson.decodeStatusAcknowledgement(
                    body,
                    SupervisorProtocolJson.MAX_RESPONSE_BYTES
            );
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private void completeAdvice(
            long generation,
            AdviceExchange exchange,
            RecoveryAdvice advice,
            Throwable failure
    ) {
        synchronized (adviceLock) {
            if (generation != adviceGeneration || adviceExchange != exchange) {
                return;
            }
            adviceExchange = null;
            if (failure == null) {
                adviceSnapshot = AdviceSnapshot.succeeded(advice);
                return;
            }
            Throwable cause = unwrap(failure);
            if (cause instanceof CancellationException) {
                adviceSnapshot = IDLE;
            } else if (cause instanceof IOException
                    || cause instanceof TimeoutException
                    || cause instanceof CompanionUnavailableException) {
                adviceSnapshot = AdviceSnapshot.unavailable("Companion request unavailable");
            } else {
                adviceSnapshot = AdviceSnapshot.failed("Companion response rejected");
            }
        }
    }

    private void cancelAdviceLocked() {
        AdviceExchange exchange = adviceExchange;
        adviceExchange = null;
        if (exchange != null) {
            exchange.parsed().cancel(true);
            exchange.raw().cancel(true);
        }
    }

    private CompletableFuture<HttpResponse<byte[]>> sendBounded(HttpRequest request) {
        CompletableFuture<HttpResponse<byte[]>> raw = client.sendAsync(
                request,
                ignored -> new BoundedBodySubscriber(SupervisorProtocolJson.MAX_RESPONSE_BYTES)
        );
        // Keep the deadline separate so timing out can still cancel the HTTP exchange.
        // A request timeout alone may stop applying once response headers arrive.
        CompletableFuture<HttpResponse<byte[]>> bounded = raw.copy().orTimeout(
                requestTimeout.toNanos(),
                TimeUnit.NANOSECONDS
        );
        bounded.whenComplete((response, failure) -> {
            if (failure != null) {
                raw.cancel(true);
            }
        });
        return bounded;
    }

    private HttpRequest request(URI uri, byte[] body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) {
            builder.header("X-Supervisor-Token", token);
        }
        return builder.build();
    }

    private static void requireSuccessfulJson(
            HttpResponse<?> response,
            int expectedStatus
    ) {
        if (response.statusCode() != expectedStatus) {
            throw new CompanionUnavailableException(
                    "companion returned HTTP " + response.statusCode()
            );
        }
        List<String> values = response.headers().allValues("Content-Type");
        if (values.size() != 1) {
            throw new SupervisorProtocolJson.ProtocolException(
                    "companion response must declare one Content-Type"
            );
        }
        String mediaType = values.getFirst().split(";", 2)[0].strip()
                .toLowerCase(Locale.ROOT);
        if (!"application/json".equals(mediaType)) {
            throw new SupervisorProtocolJson.ProtocolException(
                    "companion response must be application/json"
            );
        }
    }

    private static URI requireLoopbackBase(URI uri) {
        Objects.requireNonNull(uri, "companionUri");
        if (!"http".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getPort() < 1
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "companion URI must be an absolute loopback HTTP origin"
            );
        }
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalArgumentException("companion URI must not contain a path");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            if (addresses.length == 0) {
                throw new IllegalArgumentException("companion host did not resolve");
            }
            for (InetAddress address : addresses) {
                if (!address.isLoopbackAddress()) {
                    throw new IllegalArgumentException(
                            "companion URI must resolve only to loopback"
                    );
                }
            }
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("companion host could not be resolved", exception);
        }
        return uri;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String validateToken(String value) {
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
        return value;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("companion gateway is closed");
        }
    }

    private record AdviceExchange(
            CompletableFuture<HttpResponse<byte[]>> raw,
            CompletableFuture<RecoveryAdvice> parsed
    ) {
    }

    private static final class BoundedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private final int maximumBytes;
        private Flow.Subscription subscription;
        private int receivedBytes;
        private boolean complete;

        private BoundedBodySubscriber(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (complete) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maximumBytes - receivedBytes) {
                    complete = true;
                    subscription.cancel();
                    delegate.onError(new SupervisorProtocolJson.ProtocolException(
                            "companion response exceeds the size limit"
                    ));
                    return;
                }
                receivedBytes += buffer.remaining();
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable failure) {
            if (!complete) {
                complete = true;
                delegate.onError(failure);
            }
        }

        @Override
        public void onComplete() {
            if (!complete) {
                complete = true;
                delegate.onComplete();
            }
        }
    }

    private static final class CompanionUnavailableException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private CompanionUnavailableException(String message) {
            super(message);
        }
    }
}
