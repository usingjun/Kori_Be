package core.global.entity.image.service;

import core.domain.post.entity.Post;
import core.global.entity.image.dto.PresignedUrlRequest;
import core.global.entity.image.dto.PresignedUrlResponse;
import jakarta.transaction.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface PostImageService {

    List<PresignedUrlResponse> generatePresignedUrls(PresignedUrlRequest request);

    void savePostImages(Long postId, List<String> toAdd);

    void updatePostImages(Long postId, List<String> toAdd, List<String> toRemove);

    @Transactional
    void uploadAndSavePostImages(Post post, List<MultipartFile> multipartFiles) throws IOException;

    void uploadAndSavePostImagesFromUrls(Post post, List<String> imageUrls);
}
