package core.domain.post.service.crawling;

import core.domain.post.entity.CrawledData;
import core.domain.post.repository.CrawledDataRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrawledDataWriterTest {

    @Mock CrawledDataRepository crawledDataRepository;
    @InjectMocks CrawledDataWriter writer;

    @Test
    void save_returnsSaved() {
        CrawledData data = data("https://example.com/1");
        when(crawledDataRepository.saveAndFlush(data)).thenReturn(data);

        assertThat(writer.save(data)).isEqualTo(CrawledDataWriteResult.SAVED);
    }

    @Test
    void save_returnsDuplicateForConstraintViolation() {
        CrawledData data = data("https://example.com/1");
        when(crawledDataRepository.saveAndFlush(data))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThat(writer.save(data)).isEqualTo(CrawledDataWriteResult.DUPLICATE);
    }

    @Test
    void save_doesNotSwallowUnexpectedDatabaseFailure() {
        CrawledData data = data("https://example.com/1");
        when(crawledDataRepository.saveAndFlush(data)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> writer.save(data)).isInstanceOf(IllegalStateException.class);
    }

    private CrawledData data(String url) {
        return new CrawledData("title", "content", url, "example.com", List.of());
    }
}
