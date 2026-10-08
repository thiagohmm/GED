package br.com.ged.diff;

import br.com.ged.exception.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @TempDir
    Path tempDir;

    @Test
    void extraiLinhasDeTextoDoPdf() throws IOException {
        Path pdf = tempDir.resolve("acordao.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(25, 500);
                content.showText("Peticao inicial da acao");
                content.endText();
            }
            document.save(pdf.toFile());
        }

        List<String> linhas = extractor.extract(pdf);

        assertEquals(1, linhas.size());
        assertTrue(linhas.get(0).contains("Peticao inicial da acao"));
    }

    @Test
    void lancaErroDeNegocioQuandoArquivoEhInvalido() throws IOException {
        Path pdf = tempDir.resolve("quebrado.pdf");
        Files.write(pdf, new byte[] {45, 46, 47});

        assertThrows(BusinessException.class, () -> extractor.extract(pdf));
    }

    @Test
    void suportaSomenteAExtensaoPdf() {
        assertEquals(Set.of("pdf"), extractor.extensions());
    }
}
