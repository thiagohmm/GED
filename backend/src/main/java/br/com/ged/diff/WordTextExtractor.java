package br.com.ged.diff;

import br.com.ged.exception.BusinessException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.util.RecordFormatException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

@Component
public class WordTextExtractor implements TextExtractor {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("docx");
    private static final int MAX_PARAGRAPHS = 100_000;

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
             XWPFDocument document = new XWPFDocument(stream)) {
            return document.getParagraphs().stream()
                    .limit(MAX_PARAGRAPHS)
                    .map(XWPFParagraph::getText)
                    .filter(WordTextExtractor::containsContent)
                    .map(String::strip)
                    .toList();
        } catch (IOException | NotOfficeXmlFileException | POIXMLException | RecordFormatException exception) {
            throw new BusinessException("Falha ao ler o documento Word \"%s\".".formatted(file.getFileName()));
        }
    }

    private static boolean containsContent(String text) {
        return text != null && !text.isBlank();
    }
}
