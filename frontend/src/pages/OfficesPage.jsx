import {
  createOffice,
  createOfficeAdmin,
  deleteOffice,
  listOffices,
  updateOffice,
} from "../api/ged";
import { apiErrorMessage } from "../api/client";
import { useEffect, useState } from "react";
import { PasswordModal } from "../components/PasswordModal";

export function OfficesPage() {
  const [offices, setOffices] = useState([]);
  const [name, setName] = useState("");
  const [adminOfficeId, setAdminOfficeId] = useState("");
  const [adminUser, setAdminUser] = useState("");
  const [adminPassword, setAdminPassword] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    loadOffices();
  }, []);

  async function loadOffices() {
    try {
      setOffices(await listOffices());
    } catch (listError) {
      setError(apiErrorMessage(listError));
    }
  }

  async function handleCreate(event) {
    event.preventDefault();
    try {
      const created = await createOffice(name.trim());
      setNotice(`Escritorio "${created.name}" criado.`);
      setName("");
      await loadOffices();
    } catch (createError) {
      setError(apiErrorMessage(createError));
    }
  }

  async function createAdmin(event) {
    event.preventDefault();
    const office = offices.find((item) => item.id === Number(adminOfficeId));
    if (!office || !adminUser.trim() || !adminPassword) return;
    try {
      const created = await createOfficeAdmin(office.id, adminUser.trim(), adminPassword);
      setNotice(`ADMIN "${created.username}" criado em "${office.name}".`);
      setAdminUser("");
      setAdminPassword("");
    } catch (adminError) {
      setError(apiErrorMessage(adminError));
    }
  }

  async function patch(office, payload, message) {
    try {
      const updated = await updateOffice(office.id, payload);
      setOffices((current) => current.map((item) => (item.id === updated.id ? updated : item)));
      setNotice(message);
    } catch (patchError) {
      setError(apiErrorMessage(patchError));
    }
  }

  async function handleDelete(office) {
    if (!window.confirm(`Excluir o escritorio "${office.name}"? Pastas, projetos e usuarios do escritorio ficam inacessiveis.`)) {
      return;
    }
    try {
      await deleteOffice(office.id);
      setNotice(`Escritorio "${office.name}" excluido.`);
      await loadOffices();
    } catch (deleteError) {
      setError(apiErrorMessage(deleteError));
    }
  }

  return (
    <section className="main-panel wide">
      <p className="eyebrow">Administracao global</p>
      <h1>Escritórios</h1>
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
        <h2>Novo escritorio</h2>
        <form className="inline-form" onSubmit={handleCreate}>
          <input
            placeholder="Nome do escritorio"
            value={name}
            onChange={(event) => setName(event.target.value)}
            required
            minLength={2}
            maxLength={120}
          />
          <button type="submit" className="btn btn-primary">Criar</button>
        </form>
      </div>

      <div className="panel-card">
        <h2>ADMIN do escritorio</h2>
        <form className="inline-form" onSubmit={createAdmin}>
          <select
            value={adminOfficeId}
            onChange={(event) => setAdminOfficeId(event.target.value)}
            required
          >
            <option value="">Selecione o escritorio</option>
            {offices.map((office) => (
              <option key={office.id} value={office.id}>{office.name}</option>
            ))}
          </select>
          <input
            placeholder="Usuario"
            value={adminUser}
            onChange={(event) => setAdminUser(event.target.value)}
            required
            minLength={3}
          />
          <input
            type="password"
            placeholder="Senha (min. 6)"
            value={adminPassword}
            onChange={(event) => setAdminPassword(event.target.value)}
            required
            minLength={6}
          />
          <button type="submit" className="btn btn-primary">Criar ADMIN</button>
        </form>
      </div>

      <div className="panel-card">
        <h2>Escritórios</h2>
        <table className="data-table">
          <thead>
            <tr>
              <th>Escritorio</th>
              <th>Status</th>
              <th>Acoes</th>
            </tr>
          </thead>
          <tbody>
            {offices.map((office) => (
              <tr key={office.id}>
                <td>{office.name}</td>
                <td>{office.enabled ? "ativo" : "inativo"}</td>
                <td className="row-actions">
                  <button
                    type="button"
                    className="btn btn-small"
                    onClick={() => patch(office, { enabled: !office.enabled },
                      `Escritorio "${office.name}" ${office.enabled ? "inativado" : "ativado"}.`)}
                  >
                    {office.enabled ? "Inativar" : "Ativar"}
                  </button>
                  <button type="button" className="btn btn-small" onClick={() => handleDelete(office)}>
                    Excluir
                  </button>
                </td>
              </tr>
            ))}
            {offices.length === 0 && (
              <tr>
                <td colSpan={3} className="small">Nenhum escritorio cadastrado.</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}
