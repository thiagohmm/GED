package br.com.ged.diff;

import br.com.ged.exception.BusinessException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class PdfTextExtractor implements TextExtractor {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf");
    private static final int MAX_PAGES = 2_000;
    private static final int MAX_LINES = 100_000;

    @Override
    public Set<String> extensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public List<String> extract(Path file) {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(document.getNumberOfPages(), MAX_PAGES));
            List<String> lines = new ArrayList<>();
            for (String rawLine : stripper.getText(document).split("\r?\n")) {
                String line = rawLine.strip();
                if (!line.isEmpty()) {
                    lines.add(line);
                    if (lines.size() >= MAX_LINES) {
                        break;
                    }
                }
            }
            return lines;
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException("Falha ao ler o documento PDF \"%s\".".formatted(file.getFileName()));
        }
    }
}
