import { useState } from "react";

export function PasswordModal({ title, username, requireCurrent = false, onClose, onSubmit }) {
  const [currentPassword, setCurrentPassword] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(event) {
    event.preventDefault();
    setError("");
    if (password.length < 6) return setError("A senha deve ter pelo menos 6 caracteres.");
    if (password !== confirmation) return setError("As senhas não conferem.");
    setBusy(true);
    try {
      await onSubmit(currentPassword, password);
      onClose();
    } catch (submitError) {
      setError(submitError.message || "Não foi possível alterar a senha.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <form className="modal-card password-modal" onSubmit={submit}>
        <button type="button" className="modal-close" onClick={onClose} aria-label="Fechar">×</button>
        <div className="modal-icon">⌑</div>
        <p className="eyebrow">Segurança da conta</p>
        <h2>{title}</h2>
        {username && <p className="muted">Usuário: <strong>{username}</strong></p>}
        {requireCurrent && <label>Senha atual<input autoComplete="current-password" type="password" value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} required /></label>}
        <label>Nova senha<input autoComplete="new-password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} minLength={6} required /></label>
        <label>Confirmar nova senha<input autoComplete="new-password" type="password" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} minLength={6} required /></label>
        {error && <div className="error-banner" role="alert">{error}</div>}
        <div className="modal-actions"><button type="button" className="btn" onClick={onClose}>Cancelar</button><button className="btn btn-primary" disabled={busy}>{busy ? "Salvando…" : "Salvar senha"}</button></div>
      </form>
    </div>
  );
}
