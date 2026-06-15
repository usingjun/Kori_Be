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
public class AllkpopCrawlerService extends AbstractCrawler<Element> {

    private static final String BASE_URL = "https://www.allkpop.com";
    private static final String LIST_URL = BASE_URL + "/category/news";
    private static final String SOURCE_SITE = "allkpop.com";

    private static final String ARTICLE_SELECTOR = "div#more_stories_scr article.list";
    private static final String LINK_SELECTOR = "div.text div.title a";
    private static final String THUMBNAIL_SELECTOR = "div.image img.b-lazy";
    private static final String DETAIL_CONTENT_SELECTOR = "div.entry_content";

    public AllkpopCrawlerService(
            CrawledDataRepository crawledDataRepository,
            CrawledDataWriter crawledDataWriter
    ) {
        super(crawledDataRepository, crawledDataWriter);
    }

    @Scheduled(cron = "0 30 6 * * *")
    public void crawlAllkpopNews() {
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
        log.info("Found {} articles on allkpop list page.", articles.size());
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
            return Optional.empty();
        }

        String originalUrl = originalUrl(articleElement);
        String title = linkElement.text();
        Set<String> imageUrlSet = new HashSet<>();

        Element thumbnailElement = articleElement.selectFirst(THUMBNAIL_SELECTOR);
        if (thumbnailElement != null) {
            imageUrlSet.add(cleanImageUrl(thumbnailElement.absUrl("data-src")));
        }

        log.info("Crawling detail page: {}", originalUrl);
        Document detailDoc = Jsoup.connect(originalUrl).timeout(10000).get();
        Element contentElement = detailDoc.selectFirst(DETAIL_CONTENT_SELECTOR);
        String fullContent = "";

        if (contentElement != null) {
            StringBuilder contentBuilder = new StringBuilder();
            for (Element paragraph : contentElement.select("p")) {
                String text = paragraph.text();
                if (!text.startsWith("SEE ALSO:") && !text.isBlank()) {
                    contentBuilder.append(text).append("\n\n");
                }
            }
            fullContent = contentBuilder.toString().trim();
            contentElement.select("figure > img")
                    .forEach(image -> imageUrlSet.add(cleanImageUrl(image.absUrl("src"))));
        }

        if (fullContent.isEmpty()) {
            fullContent = title;
        }

        return Optional.of(new CrawledData(
                title,
                fullContent,
                originalUrl,
                SOURCE_SITE,
                new ArrayList<>(imageUrlSet)
        ));
    }

    private String cleanImageUrl(String rawUrl) {
        String cleanUrl = rawUrl;
        if (cleanUrl.contains("?")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.indexOf("?"));
        }
        return cleanUrl.replace("/thumb", "");
    }
}
