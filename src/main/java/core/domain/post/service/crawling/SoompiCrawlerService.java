package core.domain.post.service.crawling;

import core.domain.post.entity.CrawledData;
import core.domain.post.repository.CrawledDataRepository;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
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
public class SoompiCrawlerService extends AbstractCrawler<Element> {

    private static final String RSS_FEED_URL = "https://www.soompi.com/feed";
    private static final String SOURCE_SITE = "soompi.com";
    private static final String RSS_ITEM_SELECTOR = "item";
    private static final String RSS_LINK_SELECTOR = "link";
    private static final String RSS_TITLE_SELECTOR = "title";
    private static final String DETAIL_CONTENT_WRAPPER = "div.article-wrapper > div";
    private static final String DETAIL_MAIN_IMAGE_SELECTOR = "span.image-wrapper img";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";

    public SoompiCrawlerService(
            CrawledDataRepository crawledDataRepository,
            CrawledDataWriter crawledDataWriter
    ) {
        super(crawledDataRepository, crawledDataWriter);
    }

    @Scheduled(cron = "0 45 6 * * *")
    public void crawlSoompiLatest() {
        crawl();
    }

    @Override
    protected String sourceSite() {
        return SOURCE_SITE;
    }

    @Override
    protected List<Element> fetchArticleReferences() throws Exception {
        Document feedDoc = Jsoup.connect(RSS_FEED_URL)
                .userAgent(USER_AGENT)
                .parser(Parser.xmlParser())
                .get();
        Elements articles = feedDoc.select(RSS_ITEM_SELECTOR);
        log.info("Found {} articles from Soompi RSS feed.", articles.size());
        if (articles.isEmpty()) {
            log.warn("No articles found in RSS feed.");
        }
        return new ArrayList<>(articles);
    }

    @Override
    protected String originalUrl(Element articleElement) {
        Element linkElement = articleElement.selectFirst(RSS_LINK_SELECTOR);
        return linkElement == null ? null : linkElement.text();
    }

    @Override
    protected Optional<CrawledData> parseArticle(Element articleElement) throws Exception {
        Element titleElement = articleElement.selectFirst(RSS_TITLE_SELECTOR);
        if (titleElement == null) {
            return Optional.empty();
        }

        String originalUrl = originalUrl(articleElement);
        String title = titleElement.text();
        Set<String> imageUrlSet = new HashSet<>();
        log.info("Crawling detail page: {}", originalUrl);
        Document detailDoc = Jsoup.connect(originalUrl)
                .userAgent(USER_AGENT)
                .referrer("https://www.soompi.com/latest")
                .get();

        Element thumbElement = detailDoc.selectFirst(DETAIL_MAIN_IMAGE_SELECTOR);
        if (thumbElement != null) {
            imageUrlSet.add(thumbElement.absUrl("src"));
        }

        Element contentWrapper = detailDoc.selectFirst(DETAIL_CONTENT_WRAPPER);
        String fullContent = "";
        if (contentWrapper != null) {
            StringBuilder contentBuilder = new StringBuilder();
            for (Element paragraph : contentWrapper.select("p")) {
                String text = paragraph.text();
                if (!text.startsWith("Source (")
                        && !text.startsWith("In the meantime, watch")
                        && !paragraph.hasClass("has-text-align-center")) {
                    contentBuilder.append(text).append("\n\n");
                }
            }
            fullContent = contentBuilder.toString().trim();
            contentWrapper.select("figure.wp-block-image img")
                    .forEach(image -> imageUrlSet.add(image.absUrl("src")));
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
}
