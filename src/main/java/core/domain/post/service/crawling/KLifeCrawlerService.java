package core.domain.post.service.crawling;

import core.domain.post.entity.CrawledData;
import core.domain.post.repository.CrawledDataRepository;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class KLifeCrawlerService extends AbstractCrawler<Element> {

    private static final String BASE_URL = "https://k-life.co";
    private static final String LIST_URL = BASE_URL + "/community";
    private static final String SOURCE_SITE = "k-life.co";
    private static final String ARTICLE_SELECTOR = "div.article-section";
    private static final String LINK_SELECTOR = "a.uk-link-toggle";
    private static final String TITLE_SELECTOR = "h3.document-list-title strong";
    private static final String SNIPPET_SELECTOR = "p > span.uk-text-break";
    private static final String THUMBNAIL_SELECTOR = "div.files-area img";
    private static final String DETAIL_CONTENT_SELECTOR = "div.rhymix_content.xe_content";
    private static final String DETAIL_IMAGE_SELECTOR = "div.rhymix_content.xe_content img";

    public KLifeCrawlerService(
            CrawledDataRepository crawledDataRepository,
            CrawledDataWriter crawledDataWriter
    ) {
        super(crawledDataRepository, crawledDataWriter);
    }

    @Scheduled(cron = "0 30 5 * * *")
    public void crawlKLifeCommunity() {
        crawl();
    }

    @Override
    protected String sourceSite() {
        return SOURCE_SITE;
    }

    @Override
    protected List<Element> fetchArticleReferences() throws Exception {
        Document listDoc = Jsoup.connect(LIST_URL).timeout(10000).get();
        Elements articles = listDoc.select(ARTICLE_SELECTOR);
        log.info("Found {} articles on k-life list page.", articles.size());
        return new ArrayList<>(articles);
    }

    @Override
    protected String originalUrl(Element articleElement) {
        Element linkElement = articleElement.selectFirst(LINK_SELECTOR);
        return linkElement == null ? null : BASE_URL + linkElement.attr("href");
    }

    @Override
    protected Optional<CrawledData> parseArticle(Element articleElement) throws Exception {
        Element linkElement = articleElement.selectFirst(LINK_SELECTOR);
        if (linkElement == null) {
            log.warn("Skipping article, link not found.");
            return Optional.empty();
        }

        String originalUrl = originalUrl(articleElement);
        String title = linkElement.selectFirst(TITLE_SELECTOR).text();
        String description = articleElement.selectFirst(SNIPPET_SELECTOR).text();
        Set<String> imageUrlSet = new HashSet<>();

        Element thumbElement = articleElement.selectFirst(THUMBNAIL_SELECTOR);
        if (thumbElement != null) {
            String thumbUrl = thumbElement.absUrl("src");
            if (!thumbUrl.contains("no-image.png")) {
                imageUrlSet.add(thumbUrl);
            }
        }

        log.info("Crawling detail page: {}", originalUrl);
        Document detailDoc = Jsoup.connect(originalUrl).timeout(10000).get();
        Element contentElement = detailDoc.selectFirst(DETAIL_CONTENT_SELECTOR);
        String fullContent = contentElement != null ? contentElement.text() : description;

        if (contentElement != null) {
            contentElement.select(DETAIL_IMAGE_SELECTOR)
                    .forEach(image -> imageUrlSet.add(image.absUrl("src")));
        }

        return Optional.of(new CrawledData(
                title,
                fullContent,
                originalUrl,
                SOURCE_SITE,
                new ArrayList<>(imageUrlSet)
        ));
    }
}
