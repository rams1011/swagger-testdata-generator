package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.dto.ExcelExport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ExcelExportPackage {

    public static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String ZIP_MEDIA_TYPE = "application/zip";
    public static final String FILES_DIR = "files";

    private ExcelExportPackage() {
    }

    public static ExcelExport toDownload(String xlsxFileName, byte[] xlsx, Map<String, byte[]> files, int rowCount) {
        String sheetName = fileNameOnly(xlsxFileName);
        if (files == null || files.isEmpty()) {
            return new ExcelExport(sheetName, xlsx == null ? new byte[0] : xlsx, rowCount, XLSX_MEDIA_TYPE, 0);
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
                put(zip, sheetName, xlsx == null ? new byte[0] : xlsx);
                for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                    if (entry.getKey() == null || entry.getKey().isBlank()) {
                        continue;
                    }
                    put(zip, FILES_DIR + "/" + entry.getKey().replace('\\', '/'),
                            entry.getValue() == null ? new byte[0] : entry.getValue());
                }
            }
            return new ExcelExport(zipFileName(sheetName), buffer.toByteArray(), rowCount, ZIP_MEDIA_TYPE, files.size());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to build Excel zip", exception);
        }
    }

    public static String zipFileName(String xlsxFileName) {
        String name = fileNameOnly(xlsxFileName);
        if (name.toLowerCase().endsWith(".xlsx")) {
            name = name.substring(0, name.length() - 5);
        }
        return name + ".zip";
    }

    public static String relativeFilePath(String caseId, String fileId, String fileName) {
        return CollectionFormat.relativeFilePath(caseId, fileId, fileName);
    }

    public static String uploadReference(String fieldName, String caseId, String fileId, String fileName) {
        return fieldName + "=file:" + FILES_DIR + "/" + relativeFilePath(caseId, fileId, fileName);
    }

    private static String fileNameOnly(String name) {
        String value = name == null ? "TestData.xlsx" : name.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        return value.isBlank() ? "TestData.xlsx" : value;
    }

    private static void put(ZipOutputStream zip, String path, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }
}
