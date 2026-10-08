package br.com.ged.diff;

import br.com.ged.exception.BusinessException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class PdfTextExtractor implements TextExtractor {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf");

    @Override
    public Set<String> extensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public List<String> extract(Path file) {
        try (PDDocument document = Loader.loadPDF(Files.readAllBytes(file))) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<String> lines = new ArrayList<>();
            for (String rawLine : stripper.getText(document).split("\r?\n")) {
                String line = rawLine.strip();
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
            return lines;
        } catch (IOException exception) {
            throw new BusinessException("Falha ao ler o documento PDF \"%s\".".formatted(file.getFileName()));
        }
    }
}
