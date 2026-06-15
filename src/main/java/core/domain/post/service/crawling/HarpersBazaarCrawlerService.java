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

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class HarpersBazaarCrawlerService extends AbstractCrawler<Element> {

    private static final String BASE_URL = "https://www.harpersbazaar.co.kr";
    private static final String AJAX_URL = BASE_URL + "/fashion/news/more";
    private static final String SOURCE_SITE = "harpersbazaar.co.kr";
    private static final String LIST_ARTICLE_SELECTOR = "li";
    private static final String LIST_LINK_SELECTOR = "a";
    private static final String LIST_THUMBNAIL_SELECTOR = "img";
    private static final String LIST_TITLE_SELECTOR = "p.tit";
    private static final String DETAIL_CONTENT_SELECTOR = "div.atc_body_cont > div:not(.atc_mask_login)";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";

    private final TranslationService translationService;

    public HarpersBazaarCrawlerService(
            CrawledDataRepository crawledDataRepository,
            CrawledDataWriter crawledDataWriter,
            TranslationService translationService
    ) {
        super(crawledDataRepository, crawledDataWriter);
        this.translationService = translationService;
    }

    @Scheduled(cron = "0 0 9 * * *")
    public void crawlHarpersBazaar() {
        crawl();
    }

    @Override
    protected String sourceSite() {
        return SOURCE_SITE;
    }

    @Override
    protected List<Element> fetchArticleReferences() throws Exception {
        Document listDoc = Jsoup.connect(AJAX_URL)
                .userAgent(USER_AGENT)
                .header("Referer", BASE_URL + "/fashion/news")
                .header("Content-Type", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .requestBody("{\"offset\":0,\"limit\":24}")
                .ignoreContentType(true)
                .post();
        Elements articles = listDoc.select(LIST_ARTICLE_SELECTOR);
        log.info("Found {} articles from HarpersBazaar AJAX response.", articles.size());
        if (articles.isEmpty()) {
            log.warn("No articles found. AJAX request might have failed.");
        }
        return new ArrayList<>(articles);
    }

    @Override
    protected String originalUrl(Element articleElement) {
        Element linkElement = articleElement.selectFirst(LIST_LINK_SELECTOR);
        return linkElement == null ? null : linkElement.absUrl("href");
    }

    @Override
    protected Optional<CrawledData> parseArticle(Element articleElement) throws Exception {
        Element thumbElement = articleElement.selectFirst(LIST_THUMBNAIL_SELECTOR);
        Element titleElement = articleElement.selectFirst(LIST_TITLE_SELECTOR);
        if (thumbElement == null || titleElement == null) {
            return Optional.empty();
        }

        String originalUrl = originalUrl(articleElement);
        String title = titleElement.text();
        Set<String> imageUrlSet = new HashSet<>();
        String thumbUrl = thumbElement.hasAttr("data-src")
                ? thumbElement.absUrl("data-src")
                : thumbElement.absUrl("src");
        if (!thumbUrl.isEmpty()) {
            imageUrlSet.add(getHighQualityUrl(thumbUrl));
        }

        log.info("Crawling detail page: {}", originalUrl);
        Document detailDoc = Jsoup.connect(originalUrl)
                .userAgent(USER_AGENT)
                .referrer(BASE_URL + "/fashion/news")
                .get();
        Element contentWrapper = detailDoc.selectFirst(DETAIL_CONTENT_SELECTOR);
        String koreanContent = "";
        if (contentWrapper != null) {
            contentWrapper.select("br").after("\n");
            contentWrapper.select("div.ab_related_article").remove();
            koreanContent = contentWrapper.text().replace("&nbsp;", " ");
            contentWrapper.select("img").forEach(image -> {
                String imageUrl = image.hasAttr("data-src")
                        ? image.absUrl("data-src")
                        : image.absUrl("src");
                if (!imageUrl.isEmpty()) {
                    imageUrlSet.add(getHighQualityUrl(imageUrl));
                }
            });
        }

        if (koreanContent.isEmpty()) {
            koreanContent = title;
        }

        return Optional.of(new CrawledData(
                title,
                translationService.translatePost(koreanContent, "en"),
                originalUrl,
                SOURCE_SITE,
                new ArrayList<>(imageUrlSet)
        ));
    }

    @Override
    protected void onCrawlFailed(Exception exception) {
        log.error("Error occurred during crawling harpersbazaar.co.kr", exception);
        throw new RuntimeException("HarpersBazaar 크롤링 실패: " + exception.getMessage(), exception);
    }

    @Override
    protected void onCrawlInterrupted(InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("HarpersBazaar 크롤링 실패: " + exception.getMessage(), exception);
    }

    private String getHighQualityUrl(String originalUrl) {
        if (originalUrl == null || originalUrl.isBlank()) {
            return "";
        }

        String cleanUrl = originalUrl.contains("?")
                ? originalUrl.substring(0, originalUrl.indexOf("?"))
                : originalUrl;
        String highQualityUrl = cleanUrl.contains("harpersbazaar.co.kr") && cleanUrl.contains("/thumbnail/")
                ? cleanUrl.replaceAll("/thumbnail/[a-z]+/", "/online_image/")
                : cleanUrl;

        if (highQualityUrl.equals(originalUrl) || isValidUrl(highQualityUrl)) {
            return highQualityUrl;
        }
        log.warn("High quality image check failed (404). Using original. URL: {}", highQualityUrl);
        return originalUrl;
    }

    private boolean isValidUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            return connection.getResponseCode() == HttpURLConnection.HTTP_OK;
        } catch (Exception exception) {
            return false;
        }
    }
}
