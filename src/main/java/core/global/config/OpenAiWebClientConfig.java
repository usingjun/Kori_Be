package core.global.config;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

@Configuration
public class OpenAiWebClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);

    @Bean(destroyMethod = "dispose")
    public ConnectionProvider openAiConnectionProvider() {
        return ConnectionProvider.builder("openai")
                .maxConnections(10)
                .pendingAcquireMaxCount(100)
                .pendingAcquireTimeout(RESPONSE_TIMEOUT)
                .build();
    }

    @Bean("openAiWebClient")
    public WebClient openAiWebClient(
            @Value("${openai.api-key}") String apiKey,
            ConnectionProvider openAiConnectionProvider
    ) {
        HttpClient httpClient = HttpClient.create(openAiConnectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(CONNECT_TIMEOUT.toMillis()))
                .responseTimeout(RESPONSE_TIMEOUT);

        return WebClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
