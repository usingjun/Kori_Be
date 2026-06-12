package core.global.entity.image.service;

import core.domain.post.entity.Post;
import core.global.entity.image.dto.ImageDto;
import core.global.entity.image.dto.PresignedUrlRequest;
import core.global.entity.image.dto.PresignedUrlResponse;
import core.global.enums.PollType;
import core.global.exception.BusinessException;
import jakarta.transaction.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface ImageService {

    List<PresignedUrlResponse> generatePresignedUrls(PresignedUrlRequest request);

    void savePostImages(Long postId, List<String> toAdd) throws BusinessException;

    void updatePostImages(Long postId, List<String> toAdd, List<String> toRemove);

    void upsertPollImages(Long id, List<String> addImageUrls, List<String> removeImageUrls, PollType type);

    @Transactional
    void deleteFolder(String fileLocation);

    @Transactional
    void saveUserProfileImage(Long userId, String requestedKeyOrUrl);

    @Transactional
    String updateUserProfileImage(Long userId, String requestedKeyOrUrl);

    /** 현재 프로필 이미지를 삭제(S3 + image 레코드) */
    @Transactional
    void deleteUserProfileImage(Long userId);

    @Transactional
    void saveChatRoomProfileImage(Long roomId, String requestedKeyOrUrl);

    @Transactional
    String updateChatRoomProfileImage(Long roomId, String requestedKeyOrUrl);

    @Transactional
    void deleteChatRoomProfileImage(Long roomId);

    /** 현재 프로필 이미지 key 조회(없으면 null) */
    String getUserProfileKey(Long userId);

    String getRoomImageUrl(Long roomId);

    List<ImageDto> findImagesForChatRooms(List<Long> roomIds);

    @Transactional
    void uploadAndSavePostImages(Post post, List<MultipartFile> images) throws IOException;

}
