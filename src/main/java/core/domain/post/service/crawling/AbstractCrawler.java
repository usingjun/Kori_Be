package core.domain.post.service.crawling;

import core.domain.post.entity.CrawledData;
import core.domain.post.repository.CrawledDataRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Slf4j
public abstract class AbstractCrawler<R> {

    private final CrawledDataRepository crawledDataRepository;
    private final CrawledDataWriter crawledDataWriter;

    protected AbstractCrawler(
            CrawledDataRepository crawledDataRepository,
            CrawledDataWriter crawledDataWriter
    ) {
        this.crawledDataRepository = crawledDataRepository;
        this.crawledDataWriter = crawledDataWriter;
    }

    public final void crawl() {
        log.info("Starting {} crawling...", sourceSite());
        try {
            beforeCrawl();
            for (R reference : fetchArticleReferences()) {
                processReference(reference);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            onCrawlInterrupted(exception);
        } catch (Exception exception) {
            onCrawlFailed(exception);
        } finally {
            afterCrawl();
            log.info("Finished {} crawling.", sourceSite());
        }
    }

    private void processReference(R reference) throws Exception {
        String originalUrl = originalUrl(reference);
        if (originalUrl == null || originalUrl.isBlank()) {
            return;
        }
        if (crawledDataRepository.existsByOriginalUrl(originalUrl)) {
            log.debug("Skipping already crawled article: {}", originalUrl);
            return;
        }

        Optional<CrawledData> crawledData;
        try {
            crawledData = parseArticle(reference);
        } catch (InterruptedException exception) {
            throw exception;
        } catch (Exception exception) {
            onArticleFailed(originalUrl, exception);
            return;
        }

        if (crawledData.isEmpty()) {
            return;
        }

        crawledDataWriter.save(crawledData.get());
        pause(delayAfterProcessed());
    }

    protected abstract String sourceSite();

    protected abstract List<R> fetchArticleReferences() throws Exception;

    protected abstract String originalUrl(R reference);

    protected abstract Optional<CrawledData> parseArticle(R reference) throws Exception;

    protected void beforeCrawl() throws Exception {
    }

    protected void afterCrawl() {
    }

    protected Duration delayAfterProcessed() {
        return Duration.ofSeconds(3);
    }

    protected void pause(Duration delay) throws InterruptedException {
        Thread.sleep(delay.toMillis());
    }

    protected void onArticleFailed(String originalUrl, Exception exception) {
        log.error("Failed to crawl detail page: {}. Skipping.", originalUrl, exception);
    }

    protected void onCrawlFailed(Exception exception) {
        log.error("Error occurred during {} crawling", sourceSite(), exception);
        if (exception instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
    }

    protected void onCrawlInterrupted(InterruptedException exception) {
        log.error("{} crawling interrupted", sourceSite(), exception);
    }
}
