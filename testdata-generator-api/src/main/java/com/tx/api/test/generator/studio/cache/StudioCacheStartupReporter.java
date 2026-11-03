package com.tx.api.test.generator.studio.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
public class StudioCacheStartupReporter implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StudioCacheStartupReporter.class);

    private final StudioCacheDiagnostics diagnostics;

    public StudioCacheStartupReporter(StudioCacheDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        switch (diagnostics.status()) {
            case ACTIVE -> log.info("Studio cache: {}", diagnostics.detail());
            case UNAVAILABLE -> log.warn("Studio cache: {}", diagnostics.detail());
            case DISABLED -> log.info("Studio cache: {}", diagnostics.detail());
            default -> {
            }
        }
    }
}
