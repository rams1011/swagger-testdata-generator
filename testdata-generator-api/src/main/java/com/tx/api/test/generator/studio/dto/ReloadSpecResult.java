package com.tx.api.test.generator.studio.dto;

import java.util.List;

public record ReloadSpecResult(
        SpecSummary spec,
        List<String> addedOperations,
        List<String> removedOperations,
        int unchangedOperations,
        int orphanedCaseCount
) {
}
