# Plano: GED — Flyway, credenciais de produção, backup testado, multiempresa e auditoria

## Contexto

O GED (Gestão Eletrônica de Documentos para advogados) está pronto para uso local, mas não está
preparado para produção/multiempresa:

- **Schema**: `spring.jpa.hibernate.ddl-auto: update` + runner ad hoc `DataMigration.java` (SQL cru
  com `try/catch` que engole erros). Não há migrações versionadas.
- **Credenciais**: defaults inseguros no caminho de produção — `admin123` como senha de seed,
  secret JWT fixo conhecido (mesma string no `application.yml` e no `docker-compose.yml`),
  `POSTGRES_PASSWORD: ged` hard-coded no compose.
- **Backup**: binários em disco local (`storage/`) e metadados no Postgres sem nenhum backup/restore.
- **Isolamento**: qualquer usuário logado vê/edita todas as pastas, documentos, revisões e projetos.
  Não há conceito de escritório/tenant.
- **Auditoria**: apenas `document_status_changes` (mudanças de status). Login, download, exclusão,
  gestão de usuários etc. não são auditados.
- **Autorização**: quase todo endpoint é aberto a qualquer usuário autenticado (sem `@PreAuthorize`
  nem escopo de dados); PROTOCOLADOR pode desativar/rebaixar ADMIN.

**Decisões confirmadas com o usuário:**
1. Multiempresa: **entidade `Office` (escritório)** — usuário pertence a um escritório, dados
   escopados por escritório, `SUPER_ADMIN` global opcional.
2. Flyway: **banco dev atual é descartável** — schema canônico novo, sem migração de dados
   legacy (bancos dev antigos são recriados, documentado).
3. Backup: **API admin + CLI + teste automatizado** de backup→corrupção→restore com verificação de
   integridade por SHA-256 (Postgres real via Testcontainers).
4. Auditoria: **trilha completa** em `audit_events` (login, upload, download, exclusão, move,
   status, revisões, usuários, escritórios, backup/restore, acessos negados).

## Abordagem

### WS1 — Flyway (substitui `ddl-auto: update` e `DataMigration`)

- `pom.xml`: adicionar `org.springframework.boot:spring-boot-starter-flyway` (versão gerida pelo
  BOM 4.0.8) + `org.flywaydb:flyway-database-postgresql` (runtime, sem versão explícita para
  seguir o BOM — Flyway 10+ exige o módulo por banco).
- `application.yml`: `spring.flyway.enabled: true`; `spring.jpa.hibernate.ddl-auto: validate`
  (o Hibernate passa a **validar** entidades × schema em todo boot; drift quebra o start).
- `backend/src/main/resources/db/migration/V1__init.sql`: schema **canônico completo** espelhando
  as entidades (atualizadas pelo WS2/WS4), incluindo novas tabelas `offices` e `audit_events`:
  - `offices(id BIGINT IDENTITY PK, name VARCHAR(120) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT true, created_at TIMESTAMPTZ NOT NULL)` + `UNIQUE(name)`;
  - `users(…, office_id BIGINT NOT NULL REFERENCES offices(id))` — username continua unique global;
  - `projects(…, office_id BIGINT NOT NULL REFERENCES offices(id))` — unique vira `(office_id, name)`;
  - `folders`, `documents` (uk `folder_id+name`), `document_versions` (uk `document_id+version_number`),
    `document_status_changes`, `document_tags` (pk `document_id+tag_order`), `reviews` (uk `document_id+round`),
    `review_comments` — idênticos ao schema atual (verificar coluna a coluna contra as anotações
    JPA com `ddl-auto: validate` no boot dev);
  - `audit_events(id BIGINT IDENTITY PK, occurred_at TIMESTAMPTZ NOT NULL, actor_username VARCHAR(60), actor_role VARCHAR(60), action VARCHAR(60) NOT NULL, resource_type VARCHAR(40), resource_id VARCHAR(60), detail JSONB, ip VARCHAR(64))` + índices em `(occurred_at DESC)` e `(resource_type, resource_id)`.
- **Remover** `br/com/ged/config/DataMigration.java` (bancos legacy descartados; o runner de dados
  do README é substituído pelas migrações).
- Risco conhecido: relatos de Flyway não executar com Boot 4 em algumas versões. Mitigação: no
  primeiro boot conferir `flyway_schema_history` e logs; se a autoconfiguração falhar, declarar
  bean explícita de `FlywayMigrationCommand`/`Flyway` em `FlywayConfig`.
- Instrução de recriação dev: `docker compose down -v && docker compose up -d` (documentar no
  README — o banco antigo, criado por `ddl-auto: update`, não é compatível com o schema novo).

### WS2 — Multiempresa: entidade Office

- `Role`: adicionar `SUPER_ADMIN`. Perfis:
  - `SUPER_ADMIN` (global): cria/gere Escritórios, todos os usuários e dados, backup/restore,
    auditoria, integridade. Sempertence a um escritório (escopo ilimitado).
  - `ADMIN` (do escritório): tudo dentro do **próprio** escritório (usuários do escritório,
    projetos, pastas raiz, backup? **não** — backup/restore e auditoria são de SUPER_ADMIN apenas).
  - `PROTOCOLADOR`/`REVISOR`/`ADVOGADO`: inalterados, agora escopados.
- `User` ganha `officeId` (Long, nullable apenas para SUPER_ADMIN). `Project` ganha `officeId`.
- `Office` entity + `OfficeRepository` + `OfficeController`/`OfficeService`:
  - `GET /api/offices` (SUPER_ADMIN: todos; ADMIN: o próprio), `POST /api/offices` (SUPER_ADMIN),
    `PATCH /api/offices/{id}` (SUPER_ADMIN: nome/enabled).
  - `POST /api/offices/{id}/admins {username, password}` (SUPER_ADMIN): cria o ADMIN inicial do
    escritório.
- **Escopo de dados** — novo `br/com/ged/tenant/TenantScope` (service):
  - resolve `officeId` do principal (SUPER_ADMIN → `Optional.empty()` = tudo);
  - `requireAccessToFolder(Folder)`: sobe até a pasta raiz (pattern já usado em
    `FolderService.toNode`/`SearchFilterService.assignedAttorneyId`), pega `project.officeId`,
    compara com o do usuário; violação → `AccessDeniedException` (403) + evento de auditoria
    `ACCESS_DENIED`;
  - `requireAccessToDocument(Document)` → via pasta; `requireAccessToReview(Review)` → via documento.
- Aplicar o escopo em: `FolderService` (`tree()` filtra escritórios; create/rename/move/delete/
  assignRoot), `DocumentService` (upload/list/detail/versions/diff/download/delete/move/tags),
  `WorkflowService.requestStatusChange`, `ReviewService` (queue, detail, addComment, decide,
  reviewsOfDocument), `ProjectService` (list/create), `UserService` (listAll/create/update),
  `SearchController`/`SearchFilterService` (ver abaixo), `AccountController` (apenas próprio
  usuário).
- **Busca**: índice ES **global** permanece (`ged_versions`); o escopo é aplicado em
  `SearchFilterService` (que já carrega os `Document` dos hits via `findAllById` e sobe até a raiz
  para o filtro de advogado) — descartar hits de outro escritório, e validar que os usuários dos
  filtros `attorneyId`/`reviewerId` pertencem ao escritório do chamador. `SearchFilterOptions`
  (listas de advogados/revisores) também fica escopado.
- **Usuários**: `CreateUserRequest`/`UpdateUserRequest` sem escolha de escritório — o escritório
  do gestor é o do usuário criado (SUPER_ADMIN: campo `officeId` obrigatório na criação; ao
  alterar usuário, bloqueia trocar escritório). `UserResponse` expõe `officeId`.
- **Autenticação**: `JwtService.issueToken` inclui `officeId`; `JwtAuthenticationFilter` passa a
  carregar o `User` do banco por username (PK lookup) em vez de confiar só no claim de papel —
  desativação/alteração de papel/escritório valem imediatamente. Principal =
  `AuthenticatedUser(userId, username, role, officeId)` em `SecurityContextHolder`;
  `@AuthenticationPrincipal` dos controllers passa a receber esse objeto (ajustar assinaturas).
- **Editor (OnlyOffice)**: claims `officeId` no token de arquivo e no token de sessão;
  `editorFile`/`callback` validam escritório (o callback hoje é `permitAll` — passa a validar o
  escopo via claim).
- **Seed**: dev → cria `Office("Escritório Dev")` + ADMIN `admin/admin123` (apenas quando o banco
  está vazio, como hoje). Prod → cria apenas o SUPER_ADMIN (ver WS3).
- **Frontend**: o escopo vem do backend (`/folders/tree` já só retorna o que o usuário vê), então
  a árvore/explorador funcionam sem mudanças. Ajustes pequenos: page de usuários mostra escritório;
  nova page "Escritórios" para SUPER_ADMIN (listar, criar, ADMIN inicial, desativar); badge de
  papel SUPER_ADMIN no nav.

### WS3 — Credenciais e padrões de produção

- **Perfis**: `application.yml` (base, sem segredos) + `application-dev.yml`
  (default; mantém secret dev, seed `admin/admin123` no escritório Dev) + `application-prod.yml`
  (sem defaults para secret/senhas — a ausência de default já exige env var).
- **Fail-fast** — novo `ProductionGuard` (`SmartInitializingSingleton` ou `ApplicationRunner`
  @Order(0)) ativo no perfil `prod`:
  - `GED_JWT_SECRET`: obrigatório, ≥ 32 caracteres, rejeitar o valor dev conhecido
    (`troque-este-segredo…`) → falha o boot com mensagem clara;
  - seed: se não existir SUPER_ADMIN, exigir `GED_SUPER_ADMIN_USERNAME` e
    `GED_SUPER_ADMIN_PASSWORD` (≥ 12 chars); cria só o super admin (sem escritório);
  - seed dev no perfil prod **desativado** (não criar `admin/admin123`).
- `JwtService`: validação de tamanho do secret em **todos** os perfis (HMAC-SHA256 exige ≥ 32
  bytes — hoje um secret curto nem sobe).
- **docker-compose.yml**:
  - `POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD obrigatorio no .env}`;
  - OnlyOffice `JWT_SECRET: ${GED_JWT_SECRET:?GED_JWT_SECRET obrigatorio no .env}` (sem default fixo);
  - `.env.example`: já tem `change-me` — adicionar `GED_SUPER_ADMIN_USERNAME/PASSWORD` (comentado,
    para prod) e nota de que o `.env` é obrigatório para `docker compose up`.
- **README**: seção "Produção" — `--spring.profiles.active=prod`, env vars obrigatórias, como
  gerar o secret (`openssl rand -hex 32`).

### WS4 — Autorização, integridade de arquivos e auditoria

- **Autorização explícita** (hoje só 3 endpoints têm `@PreAuthorize`):
  - `DocumentController`: download/preview/versions/diff = qualquer autenticado **do escritório**
    (escopo via service); delete = ADMIN/SUPER_ADMIN do escritório; move/tags = mantêm regra
    atual + escopo;
  - `FolderController`: create/rename/move/assignRoot = ADMIN/PROTOCOLADOR/SUPER_ADMIN do
    escritório; delete = ADMIN/SUPER_ADMIN do escritório;
  - `ProjectController`: list = escopado; create = ADMIN/PROTOCOLADOR/SUPER_ADMIN do próprio
    escritório (SUPER_ADMIN: `officeId` no corpo);
  - `UserController`: list/patch = ADMIN/PROTOCOLADOR/SUPER_ADMIN **do escritório** (SUPER_ADMIN
    todos); proteções: só ADMIN/SUPER_ADMIN do escritório pode alterar outro
    ADMIN; **nunca** é possível alterar/desativar um SUPER_ADMIN nem a si mesmo o papel
    (bloquear self-demote/self-disable);
  - `AccountController` (mudar própria senha) = qualquer autenticado;
  - `BackupController`/`IntegrityController`/`AuditController`/`OfficeController` = SUPER_ADMIN
    (GET auditoria também para ADMIN do próprio escritório? — decisão: **SUPER_ADMIN**, mantendo
    trilha governada por quem tem visão total; ADMIN do escritório não vê trilha).
- **Integridade de arquivos** — novo `IntegrityService`:
  - `verifyStorage()`: para cada `document_versions` line, recomputa SHA-256 do arquivo em
    `storage/` (reaproveitando a digest do `StorageService`) e compara com `sha256` da coluna;
    retorna `IntegrityReport(total, ok, missing[], mismatch[])`;
  - `GET /api/admin/integrity` (SUPER_ADMIN) + auditoria `INTEGRITY_CHECK`; usado também no pós
    -restore do WS5.
- **Auditoria** — nova entidade `AuditEvent` (schema acima) + `AuditEventRepository` +
  `AuditEventService`:
  - `record(action, resourceType, resourceId, detail)` com `@Transactional(REQUIRES_NEW)`;
    actor e `occurred_at` do `SecurityContext`; `ip` do request (handler de exception de
    `AccessDeniedException` grava `ACCESS_DENIED` com o caminho);
  - Ações: `LOGIN_SUCCESS`, `LOGIN_FAILURE`, `UPLOAD`, `DOWNLOAD`, `PREVIEW`, `DELETE_DOCUMENT`,
    `CREATE_FOLDER`, `RENAME_FOLDER`, `MOVE_FOLDER`, `DELETE_FOLDER`, `CREATE_PROJECT`,
    `STATUS_CHANGE`, `REVIEW_OPEN`, `REVIEW_COMMENT`, `REVIEW_DECIDE`, `USER_CREATE`, `USER_UPDATE`,
    `PASSWORD_CHANGE`, `OFFICE_CREATE`, `OFFICE_UPDATE`, `BACKUP_CREATE`, `BACKUP_DELETE`,
    `RESTORE`, `INTEGRITY_CHECK`, `ACCESS_DENIED`;
  - hooks: `AuthController` (login ok/falha), `DocumentController` (upload/download/preview/
    delete/move), `DocumentService.saveEditedVersion` (editor), `WorkflowService` (status),
    `ReviewService` (open/comment/decide), `FolderController`, `ProjectController`,
    `UserController`, `AccountController`, `OfficeController`, `BackupController`,
    `GlobalExceptionHandler` (403);
  - `GET /api/admin/audit?from&to&action&actor&resourceType` (SUPER_ADMIN, paginação
    `page/size`, ordem `occurred_at DESC`) — leitura simples, sem mutação da trilha.

### WS5 — Backup e restauração (API + CLI + teste)

- Novo pacote `br/com/ged/backup`:
  - `BackupProperties`: `ged.backup.dir` (default `backups/`), `ged.backup.pg-bin-dir`
    (default PATH; prod aponta para o binário do Postgres) e
    `ged.backup.maintenance-db` (default `postgres`, para drop/create do DB de dados);
  - `BackupService`:
    - `create()`: bundle `ged-<timestamp>/` no dir configurado com: `database.dump`
      (`pg_dump -Fc`, ProcessBuilder com `PGPASSWORD`/`PGHOST`/`PGPORT`), `storage.tar.gz`
      (`tar -czf` sobre `ged.storage-dir`), `manifest.json` (createdAt, contagens de
      users/offices/documents/versions, lista `files[{relPath, sha256}]` lida do banco) e
      `SHA256SUMS` (hashes dos artefatos); auditoria `BACKUP_CREATE`;
    - `restore(bundleFile)`: 1) valida `SHA256SUMS`; 2) dropa/recria o banco via manutenção DB
      (JDBC: `pg_terminate_backend` + `DROP DATABASE … WITH (FORCE)` + `CREATE DATABASE`) e
      `pg_restore`; 3) esvazia e extrai `storage.tar.gz`; 4) `IntegrityService.verifyStorage()`
      (falha = restore abortado com mensagem); 5) reindex ES via `ReindexService` (novelo serviço
      extraído do `SearchIndexCoordinator`: `ensureIndex + indexAll`, reaproveitado no startup,
      no restore e no admin `POST /api/admin/reindex`); 6) auditoria `RESTORE`;
    - `list()` / `delete(bundle)`.
  - `BackupController` (`/api/admin/backups`, `POST /api/admin/restore`, `DELETE
    /api/admin/backups/{name}`, `GET /api/admin/backups`) — SUPER_ADMIN;
  - `CliRunner` (ApplicationRunner @Order(0), ativo quando `--ged.cli=backup|restore|verify`):
    executa a mesma lógica e `System.exit`; doc de uso:
    `mvn spring-boot:run -Dspring-boot.run.arguments="--spring.main.web-application-type=none,--ged.cli=backup"`.
- ES no restore: reconstruído do Postgres (texto extraído/diff vivem no banco) — não é preciso
  backupar o ES.
- `backups/` para `.gitignore`.

### WS6 — Testes

- **Infra**: `org.testcontainers:postgresql` (test) + `@SpringBootTest` com
  `@Testcontainers`; ES desligado nos testes (`GED_ES_ENABLED=false`, gateway no-op já existe —
  `NoopIndexingService`/`enabled=false`). Docker daemon necessário no ambiente de testes
  (disponível aqui; `docker` instalado, daemon a subir).
- **Novos testes de integração**:
  - `FlywaySchemaTest`: boot com banco Testcontainers aplica V1 e `ddl-auto: validate` passa com
    todas as entidades;
  - `AuthorizationMatrixTest`: papéis × endpoints (escritório A não acessa documento/projeto/
    revisão/usuário do escritório B; PROTOCOLADOR não desativa ADMIN nem a si mesmo; SUPER_ADMIN
    acessa tudo; 403 auditado);
  - `BackupRestoreIT`: cria escritório+usuários+pasta+upload real (arquivo em `storage/`
    temporário) → backup → corrompe (apaga banco/storage) → restore → asserta contagens,
    nomes e **SHA-256 de todos os binários** e login do admin restaurado;
  - `IntegrityServiceTest`: arquivo alterado em disco → mismatch detectado;
  - `AuditTest`: login falho, download, delete e 403 geram eventos; endpoint retorna a trilha;
  - `TenantScopeTest`: escopo de folder/document/review/busca;
  - `ProductionGuardTest`: boot prod sem secret → falha com mensagem; seed super admin exige env.
- Manter os unitários existentes (diff, extratores, upload, workflow, revisão, editor) ajustando
  assinaturas que mudarem (principal, escopo).

### WS7 — Docs

- README: seção Produção (env vars obrigatórias, profiles), Multiempresa (papéis × escritórios),
  Backup/restore (API + CLI + política recomendada de retenção/cron), Auditoria (o que é
  registrado), Migrações (Flyway; recriar banco dev antigo), testes (exigência de Docker).
- `.env.example` + `docker-compose.yml` conforme WS3/WS5.

## Arquivos a modificar (principais)

- `backend/pom.xml` — flyway, testcontainers
- `backend/src/main/resources/application.yml` (+ novos `application-dev.yml`, `application-prod.yml`)
- `backend/src/main/resources/db/migration/V1__init.sql` (novo)
- `backend/src/main/java/br/com/ged/config/` — remover `DataMigration`; `JwtService`,
  `JwtAuthenticationFilter`, `SecurityConfig`, `DataSeeder` (rewritten), novo `ProductionGuard`
- `backend/src/main/java/br/com/ged/user/` — `Role`, `User`, `UserResponse`, `UserService`,
  `UserController`, `CreateUserRequest`, `UpdateUserRequest`
- `backend/src/main/java/br/com/ged/office/` (novo: `Office`, `OfficeRepository`, `OfficeService`,
  `OfficeController`, requests/responses)
- `backend/src/main/java/br/com/ged/tenant/TenantScope.java` (novo)
- `backend/src/main/java/br/com/ged/audit/` (novo: `AuditEvent`, `AuditEventRepository`,
  `AuditEventService`, `AuditController`)
- `backend/src/main/java/br/com/ged/backup/` (novo: `BackupService`, `BackupController`,
  `BackupProperties`, `CliRunner`)
- `backend/src/main/java/br/com/ged/document/` — `DocumentService`, `DocumentController`,
  `DocumentEditorService` (claims de escritório), `DocumentVersion` (nada além de uso em
  integridade), `DocumentRepository` (+ consultas de contagem p/ manifest)
- `backend/src/main/java/br/com/ged/folder/FolderService.java` (+ `FolderController`)
- `backend/src/main/java/br/com/ged/project/Project*.java` (escopo + officeId)
- `backend/src/main/java/br/com/ged/review/ReviewService.java` (+ controller)
- `backend/src/main/java/br/com/ged/workflow/WorkflowService.java`
- `backend/src/main/java/br/com/ged/search/` — `SearchFilterService` (escopo),
  `SearchIndexCoordinator` → extrair `ReindexService`, `SearchController`
- `backend/src/main/java/br/com/ged/auth/AuthController.java` + `AuthService` (auditoria)
- `backend/src/main/java/br/com/ged/exception/GlobalExceptionHandler.java` (auditar 403)
- `backend/src/test/...` (novos testes de integração, WS6)
- `frontend/src/pages/` — `AdminUsersPage` (coluna escritório), nova `OfficesPage` (SUPER_ADMIN),
  nav/roteamento
- `docker-compose.yml`, `.env.example`, `.gitignore`, `README.md`

## Reuso (código existente)

- `StorageService.copyWithDigest`/SHA-256 → base da `IntegrityService` (mesmo algoritmo,
  `DigestInputStream`).
- `FolderService`/`SearchFilterService.assignedAttorneyId`: pattern de subir até a pasta raiz →
  `TenantScope.requireAccessToFolder`.
- `SearchIndexCoordinator` (reindex no startup) → `ReindexService` reutilizado no restore.
- `SearchFilterService.filter` (já carrega `Document` por id e aplica filtros em Java) → ponto de
  escopo da busca sem alterar o índice ES.
- `NoopIndexingService`/`ged.elasticsearch.enabled=false` → desliga ES nos testes de integração.
- `RoleChecker` → manter e estender; `GlobalExceptionHandler` já mapeia
  `AccessDeniedException` (validar status 403).
- `DataSeeder` (condição "banco vazio") → reaproveitado no seed prod/super admin.
- `SearchGateway.indexAll`/`indexVersion`/`deleteVersions` → reindex pós-restore.
- BCrypt `PasswordEncoder` (bean existente em `SecurityConfig`).

## Steps

- [x] 1. Pom: flyway starter + flyway-database-postgresql + testcontainers (test)
- [x] 2. Entidades alvo: `Role.SUPER_ADMIN`, `User.officeId`, `Project.officeId`, `Office`,
      `AuditEvent` (+ repos/responses)
- [x] 3. `V1__init.sql` canônico (todas as tabelas acima) + `ddl-auto: validate`; remover
      `DataMigration`; boot dev confirma `flyway_schema_history` e validate ok
- [x] 4. `TenantScope` + escopo em folder/document/review/project/user/search/account;
      principal carregado do banco no `JwtAuthenticationFilter`; claims `officeId` no JWT e nos
      tokens do editor
- [x] 5. `Office` CRUD + criação de ADMIN do escritório; seed dev (escritório Dev) e
      `ProductionGuard` (prod: secret/super admin obrigatórios, seed admin123 bloqueada)
- [x] 6. Perfis de config (`application-dev.yml`/`application-prod.yml`), compose com env
      obrigatórios, `.env.example`, `JwtService` com validação de secret
- [x] 7. Autorização: `@PreAuthorize` por controller + proteções (self-demote, ADMIN outro
      ADMIN, SUPER_ADMIN intocável)
- [x] 8. Auditoria: `AuditEventService` + hooks em auth/documents/folders/reviews/users/offices/
      backup + 403; endpoint `GET /api/admin/audit`
- [x] 9. `IntegrityService` + `GET /api/admin/integrity`
- [x] 10. Backup: `BackupService` (pg_dump/tar/manifest/SHA256SUMS), `ReindexService`, restore,
      `BackupController`, `CliRunner`; `backups/` no `.gitignore`
- [x] 11. Testes: `FlywaySchemaTest`, `AuthorizationMatrixTest`, `BackupRestoreIT`,
      `IntegrityServiceTest`, `AuditTest`, `TenantScopeTest`, `ProductionGuardTest`; ajustar
      unitários existentes
- [x] 12. Frontend: page Escritórios (SUPER_ADMIN), coluna escritório em usuários, nav
- [x] 13. README: produção, multiempresa, backup/restore, auditoria, migrações, testes

## Verificação

1. `docker compose down -v && docker compose up -d` (banco novo; `pg_isready`).
2. `JAVA_HOME=$HOME/jdk mvn -f backend/pom.xml test` — suíte completa (integração requer Docker).
3. Boot dev (`mvn spring-boot:run`): log de migração Flyway aplicada, `validate` ok, seed
   escritório Dev + `admin/admin123`; login no frontend (http://localhost:5173).
4. Multiempresa manual: login como super admin (rodar com env de super admin) → criar escritório
   B + admin; usuário do escritório A não vê documentos/pastas/projetos/revisões/busca de B
   (tentar também por ID direto → 403 auditado).
5. Backup manual: `POST /api/admin/backups` → apagar volume/DB e `storage/` → `POST
   /api/admin/restore` → confirmar report de integridade (hashes) e reindex da busca
   (`GET /api/search?q=…`).
6. CLI: `--ged.cli=backup` e `verify` fora do modo web.
7. Produção: `SPRING_PROFILES_ACTIVE=prod` sem `GED_JWT_SECRET` → boot falha com mensagem clara;
   com secret + `GED_SUPER_ADMIN_*` → sobe, cria apenas super admin; `admin/admin123` inexistente.
8. Auditoria: `GET /api/admin/audit` mostra login falho, downloads, exclusões e 403 com
   actor/hora/recurso; `GET /api/admin/integrity` reflete arquivo corrompido de propósito.

## Riscos

- **Flyway + Boot 4**: autoconfiguração pode não disparar (relatos na issue #4165). Mitigação:
  verificar `flyway_schema_history` no primeiro boot; fallback com bean `Flyway` explícito.
- **`ddl-auto: validate`** pode quebrar por divergência tênue de tipo (ex.: `created_at`
  `timestamp` vs `timestamptz`). Mitigação: alinhar `V1__init.sql` coluna a coluna e iterar
  até o validate passar (ele é exatamente o gate).
- **Restore com app no ar**: `DROP DATABASE` sobe 500 transitórios no pool Hikari; documentado e
  aceitável em ferramenta de desastre (afinal restaura o sistema).
- **pg_dump/tar no host**: requer binários acessíveis ao backend (em dev, rodar o CLI dentro do
  compose ou expor `GED_PGBIN_DIR`); o teste `BackupRestoreIT` valida o caminho completo.
- **ES index global + escopo em Java**: filtragem em Java é o mesmo pattern já usado para
  advogados/revisores; em volume muito alto, adicionar `office_id` ao mapping (notado como
  evolução, fora do escopo).
- **JDK/Docker neste ambiente**: daemon Docker parado — subir antes de rodar a suíte
  (`systemctl start docker` ou equivalente) para os Testcontainers.
