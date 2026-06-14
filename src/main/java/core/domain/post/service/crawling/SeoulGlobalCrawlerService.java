package core.domain.post.service.crawling;

import core.domain.post.entity.CrawledData;
import core.domain.post.repository.CrawledDataRepository;
import core.global.service.TranslationService;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class SeoulGlobalCrawlerService extends AbstractCrawler<Element> {

    private static final String BASE_URL = "https://global.seoul.go.kr";
    private static final String LIST_API_URL = BASE_URL + "/web/news/senw/bordContListPgng.do";
    private static final String DETAIL_URL_PREFIX = BASE_URL + "/web/news/senw/bordContDetail.do?brd_no=5&lang=ko&post_no=";
    private static final String SOURCE_SITE = "global.seoul.go.kr";
    private static final Pattern POST_NO_PATTERN = Pattern.compile("contDetail\\('([^']+)'\\)");

    private final TranslationService translationService;

    public SeoulGlobalCrawlerService(
            CrawledDataRepository crawledDataRepository,
            CrawledDataWriter crawledDataWriter,
            TranslationService translationService
    ) {
        super(crawledDataRepository, crawledDataWriter);
        this.translationService = translationService;
    }

    @Scheduled(cron = "0 0 6 * * *")
    public void crawlSeoulGlobalNews() {
        crawl();
    }

    @Override
    protected String sourceSite() {
        return SOURCE_SITE;
    }

    @Override
    protected List<Element> fetchArticleReferences() throws Exception {
        Document listDoc = Jsoup.connect(LIST_API_URL)
                .method(Connection.Method.POST)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "https://global.seoul.go.kr/web/news/senw/bordContListPage.do?brd_no=5")
                .data("miv_pageNo", "1")
                .data("brd_no", "5")
                .data("searchkey", "T")
                .data("lang", "ko")
                .data("sidx", "NOTI_YN DESC, REF_NO DESC, REF_STEP_NO")
                .data("sord", "ASC")
                .post();
        Elements articles = listDoc.select("tbody tr");
        log.info("Found {} articles on Seoul Global list page.", articles.size());
        return new ArrayList<>(articles);
    }

    @Override
    protected String originalUrl(Element articleElement) {
        Element linkElement = articleElement.selectFirst("td.title_box a.title");
        if (linkElement == null) {
            return null;
        }
        Matcher matcher = POST_NO_PATTERN.matcher(linkElement.attr("href"));
        return matcher.find() ? DETAIL_URL_PREFIX + matcher.group(1) : null;
    }

    @Override
    protected Optional<CrawledData> parseArticle(Element articleElement) throws Exception {
        Element linkElement = articleElement.selectFirst("td.title_box a.title");
        if (linkElement == null) {
            return Optional.empty();
        }

        String originalUrl = originalUrl(articleElement);
        String title = linkElement.text();
        log.info("Crawling detail page: {}", originalUrl);
        Document detailDoc = Jsoup.connect(originalUrl).timeout(10000).get();
        Element contentElement = detailDoc.selectFirst("div.content");
        String fullContent = contentElement != null ? contentElement.text() : title;
        Set<String> imageUrlSet = new HashSet<>();

        if (contentElement != null) {
            contentElement.select("img")
                    .forEach(image -> imageUrlSet.add(cleanImageUrl(image.absUrl("src"))));
        }

        log.info("Translating content for: {}", title);
        return Optional.of(new CrawledData(
                title,
                translationService.translatePost(fullContent, "en"),
                originalUrl,
                SOURCE_SITE,
                new ArrayList<>(imageUrlSet)
        ));
    }

    private String cleanImageUrl(String rawUrl) {
        return rawUrl.contains("?") ? rawUrl.substring(0, rawUrl.indexOf("?")) : rawUrl;
    }
}
