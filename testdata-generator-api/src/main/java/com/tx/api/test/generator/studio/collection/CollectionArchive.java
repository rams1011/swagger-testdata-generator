package com.tx.api.test.generator.studio.collection;

import java.util.Map;

public record CollectionArchive(
        StudioCollection collection,
        Map<String, byte[]> files
) {
}
