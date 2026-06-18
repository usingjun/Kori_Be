package core.domain.aiuser.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("performance")
class OpenAiHttpClientConcurrencyBenchmarkTest {

    private static final int WORKER_COUNT = 10;
    private static final int REQUEST_COUNT = 10;
    private static final int FOLLOW_UP_TASK_COUNT = 50;
    private static final int ROUNDS = 5;
    private static final long SERVER_DELAY_MILLIS = 750;

    @Test
    void compareBlockingAndNonBlockingSchedulerAvailability() throws Exception {
        assumeTrue(enabled(), "Run with OPENAI_WEBCLIENT_BENCHMARK=true");

        try (DelayedHttpServer server = new DelayedHttpServer(SERVER_DELAY_MILLIS)) {
            RestTemplate restTemplate = restTemplate();
            WebClientResources webClientResources = webClient(server.baseUrl());

            try {
                measureBlocking(restTemplate, server);
                measureNonBlocking(webClientResources.webClient(), server);

                List<Measurement> blocking = new ArrayList<>();
                List<Measurement> nonBlocking = new ArrayList<>();

                System.out.println();
                System.out.println("mode,round,totalMs,schedulerProbeDelayMs,followUpP50DelayMs,followUpP95DelayMs,followUpMaxDelayMs");
                for (int round = 1; round <= ROUNDS; round++) {
                    Measurement blockingResult = measureBlocking(restTemplate, server);
                    blocking.add(blockingResult);
                    print("rest-template", round, blockingResult);

                    Measurement nonBlockingResult = measureNonBlocking(webClientResources.webClient(), server);
                    nonBlocking.add(nonBlockingResult);
                    print("web-client", round, nonBlockingResult);
                }

                Summary blockingMedian = summarize(blocking);
                Summary nonBlockingMedian = summarize(nonBlocking);
                System.out.printf(
                        "median,rest-template,%d,%d,%d,%d,%d%n",
                        blockingMedian.totalMillisMedian(),
                        blockingMedian.schedulerProbeDelayMillisMedian(),
                        blockingMedian.followUpP50DelayMillisMedian(),
                        blockingMedian.followUpP95DelayMillisMedian(),
                        blockingMedian.followUpMaxDelayMillisMedian()
                );
                System.out.printf(
                        "median,web-client,%d,%d,%d,%d,%d%n",
                        nonBlockingMedian.totalMillisMedian(),
                        nonBlockingMedian.schedulerProbeDelayMillisMedian(),
                        nonBlockingMedian.followUpP50DelayMillisMedian(),
                        nonBlockingMedian.followUpP95DelayMillisMedian(),
                        nonBlockingMedian.followUpMaxDelayMillisMedian()
                );

                assertThat(blockingMedian.schedulerProbeDelayMillisMedian())
                        .isGreaterThanOrEqualTo(SERVER_DELAY_MILLIS / 2);
                assertThat(nonBlockingMedian.schedulerProbeDelayMillisMedian())
                        .isLessThan(blockingMedian.schedulerProbeDelayMillisMedian() / 4);
                assertThat(nonBlockingMedian.followUpP95DelayMillisMedian())
                        .isLessThan(blockingMedian.followUpP95DelayMillisMedian() / 4);
            } finally {
                webClientResources.connectionProvider().dispose();
            }
        }
    }

    private Measurement measureBlocking(RestTemplate restTemplate, DelayedHttpServer server) throws Exception {
        ExecutorService scheduler = Executors.newFixedThreadPool(WORKER_COUNT);
        CountDownLatch requestsCompleted = new CountDownLatch(REQUEST_COUNT);
        server.prepare(REQUEST_COUNT);

        try {
            long startedAt = System.nanoTime();
            for (int index = 0; index < REQUEST_COUNT; index++) {
                scheduler.submit(() -> {
                    try {
                        restTemplate.getForObject(server.baseUrl() + "/responses", String.class);
                    } finally {
                        requestsCompleted.countDown();
                    }
                });
            }

            server.awaitAllRequests();
            long probeStartedAt = System.nanoTime();
            Future<Long> probe = scheduler.submit(() -> elapsedMillis(probeStartedAt));
            FollowUpStats followUpStats = submitFollowUpTasks(scheduler, probeStartedAt);
            long probeDelayMillis = probe.get(5, TimeUnit.SECONDS);

            assertThat(requestsCompleted.await(5, TimeUnit.SECONDS)).isTrue();
            return new Measurement(elapsedMillis(startedAt), probeDelayMillis, followUpStats);
        } finally {
            scheduler.shutdownNow();
        }
    }

    private Measurement measureNonBlocking(WebClient webClient, DelayedHttpServer server) throws Exception {
        ExecutorService scheduler = Executors.newFixedThreadPool(WORKER_COUNT);
        CountDownLatch requestsCompleted = new CountDownLatch(REQUEST_COUNT);
        server.prepare(REQUEST_COUNT);

        try {
            long startedAt = System.nanoTime();
            for (int index = 0; index < REQUEST_COUNT; index++) {
                scheduler.submit(() -> webClient.get()
                        .uri("/responses")
                        .retrieve()
                        .bodyToMono(String.class)
                        .doFinally(signalType -> requestsCompleted.countDown())
                        .subscribe());
            }

            server.awaitAllRequests();
            long probeStartedAt = System.nanoTime();
            Future<Long> probe = scheduler.submit(() -> elapsedMillis(probeStartedAt));
            FollowUpStats followUpStats = submitFollowUpTasks(scheduler, probeStartedAt);
            long probeDelayMillis = probe.get(5, TimeUnit.SECONDS);

            assertThat(requestsCompleted.await(5, TimeUnit.SECONDS)).isTrue();
            return new Measurement(elapsedMillis(startedAt), probeDelayMillis, followUpStats);
        } finally {
            scheduler.shutdownNow();
        }
    }

    private RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new RestTemplate(factory);
    }

    private WebClientResources webClient(String baseUrl) {
        ConnectionProvider connectionProvider = ConnectionProvider.builder("openai-benchmark")
                .maxConnections(WORKER_COUNT)
                .pendingAcquireMaxCount(100)
                .build();
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .responseTimeout(Duration.ofSeconds(5));
        WebClient webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        return new WebClientResources(webClient, connectionProvider);
    }

    private void print(String mode, int round, Measurement measurement) {
        System.out.printf(
                "%s,%d,%d,%d,%d,%d,%d%n",
                mode,
                round,
                measurement.totalMillis(),
                measurement.schedulerProbeDelayMillis(),
                measurement.followUpStats().p50DelayMillis(),
                measurement.followUpStats().p95DelayMillis(),
                measurement.followUpStats().maxDelayMillis()
        );
    }

    private FollowUpStats submitFollowUpTasks(ExecutorService scheduler, long submittedAt) throws Exception {
        List<Future<Long>> futures = new ArrayList<>();
        for (int index = 0; index < FOLLOW_UP_TASK_COUNT; index++) {
            futures.add(scheduler.submit(() -> elapsedMillis(submittedAt)));
        }

        List<Long> delays = new ArrayList<>();
        for (Future<Long> future : futures) {
            delays.add(future.get(5, TimeUnit.SECONDS));
        }
        delays.sort(Long::compareTo);

        return new FollowUpStats(
                percentile(delays, 50),
                percentile(delays, 95),
                delays.get(delays.size() - 1)
        );
    }

    private Summary summarize(List<Measurement> measurements) {
        List<Long> totalMillis = measurements.stream()
                .map(Measurement::totalMillis)
                .sorted()
                .toList();
        List<Long> probeDelayMillis = measurements.stream()
                .map(Measurement::schedulerProbeDelayMillis)
                .sorted()
                .toList();
        List<Long> followUpP50DelayMillis = measurements.stream()
                .map(measurement -> measurement.followUpStats().p50DelayMillis())
                .sorted()
                .toList();
        List<Long> followUpP95DelayMillis = measurements.stream()
                .map(measurement -> measurement.followUpStats().p95DelayMillis())
                .sorted()
                .toList();
        List<Long> followUpMaxDelayMillis = measurements.stream()
                .map(measurement -> measurement.followUpStats().maxDelayMillis())
                .sorted()
                .toList();
        int medianIndex = measurements.size() / 2;
        return new Summary(
                totalMillis.get(medianIndex),
                probeDelayMillis.get(medianIndex),
                followUpP50DelayMillis.get(medianIndex),
                followUpP95DelayMillis.get(medianIndex),
                followUpMaxDelayMillis.get(medianIndex)
        );
    }

    private long percentile(List<Long> sortedValues, int percentile) {
        int index = (int) Math.ceil((percentile / 100.0) * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("OPENAI_WEBCLIENT_BENCHMARK"))
                || Boolean.getBoolean("OPENAI_WEBCLIENT_BENCHMARK");
    }

    private record Measurement(long totalMillis, long schedulerProbeDelayMillis, FollowUpStats followUpStats) {
    }

    private record FollowUpStats(long p50DelayMillis, long p95DelayMillis, long maxDelayMillis) {
    }

    private record Summary(
            long totalMillisMedian,
            long schedulerProbeDelayMillisMedian,
            long followUpP50DelayMillisMedian,
            long followUpP95DelayMillisMedian,
            long followUpMaxDelayMillisMedian
    ) {
    }

    private record WebClientResources(WebClient webClient, ConnectionProvider connectionProvider) {
    }

    private static final class DelayedHttpServer implements AutoCloseable {

        private static final byte[] RESPONSE = "{\"output\":[]}".getBytes(StandardCharsets.UTF_8);

        private final HttpServer server;
        private final ExecutorService executor = Executors.newFixedThreadPool(REQUEST_COUNT * 2);
        private final long delayMillis;
        private volatile CountDownLatch requestsArrived = new CountDownLatch(0);

        private DelayedHttpServer(long delayMillis) throws IOException {
            this.delayMillis = delayMillis;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/responses", this::respondAfterDelay);
            server.setExecutor(executor);
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void prepare(int expectedRequests) {
            requestsArrived = new CountDownLatch(expectedRequests);
        }

        private void awaitAllRequests() throws InterruptedException {
            assertThat(requestsArrived.await(5, TimeUnit.SECONDS)).isTrue();
        }

        private void respondAfterDelay(HttpExchange exchange) throws IOException {
            requestsArrived.countDown();
            try {
                Thread.sleep(delayMillis);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, RESPONSE.length);
                exchange.getResponseBody().write(RESPONSE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
