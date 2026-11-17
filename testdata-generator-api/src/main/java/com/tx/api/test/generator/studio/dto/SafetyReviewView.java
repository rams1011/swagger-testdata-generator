package com.tx.api.test.generator.studio.dto;

import java.time.Instant;
import java.util.List;

/**
 * Settings → Production safety → Review: what production safety stopped ({@code blocked}) or would have stopped in
 * Shadow mode ({@code wouldBlock}) over the last {@code days}, grouped by operation, target and reason. An item's
 * {@code suggestTreatAsRead} is true for a write to a protected target that no Treat-as-read rule covers yet: if the
 * operation only reads (POST /search), a rule lets it through.
 */
public record SafetyReviewView(
        int days,
        String mode,
        long wouldBlock,
        long blocked,
        List<Item> items,
        boolean truncated
) {
    public record Item(
            String method,
            String pathTemplate,
            String targetId,
            String target,
            String code,
            boolean shadow,
            long count,
            Instant lastSeen,
            String caseId,
            String caseName,
            String specId,
            boolean suggestTreatAsRead
    ) {
    }
}
