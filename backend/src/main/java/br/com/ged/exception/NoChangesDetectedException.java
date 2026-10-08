package br.com.ged.exception;

public class NoChangesDetectedException extends BusinessException {

    public NoChangesDetectedException() {
        super("""
                O arquivo enviado tem o mesmo conteudo da versao atual do documento. \
                Nenhuma alteracao foi detectada, portanto a nova versao nao foi criada. \
                Edite o arquivo e tente novamente.""");
    }
}
