package core.global.translation.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleTranslationClientTest {

    private static final String API_URL = "https://translation.example.test/translate";

    private MockRestServiceServer server;
    private GoogleTranslationClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new GoogleTranslationClient(restTemplate, API_URL);
    }

    @Test
    void translate_sendsExistingHttpContractAndReturnsTextsInOrder() {
        server.expect(once(), requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("ngrok-skip-browser-warning", "69420"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "description": ["안녕하세요", "반갑습니다"],
                          "targetLanguageCode": "en"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "translations": [
                            {"translatedText": "Hello"},
                            {"translatedText": "Nice to meet you"}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<String> result = client.translate(List.of("안녕하세요", "반갑습니다"), "en");

        assertThat(result).containsExactly("Hello", "Nice to meet you");
        server.verify();
    }

    @Test
    void translate_rejectsNullResponseBody() {
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.translate(List.of("hello"), "ko"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not contain translations");
    }

    @Test
    void translate_rejectsMissingTranslations() {
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.translate(List.of("hello"), "ko"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not contain translations");
    }

    @Test
    void translate_rejectsEmptyTranslatedText() {
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess("""
                        {"translations": [{"translatedText": ""}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.translate(List.of("hello"), "ko"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty translated text");
    }

    @Test
    void translate_doesNotSwallowHttpFailure() {
        server.expect(requestTo(API_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.translate(List.of("hello"), "ko"))
                .isInstanceOf(HttpServerErrorException.class);
    }
}
