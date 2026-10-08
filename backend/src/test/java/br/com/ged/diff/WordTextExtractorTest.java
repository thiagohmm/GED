package br.com.ged.diff;

import br.com.ged.exception.BusinessException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordTextExtractorTest {

    private final WordTextExtractor extractor = new WordTextExtractor();

    @TempDir
    Path tempDir;

    @Test
    void extraiParagrafosIgnorandoLinhasVazias() throws IOException {
        Path docx = tempDir.resolve("contrato.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Primeiro paragrafo");
            document.createParagraph();
            document.createParagraph().createRun().setText("Segundo paragrafo");
            try (OutputStream output = Files.newOutputStream(docx)) {
                document.write(output);
            }
        }

        assertEquals(List.of("Primeiro paragrafo", "Segundo paragrafo"), extractor.extract(docx));
    }

    @Test
    void lancaErroDeNegocioQuandoArquivoEhInvalido() throws IOException {
        Path invalido = tempDir.resolve("raiz.docx");
        Files.write(invalido, new byte[] {1, 2, 3, 4});

        BusinessException exception = assertThrows(BusinessException.class, () -> extractor.extract(invalido));
        assertTrue(exception.getMessage().contains("Word"));
    }

    @Test
    void suportaSomenteAExtensaoDocx() {
        assertEquals(Set.of("docx"), extractor.extensions());
    }
}
