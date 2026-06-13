package core.global.entity.image.repository;

import core.global.entity.image.entity.ImageUploadSession;
import core.global.enums.common.ImageUploadSessionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImageUploadSessionRepository extends JpaRepository<ImageUploadSession, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ImageUploadSession s where s.objectKey = :objectKey")
    Optional<ImageUploadSession> findByObjectKeyForUpdate(@Param("objectKey") String objectKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from ImageUploadSession s
            where s.objectKey in :objectKeys
            order by s.createdAt
            """)
    List<ImageUploadSession> findByObjectKeyInForUpdate(@Param("objectKeys") Collection<String> objectKeys);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<ImageUploadSession> findTop200ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
            ImageUploadSessionStatus status,
            LocalDateTime expiresAt
    );
}
