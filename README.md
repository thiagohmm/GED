# GED — Gestão Eletrônica de Documentos

Sistema web para advogados: pastas hierárquicas, **versionamento de Word/Excel com diff das alterações**, **busca full-text no Elasticsearch**, **workflow de status estilo PR do GitHub** (edição → revisão → protocolo), **papéis por perfil** (ADVOGADO, REVISOR, PROTOCOLADOR, ADMIN) e **multi-escritório (SaaS)** com isolamento total de dados por escritório.

Pronto para produção: **migrações versionadas (Flyway)**, **seed de admin apenas em dev**, **segredos obrigatórios em produção**, **backup/restauração testados** (banco + documentos) e **trilha de auditoria** completa.

## Stack

| Camada | Stack |
|---|---|
| Backend | Java 25 · Spring Boot 4 · Spring Security (JWT) · JPA/Hibernate · PostgreSQL · Flyway |
| Extração | Apache POI (.docx/.xlsx) · Apache PDFBox (.pdf) · java-diff-utils (diff) |
| Busca | Elasticsearch 8.15 (docker) — texto de Word/Excel/PDF |
| Frontend | React 18 · Vite 5 · axios · react-router-dom |

## Multi-escritório (multi-tenant)

Cada instalação atende vários escritórios, cada um com seus próprios projetos, pastas, documentos, revisões e usuários. O escopo vem **do backend** a cada request (nenhum filtro é deixado no cliente):

- **SUPER_ADMIN** (global): enxerga e gerencia todos os escritórios — criar/inativar/excluir escritórios, criar o ADMIN de cada escritório, ver usuários de todos, auditoria, integridade, backup/restore.
- **ADMIN** (do escritório): acesso completo **somente no próprio escritório**; não acessa dados de outros escritórios (projetos, pastas, documentos, revisões, busca e usuários).
- **ADVOGADO / REVISOR / PROTOCOLADOR**: perfis operacionais, sempre no próprio escritório.

Isolamento aplicado em todas as queries (JPA) e no índice de busca (resultado e opções de filtro filtrados por escritório). Tokens do editor (arquivo/callback do OnlyOffice) carregam o escritório e são validados contra o recurso.

## Papéis

- **ADVOGADO**: pastas, upload de documentos, envio para revisão, busca por texto.
- **REVISOR**: tudo de ADVOGADO + fila de revisão: comentar no diff, **Aprovar** (→ Pronto para protocolar) ou **Solicitar correções** (→ Editando), reabrir protocolado.
- **PROTOCOLADOR**: tudo de ADVOGADO + **Protocolar**.
- **ADMIN**: acesso técnico completo no escritório + gestão de usuários.
- **SUPER_ADMIN**: global (todos os escritórios) + gestão de escritórios, auditoria, integridade e backup.

Proteções de papel (testadas): ninguém desativa a si mesmo nem altera o próprio papel; SUPER_ADMIN só é alterado por outro SUPER_ADMIN (papel/status imutáveis); ADMIN não altera outro ADMIN; apenas SUPER_ADMIN cria SUPER_ADMIN.

Seed (apenas em dev, `ged.seed.enabled=true`): escritório **"Escritório Dev"** + `admin / admin123` (ADMIN) + `root` (SUPER_ADMIN, mesma senha). Em produção o seed de dev nunca roda: o app exige `GED_SUPER_ADMIN_USERNAME`/`GED_SUPER_ADMIN_PASSWORD` (cria o primeiro SUPER_ADMIN se nenhum existir).

## Workflow de status

```
EDITANDO ──(enviar p/ revisão, qualquer logado)──▶ EM_REVISAO
EM_REVISAO ──(revisor: APROVAR)──▶ PRONTO_PARA_PROTOCOLO
EM_REVISAO ──(revisor: SOLICITAR CORREÇÕES)──▶ EDITANDO
PRONTO_PARA_PROTOCOLO ──(protocolador)──▶ PROTOCOLADO
PROTOCOLADO ──(revisor reabre)──▶ EDITANDO
```

- **Mudanças de status não criam versões de arquivo**. Elas ficam no histórico de status do documento; versões representam somente arquivos enviados.
- A saída de `EM_REVISAO` acontece somente pela **decisão da revisão**.
- **Revisões estilo PR**: cada envio para revisão abre uma rodada (`#N`, diff `vFrom -> vTo`); comentário geral e **comentário inline citando uma linha do diff** (bot "+" na linha); parecer na decisão; rodadas anteriores ficam no histórico do documento.
- **Upload idêntico é rejeitado** (400): reenviar Word/Excel com o mesmo conteúdo da versão atual não cria versão.
- PDF e foto: um arquivo por nome (reenvio → 409); PDFs têm texto extraído para busca.
- **Versão em qualquer status**: upload ou salvamento do editor cria versão nova mesmo em revisão/protocolado; revisões pendentes são fechadas como "Fechada (superada)" e o status volta para Editando (tudo com auditoria no histórico de status).
- **Mover arquivos e pastas**: arraste a linha do documento (ou da pasta na árvore) para a pasta de destino, ou selecione documentos (checkbox) e clique em "Mover"; nome duplicado no destino → 409, mover pasta para dentro dela mesma/subárvore → 400; mover não altera status/versões e reindexa os caminhos na busca.
- **Visualização**: Word, Excel e PDF abrem no OnlyOffice em modo somente leitura em qualquer status; em "Editando" o documento abre editável e, ao fechar o editor, alterações viram uma nova versão (Excel incluído). Imagens têm preview direto. O Document Server demora alguns minutos para subir; o frontend repete a conexão e oferece "Tentar novamente".
- Busca: `GET /api/search?q=...&status=...` — resultado traz **caminho da pasta** (breadcrumb), status, versão e **snippet com o termo destacado**, com link para a tela do documento. Sempre restrita ao escritório do usuário (SUPER_ADMIN vê todos).

## Migrações de banco (Flyway)

O schema é versionado em `backend/src/main/resources/db/migration/` (`V1__init.sql` cria tudo a partir do zero). Para evoluir o schema, adicione `V2__<descricao>.sql` etc. — nunca edite migrações aplicadas. O app sobe com `ddl-auto: validate`: entidade e schema divergirem → falha no startup.

## Backup e restauração

O backup é um **bundle** (`<ged.backup.dir>/ged-backup-YYYYMMDD-HHMMSS/`):

- `database.dump` — `pg_dump -Fc` do banco;
- `storage.tar.gz` — todos os binários (documentos);
- `manifest.json` — versão, versão do schema, contagens (offices/users/projects/folders/documents/versions), SHA-256 dos componentes, tempo de execução;
- `SHA256SUMS` — hashes para verificação.

O índice Elasticsearch **não** é incluído: é reconstruído do Postgres durante a restauração (o índice é derivado).

**API** (SUPER_ADMIN):

| Rota | Ação |
|---|---|
| `POST /api/admin/backups` | Cria backup |
| `GET /api/admin/backups` | Lista backups (mais recente primeiro) |
| `POST /api/admin/backups/{name}/verify` | Valida `SHA256SUMS` e manifest |
| `POST /api/admin/restore` | Restauração: valida hashes → drop/create do banco → `pg_restore` → extrai `storage/` → check de integridade (hashes) → reindexa o ES |
| `DELETE /api/admin/backups/{name}` | Remove o bundle do disco |

**CLI** (para agendar com cron, sem o app web):

```bash
# dentro do container backend (compgen pgb):
docker compose exec -T backend java -jar /app.jar --spring.profiles.active=prod --ged.cli=backup
docker compose exec -T backend java -jar /app.jar --spring.profiles.active=prod --ged.cli=verify --ged.cli-path=/backups/ged-backup-...
docker compose exec -T backend java -jar /app.jar --spring.profiles.active=prod --ged.cli=restore --ged.cli-path=/backups/ged-backup-...
```

ou as variáveis `GED_CLI=backup` / `GED_CLI_PATH=...`. Exemplo de cron diário (3 AM) com retenção de 14 dias:

```cron
0 3 * * * docker compose exec -T backend java -jar /app.jar --spring.profiles.active=prod --ged.cli=backup >> /var/log/ged-backup.log 2>&1
30 3 * * * find /caminho/backups -maxdepth 1 -type d -name 'ged-backup-*' -mtime +14 -exec rm -rf {} +
```

A restauração é testada de ponta a ponta em `BackupRestoreIT` (cria dados, faz backup, destrói banco + storage, restaura e confere bytes, hashes e busca).

## Auditoria

Toda ação relevante grava em `audit_events` (usuário, papel, escritório, ação, recurso, detalhe JSON, instante): login (sucesso/falha), criação/alteração de usuários e escritórios, upload/edição de tags/exclusão/movimentação de documentos, mudança de status, decisão de revisão, backup/restauração, check de integridade e **toda negação de acesso (403)**.

Consulta (SUPER_ADMIN): `GET /api/admin/audit?action=...&userName=...&from=...&to=...&size=50` (mais recentes primeiro).

## Integridade

`GET /api/admin/integrity` (SUPER_ADMIN) verifica, para todas as versões: existência do arquivo em disco e coincidência do SHA-256 armazenado. Responde `{allOk, issues:[{documentId, versionId, problem: ARQUIVO_INEXISTENTE|HASH_DIFERENTE}]}` e grava o resultado na auditoria.

## Pré-requisitos

- JDK 25 com `javac`
- Maven 3.9+ · Docker · Node 20+
- `pg_dump`/`pg_restore` e `tar` no `PATH` do host (para backup/restauração)

## Como executar

### Desenvolvimento

```bash
cp .env.example .env        # padrões de dev; ajuste se necessário
docker compose up -d        # Postgres + Elasticsearch (+ OnlyOffice opcional)
cd backend && mvn spring-boot:run   # porta 8080
cd frontend && npm install && npm run dev  # porta 5173
```

Acesse **http://localhost:5173** e faça login com `admin / admin123` (escritório dev) ou `root / admin123` (SUPER_ADMIN).

### Produção

1. Crie um `.env` com as variáveis **obrigatórias**:

   ```
   POSTGRES_PASSWORD=<senha forte>
   GED_JWT_SECRET=<>=32 chars, aleatório>
   GED_SUPER_ADMIN_USERNAME=seu-usuario
   GED_SUPER_ADMIN_PASSWORD=<senha forte>
   ```

   `docker compose up -d` falha sem elas. O primeiro start cria o SUPER_ADMIN indicado; depois use a UI **Escritórios** (ou a API) para criar os escritórios e os ADMINs.

2. Persistência: os volumes `pgdata`/`esdata` e a pasta `backups/` precisam sobreviver a deploys; monte `backups/` (e `storage/`) em disco compartilhado se o backend for recriado.

3. Hardening adicional recomendado (imagem): rodar o container sem root e com root filesystem somente leitura (`tmpfs /tmp`), e expor o app atrás de proxy TLS.

## Testes

```bash
mvn -f backend/pom.xml test
```

- **Unit** (sempre): serviços de pasta/documento/revisão/editor, regras de upload, matriz de transições de status, revisões, extratores (Word/Excel/PDF), diff, JWT.
- **Integração** (Testcontainers — exigem Docker; pulados se indisponível):
  - `FlywaySchemaTest` — schema canônico + seed de produção;
  - `AuthorizationMatrixTest` — matriz completa: cada papel x cada endpoint, cruzando escritórios (escritório A não acessa B), proteções de papel, upload/status/revisão;
  - `BackupRestoreIT` — backup, corrupção detectada por `verify`/`restore`, desastre + restauração + integridade + busca reindexada.

## Regras principais

- Word/Excel: reenvio do mesmo nome = versão nova; idêntico = 400.
- PDF/Imagem: um por nome (409 em duplicidade); PDF indexado por texto (PDFBox); foto indexada por nome/caminho.
- Reindex do Elasticsearch: automático no startup quando o índice estiver vazio; mudanças de status atualizam o status indexado sem gerar versão de arquivo. Falha do ES não derruba o app (busca responde 503). `POST /api/admin/reindex` reconstrói o índice inteiro (SUPER_ADMIN).
- Nome de projeto é único **por escritório** (`uk_projects_office_name`).

## Configurações (env)

| Variável | Padrão | Descrição |
|---|---|---|
| `GED_DB_HOST`/`PORT`/`NAME`/`USER`/`PASSWORD` | localhost/5432/ged/ged/ged | PostgreSQL (`POSTGRES_PASSWORD` no compose é obrigatória) |
| `GED_DB_MAINTENANCE_NAME` | `postgres` | Banco de manutenção usado no restore (drop/create) |
| `GED_STORAGE_DIR` | `storage` | Binários em disco |
| `GED_JWT_SECRET` | dev | **Obrigatória em produção** (>= 32 chars; o valor de dev é rejeitado no perfil prod) |
| `GED_JWT_EXPIRATION_MINUTES` | 720 | Validade do token |
| `GED_SEED_ENABLED` | dev: `true` | Seed do escritório dev + admin (nunca em produção) |
| `GED_SEED_ADMIN_USERNAME/PASSWORD` | admin/admin123 | Seed dev |
| `GED_SUPER_ADMIN_USERNAME/PASSWORD` | — | **Obrigatórias em produção** se não houver SUPER_ADMIN |
| `GED_ES_ENABLED` / `GED_ES_URL` | `true` / `http://localhost:9200` | Elasticsearch |
| `GED_BACKUP_DIR` | `backups` | Pasta dos bundles de backup |
| `GED_PGBIN_DIR` | `PATH` | Diretório do `pg_dump`/`pg_restore` |
| `GED_CLI` / `GED_CLI_PATH` | — | Modo CLI: `backup` \| `restore` \| `verify` (e caminho do bundle) |
| `GED_EDITOR_URL` / `GED_EDITOR_PUBLIC_BACKEND_URL` | localhost:8090 / host.docker.internal:8080 | OnlyOffice Document Server |

## API REST (`/api`)

Auth: `POST /auth/login` · Usuários: `GET/POST /users`, `PATCH /users/{id}` (ADMIN/SUPER_ADMIN; SUPER_ADMIN vê/gerencia todos os escritórios) · Escritórios (SUPER_ADMIN): `GET/POST /admin/offices`, `PATCH/DELETE /admin/offices/{id}`, `POST /admin/offices/{id}/admin` · Pastas: `GET /folders/tree`, `POST /folders`, `PATCH/DELETE /folders/{id}` (ADMIN/PROTOCOLADOR/SUPER_ADMIN) · Documentos: `POST/GET /folders/{id}/documents`, `GET /documents/{id}` (+`allowedTransitions`), `GET /documents/{id}/versions`, `GET /documents/{id}/status-history`, `GET /documents/{id}/diff?from&to`, `GET /versions/{id}/download`, `DELETE /documents/{id}` · **Status**: `POST /documents/{id}/status {to, note?}` (registra histórico sem criar versão de arquivo) · **Revisões**: `GET /reviews/queue` (REVISOR/ADMIN/SUPER_ADMIN), `GET /reviews/{id}`, `POST /reviews/{id}/comments {body, diffLineIndex?, diffLineContent?}`, `POST /reviews/{id}/decide {verdict, summary?}`, `GET /documents/{id}/reviews` · **Busca**: `GET /search?q=...&status=...` (+`/search/filters`) · **Auditoria**: `GET /admin/audit` (SUPER_ADMIN) · **Integridade**: `GET /admin/integrity` (SUPER_ADMIN) · **Backup**: `GET/POST /admin/backups`, `POST /admin/backups/{name}/verify`, `POST /admin/restore`, `DELETE /admin/backups/{name}` · **Reindex**: `POST /admin/reindex` (SUPER_ADMIN).

Erros: `{status, message}` (400/401/403/404/409/413/503). Editor: `GET /editor/documents/{id}/config?mode=view|edit` (view: Word/Excel/PDF em qualquer status; edit: somente em `EDITANDO`), `GET /editor/versions/{id}/file?token=...`, `POST /editor/callback/{documentId}?token=...`.

## Limitações conhecidas

- Sem OCR: PDF escaneado / imagem não têm texto pesquisável (nome/caminho ainda pesquisáveis).
- O backup usa `pg_dump`/`pg_restore`/`tar` do host; sem eles os endpoints falham com mensagem clara.
- O índice do ES é derivado: em um desastre que perca Postgres **e** storage, restaure o backup (o ES é reconstruído).
- Consumo do ES: heap 512MB configurado no compose.
