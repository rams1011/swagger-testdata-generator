package com.tx.api.test.generator.studio.dto;

import java.util.List;

public record SpecListPage(
        List<SpecSummary> items,
        long total,
        int offset,
        int limit,
        boolean hasMore
) {
}
