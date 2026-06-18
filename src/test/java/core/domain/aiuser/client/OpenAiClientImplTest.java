package core.domain.aiuser.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiClientImplTest {

    @Test
    void generateResponse_extractsOutputText() {
        AtomicInteger requests = new AtomicInteger();
        OpenAiClientImpl client = client(request -> {
            requests.incrementAndGet();
            return Mono.just(jsonResponse(HttpStatus.OK, successBody("hello")));
        });

        StepVerifier.create(client.generateResponse(List.of(Map.of("role", "user", "content", "hi"))))
                .expectNext("hello")
                .verifyComplete();

        assertThat(requests).hasValue(1);
    }

    @Test
    void generateResponse_retriesServerErrorsWithoutBlockingThread() {
        AtomicInteger requests = new AtomicInteger();
        OpenAiClientImpl client = client(request -> {
            int attempt = requests.incrementAndGet();
            if (attempt < 3) {
                return Mono.just(jsonResponse(HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"temporary\"}"));
            }
            return Mono.just(jsonResponse(HttpStatus.OK, successBody("recovered")));
        });

        StepVerifier.withVirtualTime(() -> client.generateResponse(List.of()))
                .thenAwait(Duration.ofSeconds(4))
                .expectNext("recovered")
                .verifyComplete();

        assertThat(requests).hasValue(3);
    }

    @Test
    void generateResponse_doesNotRetryNonRateLimitedClientErrors() {
        AtomicInteger requests = new AtomicInteger();
        OpenAiClientImpl client = client(request -> {
            requests.incrementAndGet();
            return Mono.just(jsonResponse(HttpStatus.BAD_REQUEST, "{\"error\":\"invalid request\"}"));
        });

        StepVerifier.create(client.generateResponse(List.of()))
                .verifyComplete();

        assertThat(requests).hasValue(1);
    }

    private OpenAiClientImpl client(ExchangeFunction exchangeFunction) {
        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.openai.test/v1")
                .exchangeFunction(exchangeFunction)
                .build();
        return new OpenAiClientImpl(webClient);
    }

    private ClientResponse jsonResponse(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private String successBody(String text) {
        return """
                {
                  "output": [
                    {
                      "type": "message",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "%s"
                        }
                      ]
                    }
                  ]
                }
                """.formatted(text);
    }
}
