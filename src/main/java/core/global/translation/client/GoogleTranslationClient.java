package core.global.translation.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Component
public class GoogleTranslationClient implements TranslationClient {

    private final RestTemplate restTemplate;
    private final String apiUrl;

    public GoogleTranslationClient(
            @Qualifier("translationRestTemplate") RestTemplate restTemplate,
            @Value("${google.translate.api-url:https://taylor-easternmost-temple.ngrok-free.dev/v3/projects/any-id/locations/global:translateText}")
            String apiUrl
    ) {
        this.restTemplate = restTemplate;
        this.apiUrl = apiUrl;
    }

    @Override
    public List<String> translate(List<String> messages, String targetLanguage) {
        TranslationClientRequest request = new TranslationClientRequest(messages, targetLanguage);
        TranslationClientResponse response = restTemplate.postForObject(
                apiUrl,
                new HttpEntity<>(request, headers()),
                TranslationClientResponse.class
        );
        return translatedTexts(response);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("ngrok-skip-browser-warning", "69420");
        return headers;
    }

    private List<String> translatedTexts(TranslationClientResponse response) {
        if (response == null || response.translations() == null) {
            throw new IllegalStateException("Translation API response does not contain translations");
        }

        return response.translations().stream()
                .map(TranslationClientResponse.Translation::translatedText)
                .map(this::requireTranslatedText)
                .toList();
    }

    private String requireTranslatedText(String translatedText) {
        if (translatedText == null || translatedText.isBlank()) {
            throw new IllegalStateException("Translation API response contains an empty translated text");
        }
        return translatedText;
    }
}
