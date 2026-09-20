package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.dto.ExcelExport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExcelExportPackageTest {

    @Test
    void xlsxOnlyWhenNoUploads() {
        byte[] xlsx = "sheet".getBytes(StandardCharsets.UTF_8);
        ExcelExport exported = ExcelExportPackage.toDownload("Default.xlsx", xlsx, Map.of(), 3);
        assertEquals("Default.xlsx", exported.fileName());
        assertEquals(ExcelExportPackage.XLSX_MEDIA_TYPE, exported.mediaType());
        assertEquals(0, exported.fileCount());
        assertArrayEquals(xlsx, exported.content());
    }

    @Test
    void zipContainsWorkbookAndFiles() throws Exception {
        byte[] xlsx = "sheet".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("case-upload/id-photo.png", "png".getBytes(StandardCharsets.UTF_8));
        ExcelExport exported = ExcelExportPackage.toDownload("plan/Default.xlsx", xlsx, files, 1);
        assertEquals("Default.zip", exported.fileName());
        assertEquals(ExcelExportPackage.ZIP_MEDIA_TYPE, exported.mediaType());
        assertEquals(1, exported.fileCount());
        assertEquals("png", new String(readZip(exported.content(), "files/case-upload/id-photo.png"),
                StandardCharsets.UTF_8));
        assertEquals("sheet", new String(readZip(exported.content(), "Default.xlsx"), StandardCharsets.UTF_8));
        assertEquals("file=file:files/case-upload/id-photo.png",
                ExcelExportPackage.uploadReference("file", "case-upload", "id", "photo.png"));
    }

    private static byte[] readZip(byte[] zip, String name) throws Exception {
        try (ZipInputStream stream = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = stream.getNextEntry()) != null) {
                if (name.equals(entry.getName().replace('\\', '/'))) {
                    return stream.readAllBytes();
                }
            }
        }
        throw new AssertionError("missing " + name);
    }
}
