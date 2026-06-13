package core.global.entity.image.repository;

import core.global.entity.image.entity.Image;
import core.global.enums.ImageModerationStatus;
import core.global.enums.common.ImageType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ImageRepository extends JpaRepository<Image, Long> {
    @Query("SELECT i FROM Image i " +
            "WHERE i.relatedId IN :relatedIds " +
            "AND i.imageType = :imageType " +
            "AND i.orderIndex = 0") // 썸네일은 보통 0번
    List<Image> findAllByRelatedIdsAndType(@Param("relatedIds") List<Long> relatedIds,
                                           @Param("imageType") ImageType imageType);

    @Query("""
                select i.relatedId, i.url
                from Image i
                where i.id in (
                    select min(i2.id)
                    from Image i2
                    where i2.imageType = :imageType
                      and i2.relatedId in :relatedIds
                    group by i2.relatedId
                )
            """)
    List<Object[]> findFirstUrlByRelatedIds(@Param("imageType") ImageType imageType,
                                            @Param("relatedIds") List<Long> relatedIds);

    @Query("""
                select i.relatedId, i.url
                from Image i
                where i.imageType = :imageType
                  and i.relatedId in :relatedIds
                order by i.id asc
            """)
    List<Object[]> findAllUrlsByRelatedIds(@Param("imageType") ImageType imageType,
                                           @Param("relatedIds") List<Long> relatedIds);

    @Query("select i from Image i " +
           "where i.imageType = :type and i.relatedId = :relatedId " +
           "order by i.orderIndex asc")
    List<Image> findByImageTypeAndRelatedIdOrderByPositionAsc(
            @Param("type") ImageType type,
            @Param("relatedId") Long relatedId
    );

    @Modifying
    @Query("""
                delete from Image i
                where i.imageType = :imageType
                  and i.relatedId  = :relatedId
                  and i.url in :urls
            """)
    void deleteByImageTypeAndRelatedIdAndUrlIn(ImageType imageType, Long relatedId, Collection<String> urls);

    @Modifying
    @Query("""
                delete from Image i
                 where i.imageType = :imageType
                   and i.relatedId  = :relatedId
            """)
    void deleteByImageTypeAndRelatedId(@Param("imageType") ImageType imageType,
                                       @Param("relatedId") Long relatedId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
                delete from Image i
                 where i.imageType = :imageType
                   and i.relatedId  = :relatedId
            """)
    void deleteByImageTypeAndRelatedIdWithFlushing(@Param("imageType") ImageType imageType,
                                                   @Param("relatedId") Long relatedId);

    void deleteByImageTypeAndRelatedIdAndUrlIn(ImageType imageType, Long relatedId, List<String> urls);

    List<Image> findAllByImageTypeAndRelatedIdInOrderByOrderIndexAsc(ImageType imageType,
                                                                     List<Long> relatedIds);

    Optional<Image> findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType imageType, Long relatedId);

    boolean existsByImageTypeAndRelatedId(ImageType imageType, Long relatedId);

    @Modifying
    @Query("DELETE FROM Image i WHERE i.imageType = :imageType AND i.relatedId = :relatedId")
    void deleteAllByImageTypeAndRelatedId(@Param("imageType") ImageType imageType, @Param("relatedId") Long relatedId);


    @Query("SELECT i FROM Image i WHERE i.imageType = :imageType AND i.relatedId IN :relatedIds AND i.orderIndex = 0")
    List<Image> findAllPrimaryImagesForUsers(@Param("imageType") ImageType imageType, @Param("relatedIds") List<Long> relatedIds);

    List<Image> findAllByImageTypeAndRelatedIdIn(ImageType imageType, List<Long> relatedIds);

    /**
     * [추가] 특정 타입과 ID에 해당하는 '모든' 이미지 목록을 조회합니다.
     * 채팅방의 기존 이미지를 모두 삭제하기 위해 사용됩니다.
     */
    List<Image> findByImageTypeAndRelatedId(ImageType imageType, Long relatedId);

    void deleteAllByImageTypeAndRelatedIdIn(ImageType type, List<Long> postIds);

    List<Image> findByImageTypeAndRelatedIdIn(ImageType imageType, List<Long> relatedIds);


    /**
     * 특정 이미지 타입(ImageType)과 연관 ID(relatedId)를 가진 이미지들 중
     * orderIndex가 가장 작은(가장 상위에 있는) 하나의 이미지를 조회합니다.
     * * @param imageType 이미지 타입 (예: CHAT_ROOM)
     *
     * @param relatedId 연관된 엔티티의 ID (예: 채팅방 ID)
     * @return 조회된 Image 엔티티 (Optional)
     */
    Optional<Image> findTopByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType imageType, Long relatedId);

    List<Image> findByModerationStatusOrderByIdDesc(ImageModerationStatus status);

    boolean existsByRelatedIdAndUrlAndImageType(Long contentId, String url, ImageType type);

    boolean existsByImageTypeAndRelatedIdAndUrl(ImageType imageType, Long relatedId, String url);

    boolean existsByUrl(String url);

    @Query("select i.url from Image i where i.url in :urls")
    List<String> findRegisteredUrls(@Param("urls") Collection<String> urls);

    @Query("SELECT i.relatedId, i.url FROM Image i " +
           "WHERE i.id IN (SELECT MIN(i2.id) FROM Image i2 " +
           "               WHERE i2.relatedId IN :postIds AND i2.imageType = 'POST' " +
           "               GROUP BY i2.relatedId)")
    List<Object[]> findFirstUrlsByPostIds(@Param("postIds") List<Long> postIds);

    @Query("SELECT i.relatedId, i.url FROM Image i " +
           "WHERE i.id IN (SELECT MIN(i2.id) FROM Image i2 " +
           "               WHERE i2.relatedId IN :mainContentIds AND i2.imageType = 'MAIN_PAGE_THUMBNAIL' " +
           "               GROUP BY i2.relatedId)")
    List<Object[]> findFirstUrlsByMainContentsIds(@Param("mainContentIds") List<Long> mainContentIds);

    // 포스트별 이미지 총 개수 조회
    @Query("SELECT i.relatedId, COUNT(i) FROM Image i " +
           "WHERE i.relatedId IN :postIds AND i.imageType = 'POST' " +
           "GROUP BY i.relatedId")
    List<Object[]> countImageByPostIds(@Param("postIds") List<Long> postIds);

    // 유저별 프로필 이미지 URL 조회
    @Query("SELECT i.relatedId, i.url FROM Image i " +
           "WHERE i.relatedId IN :userIds AND i.imageType = 'USER'")
    List<Object[]> findProfileImagesByUserIds(@Param("userIds") List<Long> userIds);
    List<Image> findAllByRelatedIdInAndImageTypeAndOrderIndex(
            List<Long> relatedIds,
            ImageType imageType,
            int orderIndex
    );
}
