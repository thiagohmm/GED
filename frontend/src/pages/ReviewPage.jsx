import { addReviewComment, decideReview, documentDiff, reviewDetail } from "../api/ged";
import { apiErrorMessage } from "../api/client";
import { verdictLabel } from "./model";
import { DiffView } from "../components/DiffView";
import { useAuth } from "../auth/AuthContext";
import { useCallback, useEffect, useState } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router-dom";

export function ReviewPage() {
  const { id } = useParams();
  const { user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [review, setReview] = useState(null);
  const [diff, setDiff] = useState(null);
  const [diffError, setDiffError] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState(location.state?.notice ?? "");
  const [newComment, setNewComment] = useState("");
  const [quotedLine, setQuotedLine] = useState(null);
  const [summary, setSummary] = useState("");

  const load = useCallback(async () => {
    try {
      const loaded = await reviewDetail(id);
      setReview(loaded);
      if (loaded.fromVersion === loaded.toVersion) {
        setDiff(null);
        setDiffError("Esta é a primeira versão do documento; não há alterações anteriores para comparar.");
        return;
      }
      try {
        setDiff(await documentDiff(loaded.documentId, loaded.fromVersion, loaded.toVersion));
        setDiffError("");
      } catch (diffInstanceError) {
        setDiff(null);
        setDiffError(apiErrorMessage(diffInstanceError));
      }
    } catch (loadError) {
      setError(apiErrorMessage(loadError));
    }
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

  const canDecide =
    review &&
    review.verdict === "PENDENTE" &&
    review.documentStatus === "EM_REVISAO" &&
    (user.role === "REVISOR" || user.role === "ADMIN");

  async function handleComment(event) {
    event.preventDefault();
    if (!newComment.trim()) {
      return;
    }
    try {
      await addReviewComment(id, newComment.trim(), quotedLine?.index, quotedLine?.content);
      setNewComment("");
      setQuotedLine(null);
      await load();
    } catch (commentError) {
      setError(apiErrorMessage(commentError));
    }
  }

  async function handleDecide(verdict) {
    setError("");
    try {
      await decideReview(id, verdict, summary);
      navigate("/reviews", {
        state: {
          notice: verdict === "APROVADO" ? "Revisão aprovada: documento pronto para protocolar." : "Correções solicitadas."
        }
      });
    } catch (decideError) {
      setError(apiErrorMessage(decideError));
    }
  }

  if (error && !review) {
    return <section className="main-panel wide"><div className="error-banner">{error}</div></section>;
  }
  if (!review) {
    return <p className="muted">Carregando revisão...</p>;
  }

  return (
    <section className="main-panel wide">
      <div className="breadcrumb">
        <Link to="/reviews">Voltar à fila</Link>
      </div>
      <h1>
        Revisão #{review.round} — {review.documentName}
        <span className={`badge ${String(review.verdict).toLowerCase()}`}>{verdictLabel(review.verdict)}</span>
      </h1>
      <p className="muted small">
        Diferença analisada: v{review.fromVersion} → v{review.toVersion} &middot; aberta por {review.openedBy} &middot;{" "}
        <Link to={`/documents/${review.documentId}`}>abrir documento</Link>
      </p>
      {error && (
        <div className="error-banner" role="alert">
          <span>{error}</span>
          <button type="button" className="btn btn-ghost" onClick={() => setError("")}>fechar</button>
        </div>
      )}
      {notice && (
        <div className="notice-banner">
          <span>{notice}</span>
          <button type="button" className="btn btn-ghost" onClick={() => setNotice("")}>fechar</button>
        </div>
      )}

      {diff ? (
        <div className="panel-card">
          <h2>Diferenças entre versões (clique o + para comentar na linha)</h2>
          <DiffView
            lines={diff.lines}
            fromVersion={diff.fromVersion}
            toVersion={diff.toVersion}
            onCommentLine={(index, content) => setQuotedLine({ index, content })}
          />
        </div>
      ) : (
        <div className="panel-card">
          {diffError
            ? <p className="muted">{diffError}</p>
            : <p className="muted">Carregando diferenças...</p>}
        </div>
      )}

      {quotedLine && (
        <div className="panel-card">
          <h2>Novo comentário na linha {quotedLine.index + 1}</h2>
          <div className="quote-chip">"{quotedLine.content}"</div>
          <form className="stacked-form" onSubmit={handleComment}>
            <textarea
              rows={3}
              value={newComment}
              onChange={(event) => setNewComment(event.target.value)}
              placeholder="Escreva sua observação..."
            />
            <div className="inline-form">
              <button type="submit" className="btn btn-primary" disabled={!newComment.trim()}>Comentar</button>
              <button type="button" className="btn" onClick={() => setQuotedLine(null)}>Remover citação</button>
            </div>
          </form>
        </div>
      )}

      <div className="panel-card">
        <h2>Conversa ({review.comments.length} comentários)</h2>
        {review.comments.length === 0 ? (
          <p className="muted">Nenhum comentário ainda. Use o “+” nas linhas do diff ou comente geralmente:</p>
        ) : (
          <ul className="comment-thread">
            {review.comments.map((comment) => (
              <li key={comment.id} className="comment-item">
                <div className="comment-meta">
                  <strong>{comment.author}</strong>
                  <span className="muted small">{new Date(comment.createdAt).toLocaleString("pt-BR")}</span>
                </div>
                {comment.diffLineContent && <div className="quote-chip">"{comment.diffLineContent}"</div>}
                <p className="comment-body">{comment.body}</p>
              </li>
            ))}
          </ul>
        )}
        <form className="stacked-form" onSubmit={handleComment}>
          <textarea
            rows={2}
            value={newComment}
            onChange={(event) => setNewComment(event.target.value)}
            placeholder="Observação geral da revisão..."
          />
          <div className="inline-form">
            <button type="submit" className="btn btn-primary" disabled={!newComment.trim()}>Comentar</button>
          </div>
        </form>
      </div>

      {canDecide ? (
        <div className="panel-card">
          <h2>Decisão do revisor</h2>
          <form className="stacked-form" onSubmit={(event) => event.preventDefault()}>
            <textarea
              rows={3}
              value={summary}
              onChange={(event) => setSummary(event.target.value)}
              placeholder="Parecer da revisão (opcional)..."
            />
            <div className="inline-form">
              <button type="button" className="btn btn-primary" onClick={() => handleDecide("APROVADO")}>
                Aprovar (pronto para protocolar)
              </button>
              <button type="button" className="btn btn-danger" onClick={() => handleDecide("CORRECOES_SOLICITADAS")}>
                Solicitar correções (voltar para edição)
              </button>
            </div>
          </form>
        </div>
      ) : (
        review.verdict === "PENDENTE" ? (
          <div className="panel-card">
            <p className="muted">Revisão pendente: somente revisores podem aprovar ou pedir correções.</p>
          </div>
        ) : (
          <div className="panel-card">
            <p className="muted">
              Decidida por {review.decidedBy || "-"} em{" "}
              {review.decidedAt ? new Date(review.decidedAt).toLocaleString("pt-BR") : "-"}
            </p>
            {review.summary && <p><strong>Parecer: </strong>{review.summary}</p>}
          </div>
        )
      )}
    </section>
  );
}
