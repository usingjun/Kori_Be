package core.global.entity.image.service.impl;

import core.domain.post.entity.Post;
import core.global.entity.image.dto.ImageDto;
import core.global.entity.image.dto.PresignedUrlRequest;
import core.global.entity.image.dto.PresignedUrlResponse;
import core.global.entity.image.service.ImageService;
import core.global.entity.image.service.ImageStorageClient;
import core.global.entity.image.service.ImagePersistenceTransactionService;
import core.global.entity.image.service.PostImageOperationPipelineService;
import core.global.entity.image.service.PostImageService;
import core.global.entity.image.service.ProfileImageService;
import core.global.enums.PollType;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ImageServiceImpl implements ImageService {

    private final PostImageService postImageService;
    private final ProfileImageService profileImageService;
    private final ImageStorageClient imageStorageClient;
    private final MainContentImageService mainContentImageService;
    private final PostImageOperationPipelineService postImageOperationPipelineService;
    private final ImagePersistenceTransactionService imagePersistenceTransactionService;

    @Value("${image.cleanup.rabbit.enabled:false}")
    private boolean imageOperationRabbitEnabled;

    @Override
    public List<PresignedUrlResponse> generatePresignedUrls(PresignedUrlRequest request) {
        return postImageService.generatePresignedUrls(request);
    }

    @Override
    public void savePostImages(Long postId, List<String> toAdd) {
        List<String> adds = copyNullableList(toAdd);
        if (usesFinalPostKeys(adds)) {
            imagePersistenceTransactionService.saveFinalPostImages(postId, adds);
            return;
        }
        if (imageOperationRabbitEnabled) {
            postImageOperationPipelineService.scheduleCreate(postId, adds);
            return;
        }
        runAfterCommit(() -> postImageService.savePostImages(postId, adds));
    }

    @Override
    public void updatePostImages(Long postId, List<String> toAdd, List<String> toRemove) {
        List<String> adds = copyNullableList(toAdd);
        List<String> removes = copyNullableList(toRemove);
        if (usesFinalPostKeys(adds)) {
            imagePersistenceTransactionService.updateFinalPostImages(postId, adds, removes);
            return;
        }
        runAfterCommit(() -> postImageService.updatePostImages(postId, adds, removes));
    }

    @Override
    public void deletePostImages(Long postId) {
        imagePersistenceTransactionService.deletePostImages(postId);
    }

    @Override
    @Transactional
    public void saveUserProfileImage(Long userId, String requestedKeyOrUrl) {
        profileImageService.saveUserProfileImage(userId, requestedKeyOrUrl);
    }

    @Override
    @Transactional
    public String updateUserProfileImage(Long userId, String requestedKeyOrUrl) {
        return profileImageService.updateUserProfileImage(userId, requestedKeyOrUrl);
    }

    @Override
    @Transactional
    public void deleteUserProfileImage(Long userId) {
        profileImageService.deleteUserProfileImage(userId);
    }

    @Override
    @Transactional
    public void saveChatRoomProfileImage(Long chatRoomId, String requestedKeyOrUrl) {
        profileImageService.saveChatRoomProfileImage(chatRoomId, requestedKeyOrUrl);
    }

    @Override
    @Transactional
    public String updateChatRoomProfileImage(Long chatRoomId, String requestedKeyOrUrl) {
        return profileImageService.updateChatRoomProfileImage(chatRoomId, requestedKeyOrUrl);
    }

    @Override
    @Transactional
    public void deleteChatRoomProfileImage(Long chatRoomId) {
        profileImageService.deleteChatRoomProfileImage(chatRoomId);
    }

    @Override
    public String getUserProfileKey(Long userId) {
        return profileImageService.getUserProfileKey(userId);
    }

    @Override
    public String getRoomImageUrl(Long roomId) {
        return profileImageService.getRoomImageUrl(roomId);
    }

    @Override
    public List<ImageDto> findImagesForChatRooms(List<Long> roomIds) {
        return profileImageService.findImagesForChatRooms(roomIds);
    }

    @Override
    @Transactional
    public void uploadAndSavePostImages(Post post, List<MultipartFile> multipartFiles) throws IOException {
        postImageService.uploadAndSavePostImages(post, multipartFiles);
    }

    @Override
    public void upsertPollImages(Long id, List<String> addImageUrls, List<String> removeImageUrls, PollType type) {
        List<String> adds = copyNullableList(addImageUrls);
        List<String> removes = copyNullableList(removeImageUrls);
        runAfterCommit(() -> mainContentImageService.upsertPollImages(id, adds, removes, type));
    }

    @Override
    @Transactional
    public void deleteFolder(String fileLocation) {
        imageStorageClient.deleteFolder(fileLocation);
    }

    private List<String> copyNullableList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private boolean usesFinalPostKeys(List<String> values) {
        return values.stream().allMatch(value ->
                value != null && (value.startsWith("posts/objects/") || value.contains("/posts/objects/"))
        );
    }

    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
