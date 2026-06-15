package core.global.translation.client;

import java.util.List;

public interface TranslationClient {

    List<String> translate(List<String> messages, String targetLanguage);
}
