package com.tx.api.test.generator.studio.dto;

import java.time.Instant;
import java.util.Map;

/**
 * @param current      this is what is published now
 * @param missingFiles this version uses an uploaded file that is no longer stored, so it can't be restored
 */
public record CaseVersionView(
        String id,
        Instant publishedAt,
        String publishedBy,
        boolean current,
        boolean missingFiles,
        Map<String, Object> document
) {
}
