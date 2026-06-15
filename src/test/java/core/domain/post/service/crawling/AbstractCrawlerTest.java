package core.domain.post.service.crawling;

import core.domain.post.entity.CrawledData;
import core.domain.post.repository.CrawledDataRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbstractCrawlerTest {

    private final CrawledDataRepository repository = mock(CrawledDataRepository.class);
    private final CrawledDataWriter writer = mock(CrawledDataWriter.class);

    @Test
    void crawl_skipsExistingUrlAndProcessesNextReference() {
        TestCrawler crawler = new TestCrawler(repository, writer, List.of("existing", "new"));
        when(repository.existsByOriginalUrl("existing")).thenReturn(true);
        when(repository.existsByOriginalUrl("new")).thenReturn(false);
        when(writer.save(any(CrawledData.class))).thenReturn(CrawledDataWriteResult.SAVED);

        crawler.crawl();

        assertThat(crawler.parsed).containsExactly("new");
        assertThat(crawler.pauseCount).isEqualTo(1);
        verify(writer, times(1)).save(any(CrawledData.class));
    }

    @Test
    void crawl_continuesAfterArticleParseFailure() {
        TestCrawler crawler = new TestCrawler(repository, writer, List.of("bad", "new"));
        crawler.failReference = "bad";
        when(writer.save(any(CrawledData.class))).thenReturn(CrawledDataWriteResult.SAVED);

        crawler.crawl();

        assertThat(crawler.parsed).containsExactly("bad", "new");
        assertThat(crawler.failedUrls).containsExactly("bad");
        assertThat(crawler.pauseCount).isEqualTo(1);
    }

    @Test
    void crawl_doesNotPauseWhenParseReturnsEmpty() {
        TestCrawler crawler = new TestCrawler(repository, writer, List.of("empty"));
        crawler.emptyReference = "empty";

        crawler.crawl();

        assertThat(crawler.pauseCount).isZero();
        verify(writer, times(0)).save(any(CrawledData.class));
    }

    @Test
    void crawl_skipsBlankOriginalUrl() {
        TestCrawler crawler = new TestCrawler(repository, writer, List.of(""));

        crawler.crawl();

        assertThat(crawler.parsed).isEmpty();
        assertThat(crawler.pauseCount).isZero();
        verify(writer, times(0)).save(any(CrawledData.class));
    }

    @Test
    void crawl_pausesAfterSaveTimeDuplicate() {
        TestCrawler crawler = new TestCrawler(repository, writer, List.of("new"));
        when(writer.save(any(CrawledData.class))).thenReturn(CrawledDataWriteResult.DUPLICATE);

        crawler.crawl();

        assertThat(crawler.pauseCount).isEqualTo(1);
    }

    @Test
    void crawl_handlesUnexpectedWriterFailureAsCrawlFailure() {
        TestCrawler crawler = new TestCrawler(repository, writer, List.of("new", "next"));
        when(writer.save(any(CrawledData.class))).thenThrow(new IllegalStateException("db down"));

        crawler.crawl();

        assertThat(crawler.crawlFailures).hasSize(1);
        assertThat(crawler.parsed).containsExactly("new");
    }

    @Test
    void crawl_runsLifecycleHooks() {
        TestCrawler crawler = new TestCrawler(repository, writer, List.of());

        crawler.crawl();

        assertThat(crawler.beforeCrawlCount).isEqualTo(1);
        assertThat(crawler.afterCrawlCount).isEqualTo(1);
    }

    @Test
    void crawl_propagatesUnexpectedRuntimeFailureAfterCleanup() {
        TestCrawler crawler = new TestCrawler(repository, writer, List.of("new"));
        crawler.propagateCrawlFailure = true;
        when(writer.save(any(CrawledData.class))).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(crawler::crawl).isInstanceOf(IllegalStateException.class);
        assertThat(crawler.afterCrawlCount).isEqualTo(1);
    }

    private static class TestCrawler extends AbstractCrawler<String> {

        private final List<String> references;
        private final List<String> parsed = new ArrayList<>();
        private final List<String> failedUrls = new ArrayList<>();
        private final List<Exception> crawlFailures = new ArrayList<>();
        private String failReference;
        private String emptyReference;
        private int pauseCount;
        private int beforeCrawlCount;
        private int afterCrawlCount;
        private boolean propagateCrawlFailure;

        private TestCrawler(
                CrawledDataRepository repository,
                CrawledDataWriter writer,
                List<String> references
        ) {
            super(repository, writer);
            this.references = references;
        }

        @Override
        protected String sourceSite() {
            return "example.com";
        }

        @Override
        protected List<String> fetchArticleReferences() {
            return references;
        }

        @Override
        protected String originalUrl(String reference) {
            return reference;
        }

        @Override
        protected Optional<CrawledData> parseArticle(String reference) {
            parsed.add(reference);
            if (reference.equals(failReference)) {
                throw new IllegalStateException("parse failed");
            }
            if (reference.equals(emptyReference)) {
                return Optional.empty();
            }
            return Optional.of(data(reference));
        }

        @Override
        protected void beforeCrawl() {
            beforeCrawlCount++;
        }

        @Override
        protected void afterCrawl() {
            afterCrawlCount++;
        }

        private CrawledData data(String url) {
            return new CrawledData("title", "content", url, "example.com", List.of());
        }

        @Override
        protected void pause(Duration delay) {
            pauseCount++;
        }

        @Override
        protected void onArticleFailed(String originalUrl, Exception exception) {
            failedUrls.add(originalUrl);
        }

        @Override
        protected void onCrawlFailed(Exception exception) {
            crawlFailures.add(exception);
            if (propagateCrawlFailure) {
                super.onCrawlFailed(exception);
            }
        }
    }
}
