package core.global.translation.client;

import java.util.List;

public record TranslationClientResponse(List<Translation> translations) {

    public record Translation(String translatedText) {
    }
}
