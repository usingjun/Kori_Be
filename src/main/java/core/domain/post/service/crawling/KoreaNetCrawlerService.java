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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class KoreaNetCrawlerService extends AbstractCrawler<Element> {

    private static final String BASE_URL = "https://www.korea.net";
    private static final String LIST_URL = BASE_URL + "/Events/Festivals";
    private static final String SOURCE_SITE = "korea.net";
    private static final Pattern ARTICLE_ID_PATTERN = Pattern.compile("contentView\\(\\s*'[^']+',\\s*'(\\d+)',");

    public KoreaNetCrawlerService(
            CrawledDataRepository crawledDataRepository,
            CrawledDataWriter crawledDataWriter
    ) {
        super(crawledDataRepository, crawledDataWriter);
    }

    @Scheduled(cron = "0 0 5 * * *")
    public void crawlKoreaNetFestivals() {
        crawl();
    }

    @Override
    protected String sourceSite() {
        return SOURCE_SITE;
    }

    @Override
    protected List<Element> fetchArticleReferences() throws Exception {
        Document listDoc = Jsoup.connect(LIST_URL).timeout(10000).get();
        Elements articles = listDoc.select("div.thumb-list div.list-box");
        log.info("Found {} articles on the list page.", articles.size());
        return new ArrayList<>(articles);
    }

    @Override
    protected String originalUrl(Element articleElement) {
        Element linkElement = articleElement.selectFirst("div.txt-wrap > a");
        if (linkElement == null) {
            return null;
        }

        Matcher matcher = ARTICLE_ID_PATTERN.matcher(linkElement.attr("href"));
        return matcher.find()
                ? BASE_URL + "/Events/Festivals/view?articleId=" + matcher.group(1)
                : null;
    }

    @Override
    protected Optional<CrawledData> parseArticle(Element articleElement) throws Exception {
        String originalUrl = originalUrl(articleElement);
        String title = articleElement.selectFirst("p.tit").text();
        String description = articleElement.selectFirst("p.txt").text();

        log.info("Crawling detail page: {}", originalUrl);
        Document detailDoc = Jsoup.connect(originalUrl).timeout(10000).get();
        Element contentDiv = detailDoc.selectFirst("div.post-txt");
        String fullContent = contentDiv != null ? contentDiv.text() : description;
        Set<String> imageUrlSet = new HashSet<>();

        Element mainImage = detailDoc.selectFirst("div.post-img img");
        if (mainImage != null) {
            imageUrlSet.add(cleanImageUrl(mainImage.absUrl("src")));
        }
        detailDoc.select("div.post-txt img")
                .forEach(image -> imageUrlSet.add(cleanImageUrl(image.absUrl("src"))));

        return Optional.of(new CrawledData(
                title,
                fullContent,
                originalUrl,
                SOURCE_SITE,
                new ArrayList<>(imageUrlSet)
        ));
    }

    @Override
    protected Duration delayAfterProcessed() {
        return Duration.ofMillis(2500);
    }

    private String cleanImageUrl(String rawUrl) {
        return rawUrl.contains("?") ? rawUrl.substring(0, rawUrl.indexOf("?")) : rawUrl;
    }
}
