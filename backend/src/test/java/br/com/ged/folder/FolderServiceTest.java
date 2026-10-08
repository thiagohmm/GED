package br.com.ged.folder;

import br.com.ged.document.Document;
import br.com.ged.document.DocumentKind;
import br.com.ged.document.DocumentVersion;
import br.com.ged.exception.BusinessException;
import br.com.ged.exception.ConflictException;
import br.com.ged.exception.NotFoundException;
import br.com.ged.search.SearchGateway;
import br.com.ged.user.RoleChecker;
import br.com.ged.project.ProjectRepository;
import br.com.ged.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FolderServiceTest {

    @Mock
    private FolderRepository folderRepository;

    @Mock
    private br.com.ged.document.DocumentRepository documentRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleChecker roleChecker;

    @Mock
    private SearchGateway searchGateway;

    private FolderService folderService;

    @BeforeEach
    void setUp() {
        folderService = new FolderService(folderRepository, documentRepository,
                projectRepository, userRepository, roleChecker, searchGateway);
    }

    @Test
    void moverSubpastaParaOutraPastaAtualizaPaiEReindexaCaminhos() {
        Folder origem = new Folder("Contratos", null, "admin");
        origem.setId(1L);
        Folder destino = new Folder("Processos", null, "admin");
        destino.setId(2L);
        Folder subpasta = new Folder("Joao Manuel", origem, "admin");
        subpasta.setId(3L);
        Document documento = new Document(subpasta, "pedido.docx", DocumentKind.WORD, "admin");
        DocumentVersion versao = new DocumentVersion(documento, 1, "3/v1-arquivo", 10, "abc", null, "admin");
        documento.getVersions().add(versao);

        when(folderRepository.findById(3L)).thenReturn(Optional.of(subpasta));
        when(folderRepository.findById(2L)).thenReturn(Optional.of(destino));
        when(folderRepository.findByParentAndNameIgnoreCase(destino, "Joao Manuel")).thenReturn(Optional.empty());
        when(folderRepository.findByParentId(3L)).thenReturn(List.of());
        when(documentRepository.findByFolderIdSortedByName(3L)).thenReturn(List.of(documento));

        FolderNode moved = folderService.moveFolder(3L, 2L);

        assertEquals(2L, moved.parentId());
        assertEquals(destino, subpasta.getParent());
        verify(searchGateway, atLeastOnce()).indexVersion(eq(versao), eq("Processos / Joao Manuel"));
    }

    @Test
    void moverPastaParaDentroDaPropriaSubarvoreEhBloqueado() {
        Folder origem = new Folder("Contratos", null, "admin");
        origem.setId(1L);
        Folder subpasta = new Folder("Joao Manuel", origem, "admin");
        subpasta.setId(3L);

        when(folderRepository.findById(1L)).thenReturn(Optional.of(origem));
        when(folderRepository.findById(3L)).thenReturn(Optional.of(subpasta));

        assertThrows(BusinessException.class, () -> folderService.moveFolder(1L, 3L));
    }

    @Test
    void moverPastaParaElaMesmaEhBloqueado() {
        Folder pasta = new Folder("Contratos", null, "admin");
        pasta.setId(1L);
        when(folderRepository.findById(1L)).thenReturn(Optional.of(pasta));

        assertThrows(BusinessException.class, () -> folderService.moveFolder(1L, 1L));
    }

    @Test
    void moverPastaComNomeDuplicadoNoDestinoRejeita() {
        Folder origem = new Folder("Contratos", null, "admin");
        origem.setId(1L);
        Folder destino = new Folder("Processos", null, "admin");
        destino.setId(2L);
        Folder conflito = new Folder("Joao Manuel", destino, "admin");
        conflito.setId(4L);
        Folder movida = new Folder("Joao Manuel", origem, "admin");
        movida.setId(3L);

        when(folderRepository.findById(3L)).thenReturn(Optional.of(movida));
        when(folderRepository.findById(2L)).thenReturn(Optional.of(destino));
        when(folderRepository.findByParentAndNameIgnoreCase(destino, "Joao Manuel"))
                .thenReturn(Optional.of(conflito));

        assertThrows(ConflictException.class, () -> folderService.moveFolder(3L, 2L));
    }

    @Test
    void moverPastaInexistenteRejeita() {
        when(folderRepository.findById(9L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> folderService.moveFolder(9L, 1L));
    }

    @Test
    void renomearPastaTambemReindexaCaminhosDaSubarvore() {
        Folder pasta = new Folder("Contratos", null, "admin");
        pasta.setId(1L);
        Document documento = new Document(pasta, "pedido.docx", DocumentKind.WORD, "admin");
        DocumentVersion versao = new DocumentVersion(documento, 1, "1/v1-arquivo", 10, "abc", null, "admin");
        documento.getVersions().add(versao);

        when(folderRepository.findById(1L)).thenReturn(Optional.of(pasta));
        when(folderRepository.findByParentIsNullAndNameIgnoreCase("Novo nome da raiz")).thenReturn(Optional.empty());
        when(folderRepository.findByParentId(1L)).thenReturn(List.of());
        when(documentRepository.findByFolderIdSortedByName(1L)).thenReturn(List.of(documento));

        folderService.rename(1L, new RenameFolderRequest("Novo nome da raiz"));

        assertEquals("Novo nome da raiz", pasta.getName());
        verify(searchGateway, atLeastOnce()).indexVersion(any(), anyString());
        verify(searchGateway, atLeastOnce()).indexVersion(eq(versao), eq("Novo nome da raiz"));
    }
}
