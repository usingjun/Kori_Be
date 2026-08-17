package core.domain.notification.repository;

import core.domain.notification.entity.Notification;
import core.domain.user.entity.User;
import core.global.enums.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    void deleteAllByUserId(Long userId);

    /**
     * 특정 사용자의 알림 목록을 조건에 맞게 조회합니다. (페이지네이션 지원)
     * JOIN FETCH를 사용하여 N+1 문제를 해결합니다.
     *
     * @param userId 조회할 사용자의 ID
     * @param since 데이터를 조회할 시작 시점 (예: 7일 전)
     * @param notificationType 필터링할 알림 타입 (null일 경우 전체 조회)
     * @param pageable 페이징 정보 (페이지 번호, 사이즈, 정렬)
     * @return 페이징 처리된 Notification 목록
     */
    @Query("SELECT n FROM Notification n JOIN FETCH n.actor " +
            "WHERE n.user.id = :userId " +
            "AND n.createdAt >= :since " +
            "AND (:notificationType IS NULL OR n.notificationType = :notificationType) " +
            "ORDER BY n.createdAt DESC")
    Page<Notification> findNotificationsByUserId(
            @Param("userId") Long userId,
            @Param("since") Instant since,
            @Param("notificationType") NotificationType notificationType,
            Pageable pageable
    );

    void deleteAllByActorId(Long actorId);

}
