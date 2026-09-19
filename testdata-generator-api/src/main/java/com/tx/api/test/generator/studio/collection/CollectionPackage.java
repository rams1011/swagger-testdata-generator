package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.dto.CollectionExport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class CollectionPackage {

    private CollectionPackage() {
    }

    public static CollectionArchive parse(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            throw new CollectionFormatException("Collection JSON is required");
        }
        if (isZip(bytes, fileName)) {
            return parseZip(bytes);
        }
        return new CollectionArchive(
                CollectionDocuments.parseCollection(new String(bytes, StandardCharsets.UTF_8)),
                Map.of()
        );
    }

    public static CollectionExport toDownload(StudioCollection collection, Map<String, byte[]> files) {
        CollectionMapper.validate(collection);
        String specName = collection.spec().name();
        String planName = collection.plan().name();
        byte[] json = CollectionDocuments.toJson(collection).getBytes(StandardCharsets.UTF_8);
        int fileCount = files == null ? 0 : files.size();
        if (fileCount == 0) {
            return new CollectionExport(
                    CollectionFormat.jsonFileName(specName, planName),
                    json,
                    CollectionFormat.JSON_MEDIA_TYPE,
                    collection.cases().size(),
                    0
            );
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
                put(zip, CollectionFormat.collectionEntryPath(specName, planName), json);
                for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                    byte[] content = entry.getValue() == null ? new byte[0] : entry.getValue();
                    put(zip, CollectionFormat.zipFileEntryPath(specName, entry.getKey()), content);
                }
            }
            return new CollectionExport(
                    CollectionFormat.zipFileName(specName, planName),
                    buffer.toByteArray(),
                    CollectionFormat.ZIP_MEDIA_TYPE,
                    collection.cases().size(),
                    fileCount
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to build collection zip", exception);
        }
    }

    private static void put(ZipOutputStream zip, String path, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private static boolean isZip(byte[] bytes, String fileName) {
        if (fileName != null && fileName.toLowerCase().endsWith(".zip")) {
            return true;
        }
        return bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K';
    }

    private static CollectionArchive parseZip(byte[] bytes) {
        String json = null;
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                if (name.contains("..")) {
                    throw new CollectionFormatException("Collection zip path is not allowed: " + name);
                }
                byte[] content = zip.readAllBytes();
                int filesAt = name.lastIndexOf("/files/");
                if (filesAt >= 0) {
                    files.put(name.substring(filesAt + "/files/".length()), content);
                    continue;
                }
                if (name.endsWith(".collection.json") || (json == null && name.endsWith(".json"))) {
                    json = new String(content, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException exception) {
            throw new CollectionFormatException("Collection zip is not valid: " + exception.getMessage());
        }
        if (json == null || json.isBlank()) {
            throw new CollectionFormatException("Collection zip is missing a .collection.json file");
        }
        return new CollectionArchive(CollectionDocuments.parseCollection(json), Map.copyOf(files));
    }
}
