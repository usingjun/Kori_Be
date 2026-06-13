package core.global.entity.image.service.impl;

import core.global.entity.image.service.PostImageService;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PostImageExecutorAssignmentTest {

    @Test
    void postAndPollPresignedImageMethodsUseLimitedExecutor() throws NoSuchMethodException {
        assertAsyncExecutor(
                PostImageServiceImpl.class.getMethod("savePostImages", Long.class, List.class),
                "postImageExecutor"
        );
        assertAsyncExecutor(
                PostImageServiceImpl.class.getMethod("updatePostImages", Long.class, List.class, List.class),
                "postImageExecutor"
        );
        assertAsyncExecutor(
                MainContentImageServiceImpl.class.getMethod(
                        "upsertPollImages",
                        Long.class,
                        List.class,
                        List.class,
                        core.global.enums.PollType.class
                ),
                "postImageExecutor"
        );

        assertNotTransactional(PostImageService.class.getMethod("savePostImages", Long.class, List.class));
        assertNotTransactional(
                PostImageService.class.getMethod("updatePostImages", Long.class, List.class, List.class)
        );
        assertNotTransactional(
                MainContentImageService.class.getMethod(
                        "upsertPollImages",
                        Long.class,
                        List.class,
                        List.class,
                        core.global.enums.PollType.class
                )
        );
    }

    private void assertAsyncExecutor(java.lang.reflect.Method method, String expectedExecutor) {
        assertThat(method.getAnnotation(Async.class))
                .isNotNull()
                .extracting(Async::value)
                .isEqualTo(expectedExecutor);
    }

    private void assertNotTransactional(java.lang.reflect.Method method) {
        assertThat(method.getAnnotation(Transactional.class)).isNull();
    }
}
