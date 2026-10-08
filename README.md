# GED — Gestão Eletrônica de Documentos

Sistema web para advogados: pastas hierárquicas, **versionamento de Word/Excel com diff das alterações**, **busca full-text no Elasticsearch**, **workflow de status estilo PR do GitHub** (edição → revisão → protocolo) e **papéis por perfil** (ADVOGADO, REVISOR, PROTOCOLADOR, ADMIN).

## Stack

| Camada | Stack |
|---|---|
| Backend | Java 25 · Spring Boot 3.5 · Spring Security (JWT) · JPA/Hibernate · PostgreSQL |
| Extração | Apache POI (.docx/.xlsx) · Apache PDFBox (.pdf) · java-diff-utils (diff) |
| Busca | Elasticsearch 8.15 (docker) — texto de Word/Excel/PDF |
| Frontend | React 18 · Vite 5 · axios · react-router-dom |

## Papéis (templates)

- **ADVOGADO**: pastas, upload de documentos, envio para revisão, busca por texto.
- **REVISOR**: tudo de ADVOGADO + fila de revisão: comentar no diff, **Aprovar** (→ Pronto para protocolar) ou **Solicitar correções** (→ Editando), reabrir protocolado.
- **PROTOCOLADOR**: tudo de ADVOGADO + **gerencia usuários** (criar/ativar/trocar perfil/resetar senha) + **Protocolar**.
- **ADMIN**: acesso técnico completo.

Seed do primeiro start: `admin / admin123` (via `GED_SEED_ADMIN_PASSWORD`).

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
- **Revisões estilo PR**: cada envio para revisão abre uma rodada (`#N`, diff `vFrom -> vTo`); token comentária geral e **comentário inline citando uma linha do diff** (bot "+" na linha); parecer na decisão; rodadas anteriores ficam no histórico do documento.
- **Upload idêntico é rejeitado** (400): reenviar Word/Excel com o mesmo conteúdo da versão atual não cria versão.
- PDF e foto: um arquivo por nome (reenvio → 409); PDFs têm texto extraído para busca.
- **Versão em qualquer status**: upload ou salvamento do editor cria versão nova mesmo em revisão/protocolado; revisões pendentes são fechadas como "Fechada (superada)" e o status volta para Editando (tudo com auditoria no histórico de status).
- **Mover arquivos e pastas**: arraste a linha do documento (ou da pasta na árvore) para a pasta de destino, ou selecione documentos (checkbox) e clique em "Mover"; nome duplicado no destino → 409, mover pasta para dentro dela mesma/subárvore → 400; mover não altera status/versões e reindexa os caminhos na busca.
- **Visualização**: Word, Excel e PDF abrem no OnlyOffice em modo somente leitura em qualquer status; em "Editando" o documento abre editável e, ao fechar o editor, alterações viram uma nova versão (Excel incluído). Imagens têm preview direto. O Document Server demora alguns minutos para subir; o frontend repete a conexão e oferece "Tentar novamente".
- Busca: `GET /api/search?q=...&status=...` — resultado traz **caminho da pasta** (breadcrumb), status, versão e **snippet com o termo destacado**, com link para a tela do documento.

## Pré-requisitos

- JDK 25 com `javac` (neste ambiente: `$HOME/jdk`, Temurin)
- Maven 3.9+ · Docker · Node 20+

## Como executar

1. Bancos/serviços (PostgreSQL + Elasticsearch):

   ```bash
   docker compose up -d
   ```

2. Backend (porta 8080):

   Requer JDK 25. O Maven usa Spring Boot 4.0.8 conforme definido no `backend/pom.xml`.

   ```bash
   cd backend
   java -version # confirme que o runtime ativo e Java 25
   mvn spring-boot:run
   ```

3. Frontend (porta 5173):

   ```bash
   cd frontend
   npm install
   npm run dev
   ```

4. Acesse **http://localhost:5173**, faça login (`admin / admin123`) e:
   - Crie pastas/subpastas na árvore; envie `.docx`/`.xlsx` com uma descrição;
  - Reenvie um arquivo alterado enquanto o documento estiver em edição → nova versão + diff;
   - Use a busca na barra superior;
   - **Revisões** (nav) — fila do revisor; **Usuários** (nav) — gestão pelo ADMIN/PROTOCOLADOR.

## Testes

```bash
JAVA_HOME=$HOME/jdk mvn -f backend/pom.xml test
```

Cobrem: diff calculator, extratores Word/Excel/PDF (fixtures gerados em memória), regras de upload (nova versão, rejeição de idêntico, bloqueio de edição durante revisão, conflitos 409, tipos não suportados), matriz de transições de status, revisões (rodadas, decisão→transição, comentário inline, fila).

## Regras principais

- Word/Excel: reenvio do mesmo nome = versão nova; idêntico = 400.
- PDF/Imagem: um por nome (409 em duplicidade); PDF indexado por texto (PDFBox); foto indexada por nome/caminho.
- Reindex do Elasticsearch: automático no startup quando o índice estiver vazio; mudanças de status atualizam o status indexado sem gerar versão de arquivo. Falha do ES não derruba o app (busca responde 503).
- Migrações de dados no startup: papéis antigos `USER` viram `ADVOGADO`; documentos/versões sem status viram `EDITANDO`; constraints CHECK de enum antigas são descartadas antes.

## Configurações (env)

| Variável | Padrão | Descrição |
|---|---|---|
| `GED_DB_*` | localhost/ged/ged | PostgreSQL |
| `GED_STORAGE_DIR` | `storage` | Binários em disco |
| `GED_JWT_SECRET`, `GED_JWT_EXPIRATION_MINUTES` | dev / 720 | Tokens JWT |
| `GED_SEED_ADMIN_USERNAME/PASSWORD` | admin/admin123 | Seed do primeiro start |
| `GED_ES_ENABLED` | `true` | Liga/desliga o Elasticsearch |
| `GED_ES_URL` | `http://localhost:9200` | Endpoint do ES |

## API REST (`/api`)

Auth: `POST /auth/login` · Usuários: `GET/POST /users`, `PATCH /users/{id}` (ADMIN/PROTOCOLADOR) · Pastas: `GET /folders/tree`, `POST /folders`, `PATCH/DELETE /folders/{id}` · Documentos: `POST/GET /folders/{id}/documents`, `GET /documents/{id}`(+`allowedTransitions`), `GET /documents/{id}/versions`, `GET /documents/{id}/status-history`, `GET /documents/{id}/diff?from&to`, `GET /versions/{id}/download`, `DELETE /documents/{id}` · **Status**: `POST /documents/{id}/status {to, note?}` (registra histórico sem criar versão de arquivo) · **Revisões**: `GET /reviews/queue` (REVISOR/ADMIN), `GET /reviews/{id}`, `POST /reviews/{id}/comments {body, diffLineIndex?, diffLineContent?}`, `POST /reviews/{id}/decide {verdict, summary?}`, `GET /documents/{id}/reviews` · **Busca**: `GET /search?q=...&status=...`.

Erros: `{status, message}` (400/401/403/404/409/413/503). Editor: `GET /editor/documents/{id}/config?mode=view|edit` (view: Word/Excel/PDF em qualquer status; edit: somente em `EDITANDO`), `GET /editor/versions/{id}/file?token=...`, `POST /editor/callback/{documentId}?token=...`.

## Limitações conhecidas

- Sem OCR: PDF escaneado / imagem não têm texto pesquisável (nome/caminho ainda pesquisáveis).
- `ddl-auto: update` + migrações por runner (sem Flyway) — adequado ao uso local.
- Consumo do ES: heap 512MB configurado no compose.
