package br.com.ged;

import br.com.ged.audit.AuditController;
import br.com.ged.audit.AuditEvent;
import br.com.ged.audit.AuditEventService;
import br.com.ged.backup.BackupProperties;
import br.com.ged.backup.BackupService;
import br.com.ged.backup.Sha256Utils;
import br.com.ged.config.AuthenticatedUser;
import br.com.ged.config.JwtAuthenticationFilter;
import br.com.ged.config.JwtService;
import br.com.ged.config.ProductionGuard;
import br.com.ged.document.DocumentRepository;
import br.com.ged.document.DocumentVersionRepository;
import br.com.ged.document.StorageService;
import br.com.ged.exception.BusinessException;
import br.com.ged.integrity.IntegrityService;
import br.com.ged.office.Office;
import br.com.ged.office.OfficeRepository;
import br.com.ged.project.ProjectRepository;
import br.com.ged.search.ReindexService;
import br.com.ged.tenant.TenantScope;
import br.com.ged.user.ChangePasswordRequest;
import br.com.ged.user.CreateUserRequest;
import br.com.ged.user.Role;
import br.com.ged.user.UpdateUserRequest;
import br.com.ged.user.User;
import br.com.ged.user.UserRepository;
import br.com.ged.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityRegressionTest {

    private static final String SESSION_SECRET = "segredo-forte-sessao-0123456789-abcdef";
    private static final String EDITOR_SECRET = "segredo-forte-editor-9876543210-fedcba";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void productionGuardBloqueiaSubidaSemProfileOuComSegredosFracosOuIguais() {
        ProductionGuard guardSemProfile = new ProductionGuard();
        guardSemProfile.setEnvironment(new MockEnvironment());
        ReflectionTestUtils.setField(guardSemProfile, "secret", ProductionGuard.DEV_DEFAULT_SECRET);
        ReflectionTestUtils.setField(guardSemProfile, "editorSecret", ProductionGuard.DEV_DEFAULT_EDITOR_SECRET);
        assertThrows(IllegalStateException.class, guardSemProfile::afterPropertiesSet);

        ProductionGuard guardSegredosIguais = new ProductionGuard();
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        guardSegredosIguais.setEnvironment(prodEnv);
        ReflectionTestUtils.setField(guardSegredosIguais, "secret", SESSION_SECRET);
        ReflectionTestUtils.setField(guardSegredosIguais, "editorSecret", SESSION_SECRET);
        assertThrows(IllegalStateException.class, guardSegredosIguais::afterPropertiesSet);

        ProductionGuard guardProdValido = new ProductionGuard();
        guardProdValido.setEnvironment(prodEnv);
        ReflectionTestUtils.setField(guardProdValido, "secret", SESSION_SECRET);
        ReflectionTestUtils.setField(guardProdValido, "editorSecret", EDITOR_SECRET);
        assertDoesNotThrow(guardProdValido::afterPropertiesSet);

        ProductionGuard guardDevExplicito = new ProductionGuard();
        MockEnvironment devEnv = new MockEnvironment();
        devEnv.setActiveProfiles("dev");
        guardDevExplicito.setEnvironment(devEnv);
        ReflectionTestUtils.setField(guardDevExplicito, "secret", ProductionGuard.DEV_DEFAULT_SECRET);
        ReflectionTestUtils.setField(guardDevExplicito, "editorSecret", ProductionGuard.DEV_DEFAULT_EDITOR_SECRET);
        assertDoesNotThrow(guardDevExplicito::afterPropertiesSet);
    }

    @Test
    void tenantScopeBloqueiaContextoAnonimoEUsuarioSemEscritorio() {
        TenantScope tenantScope = new TenantScope(mock(ProjectRepository.class));

        assertFalse(tenantScope.canAccessOffice(1L));
        assertThrows(AccessDeniedException.class, tenantScope::currentOffice);

        authenticate(new AuthenticatedUser(10L, "orfao", Role.ADVOGADO, null));
        assertFalse(tenantScope.canAccessOffice(1L));
        assertThrows(AccessDeniedException.class, tenantScope::currentOffice);

        authenticate(new AuthenticatedUser(1L, "root", Role.SUPER_ADMIN, null));
        assertTrue(tenantScope.canAccessOffice(1L));
        assertTrue(tenantScope.currentOffice().isEmpty());
    }

    @Test
    void adminNaoCriaNemPromoveOutroAdminETrocaDeSenhaIncrementaTokenVersion() {
        UserRepository userRepository = mock(UserRepository.class);
        OfficeRepository officeRepository = mock(OfficeRepository.class);
        AuditEventService auditEventService = mock(AuditEventService.class);
        TenantScope tenantScope = new TenantScope(mock(ProjectRepository.class));
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        UserService userService = new UserService(userRepository, encoder, tenantScope, auditEventService, officeRepository);

        Office office = new Office("Escritorio 1");
        office.setId(5L);
        when(officeRepository.findById(5L)).thenReturn(Optional.of(office));

        authenticate(new AuthenticatedUser(2L, "admin1", Role.ADMIN, 5L));

        assertThrows(AccessDeniedException.class,
                () -> userService.create(new CreateUserRequest("admin2", "senha123", Role.ADMIN, 5L)));

        User advogado = new User("adv1", encoder.encode("antiga123"), Role.ADVOGADO, true, 5L);
        advogado.setId(9L);
        advogado.setTokenVersion(0);
        when(userRepository.findById(9L)).thenReturn(Optional.of(advogado));
        when(userRepository.findByUsernameIgnoreCase("adv1")).thenReturn(Optional.of(advogado));

        assertThrows(AccessDeniedException.class,
                () -> userService.update(9L, new UpdateUserRequest(null, Role.ADMIN, null)));
        assertThrows(BusinessException.class,
                () -> userService.update(9L, new UpdateUserRequest(null, Role.SUPER_ADMIN, null)));

        userService.changeOwnPassword("adv1", new ChangePasswordRequest("antiga123", "novaSenha123"));
        assertEquals(1, advogado.getTokenVersion());
    }

    @Test
    void filtroJwtRejeitaTokenAntigoAposTrocaDeSenhaEscritorioDesativadoETokenDoEditor() throws Exception {
        JwtService jwtService = new JwtService(SESSION_SECRET, EDITOR_SECRET, 720);
        UserRepository userRepository = mock(UserRepository.class);
        OfficeRepository officeRepository = mock(OfficeRepository.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, userRepository, officeRepository);

        Office office = new Office("Escritorio Ativo");
        office.setId(3L);
        when(officeRepository.findById(3L)).thenReturn(Optional.of(office));

        User user = new User("maria", "hash", Role.ADVOGADO, true, 3L);
        user.setId(15L);
        user.setTokenVersion(1);
        when(userRepository.findByUsernameIgnoreCase("maria")).thenReturn(Optional.of(user));

        String validToken = jwtService.issueToken(user);

        // 1. Token valido autentica normalmente
        MockHttpServletRequest reqOk = new MockHttpServletRequest();
        reqOk.addHeader("Authorization", "Bearer " + validToken);
        filter.doFilter(reqOk, new MockHttpServletResponse(), new MockFilterChain());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        SecurityContextHolder.clearContext();

        // 2. Apos incremento de tokenVersion (troca de senha), o mesmo JWT eh rejeitado
        user.setTokenVersion(2);
        MockHttpServletRequest reqOldPwd = new MockHttpServletRequest();
        reqOldPwd.addHeader("Authorization", "Bearer " + validToken);
        filter.doFilter(reqOldPwd, new MockHttpServletResponse(), new MockFilterChain());
        assertNull(SecurityContextHolder.getContext().getAuthentication());

        // 3. Escritorio desativado rejeita ate token novo
        String newToken = jwtService.issueToken(user);
        office.setEnabled(false);
        MockHttpServletRequest reqDisabledOffice = new MockHttpServletRequest();
        reqDisabledOffice.addHeader("Authorization", "Bearer " + newToken);
        filter.doFilter(reqDisabledOffice, new MockHttpServletResponse(), new MockFilterChain());
        assertNull(SecurityContextHolder.getContext().getAuthentication());

        // 4. Token de editor nunca autentica no filtro de sessao
        office.setEnabled(true);
        String editorToken = jwtService.signEditor(
                Map.of(JwtService.TYPE_CLAIM, JwtService.TYPE_EDITOR_FILE, "sub", "maria", "versionId", 1L),
                Instant.now().plusSeconds(600));
        MockHttpServletRequest reqEditor = new MockHttpServletRequest();
        reqEditor.addHeader("Authorization", "Bearer " + editorToken);
        filter.doFilter(reqEditor, new MockHttpServletResponse(), new MockFilterChain());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void verifyBackupRejeitaPathTraversalEmSha256SumsEAusenciaDeArtefatos(@TempDir Path tempDir) throws Exception {
        BackupProperties props = new BackupProperties();
        props.setDir(tempDir.toString());
        BackupService backupService = new BackupService(
                props,
                new StorageService(tempDir.resolve("storage").toString()),
                mock(UserRepository.class),
                mock(OfficeRepository.class),
                mock(DocumentRepository.class),
                mock(DocumentVersionRepository.class),
                mock(AuditEventService.class),
                mock(IntegrityService.class),
                mock(ReindexService.class),
                new ObjectMapper(),
                null);

        Path legit = Files.createDirectories(tempDir.resolve("ged-20260101T000000"));
        Files.writeString(legit.resolve("database.dump"), "dump", StandardCharsets.UTF_8);
        Files.writeString(legit.resolve("storage.tar.gz"), "tar", StandardCharsets.UTF_8);
        Files.writeString(legit.resolve("manifest.json"), "{}", StandardCharsets.UTF_8);

        Path tampered = Files.createDirectories(tempDir.resolve("ged-20260102T000000"));
        String traversalSums = Sha256Utils.sha256Hex(legit.resolve("database.dump"))
                + "  ../ged-20260101T000000/database.dump\n";
        Files.writeString(tampered.resolve("SHA256SUMS"), traversalSums, StandardCharsets.UTF_8);

        List<String> failures = backupService.verify("ged-20260102T000000");
        assertFalse(failures.isEmpty());
        assertTrue(failures.stream().anyMatch(msg -> msg.contains("nao permitido")));
    }

    @Test
    void paginacaoDeAuditoriaRespeitaOffsetArbitrario() {
        AuditEventService auditEventService = mock(AuditEventService.class);
        when(auditEventService.list(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.<AuditEvent>of()));
        AuditController controller = new AuditController(auditEventService);

        controller.list(null, null, null, null, null, null, 25L, 50);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(auditEventService).list(any(), any(), any(), any(), any(), captor.capture());
        assertEquals(25L, captor.getValue().getOffset());
        assertEquals(50, captor.getValue().getPageSize());
    }

    private void authenticate(AuthenticatedUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        user,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()))));
    }
}
