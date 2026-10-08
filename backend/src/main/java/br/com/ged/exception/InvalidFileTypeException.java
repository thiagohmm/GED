package br.com.ged.exception;

public class InvalidFileTypeException extends BusinessException {

    private static final String DEFAULT_MESSAGE = """
            Tipo de arquivo nao suportado. Formatos aceitos: docx, xlsx, pdf, png, jpg, jpeg.""";

    public InvalidFileTypeException(String fileName) {
        super(fileName == null || fileName.isBlank() ? DEFAULT_MESSAGE : "\"%s\" nao e um arquivo suportado.\n%s".formatted(fileName, DEFAULT_MESSAGE));
    }
}
