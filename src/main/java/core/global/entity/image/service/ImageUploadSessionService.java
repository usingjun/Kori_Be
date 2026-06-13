package core.global.entity.image.service;

import core.global.entity.image.entity.ImageUploadSession;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.repository.ImageUploadSessionRepository;
import core.global.enums.common.ImageType;
import core.global.enums.common.ImageUploadSessionStatus;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ImageUploadSessionService {

    private final ImageUploadSessionRepository sessionRepository;
    private final ImageRepository imageRepository;
    private final ImageStorageClient storageClient;
    private final ImageOperationRecoveryService recoveryService;

    @Value("${image.upload-session.retention:PT24H}")
    private Duration retention;

    @Value("${image.upload-session.delete-failed-retry-delay:PT1H}")
    private Duration deleteFailedRetryDelay;

    @Value("${image.upload-session.registered-retention:P90D}")
    private Duration registeredRetention;

    @Value("${image.upload-session.deleted-retention:P30D}")
    private Duration deletedRetention;

    @Transactional
    public void issue(String objectKey, Long ownerId, ImageType imageType) {
        sessionRepository.save(ImageUploadSession.issue(
                objectKey,
                ownerId,
                imageType,
                LocalDateTime.now().plus(retention)
        ));
    }

    @Transactional
    public void claimAndRegister(List<String> objectKeys, Long ownerId) {
        if (objectKeys.isEmpty()) {
            return;
        }
        List<ImageUploadSession> sessions = sessionRepository.findByObjectKeyInForUpdate(objectKeys);
        Map<String, ImageUploadSession> sessionsByKey = new HashMap<>();
        for (ImageUploadSession session : sessions) {
            sessionsByKey.put(session.getObjectKey(), session);
        }
        if (sessionsByKey.size() != objectKeys.size()) {
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }
        for (String objectKey : objectKeys) {
            ImageUploadSession session = sessionsByKey.get(objectKey);
            try {
                session.claim(ownerId);
                session.markRegistered();
            } catch (IllegalStateException e) {
                throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
            }
        }
    }

    @Transactional
    public void registerExistingSessions(List<String> objectKeys) {
        if (objectKeys.isEmpty()) {
            return;
        }
        List<ImageUploadSession> sessions = sessionRepository.findByObjectKeyInForUpdate(objectKeys);
        for (ImageUploadSession session : sessions) {
            try {
                session.markRegistered();
            } catch (IllegalStateException e) {
                throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
            }
        }
    }

    @Transactional
    public int scheduleExpiredSessions() {
        List<ImageUploadSession> sessions =
                sessionRepository.findTop200ByStatusInAndExpiresAtBeforeOrderByExpiresAtAsc(
                        List.of(ImageUploadSessionStatus.ISSUED),
                        LocalDateTime.now()
                );
        if (sessions.isEmpty()) {
            return 0;
        }

        Map<String, ImageUploadSession> sessionsByUrl = new HashMap<>();
        for (ImageUploadSession session : sessions) {
            sessionsByUrl.put(storageClient.generatePublicUrl(session.getObjectKey()), session);
        }
        Set<String> registeredUrls = new HashSet<>(
                imageRepository.findRegisteredUrls(sessionsByUrl.keySet())
        );

        List<String> deleteKeys = sessions.stream()
                .filter(session -> {
                    String publicUrl = storageClient.generatePublicUrl(session.getObjectKey());
                    if (registeredUrls.contains(publicUrl)) {
                        session.markRegisteredFromObservedUsage();
                        return false;
                    }
                    session.markDeletePending();
                    return true;
                })
                .map(ImageUploadSession::getObjectKey)
                .toList();
        recoveryService.scheduleUnregisteredPostObjectDeletes(deleteKeys);
        return deleteKeys.size();
    }

    @Transactional
    public int retryFailedDeletes() {
        List<ImageUploadSession> sessions =
                sessionRepository.findTop100ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                        ImageUploadSessionStatus.DELETE_FAILED,
                        LocalDateTime.now().minus(deleteFailedRetryDelay)
                );
        sessions.forEach(ImageUploadSession::markDeletePending);
        return recoveryService.scheduleUnregisteredPostObjectDeletes(
                sessions.stream().map(ImageUploadSession::getObjectKey).toList()
        );
    }

    @Transactional
    public long purgeTerminalSessions() {
        int deleted = sessionRepository.deleteTerminalSessions(
                ImageUploadSessionStatus.DELETED,
                LocalDateTime.now().minus(deletedRetention)
        );
        int registered = sessionRepository.deleteTerminalSessions(
                ImageUploadSessionStatus.REGISTERED,
                LocalDateTime.now().minus(registeredRetention)
        );
        return deleted + registered;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeDelete(String objectKey) {
        sessionRepository.findByObjectKeyForUpdate(objectKey).ifPresent(session -> {
            if (imageRepository.existsByUrl(storageClient.generatePublicUrl(objectKey))) {
                session.restoreRegisteredAfterDeleteSkipped();
            } else {
                session.markDeleted();
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failDelete(String objectKey) {
        sessionRepository.findByObjectKeyForUpdate(objectKey)
                .ifPresent(ImageUploadSession::markDeleteFailed);
    }
}
