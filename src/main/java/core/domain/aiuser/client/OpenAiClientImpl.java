package core.domain.aiuser.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;


@Slf4j
@Component
public class OpenAiClientImpl implements AiClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private final WebClient openAiWebClient;

    public OpenAiClientImpl(@Qualifier("openAiWebClient") WebClient openAiWebClient) {
        this.openAiWebClient = openAiWebClient;
    }

    @Override
    public Mono<String> generateResponse(List<Map<String, Object>> messages) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-5.1-codex-mini");
        body.put("input", messages);
        body.put("max_output_tokens", 2000);

        return openAiWebClient.post()
                .uri("/responses")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(RESPONSE_TYPE)
                .map(this::extractOutputText)
                .retryWhen(Retry.backoff(MAX_ATTEMPTS - 1, INITIAL_BACKOFF)
                        .maxBackoff(Duration.ofSeconds(2))
                        .filter(this::isRetryable)
                        .doBeforeRetry(signal -> log.warn(
                                "OpenAI API 호출 재시도 ({}/{}): {}",
                                signal.totalRetries() + 2,
                                MAX_ATTEMPTS,
                                signal.failure().getMessage()
                        )))
                .doOnError(error -> log.error(
                        "OpenAI API 최종 호출 실패: {}",
                        error.getMessage()
                ))
                .onErrorResume(error -> Mono.empty());
    }

    private String extractOutputText(Map<String, Object> responseBody) {
        Object rawOutputs = responseBody.get("output");
        if (!(rawOutputs instanceof List<?> outputs)) {
            throw new IllegalStateException("Unexpected response structure or empty content");
        }

        for (Object rawOutput : outputs) {
            if (!(rawOutput instanceof Map<?, ?> output) || !"message".equals(output.get("type"))) {
                continue;
            }

            Object rawContents = output.get("content");
            if (!(rawContents instanceof List<?> contents)) {
                continue;
            }

            for (Object rawContent : contents) {
                if (rawContent instanceof Map<?, ?> content && "output_text".equals(content.get("type"))) {
                    Object text = content.get("text");
                    if (text instanceof String outputText && !outputText.isBlank()) {
                        return outputText;
                    }
                }
            }
        }

        throw new IllegalStateException("Unexpected response structure or empty content");
    }

    private boolean isRetryable(Throwable error) {
        if (error instanceof WebClientResponseException responseException) {
            HttpStatusCode status = responseException.getStatusCode();
            return status.value() == 429 || status.is5xxServerError();
        }
        return error instanceof WebClientRequestException
                || error instanceof TimeoutException
                || error instanceof IllegalStateException;
    }
}
