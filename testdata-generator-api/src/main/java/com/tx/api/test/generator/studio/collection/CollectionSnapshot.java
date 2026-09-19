package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.model.StudioTestPlan;

import java.util.List;

public record CollectionSnapshot(
        StoredSpec spec,
        StudioTestPlan plan,
        List<StudioTestCase> cases
) {
}
