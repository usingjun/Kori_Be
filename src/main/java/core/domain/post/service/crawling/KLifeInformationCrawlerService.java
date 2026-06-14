package core.domain.post.service.crawling;

import core.domain.post.entity.CrawledData;
import core.domain.post.repository.CrawledDataRepository;
import core.global.service.TranslationService;
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
public class KLifeInformationCrawlerService extends AbstractCrawler<Element> {

    private static final String BASE_URL = "https://k-life.co";
    private static final String LIST_URL = BASE_URL + "/information";
    private static final String SOURCE_SITE = "k-life.co";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";

    private static final String ARTICLE_SELECTOR = "li.article-section";
    private static final String LINK_SELECTOR = "a.uk-link-reset:has(p.uk-text-break)";
    private static final String TITLE_SELECTOR = "h3.uk-card-title strong";
    private static final String SNIPPET_SELECTOR = "p.uk-text-break";
    private static final String THUMBNAIL_SELECTOR = "img[uk-cover]";
    private static final String DETAIL_CONTENT_SELECTOR = "div.rhymix_content.xe_content";
    private static final String DETAIL_IMAGE_SELECTOR = "div.rhymix_content.xe_content img";

    private final TranslationService translationService;

    public KLifeInformationCrawlerService(
            CrawledDataRepository crawledDataRepository,
            CrawledDataWriter crawledDataWriter,
            TranslationService translationService
    ) {
        super(crawledDataRepository, crawledDataWriter);
        this.translationService = translationService;
    }

    @Scheduled(cron = "0 35 5 * * *")
    public void crawlKLifeInformation() {
        crawl();
    }

    @Override
    protected String sourceSite() {
        return SOURCE_SITE;
    }

    @Override
    protected List<Element> fetchArticleReferences() throws Exception {
        Document listDoc = Jsoup.connect(LIST_URL).userAgent(USER_AGENT).timeout(10000).get();
        Elements articles = listDoc.select(ARTICLE_SELECTOR);
        log.info("Found {} articles on k-life /information page.", articles.size());
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
        Element titleElement = articleElement.selectFirst(TITLE_SELECTOR);
        if (linkElement == null || titleElement == null) {
            log.warn("Skipping article, link or title not found.");
            return Optional.empty();
        }

        Element snippetElement = linkElement.selectFirst(SNIPPET_SELECTOR);
        Element thumbElement = linkElement.selectFirst(THUMBNAIL_SELECTOR);
        String originalUrl = originalUrl(articleElement);
        String titleKorean = titleElement.text();
        String descriptionKorean = snippetElement != null ? snippetElement.text() : "";
        Set<String> imageUrlSet = new HashSet<>();

        if (thumbElement != null) {
            String thumbUrl = thumbElement.absUrl("src");
            if (!thumbUrl.contains("no-image.png")) {
                imageUrlSet.add(getHighQualityUrl(thumbUrl));
            }
        }

        log.info("Crawling detail page: {}", originalUrl);
        Document detailDoc = Jsoup.connect(originalUrl).userAgent(USER_AGENT).timeout(10000).get();
        Element contentElement = detailDoc.selectFirst(DETAIL_CONTENT_SELECTOR);
        String fullContentKorean = contentElement != null ? contentElement.text() : descriptionKorean;

        if (contentElement != null) {
            contentElement.select(DETAIL_IMAGE_SELECTOR).forEach(image -> {
                String imageUrl = image.absUrl("src");
                if (!imageUrl.isEmpty()) {
                    imageUrlSet.add(getHighQualityUrl(imageUrl));
                }
            });
        }

        return Optional.of(new CrawledData(
                translationService.translatePost(titleKorean, "en"),
                translationService.translatePost(fullContentKorean, "en"),
                originalUrl,
                SOURCE_SITE,
                new ArrayList<>(imageUrlSet)
        ));
    }

    private String getHighQualityUrl(String url) {
        return url.contains("?") ? url.substring(0, url.indexOf("?")) : url;
    }
}
