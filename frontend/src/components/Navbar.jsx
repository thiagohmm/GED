import { NavLink, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { canManageUsers, canReview } from "../pages/model";
import { useState } from "react";
import { PasswordModal } from "./PasswordModal";
import { changeOwnPassword } from "../api/ged";
import { apiErrorMessage } from "../api/client";

export function AppFrame({ children }) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [query, setQuery] = useState("");
  const [passwordModal, setPasswordModal] = useState(false);

  if (!user) {
    return (
      <div className="auth-shell">
        <main className="content">{children}</main>
      </div>
    );
  }

  function submitSearch(event) {
    event.preventDefault();
    if (query.trim()) {
      navigate(`/search?q=${encodeURIComponent(query.trim())}`);
    }
  }

  return (
    <div className="app-shell">
      <aside className="rail">
        <NavLink to="/" className="brand" end>
          <span className="brand-mark" aria-hidden="true">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
              <path d="M7 3h7l5 5v13a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z" />
              <path d="M14 3v6h6" />
              <path d="M9 13h6M9 17h4" />
            </svg>
          </span>
          <span className="brand-text">
            <strong>GED</strong>
            <small>Acervo jurídico</small>
          </span>
        </NavLink>

        <p className="rail-label">Navegação</p>
        <nav className="rail-nav">
          <NavLink to="/" className="nav-link" end>Documentos</NavLink>
          {canReview(user.role) && <NavLink to="/reviews" className="nav-link">Revisões</NavLink>}
          {canManageUsers(user.role) && <NavLink to="/admin/users" className="nav-link">Usuários</NavLink>}
        </nav>

        <div className="rail-foot">
          <div className="nav-user">
            <span className="avatar">{user.username.charAt(0)}</span>
            <span className="nav-user-text">
              <strong>{user.username}</strong>
              <small>{roleLabel(user.role)}</small>
            </span>
          </div>
          <div className="rail-foot-actions">
            <button type="button" className="nav-btn" onClick={() => setPasswordModal(true)}>Senha</button>
            <button
              type="button"
              className="nav-btn"
              onClick={() => {
                logout();
                navigate("/login");
              }}
            >
              Sair
            </button>
          </div>
        </div>
      </aside>

      <div className="workspace">
      <header className="topbar">
        <form className="navbar-search" onSubmit={submitSearch}>
          <svg className="search-glyph" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" />
          </svg>
          <input
            placeholder="Buscar no conteúdo dos documentos"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            aria-label="Buscar documentos"
          />
          <button type="submit" className="btn btn-primary btn-small">Buscar</button>
        </form>
        <span className="topbar-meta">Gestão eletrônica de documentos</span>
      </header>
      <main className="content">{children}</main>
      </div>
      {passwordModal && <PasswordModal title="Alterar minha senha" requireCurrent onClose={() => setPasswordModal(false)} onSubmit={async (current, next) => {
        try { await changeOwnPassword(current, next); } catch (error) { throw new Error(apiErrorMessage(error)); }
      }} />}
    </div>
  );
}

function roleLabel(role) {
  const labels = {
    ADMIN: "Administração",
    PROTOCOLADOR: "Protocolo",
    ADVOGADO: "Advocacia",
    REVISOR: "Revisão"
  };
  return labels[role] ?? role;
}
