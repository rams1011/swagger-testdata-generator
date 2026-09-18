package com.tx.api.test.generator.studio.collection;

public final class CollectionFormat {

    public static final String COLLECTION_FORMAT = "testdata.studio.collection";
    public static final String ENVIRONMENT_FORMAT = "testdata.studio.environment";
    public static final int VERSION = 1;
    public static final String JSON_MEDIA_TYPE = "application/json";
    public static final String ZIP_MEDIA_TYPE = "application/zip";
    public static final String BASE_URL_PLACEHOLDER = "{{baseUrl}}";

    private CollectionFormat() {
    }

    public static String jsonFileName(String specName, String planName) {
        return downloadBase(specName, planName) + ".collection.json";
    }

    public static String environmentFileName(String specName, String environmentName) {
        return sanitizeSegment(specName, "collection") + "."
                + sanitizeSegment(environmentName, "local") + ".environment.json";
    }

    public static boolean isBaseUrlPlaceholder(String value) {
        return value != null && BASE_URL_PLACEHOLDER.equals(value.trim());
    }

    public static boolean sharesSpecBaseUrl(String caseBaseUrl, String specBaseUrl) {
        if (caseBaseUrl == null || caseBaseUrl.isBlank() || isBaseUrlPlaceholder(caseBaseUrl)) {
            return true;
        }
        return specBaseUrl != null && caseBaseUrl.trim().equals(specBaseUrl.trim());
    }

    public static String zipFileName(String specName, String planName) {
        return downloadBase(specName, planName) + ".collection.zip";
    }

    public static String collectionEntryPath(String specName, String planName) {
        return sanitizeSegment(specName, "collection") + "/" + sanitizeSegment(planName, "Default")
                + ".collection.json";
    }

    public static String zipFileEntryPath(String specName, String relativeFilePath) {
        return sanitizeSegment(specName, "collection") + "/files/" + relativeFilePath.replace('\\', '/');
    }

    public static String relativeFilePath(String caseId, String fileId, String fileName) {
        return sanitizeSegment(caseId, "case") + "/" + sanitizeSegment(fileId, "file") + "-"
                + sanitizeFileName(fileName);
    }

    public static String sanitizeFileName(String name) {
        return sanitizeSegment(name, "upload.bin");
    }

    private static String downloadBase(String specName, String planName) {
        return sanitizeSegment(specName, "collection") + "." + sanitizeSegment(planName, "Default");
    }

    private static String sanitizeSegment(String name, String fallback) {
        String base = name == null || name.isBlank() ? fallback : name.trim();
        base = base.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isBlank() || ".".equals(base) || "..".equals(base)) {
            return fallback;
        }
        return base;
    }
}
