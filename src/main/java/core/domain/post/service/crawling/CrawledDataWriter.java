package core.domain.post.service.crawling;

import core.domain.post.entity.CrawledData;
import core.domain.post.repository.CrawledDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrawledDataWriter {

    private final CrawledDataRepository crawledDataRepository;

    public CrawledDataWriteResult save(CrawledData data) {
        try {
            crawledDataRepository.saveAndFlush(data);
            log.info("Successfully crawled and saved: {}", data.getOriginalUrl());
            return CrawledDataWriteResult.SAVED;
        } catch (DataIntegrityViolationException exception) {
            log.warn("Duplicate entry found. Skipping: {}", data.getOriginalUrl());
            return CrawledDataWriteResult.DUPLICATE;
        }
    }
}
