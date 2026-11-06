package br.com.ged.search;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class SearchIndexCoordinator implements ApplicationRunner {

    private final ReindexService reindexService;

    public SearchIndexCoordinator(ReindexService reindexService) {
        this.reindexService = reindexService;
    }

    @Override
    public void run(ApplicationArguments args) {
        reindexService.reindexIfEmpty();
    }
}
