package com.tx.api.test.generator.writer;

import com.tx.api.test.generator.model.TestDataRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class TestDataExcelWriter {

    private static final String[] HEADERS = {
            "TestName",
            "BaseUrl",
            "HttpMethod",
            "ResourceUrl",
            "ContentType",
            "QueryParam",
            "QueryValue",
            "HeaderName",
            "HeaderValue",
            "RequestBody",
            "StatusCode",
            "ResponseType",
            "ExpectedResponse",
            "Enabled",
            "TestGroup",
            "JsonPathAssertions",
            "JsonSchemaPath",
            "MaxResponseTimeMs",
            "UploadFiles",
            "DownloadFilePath",
            "Extracts"
    };

    public void write(Path outputFile, List<TestDataRow> rows) {
        try {
            Path parent = outputFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream outputStream = Files.newOutputStream(outputFile)) {
                write(outputStream, rows);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write Excel file: " + outputFile, exception);
        }
    }

    public void write(OutputStream outputStream, List<TestDataRow> rows) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("TestData");
            CellStyle textStyle = createTextCellStyle(workbook);

            writeHeaderRow(sheet, textStyle);
            for (int index = 0; index < rows.size(); index++) {
                writeDataRow(sheet, index + 1, rows.get(index), textStyle);
            }

            autosizeColumns(sheet);
            workbook.write(outputStream);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write Excel workbook", exception);
        }
    }

    private void writeHeaderRow(Sheet sheet, CellStyle textStyle) {
        Row headerRow = sheet.createRow(0);
        for (int columnIndex = 0; columnIndex < HEADERS.length; columnIndex++) {
            setTextCell(headerRow, columnIndex, HEADERS[columnIndex], textStyle);
        }
    }

    private void writeDataRow(Sheet sheet, int rowIndex, TestDataRow row, CellStyle textStyle) {
        Row excelRow = sheet.createRow(rowIndex);
        setTextCell(excelRow, 0, row.getServiceName(), textStyle);
        setTextCell(excelRow, 1, row.getServiceBaseUrl(), textStyle);
        setTextCell(excelRow, 2, row.getRequestType(), textStyle);
        setTextCell(excelRow, 3, row.getEndpoint(), textStyle);
        setTextCell(excelRow, 4, row.getContentType(), textStyle);
        setTextCell(excelRow, 5, row.getQueryParamKey(), textStyle);
        setTextCell(excelRow, 6, row.getQueryParamValue(), textStyle);
        setTextCell(excelRow, 7, row.getHeaderName(), textStyle);
        setTextCell(excelRow, 8, row.getHeaderValue(), textStyle);
        setTextCell(excelRow, 9, row.getRequestBody(), textStyle);
        setTextCell(excelRow, 10, String.valueOf(row.getStatusCode()), textStyle);
        setTextCell(excelRow, 11, row.getResponseType(), textStyle);
        setTextCell(excelRow, 12, row.getExpectedResponse(), textStyle);
        setTextCell(excelRow, 13, row.getEnabled(), textStyle);
        setTextCell(excelRow, 14, row.getTestGroup(), textStyle);
        setTextCell(excelRow, 15, row.getJsonPathAssertions(), textStyle);
        setTextCell(excelRow, 16, row.getJsonSchemaPath(), textStyle);
        setTextCell(excelRow, 17, String.valueOf(row.getMaxResponseTimeMs()), textStyle);
        setTextCell(excelRow, 18, row.getUploadFiles(), textStyle);
        setTextCell(excelRow, 19, row.getDownloadFilePath(), textStyle);
        setTextCell(excelRow, 20, row.getExtracts(), textStyle);
    }

    private CellStyle createTextCellStyle(Workbook workbook) {
        CellStyle textStyle = workbook.createCellStyle();
        textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
        return textStyle;
    }

    private void setTextCell(Row row, int columnIndex, String value, CellStyle textStyle) {
        Cell cell = row.createCell(columnIndex);
        cell.setCellStyle(textStyle);
        cell.setCellValue(value == null ? "NA" : value);
    }

    private void autosizeColumns(Sheet sheet) {
        for (int columnIndex = 0; columnIndex < HEADERS.length; columnIndex++) {
            sheet.autoSizeColumn(columnIndex);
        }
    }
}
