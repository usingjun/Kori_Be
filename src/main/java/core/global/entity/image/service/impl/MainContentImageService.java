package core.global.entity.image.service.impl;

import core.global.enums.PollType;
import org.springframework.scheduling.annotation.Async;

import java.util.List;

public interface MainContentImageService {

    @Async("postImageExecutor")
    void upsertPollImages(Long id, List<String> addImageUrls, List<String> removeImages, PollType pollType);

}
