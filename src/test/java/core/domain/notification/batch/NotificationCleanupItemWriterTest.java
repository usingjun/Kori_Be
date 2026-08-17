package core.domain.notification.batch;

import org.junit.jupiter.api.Test;
import org.springframework.batch.item.Chunk;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationCleanupItemWriterTest {

    private final NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    private final NotificationCleanupItemWriter writer = new NotificationCleanupItemWriter(jdbcTemplate);

    @Test
    void write_deletesAllIdsInOneBulkQuery() throws Exception {
        when(jdbcTemplate.update(anyString(), org.mockito.ArgumentMatchers.any(SqlParameterSource.class)))
                .thenReturn(3);

        writer.write(new Chunk<>(List.of(11L, 12L, 13L)));

        ArgumentCaptor<SqlParameterSource> parametersCaptor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("notification_id IN"),
                parametersCaptor.capture()
        );
        assertThat(parametersCaptor.getValue().getValue("notificationIds"))
                .isEqualTo(List.of(11L, 12L, 13L));
    }

    @Test
    void write_doesNothingForEmptyChunk() throws Exception {
        writer.write(new Chunk<>());

        verify(jdbcTemplate, never()).update(anyString(), org.mockito.ArgumentMatchers.any(SqlParameterSource.class));
    }
}
