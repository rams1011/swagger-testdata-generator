package com.tx.api.test.generator.studio.collection;

import java.util.List;

public record StudioCollection(
        String format,
        int version,
        StudioCollectionSpec spec,
        StudioCollectionPlan plan,
        List<StudioCollectionCase> cases
) {
}
