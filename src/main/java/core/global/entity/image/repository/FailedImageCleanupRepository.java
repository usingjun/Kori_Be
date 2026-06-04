package core.global.entity.image.repository;

import core.global.entity.image.entity.FailedImageCleanup;
import core.global.enums.common.ImageCleanupOperationType;
import core.global.enums.common.ImageCleanupStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FailedImageCleanupRepository extends JpaRepository<FailedImageCleanup, Long> {

    Optional<FailedImageCleanup> findByOperationTypeAndTargetKey(
            ImageCleanupOperationType operationType,
            String targetKey
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
                select f
                from FailedImageCleanup f
                where f.status in :statuses
                  and f.nextRetryAt <= :now
                order by f.nextRetryAt asc, f.id asc
            """)
    List<FailedImageCleanup> findRetryTargets(
            @Param("statuses") Collection<ImageCleanupStatus> statuses,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );
}
