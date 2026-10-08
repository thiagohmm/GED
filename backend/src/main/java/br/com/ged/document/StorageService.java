package br.com.ged.document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

@Service
public class StorageService {

    private static final String DIGEST_ALGORITHM = "SHA-256";

    private final Path baseDirectory;

    public StorageService(@Value("${ged.storage-dir}") String storageDir) {
        this.baseDirectory = Paths.get(storageDir).toAbsolutePath().normalize();
        createBaseDirectory();
    }

    public StoredFile store(Long documentId, int versionNumber, String documentName, MultipartFile file) {
        String relativePath = relativePathOf(documentId, versionNumber, documentName);
        Path target = resolveFor(relativePath);
        createParentDirectories(target);
        String sha256 = copyWithDigest(file, target);
        return new StoredFile(relativePath, sizeOf(target), sha256);
    }

    public StoredFile store(Long documentId, int versionNumber, String documentName, byte[] content) {
        String relativePath = relativePathOf(documentId, versionNumber, documentName);
        Path target = resolveFor(relativePath);
        createParentDirectories(target);
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            byte[] hash = digest.digest(content);
            Files.write(target, content);
            return new StoredFile(relativePath, content.length, HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Algoritmo de digest nao disponivel.", exception);
        } catch (IOException exception) {
            throw new UncheckedIOException("Falha ao gravar o arquivo.", exception);
        }
    }

    public Path resolveStored(String relativePath) {
        return resolveFor(relativePath);
    }

    public void deleteStoredFile(String relativePath) {
        try {
            Files.deleteIfExists(resolveFor(relativePath));
        } catch (IOException exception) {
            throw new UncheckedIOException("Falha ao remover arquivo.", exception);
        }
    }

    public void deleteDocumentDirectory(Long documentId) {
        Path documentDirectory = baseDirectory.resolve(String.valueOf(documentId));
        if (!Files.exists(documentDirectory)) {
            return;
        }
        try (Stream<Path> contents = Files.walk(documentDirectory)) {
            contents.sorted(StorageService::deepestFirst).forEach(StorageService::delete);
        } catch (IOException exception) {
            throw new UncheckedIOException("Falha ao remover arquivos do documento.", exception);
        }
    }

    private String copyWithDigest(MultipartFile file, Path target) {
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            try (InputStream input = file.getInputStream();
                 DigestInputStream digestStream = new DigestInputStream(input, digest)) {
                Files.copy(digestStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Algoritmo de digest nao disponivel.", exception);
        } catch (IOException exception) {
            throw new UncheckedIOException("Falha ao gravar o arquivo.", exception);
        }
    }

    private String relativePathOf(Long documentId, int versionNumber, String documentName) {
        return String.join("/",
                String.valueOf(documentId),
                "v" + versionNumber + "-" + documentName);
    }

    private Path resolveFor(String relativePath) {
        Path relative = Paths.get(relativePath).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new IllegalArgumentException("Caminho de arquivo invalido.");
        }
        Path resolved = baseDirectory.resolve(relative).normalize();
        if (!resolved.startsWith(baseDirectory)) {
            throw new IllegalArgumentException("Caminho de arquivo invalido.");
        }
        return resolved;
    }

    private void createBaseDirectory() {
        try {
            Files.createDirectories(baseDirectory);
        } catch (IOException exception) {
            throw new UncheckedIOException("Falha ao criar diretorio de armazenamento.", exception);
        }
    }

    private void createParentDirectories(Path target) {
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException exception) {
            throw new UncheckedIOException("Falha ao criar diretorio do documento.", exception);
        }
    }

    private long sizeOf(Path target) {
        try {
            return Files.size(target);
        } catch (IOException exception) {
            throw new UncheckedIOException("Falha ao medir o tamanho do arquivo.", exception);
        }
    }

    private static int deepestFirst(Path first, Path second) {
        return Integer.compare(second.getNameCount(), first.getNameCount());
    }

    private static void delete(Path path) {
        try {
            Files.delete(path);
        } catch (IOException exception) {
            throw new UncheckedIOException("Falha ao remover arquivo.", exception);
        }
    }
}
