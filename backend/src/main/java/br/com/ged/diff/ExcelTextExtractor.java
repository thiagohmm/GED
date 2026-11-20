package br.com.ged.diff;

import br.com.ged.exception.BusinessException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.RecordFormatException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class ExcelTextExtractor implements TextExtractor {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("xlsx");
    private static final DataFormatter CELL_FORMATTER = new DataFormatter();
    private static final int MAX_SHEETS = 100;
    private static final int MAX_LINES = 100_000;

    static {
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(64L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(10L * 1024 * 1024);
    }

    @Override
    public Set<String> extensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public List<String> extract(Path file) {
        try (InputStream stream = Files.newInputStream(file);
             XSSFWorkbook workbook = new XSSFWorkbook(stream)) {
            int sheets = Math.min(workbook.getNumberOfSheets(), MAX_SHEETS);
            List<String> lines = new ArrayList<>();
            for (int sheetIndex = 0; sheetIndex < sheets; sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                extractSheet(sheet, lines);
                if (lines.size() >= MAX_LINES) {
                    break;
                }
            }
            return lines;
        } catch (IOException | NotOfficeXmlFileException | POIXMLException | RecordFormatException exception) {
            throw new BusinessException("Falha ao ler a planilha Excel \"%s\".".formatted(file.getFileName()));
        }
    }

    private void extractSheet(Sheet sheet, List<String> lines) {
        for (Row row : sheet) {
            for (Cell cell : row) {
                addCellAsLine(sheet, cell, lines);
                if (lines.size() >= MAX_LINES) {
                    return;
                }
            }
        }
    }

    private void addCellAsLine(Sheet sheet, Cell cell, List<String> lines) {
        String value = CELL_FORMATTER.formatCellValue(cell).strip();
        if (value.isEmpty()) {
            return;
        }
        String reference = new CellReference(sheet.getSheetName(), cell.getRowIndex(), cell.getColumnIndex(), false, false)
                .formatAsString();
        lines.add(reference + ": " + value);
    }
}
