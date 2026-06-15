package core.global.translation.client;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record TranslationClientRequest(
        @JsonProperty("description") List<String> messages,
        String targetLanguageCode
) {
}
