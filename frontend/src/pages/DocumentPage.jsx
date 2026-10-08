import {
  changeStatus,
  documentDetail,
  documentDiff,
  documentReviews,
  documentStatusHistory,
  documentVersions,
  documentPreview,
  downloadVersion,
  updateDocumentTags,
  uploadDocument
} from "../api/ged";
import { apiErrorMessage } from "../api/client";
import { isExcelOrWord, kindLabel, statusLabel, verdictLabel } from "./model";
import { DiffView } from "../components/DiffView";
import { OnlyOfficeEditor } from "../components/OnlyOfficeEditor";
import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";

export function DocumentPage() {
  const { id } = useParams();
  const [document, setDocument] = useState(null);
  const [versions, setVersions] = useState([]);
  const [reviews, setReviews] = useState([]);
  const [statusHistory, setStatusHistory] = useState([]);
  const [fromVersion, setFromVersion] = useState(null);
  const [toVersion, setToVersion] = useState(null);
  const [diff, setDiff] = useState(null);
  const [error, setError] = useState("");
  const [previewUrl, setPreviewUrl] = useState("");
  const [tagsInput, setTagsInput] = useState("");

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  async function load() {
    try {
      const detail = await documentDetail(id);
      const history = await documentVersions(id);
      const reviewList = await documentReviews(id).catch(() => []);
      const changes = await documentStatusHistory(id);
      setDocument(detail);
      setTagsInput((detail.tags ?? []).join(", "));
      setVersions(history);
      setReviews(reviewList);
      setStatusHistory(changes);
      const current = detail.currentVersion;
      setFromVersion(Math.max(1, current - 1));
      setToVersion(current);
      if (isExcelOrWord(detail.kind) && current > 1) {
        setDiff(await documentDiff(id));
      } else {
        setDiff(null);
      }
    } catch (loadError) {
      setError(apiErrorMessage(loadError));
    }
  }

  useEffect(() => {
    let url;
    if (document?.kind === "IMAGE" && versions[0]) {
      documentPreview(versions[0].id).then((blob) => {
        url = URL.createObjectURL(blob);
        setPreviewUrl(url);
      }).catch((previewError) => setError(apiErrorMessage(previewError)));
    } else {
      setPreviewUrl("");
    }
    return () => { if (url) URL.revokeObjectURL(url); };
  }, [document?.kind, versions]);

  const refreshAfterEdit = useCallback(() => { load(); }, [id]);
  const reportEditorError = useCallback((message) => setError(message), []);

  async function saveTags(event) {
    event.preventDefault();
    try {
      const tags = tagsInput.split(",").map((tag) => tag.trim()).filter(Boolean);
      const updated = await updateDocumentTags(id, tags);
      setDocument((current) => ({ ...current, tags: updated.tags ?? tags }));
    } catch (tagError) { setError(apiErrorMessage(tagError)); }
  }

  async function handleCompare() {
    if (fromVersion === toVersion) {
      setError("Selecione duas versões diferentes para comparar.");
      return;
    }
    try {
      setDiff(await documentDiff(id, fromVersion, toVersion));
    } catch (compareError) {
      setError(apiErrorMessage(compareError));
    }
  }

  async function handleDownload(version) {
    try {
      await downloadVersion(version.id, document.name);
    } catch (downloadError) {
      setError(apiErrorMessage(downloadError));
    }
  }

  async function handleTransition(target) {
    const note = window.prompt(
      `Observação sobre a transição para "${statusLabel(target)}" (opcional; deixe em branco e confirme):`,
      ""
    );
    if (note === null) {
      return;
    }
    try {
      await changeStatus(id, target, note);
      await load();
    } catch (transitionError) {
      setError(apiErrorMessage(transitionError));
    }
  }

  function formatBytes(size) {
    return size >= 1024 ? `${(size / 1024).toFixed(1)} KB` : `${size} B`;
  }

  if (error && !document) {
    return <section className="main-panel wide"><div className="error-banner">{error}</div></section>;
  }
  if (!document) {
    return <p className="muted">Carregando...</p>;
  }

  return (
    <section className="main-panel wide">
      <div className="breadcrumb">
        <Link to="/">Voltar ao acervo</Link>
      </div>
      <h1>
        {document.name}{" "}
        <span className={`badge badge-${document.kind.toLowerCase()}`}>{kindLabel(document.kind)}</span>{" "}
        <span className={`badge ${document.status.toLowerCase()}`}>{statusLabel(document.status)}</span>
      </h1>
      {error && (
        <div className="error-banner" role="alert">
          <span>{error}</span>
          <button type="button" className="btn btn-ghost" onClick={() => setError("")}>fechar</button>
        </div>
      )}

      <div className="panel-card">
        <h2>Tags</h2>
        <form className="inline-form" onSubmit={saveTags}>
          <input aria-label="Tags" placeholder="Ex.: contrato, cliente, urgente" value={tagsInput} onChange={(event) => setTagsInput(event.target.value)} />
          <button className="btn btn-primary" type="submit">Salvar tags</button>
          <span className="muted small">Separe várias tags por vírgula.</span>
        </form>
      </div>

      {(document.kind === "WORD" || document.kind === "EXCEL" || document.kind === "PDF") && (
        document.status === "EDITANDO" ? (
          <div className="panel-card">
            <h2>Visualizar e editar</h2>
            <OnlyOfficeEditor documentId={id} currentVersion={document.currentVersion} mode="edit" onSaved={refreshAfterEdit} onError={reportEditorError} />
          </div>
        ) : (
          <div className="panel-card">
            <h2>Visualizar</h2>
            <OnlyOfficeEditor documentId={id} currentVersion={document.currentVersion} mode="view" onError={reportEditorError} />
          </div>
        )
      )}
      {document.kind === "IMAGE" && (
        <div className="panel-card image-preview-card">
          <h2>Visualização</h2>
          {previewUrl ? <img className="document-image-preview" src={previewUrl} alt={document.name} /> : <p className="muted">Carregando imagem…</p>}
        </div>
      )}

      {(document.allowedTransitions ?? []).length > 0 && (
        <div className="panel-card">
          <h2>Mudar status</h2>
          <div className="inline-form">
            {document.allowedTransitions.map((target) => (
              <button
                key={target}
                type="button"
                className="btn"
                onClick={() => handleTransition(target)}
              >
                {statusLabel(target)}
              </button>
            ))}
            <span className="muted small">Mudanças de status ficam no histórico próprio; versões são criadas ao enviar arquivos alterados.</span>
          </div>
        </div>
      )}

      {isExcelOrWord(document.kind) ? (
        <div className="panel-card">
          <h2>Comparar versões</h2>
          <div className="inline-form">
            <label>
              De
              <select value={fromVersion ?? ""} onChange={(event) => setFromVersion(Number(event.target.value))}>
                {versions.map((version) => (
                  <option key={version.id} value={version.versionNumber}>v{version.versionNumber}</option>
                ))}
              </select>
            </label>
            <label>
              Para
              <select value={toVersion ?? ""} onChange={(event) => setToVersion(Number(event.target.value))}>
                {versions.map((version) => (
                  <option key={version.id} value={version.versionNumber}>v{version.versionNumber}</option>
                ))}
              </select>
            </label>
            <button type="button" className="btn btn-primary" onClick={handleCompare}
              disabled={fromVersion === toVersion}>Comparar</button>
          </div>
          {diff ? <DiffView lines={diff.lines} fromVersion={diff.fromVersion} toVersion={diff.toVersion} /> :
            document.currentVersion === 1 ? <p className="muted">O documento ainda tem apenas uma versão. Envie uma alteração para comparar versões.</p> : null}
        </div>
      ) : document.kind !== "PDF" ? (
        <p className="muted">Este formato armazena somente a versão atual (sem comparação de alterações).</p>
      ) : null}

      {isExcelOrWord(document.kind) ? (
        <div className="panel-card">
          <h2>Enviar alteração</h2>
          <UploadCorrecao document={document} onDone={load} onError={setError} />
          <p className="muted small">
            Uma versão nova fecha qualquer revisão pendente e devolve o documento para edição. Reenvio idêntico é bloqueado.
          </p>
        </div>
      ) : document.kind === "PDF" ? (
        <div className="panel-card">
          <h2>Enviar alteração</h2>
          <p className="muted small">
            PDF aceita apenas um arquivo por nome: edite pelo editor integrado ou envie com outro nome.
          </p>
        </div>
      ) : (
        <div className="panel-card">
          <h2>Enviar alteração</h2>
          <p className="muted small">Imagens são armazenadas uma única vez (sem versionamento).</p>
        </div>
      )}

      <div className="panel-card">
        <h2>Histórico de status</h2>
        {statusHistory.length === 0 ? <p className="muted">Nenhuma mudança de status registrada.</p> : (
          <ul className="comment-thread">
            {statusHistory.map((change) => (
              <li key={change.id ?? `${change.fromStatus}-${change.toStatus}-${change.changedAt}`} className="comment-item">
                <div className="comment-meta">
                  <strong>{statusLabel(change.fromStatus)} → {statusLabel(change.toStatus)}</strong>
                  <span className="muted small">{change.changedBy || "-"} · {new Date(change.changedAt).toLocaleString("pt-BR")}</span>
                </div>
                {change.note && <p className="comment-body">{change.note}</p>}
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="panel-card">
        <h2>Revisões ({reviews.length})</h2>
        {reviews.length === 0 ? (
          <p className="muted">
            Nenhuma revisão ainda. Ao mudar para "Em revisão", uma rodada é aberta para o revisor.
          </p>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Rodada</th>
                <th>Veredito</th>
                <th>Comentários</th>
                <th>Aberta por</th>
                <th>Decidida por</th>
                <th>Ação</th>
              </tr>
            </thead>
            <tbody>
              {reviews.map((review) => (
                <tr key={review.reviewId}>
                  <td>#{review.round}</td>
                  <td>{verdictLabel(review.verdict)}</td>
                  <td>{review.commentCount}</td>
                  <td className="small">{review.openedBy}</td>
                  <td className="small">{review.decidedBy ?? "-"}</td>
                  <td>
                    <Link className="btn btn-small" to={`/reviews/${review.reviewId}`}>
                      Abrir
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="panel-card">
        <h2>Histórico de versões</h2>
        <table className="data-table">
          <thead>
            <tr>
              <th>Versão</th>
              <th>Status</th>
              <th>Alteração</th>
              <th>Autor</th>
              <th>Data</th>
              <th>Tamanho</th>
              <th>Ações</th>
            </tr>
          </thead>
          <tbody>
            {versions.map((version) => (
              <tr key={version.id}>
                <td>
                  v{version.versionNumber}
                  {version.versionNumber === document.currentVersion && <strong> (atual)</strong>}
                </td>
                <td>
                  <span className={`badge ${String(version.status).toLowerCase()}`}>{statusLabel(version.status)}</span>
                </td>
                <td className="small">{version.changeDescription || "-"}</td>
                <td className="small">{version.uploadedBy}</td>
                <td className="small">{new Date(version.uploadedAt).toLocaleString("pt-BR")}</td>
                <td className="small">{formatBytes(version.sizeBytes)}</td>
                <td className="row-actions">
                  <button type="button" className="btn btn-small" onClick={() => handleDownload(version)}>
                    Baixar
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function UploadCorrecao({ document, onDone, onError }) {
  const [file, setFile] = useState(null);
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);

  async function handleUpload(event) {
    event.preventDefault();
    if (!file) {
      return;
    }
    setBusy(true);
    try {
      await uploadDocument(document.folderId, file, description);
      setFile(null);
      setDescription("");
      await onDone();
    } catch (uploadError) {
      onError(apiErrorMessage(uploadError));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <form className="inline-form" onSubmit={handleUpload}>
        <input
          key={document.currentVersion}
          type="file"
          accept=".docx,.xlsx,.pdf,.png,.jpg,.jpeg"
          onChange={(event) => setFile(event.target.files[0] ?? null)}
        />
        <input
          placeholder="Descrição da alteração (opcional)"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
        />
        <button type="submit" className="btn btn-primary" disabled={busy || !file}>
          {busy ? "Enviando..." : "Enviar"}
        </button>
      </form>
      <p className="muted small">
        Reenviar o arquivo sem nenhuma mudança no conteúdo é bloqueado pelo sistema.
      </p>
    </div>
  );
}
