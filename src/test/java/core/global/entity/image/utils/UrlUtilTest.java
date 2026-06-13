package core.global.entity.image.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UrlUtilTest {

    @Test
    void buildPostFinalKeyCreatesUuidBasedFinalObjectKey() {
        String key = UrlUtil.buildPostFinalKey("photo.JPG");

        assertThat(key).startsWith("posts/objects/");
        assertThat(key).endsWith(".jpg");
        assertThat(key).doesNotStartWith("temp/");
    }
}
