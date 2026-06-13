package core.global.entity.image.service;

import jakarta.transaction.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.util.List;

public interface ImageStorageClient {

    @Transactional
    void deleteObjectsBulk(List<String> keys);

    @Transactional
    void deleteFolder(String prefix);

    @Transactional
    void deleteObjectsByUrls(List<String> urls);

    HeadObjectResponse headObject(String key);

    String extOf(String key);

    boolean isDefaultUrlOrKey(String keyOrUrl);

    boolean isStagingKey(String key);
    String generatePublicUrl(String key);
    String generateThumbnailUrl(String key);
    String upload(MultipartFile file, String key);
    String uploadFromUrl(String url, String key);
}
