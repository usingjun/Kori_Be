package core.global.entity.image.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageUploadSessionMaintenanceScheduler {

    private final ImageUploadSessionService uploadSessionService;

    @Scheduled(
            fixedDelayString = "${image.upload-session.terminal-purge-delay-ms:86400000}",
            initialDelayString = "${image.upload-session.terminal-purge-initial-delay-ms:600000}"
    )
    public void purgeTerminalSessions() {
        long deleted = uploadSessionService.purgeTerminalSessions();
        if (deleted > 0) {
            log.info("[ImageUploadSession] terminal sessions purged={}", deleted);
        }
    }
}
