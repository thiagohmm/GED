import { useAuth } from "../auth/AuthContext";
import { useState } from "react";
import { Navigate, useNavigate } from "react-router-dom";

export function LoginPage() {
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  if (user) {
    return <Navigate to="/" replace />;
  }

  async function handleSubmit(event) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    const ok = await login(username, password);
    setSubmitting(false);
    if (ok) {
      navigate("/");
    } else {
      setError("Usuário ou senha inválidos.");
    }
  }

  return (
    <div className="login-shell">
      <section className="login-aside">
        <div className="login-brand">
          <span className="brand-mark" aria-hidden="true">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
              <path d="M7 3h7l5 5v13a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z" />
              <path d="M14 3v6h6" />
              <path d="M9 13h6M9 17h4" />
            </svg>
          </span>
          <div>
            <p className="eyebrow">Acervo jurídico</p>
            <h1>GED</h1>
          </div>
        </div>
        <p className="login-lead">
          Gestão eletrônica de documentos para escritórios: pastas por projeto, versões, revisão e protocolo no mesmo fluxo.
        </p>
        <ul className="login-points">
          <li>Arquivo hierárquico com busca no conteúdo</li>
          <li>Versões de Word e Excel com diferença entre alterações</li>
          <li>Revisão, correção e protocolo com trilha de status</li>
        </ul>
      </section>

      <form className="login-card" onSubmit={handleSubmit}>
        <div>
          <h2>Entrar</h2>
          <p className="muted small">Use suas credenciais do escritório para acessar o acervo.</p>
        </div>
        <label>
          Usuário
          <input
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            autoComplete="username"
            required
          />
        </label>
        <label>
          Senha
          <input
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
            required
          />
        </label>
        {error && <p className="error-banner">{error}</p>}
        <button type="submit" className="btn btn-primary" disabled={submitting}>
          {submitting ? "Entrando..." : "Acessar o acervo"}
        </button>
        <p className="muted small login-hint">Primeiro acesso: admin / admin123</p>
      </form>
    </div>
  );
}
