package com.tx.api.test.generator.studio.collection;

public record StudioCollectionFile(
        String fileId,
        String fileName,
        String contentType,
        long sizeBytes,
        String path
) {
}
