package com.tx.api.test.generator.studio.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SpecListFilterCacheKeysTest {

    @Test
    void distinguishesPagedFilters() {
        SpecListFilter first = SpecListFilter.of(null, null, "updated", 0, 20, null, false);
        SpecListFilter second = SpecListFilter.of(null, null, "updated", 20, 20, null, false);
        assertNotEquals(SpecListFilterCacheKeys.forFilter(first), SpecListFilterCacheKeys.forFilter(second));
    }

    @Test
    void usesStableKeyForUnpagedList() {
        assertEquals("all", SpecListFilterCacheKeys.forFilter(null));
        assertEquals("all", SpecListFilterCacheKeys.forFilter(new SpecListFilter(null, null, "updated", 0, 0, List.of(), null)));
    }
}
