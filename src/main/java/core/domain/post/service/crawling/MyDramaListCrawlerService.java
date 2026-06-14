package core.domain.post.service.crawling;

import core.domain.post.entity.CrawledData;
import core.domain.post.repository.CrawledDataRepository;
import lombok.extern.slf4j.Slf4j;
import org.htmlunit.BrowserVersion;
import org.htmlunit.ScriptException;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.javascript.JavaScriptErrorListener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

@Slf4j
@Service
public class MyDramaListCrawlerService extends AbstractCrawler<Element> {

    private static final String BASE_URL = "https://mydramalist.com";
    private static final String LIST_URL = BASE_URL + "/search?adv=titles&ty=68,77,86&co=3&so=newest&or=desc";
    private static final String SOURCE_SITE = "mydramalist.com";
    private static final String LIST_ARTICLE_SELECTOR = "div.box";
    private static final String LIST_LINK_SELECTOR = "h6.title a";
    private static final String LIST_THUMBNAIL_SELECTOR = "img.cover.lazy";
    private static final String DETAIL_CONTENT_SELECTOR = "div.show-synopsis > p > span";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private final ThreadLocal<WebClient> webClientHolder = new ThreadLocal<>();

    public MyDramaListCrawlerService(
            CrawledDataRepository crawledDataRepository,
            CrawledDataWriter crawledDataWriter
    ) {
        super(crawledDataRepository, crawledDataWriter);
    }

    @Scheduled(cron = "0 0 7 * * *")
    public void crawlMyDramaList() {
        crawl();
    }

    @Override
    protected String sourceSite() {
        return SOURCE_SITE;
    }

    @Override
    protected void beforeCrawl() {
        Logger.getLogger("org.htmlunit").setLevel(Level.OFF);
        Logger.getLogger("org.htmlunit.javascript").setLevel(Level.OFF);
        Logger.getLogger("org.htmlunit.css").setLevel(Level.OFF);

        WebClient webClient = new WebClient(BrowserVersion.CHROME);
        webClientHolder.set(webClient);
        webClient.getOptions().setJavaScriptEnabled(true);
        webClient.getOptions().setCssEnabled(false);
        webClient.getOptions().setThrowExceptionOnScriptError(false);
        webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
        webClient.getOptions().setPrintContentOnFailingStatusCode(false);
        webClient.getOptions().setTimeout(20000);
        webClient.setJavaScriptErrorListener(new SilentJavaScriptErrorListener());
        webClient.setCssErrorHandler(new SilentCssErrorHandler());
        webClient.setIncorrectnessListener((message, origin) -> {
        });
        webClient.addRequestHeader("User-Agent", USER_AGENT);
        webClient.addRequestHeader("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
        webClient.addRequestHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        webClient.addRequestHeader("Referer", "https://www.google.com/");
    }

    @Override
    protected List<Element> fetchArticleReferences() throws Exception {
        log.debug("HtmlUnit: Connecting to List Page {}", LIST_URL);
        HtmlPage listPage = webClient().getPage(LIST_URL);
        webClient().waitForBackgroundJavaScript(15000);
        log.info("HtmlUnit: List Page loaded.");

        Document listDoc = Jsoup.parse(listPage.asXml());
        Elements articles = listDoc.select(LIST_ARTICLE_SELECTOR);
        log.info("Found {} articles on mydramalist search page.", articles.size());
        if (articles.isEmpty()) {
            log.warn("Articles empty. Server IP might be blocked by Cloudflare challenge.");
        }
        return new ArrayList<>(articles);
    }

    @Override
    protected String originalUrl(Element articleElement) {
        Element linkElement = articleElement.selectFirst(LIST_LINK_SELECTOR);
        return linkElement == null ? null : BASE_URL + linkElement.attr("href");
    }

    @Override
    protected Optional<CrawledData> parseArticle(Element articleElement) throws Exception {
        Element linkElement = articleElement.selectFirst(LIST_LINK_SELECTOR);
        Element thumbElement = articleElement.selectFirst(LIST_THUMBNAIL_SELECTOR);
        if (linkElement == null || thumbElement == null) {
            return Optional.empty();
        }

        String originalUrl = originalUrl(articleElement);
        String title = linkElement.text();
        String imageUrl = thumbElement.absUrl("data-src");
        log.info("Crawling detail page: {}", originalUrl);
        HtmlPage detailPage = webClient().getPage(originalUrl);
        webClient().waitForBackgroundJavaScript(5000);

        Document detailDoc = Jsoup.parse(detailPage.asXml());
        Element contentElement = detailDoc.selectFirst(DETAIL_CONTENT_SELECTOR);
        String fullContent;
        if (contentElement != null) {
            fullContent = contentElement.text();
        } else {
            Element snippetElement = articleElement.selectFirst("div.content p:not(:has(span.rating))");
            fullContent = snippetElement != null ? snippetElement.text() : title;
        }

        return Optional.of(new CrawledData(
                title,
                fullContent,
                originalUrl,
                SOURCE_SITE,
                List.of(imageUrl)
        ));
    }

    @Override
    protected void afterCrawl() {
        WebClient webClient = webClientHolder.get();
        if (webClient != null) {
            try {
                webClient.close();
            } finally {
                webClientHolder.remove();
            }
        }
    }

    @Override
    protected void onCrawlFailed(Exception exception) {
        log.error("Error occurred during crawling mydramalist.com", exception);
    }

    private WebClient webClient() {
        return webClientHolder.get();
    }

    private static class SilentJavaScriptErrorListener implements JavaScriptErrorListener {

        @Override
        public void scriptException(HtmlPage page, ScriptException scriptException) {
        }

        @Override
        public void timeoutError(HtmlPage page, long allowedTime, long executionTime) {
        }

        @Override
        public void malformedScriptURL(HtmlPage page, String url, MalformedURLException exception) {
        }

        @Override
        public void loadScriptError(HtmlPage page, URL scriptUrl, Exception exception) {
        }

        @Override
        public void warn(String message, String sourceName, int line, String lineSource, int lineOffset) {
        }
    }

    private static class SilentCssErrorHandler implements org.htmlunit.cssparser.parser.CSSErrorHandler {

        @Override
        public void error(org.htmlunit.cssparser.parser.CSSParseException exception) {
        }

        @Override
        public void fatalError(org.htmlunit.cssparser.parser.CSSParseException exception) {
        }

        @Override
        public void warning(org.htmlunit.cssparser.parser.CSSParseException exception) {
        }
    }
}
