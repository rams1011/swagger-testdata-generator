package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.dto.CollectionExport;
import com.tx.api.test.generator.studio.model.StudioRequestFile;
import com.tx.api.test.generator.studio.storage.StudioStorage;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CollectionExporter {

    private CollectionExporter() {
    }

    public static CollectionExport export(StudioStorage storage, String specId, String planId) {
        StudioCollection collection = CollectionMapper.readFrom(storage, specId, planId);
        if (collection.cases().isEmpty()) {
            throw new IllegalStateException("No test cases have been saved for this plan yet");
        }
        Map<String, byte[]> binaries = new LinkedHashMap<>();
        for (StudioCollectionCase item : collection.cases()) {
            if (item.files() == null || item.files().isEmpty()) {
                continue;
            }
            for (StudioCollectionFile file : item.files()) {
                StudioRequestFile stored = storage.findRequestFile(item.id(), file.fileId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Saved file '" + file.fileName() + "' is missing. Re-upload it before export."));
                binaries.put(file.path(), stored.getContent() == null ? new byte[0] : stored.getContent());
            }
        }
        return CollectionPackage.toDownload(collection, binaries);
    }
}
