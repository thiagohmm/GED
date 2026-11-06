package br.com.ged.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Sha256Utils {

    private Sha256Utils() {
    }

    public static String sha256Hex(Path file) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponivel.", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Falha ao calcular o hash do arquivo " + file, exception);
        }
    }
}
