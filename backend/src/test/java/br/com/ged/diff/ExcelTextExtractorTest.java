package br.com.ged.diff;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExcelTextExtractorTest {

    private final ExcelTextExtractor extractor = new ExcelTextExtractor();

    @TempDir
    Path tempDir;

    @Test
    void extraiCelulasComAbaEReferencia() throws IOException {
        Path xlsx = tempDir.resolve("orcamento.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Orcamento");
            Row linha = sheet.createRow(1);
            linha.createCell(1).setCellValue(42);
            linha.createCell(2).setCellValue("ativo");
            try (OutputStream output = Files.newOutputStream(xlsx)) {
                workbook.write(output);
            }
        }

        assertEquals(List.of(
                "Orcamento!B2: 42",
                "Orcamento!C2: ativo"), extractor.extract(xlsx));
    }

    @Test
    void dimensionaVariasAbas() throws IOException {
        Path xlsx = tempDir.resolve("planilha.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("AbaUm").createRow(0).createCell(0).setCellValue(1);
            workbook.createSheet("AbaDois").createRow(0).createCell(0).setCellValue("x");
            try (OutputStream output = Files.newOutputStream(xlsx)) {
                workbook.write(output);
            }
        }

        assertEquals(List.of("AbaUm!A1: 1", "AbaDois!A1: x"), extractor.extract(xlsx));
    }

    @Test
    void suportaSomenteAExtensaoXlsx() {
        assertEquals(Set.of("xlsx"), extractor.extensions());
    }
}
