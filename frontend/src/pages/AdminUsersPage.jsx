import { createUser, listUsers, updateUser } from "../api/ged";
import { apiErrorMessage } from "../api/client";
import { useEffect, useState } from "react";
import { PasswordModal } from "../components/PasswordModal";

export function AdminUsersPage() {
  const [users, setUsers] = useState([]);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [role, setRole] = useState("ADVOGADO");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [resetUser, setResetUser] = useState(null);
  const [roleFilter, setRoleFilter] = useState("");
  const [createdFrom, setCreatedFrom] = useState("");
  const [createdTo, setCreatedTo] = useState("");

  useEffect(() => {
    loadUsers();
  }, []);

  async function loadUsers() {
    try {
      setUsers(await listUsers());
    } catch (listError) {
      setError(apiErrorMessage(listError));
    }
  }

  async function handleCreate(event) {
    event.preventDefault();
    try {
      const created = await createUser({ username: username.trim(), password, role });
      setNotice(`Usuario "${created.username}" criado.`);
      setUsername("");
      setPassword("");
      setRole("ADVOGADO");
      await loadUsers();
    } catch (createError) {
      setError(apiErrorMessage(createError));
    }
  }

  async function patch(user, payload, message) {
    try {
      const updated = await updateUser(user.id, payload);
      setUsers((current) => current.map((item) => (item.id === updated.id ? updated : item)));
      setNotice(message);
    } catch (patchError) {
      setError(apiErrorMessage(patchError));
    }
  }

  const filteredUsers = users.filter((user) => {
    const date = user.createdAt?.slice(0, 10) ?? "";
    return (!roleFilter || user.role === roleFilter)
      && (!createdFrom || date >= createdFrom)
      && (!createdTo || date <= createdTo);
  });

  return (
    <section className="main-panel wide">
      <p className="eyebrow">Escritório</p>
      <h1>Gestão de usuários</h1>
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

      <div className="panel-card">
        <h2>Novo usuario</h2>
        <form className="inline-form" onSubmit={handleCreate}>
          <input
            placeholder="Usuario"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            required
            minLength={3}
          />
          <input
            type="password"
            placeholder="Senha (min. 6)"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
            minLength={6}
          />
          <select value={role} onChange={(event) => setRole(event.target.value)}>
            <option value="ADVOGADO">ADVOGADO</option>
            <option value="REVISOR">REVISOR</option>
            <option value="PROTOCOLADOR">PROTOCOLADOR</option>
            <option value="ADMIN">ADMIN</option>
          </select>
          <button type="submit" className="btn btn-primary">Criar</button>
        </form>
      </div>

      <div className="panel-card">
        <h2>Usuarios</h2>
        <div className="table-filters">
          <label>Perfil<select value={roleFilter} onChange={(event) => setRoleFilter(event.target.value)}><option value="">Todos</option>{["ADMIN", "PROTOCOLADOR", "ADVOGADO", "REVISOR"].map((item) => <option key={item}>{item}</option>)}</select></label>
          <label>Cadastrado de<input type="date" value={createdFrom} onChange={(event) => setCreatedFrom(event.target.value)} /></label>
          <label>Até<input type="date" value={createdTo} onChange={(event) => setCreatedTo(event.target.value)} /></label>
          <button type="button" className="btn" onClick={() => { setRoleFilter(""); setCreatedFrom(""); setCreatedTo(""); }}>Limpar</button>
        </div>
        <table className="data-table">
          <thead>
            <tr>
              <th>Usuario</th>
              <th>Perfil</th>
              <th>Status</th>
              <th>Cadastro</th>
              <th>Acoes</th>
            </tr>
          </thead>
          <tbody>
            {filteredUsers.map((user) => (
              <tr key={user.id}>
                <td>{user.username}</td>
                <td>
                  <span className={`badge role-${user.role.toLowerCase()}`}>{user.role}</span>
                </td>
                <td>{user.enabled ? "ativo" : "inativo"}</td>
                <td className="small">{user.createdAt ? new Date(user.createdAt).toLocaleDateString("pt-BR") : "-"}</td>
                <td className="row-actions">
                  <button
                    type="button"
                    className="btn btn-small"
                    onClick={() => patch(user, { enabled: !user.enabled },
                      `Usuario "${user.username}" ${user.enabled ? "desativado" : "ativado"}.`)}
                  >
                    {user.enabled ? "Desativar" : "Ativar"}
                  </button>
                  <button
                    type="button"
                    className="btn btn-small"
                    onClick={() => {
                      const roles = ["ADVOGADO", "REVISOR", "PROTOCOLADOR", "ADMIN"];
                      const next = roles[(roles.indexOf(user.role) + 1) % roles.length];
                      patch(user, { role: next }, `Perfil de "${user.username}" alterado para ${next}.`);
                    }}
                  >
                    Trocar perfil
                  </button>
                  <button
                    type="button"
                    className="btn btn-small"
                    onClick={() => setResetUser(user)}
                  >
                    Resetar senha
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {resetUser && <PasswordModal title="Redefinir senha" username={resetUser.username} onClose={() => setResetUser(null)} onSubmit={async (_current, password) => {
        try { await updateUser(resetUser.id, { password }); setNotice(`Senha de "${resetUser.username}" alterada.`); }
        catch (error) { throw new Error(apiErrorMessage(error)); }
      }} />}
    </section>
  );
}
