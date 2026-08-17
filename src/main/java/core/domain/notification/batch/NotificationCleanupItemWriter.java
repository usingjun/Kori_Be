package core.domain.notification.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCleanupItemWriter implements ItemWriter<Long> {

    private static final String DELETE_SQL = """
            DELETE FROM notification
            WHERE notification_id IN (:notificationIds)
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public void write(Chunk<? extends Long> chunk) {
        if (chunk.isEmpty()) {
            return;
        }

        List<Long> notificationIds = List.copyOf(chunk.getItems());
        int deletedCount = jdbcTemplate.update(
                DELETE_SQL,
                new MapSqlParameterSource("notificationIds", notificationIds)
        );

        log.info("[BATCH] 오래된 알림 {}건 삭제", deletedCount);
    }
}
