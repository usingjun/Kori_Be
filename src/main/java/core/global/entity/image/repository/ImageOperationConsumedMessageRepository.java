package core.global.entity.image.repository;

import core.global.entity.image.entity.ImageOperationConsumedMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ImageOperationConsumedMessageRepository extends JpaRepository<ImageOperationConsumedMessage, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO image_operation_consumed_message
                (message_id, operation_id, step_id, consumer_name, consumed_at)
            VALUES (:messageId, :operationId, :stepId, :consumerName, CURRENT_TIMESTAMP)
            ON CONFLICT (message_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("messageId") UUID messageId,
            @Param("operationId") UUID operationId,
            @Param("stepId") UUID stepId,
            @Param("consumerName") String consumerName
    );
}
