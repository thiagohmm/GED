import { reviewQueue } from "../api/ged";
import { apiErrorMessage } from "../api/client";
import { statusLabel, verdictLabel } from "./model";
import { useEffect, useState } from "react";
import { Link, useLocation } from "react-router-dom";

export function ReviewsPage() {
  const location = useLocation();
  const [queue, setQueue] = useState(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState(location.state?.notice ?? "");

  useEffect(() => {
    let cancelled = false;
    reviewQueue()
      .then((data) => {
        if (!cancelled) setQueue(data);
      })
      .catch((queueError) => {
        if (!cancelled) setError(apiErrorMessage(queueError));
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (error) {
    return <section className="main-panel wide"><div className="error-banner">{error}</div></section>;
  }
  if (!queue) {
    return <p className="muted">Carregando fila de revisao...</p>;
  }

  return (
    <section className="main-panel wide">
      <p className="eyebrow">Fila</p>
      <h1>Revisão de documentos</h1>
      {notice && (
        <div className="notice-banner">
          <span>{notice}</span>
          <button type="button" className="btn btn-ghost" onClick={() => setNotice("")}>fechar</button>
        </div>
      )}

      <div className="panel-card">
        <h2>Aguardando decisão ({queue.pending.length})</h2>
        {queue.pending.length === 0 ? (
          <p className="muted">Nenhum documento aguardando revisão.</p>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Documento</th>
                <th>Caminho</th>
                <th>Rodada</th>
                <th>Diferença revisada</th>
                <th>Aberta por</th>
                <th>Ação</th>
              </tr>
            </thead>
            <tbody>
              {queue.pending.map((item) => (
                <tr key={item.reviewId}>
                  <td>
                    <Link to={`/documents/${item.documentId}`}>{item.documentName}</Link>
                  </td>
                  <td className="small">{item.folderPath || "Raiz"}</td>
                  <td>#{item.round}</td>
                  <td className="small">v{item.fromVersion} → v{item.toVersion}</td>
                  <td className="small">{item.openedBy}</td>
                  <td>
                    <Link className="btn btn-small btn-primary" to={`/reviews/${item.reviewId}`}>
                      Revisar
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="panel-card">
        <h2>Decididas recentemente</h2>
        {queue.recentlyDecided.length === 0 ? (
          <p className="muted">Nenhuma revisão decidida ainda.</p>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Documento</th>
                <th>Rodada</th>
                <th>Status do documento</th>
                <th>Veredito</th>
                <th>Decidida por</th>
                <th>Ação</th>
              </tr>
            </thead>
            <tbody>
              {queue.recentlyDecided.map((item) => (
                <tr key={item.reviewId}>
                  <td>
                    <Link to={`/documents/${item.documentId}`}>{item.documentName}</Link>
                  </td>
                  <td>#{item.round}</td>
                  <td>
                    <span className={`badge ${String(item.documentStatus).toLowerCase()}`}>
                      {statusLabel(item.documentStatus)}
                    </span>
                  </td>
                  <td>{verdictLabel(item.verdict)}</td>
                  <td className="small">{item.decidedBy}</td>
                  <td>
                    <Link className="btn btn-small" to={`/reviews/${item.reviewId}`}>
                      Ver revisão
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </section>
  );
}
