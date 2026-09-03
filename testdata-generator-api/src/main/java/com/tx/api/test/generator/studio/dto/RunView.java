package com.tx.api.test.generator.studio.dto;

import java.time.Instant;
import java.util.List;

public record RunView(
        String runId,
        String specId,
        String planId,
        String trigger,
        int passed,
        int failed,
        int skipped,
        Instant startedAt,
        List<RunResultView> results
) {
}
