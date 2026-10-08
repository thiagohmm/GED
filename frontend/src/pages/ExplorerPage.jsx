import { assignRootFolder, createFolder, createProject,
  deleteDocument,
  deleteFolder,
  documentsByFolder,
  folderTree,
  listProjects,
  listUsers,
  moveDocument,
  moveFolder,
  renameFolder,
  uploadDocument
} from "../api/ged";
import { apiErrorMessage } from "../api/client";
import { kindLabel, statusLabel } from "./model";
import { DOC_MIME, FolderTree } from "../components/FolderTree";
import { useAuth } from "../auth/AuthContext";
import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";

export function ExplorerPage() {
  const { user } = useAuth();
  const canManageRoots = user?.role === "ADMIN" || user?.role === "PROTOCOLADOR";
  const [tree, setTree] = useState([]);
  const [selectedFolder, setSelectedFolder] = useState(null);
  const [documents, setDocuments] = useState([]);
  const [path, setPath] = useState([]);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [newFolderName, setNewFolderName] = useState("");
  const [subfolderName, setSubfolderName] = useState("");
  const [file, setFile] = useState(null);
  const [changeDescription, setChangeDescription] = useState("");
  const [fileInputKey, setFileInputKey] = useState(0);
  const [busy, setBusy] = useState(false);
  const [statusFilter, setStatusFilter] = useState("");
  const [projects, setProjects] = useState([]);
  const [attorneys, setAttorneys] = useState([]);
  const [projectId, setProjectId] = useState("");
  const [attorneyId, setAttorneyId] = useState("");
  const [projectName, setProjectName] = useState("");
  const [assignmentFolder, setAssignmentFolder] = useState(null);
  const [selectedIds, setSelectedIds] = useState(() => new Set());
  const [moveTargetId, setMoveTargetId] = useState("");
  const [moveModalOpen, setMoveModalOpen] = useState(false);

  const loadTree = useCallback(async () => {
    try {
      setTree(await folderTree());
    } catch (loadError) {
      setError(apiErrorMessage(loadError));
    }
  }, []);

  const loadDocuments = useCallback(async (folderId) => {
    if (!folderId) {
      setDocuments([]);
      return;
    }
    try {
      setDocuments(await documentsByFolder(folderId));
    } catch (loadError) {
      setError(apiErrorMessage(loadError));
    }
  }, []);

  useEffect(() => {
    loadTree();
  }, [loadTree]);

  useEffect(() => {
    if (!canManageRoots) return;
    Promise.all([listProjects(), listUsers()]).then(([allProjects, users]) => {
      setProjects(allProjects);
      setAttorneys(users.filter((item) => item.role === "ADVOGADO" && item.enabled));
      if (allProjects.length) setProjectId(String(allProjects[0].id));
      const firstAttorney = users.find((item) => item.role === "ADVOGADO" && item.enabled);
      if (firstAttorney) setAttorneyId(String(firstAttorney.id));
    }).catch((loadError) => setError(apiErrorMessage(loadError)));
  }, [canManageRoots]);

  function handleSelect(folder) {
    setSelectedFolder(folder);
    setSelectedIds(new Set());
    setPath(pathOf(tree, folder.id));
    loadDocuments(folder.id);
  }

  async function handleRename(folder) {
    const name = window.prompt("Novo nome da pasta:", folder.name);
    if (!name || !name.trim()) {
      return;
    }
    try {
      await renameFolder(folder.id, name.trim());
      setNotice("Pasta renomeada.");
      await loadTree();
      if (selectedFolder?.id === folder.id) {
        setSelectedFolder({ ...selectedFolder, name: name.trim() });
      }
    } catch (renameError) {
      setError(apiErrorMessage(renameError));
    }
  }

  async function handleDeleteFolder(folder) {
    if (!window.confirm(`Excluir a pasta "${folder.name}"? A pasta precisa estar vazia.`)) {
      return;
    }
    try {
      await deleteFolder(folder.id);
      if (selectedFolder?.id === folder.id) {
        setSelectedFolder(null);
      }
      setNotice("Pasta excluida.");
      await loadTree();
    } catch (deleteError) {
      setError(apiErrorMessage(deleteError));
    }
  }

  async function handleCreateFolder(inSubfolder) {
    const name = (inSubfolder ? subfolderName : newFolderName).trim();
    if ((inSubfolder && !selectedFolder) || !name) {
      return;
    }
    try {
      await createFolder(name, inSubfolder ? selectedFolder.id : null,
        inSubfolder ? null : Number(projectId), inSubfolder ? null : Number(attorneyId));
      if (inSubfolder) {
        setSubfolderName("");
      } else {
        setNewFolderName("");
      }
      setNotice(inSubfolder
        ? `Subpasta "${name}" criada em "${selectedFolder.name}".`
        : `Pasta "${name}" criada na raiz.`);
      await loadTree();
    } catch (createError) {
      setError(apiErrorMessage(createError));
    }
  }

  async function handleCreateProject() {
    if (!projectName.trim()) return;
    try {
      const created = await createProject(projectName.trim());
      setProjects((items) => [...items, created].sort((a, b) => a.name.localeCompare(b.name)));
      setProjectId(String(created.id));
      setProjectName("");
    } catch (projectError) {
      setError(apiErrorMessage(projectError));
    }
  }

  async function handleAssignRoot(event) {
    event.preventDefault();
    try {
      await assignRootFolder(assignmentFolder.id, Number(projectId), Number(attorneyId));
      setAssignmentFolder(null);
      await loadTree();
    } catch (assignmentError) {
      setError(apiErrorMessage(assignmentError));
    }
  }

  async function moveDocumentsTo(ids, targetFolderId, targetFolderName) {
    if (!ids.length || !targetFolderId) {
      return;
    }
    let moved = 0;
    const failures = new Map();
    for (const id of ids) {
      try {
        await moveDocument(id, Number(targetFolderId));
        moved += 1;
      } catch (moveError) {
        const message = apiErrorMessage(moveError);
        failures.set(message, (failures.get(message) ?? 0) + 1);
      }
    }
    setError("");
    setNotice("");
    await loadDocuments(selectedFolder?.id);
    setSelectedIds(new Set());
    const failed = ids.length - moved;
    if (failed === 0) {
      setNotice(`${moved} documento(s) movido(s) para "${targetFolderName ?? "pasta destino"}.`);
    } else {
      const reasons = [...failures.entries()].map(([reason, count]) => `${count}x ${reason}`);
      setError(`${moved} movido(s); ${failed} falharam: ${reasons.join("; ")}`);
    }
  }

  async function handleDragMoveDocuments(ids, targetFolderId) {
    if (Number(targetFolderId) === selectedFolder?.id) {
      return;
    }
    await moveDocumentsTo(ids, targetFolderId, folderLabel(tree, selectedFolder?.id, Number(targetFolderId)));
  }

  async function handleDragMoveFolder(draggedFolderId, targetFolderId) {
    const draggedId = Number(draggedFolderId);
    const targetId = Number(targetFolderId);
    if (draggedId === targetId) {
      return;
    }
    const flat = flattenFolders(tree);
    const dragged = flat.find((item) => item.id === draggedId);
    const target = flat.find((item) => item.id === targetId);
    if (!dragged || !target) {
      return;
    }
    if (dragged.parentId === targetId) {
      setNotice(`Pasta "${dragged.name}" já está em "${target.name}".`);
      return;
    }
    if (targetLabelRunsThrough(flat, targetId, draggedId)) {
      setError(`Nao é possível mover "${dragged.name}" para dentro de "${target.name}": a pasta destino está dentro dela.`);
      return;
    }
    try {
      setError("");
      await moveFolder(draggedId, targetId);
      const fresh = await folderTree();
      setTree(fresh);
      if (selectedFolder) setPath(pathOf(fresh, selectedFolder.id));
      setNotice(`Pasta "${dragged.name}" movida para "${target.name}".`);
    } catch (moveError) {
      setError(apiErrorMessage(moveError));
    }
  }

  async function confirmMoveDocuments(event) {
    event.preventDefault();
    const ids = [...selectedIds];
    const target = moveFoldersOptions().find((item) => item.id === Number(moveTargetId));
    setMoveTargetId("");
    setMoveModalOpen(false);
    await moveDocumentsTo(ids, target?.id, target?.label?.split(" / ").pop());
  }

  function openMoveModal() {
    const options = moveFoldersOptions();
    if (options.length === 0) {
      setError("Crie outra pasta (ou subpasta) para mover os arquivos.");
      return;
    }
    setMoveTargetId(String(options[0].id));
    setMoveModalOpen(true);
  }

  function moveFoldersOptions() {
    const currentId = selectedFolder?.id ?? null;
    return flattenFolders(tree).filter((item) => item.id !== currentId);
  }

  function toggleDocumentSelection(documentId) {
    setSelectedIds((current) => {
      const next = new Set(current);
      if (next.has(documentId)) {
        next.delete(documentId);
      } else {
        next.add(documentId);
      }
      return next;
    });
  }

  function toggleAllFilteredSelection(checked) {
    setSelectedIds((current) => {
      const next = new Set(checked ? filteredDocuments(documents, statusFilter).map((item) => item.id) : []);
      return next;
    });
  }

  function rowDragStart(event, documentItem) {
    const ids = selectedIds.has(documentItem.id) ? [...selectedIds] : [documentItem.id];
    event.dataTransfer.setData(DOC_MIME, JSON.stringify(ids));
    event.dataTransfer.effectAllowed = "move";
    event.currentTarget.classList.add("dragging");
  }

  function rowDragEnd(event) {
    event.currentTarget.classList.remove("dragging");
  }

  async function handleUpload(event) {
    event.preventDefault();
    if (!selectedFolder || !file) {
      return;
    }
    const existing = documents.find((item) => item.name.toLocaleLowerCase() === file.name.toLocaleLowerCase());
    if (existing && existing.status !== "EDITANDO") {
      setError("Este documento não está em edição. Reabra o fluxo antes de enviar uma nova versão.");
      return;
    }
    setBusy(true);
    try {
      const stored = await uploadDocument(selectedFolder.id, file, changeDescription);
      setNotice(
        `"${stored.name}" armazenado como versao ${stored.currentVersion}` +
        (stored.currentVersion > 1 ? " (alteracoes calculadas)." : ".")
      );
      setFile(null);
      setChangeDescription("");
      setFileInputKey((key) => key + 1);
      await loadDocuments(selectedFolder.id);
    } catch (uploadError) {
      setError(apiErrorMessage(uploadError));
    } finally {
      setBusy(false);
    }
  }

  async function handleDeleteDocument(documentItem) {
    if (!window.confirm(`Excluir "${documentItem.name}" e todas as versoes?`)) {
      return;
    }
    try {
      await deleteDocument(documentItem.id);
      setNotice(`"${documentItem.name}" excluido.`);
      await loadDocuments(selectedFolder.id);
    } catch (deleteError) {
      setError(apiErrorMessage(deleteError));
    }
  }

  return (
    <div className="explorer">
      <FolderTree
        folders={tree}
        selectedId={selectedFolder?.id ?? null}
        onSelect={handleSelect}
        onRename={handleRename}
        onDelete={handleDeleteFolder}
        onAssignRoot={(folder) => {
          setAssignmentFolder(folder);
          setProjectId(folder.projectId ? String(folder.projectId) : "");
          setAttorneyId(folder.assignedAttorneyId ? String(folder.assignedAttorneyId) : "");
        }}
        onMoveDocuments={handleDragMoveDocuments}
        onMoveFolder={handleDragMoveFolder}
        canManageRoots={canManageRoots}
      />
      <section className="main-panel">
        <header className="page-header">
          <div>
            <p className="eyebrow">Acervo</p>
            <h1>{selectedFolder ? selectedFolder.name : "Documentos"}</h1>
            <p className="muted small">
              {selectedFolder && path.length > 0 ? path.join(" / ") : "Selecione uma pasta para ver e enviar arquivos."}
            </p>
          </div>
        </header>
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
          <h2>Pastas</h2>
          {canManageRoots && <div className="root-folder-form">
            <strong>Nova pasta de projeto na raiz</strong>
            <input placeholder="Nome da pasta raiz" value={newFolderName} onChange={(event) => setNewFolderName(event.target.value)} />
            <select aria-label="Projeto" value={projectId} onChange={(event) => setProjectId(event.target.value)}>
              <option value="">Selecione o projeto</option>
              {projects.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}
            </select>
            <select aria-label="Advogado responsável" value={attorneyId} onChange={(event) => setAttorneyId(event.target.value)}>
              <option value="">Selecione o advogado</option>
              {attorneys.map((attorney) => <option key={attorney.id} value={attorney.id}>{attorney.username}</option>)}
            </select>
            <button type="button" className="btn btn-primary" disabled={!newFolderName.trim() || !projectId || !attorneyId} onClick={() => handleCreateFolder(false)}>Criar pasta raiz</button>
            <div className="inline-form">
              <input placeholder="Cadastrar projeto" value={projectName} onChange={(event) => setProjectName(event.target.value)} />
              <button type="button" className="btn" disabled={!projectName.trim()} onClick={handleCreateProject}>Adicionar projeto</button>
            </div>
          </div>}
          <div className="inline-form">
            <input
              placeholder={selectedFolder ? `Nova subpasta em "${selectedFolder.name}"` : "Selecione uma pasta na árvore"}
              value={subfolderName}
              disabled={!selectedFolder}
              onChange={(event) => setSubfolderName(event.target.value)}
              onKeyDown={(event) => event.key === "Enter" && handleCreateFolder(true)}
              aria-label="Nome da subpasta"
            />
            <button
              type="button"
              className={selectedFolder && subfolderName.trim() ? "btn btn-primary" : "btn"}
              disabled={!selectedFolder || !subfolderName.trim()}
              onClick={() => handleCreateFolder(true)}
              title={selectedFolder ? `Criar dentro de "${selectedFolder.name}"` : "Selecione uma pasta na árvore"}
            >
              Criar subpasta
            </button>
            {!canManageRoots && <span className="muted small">Pastas raiz são criadas pela administração; subpastas você cria aqui.</span>}
          </div>
        </div>

        {assignmentFolder && <div className="modal-backdrop" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && setAssignmentFolder(null)}>
          <form className="modal-card" onSubmit={handleAssignRoot}>
            <button type="button" className="modal-close" onClick={() => setAssignmentFolder(null)} aria-label="Fechar">×</button>
            <p className="eyebrow">Pasta raiz</p><h2>Projeto e responsável</h2>
            <p className="muted">{assignmentFolder.name}</p>
            <label>Projeto<select required value={projectId} onChange={(event) => setProjectId(event.target.value)}><option value="">Selecione</option>{projects.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}</select></label>
            <label>Advogado<select required value={attorneyId} onChange={(event) => setAttorneyId(event.target.value)}><option value="">Selecione</option>{attorneys.map((attorney) => <option key={attorney.id} value={attorney.id}>{attorney.username}</option>)}</select></label>
            <div className="modal-actions"><button type="button" className="btn" onClick={() => setAssignmentFolder(null)}>Cancelar</button><button className="btn btn-primary">Salvar associação</button></div>
          </form>
        </div>}

        {moveModalOpen && (
          <div className="modal-backdrop" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && setMoveModalOpen(false)}>
            <form className="modal-card" onSubmit={confirmMoveDocuments}>
              <button type="button" className="modal-close" onClick={() => setMoveModalOpen(false)} aria-label="Fechar">×</button>
              <p className="eyebrow">Mover</p>
              <h2>Mover {selectedIds.size} documento(s)</h2>
              <label>
                Pasta destino
                <select required value={moveTargetId} onChange={(event) => setMoveTargetId(event.target.value)}>
                  <option value="">Selecione a pasta destino</option>
                  {moveFoldersOptions().map((option) => <option key={option.id} value={option.id}>{option.label}</option>)}
                </select>
              </label>
              <div className="modal-actions">
                <button type="button" className="btn" onClick={() => setMoveModalOpen(false)}>Cancelar</button>
                <button type="submit" className="btn btn-primary">Mover</button>
              </div>
            </form>
          </div>
        )}

        <div className="panel-card">
          <h2>Enviar arquivo para a pasta selecionada</h2>
          <form className="inline-form" onSubmit={handleUpload}>
            <input
              key={fileInputKey}
              type="file"
              accept=".docx,.xlsx,.pdf,.png,.jpg,.jpeg"
              onChange={(event) => setFile(event.target.files[0] ?? null)}
            />
            <input
              placeholder="Descricao da alteracao (opcional)"
              value={changeDescription}
              onChange={(event) => setChangeDescription(event.target.value)}
            />
            <button
              type="submit"
              className="btn btn-primary"
              disabled={busy || !selectedFolder || !file}
              title={selectedFolder ? "" : "Selecione uma pasta antes de enviar"}
            >
              {busy ? "Enviando..." : "Enviar"}
            </button>
          </form>
          <p className="muted small">
            Word, Excel e PDF aceitam versões com o mesmo nome. Imagens ficam disponíveis somente para visualização.
          </p>
        </div>

        <div className="panel-card">
          <h2>Documentos</h2>
          <div className="inline-form" style={{ marginBottom: 10 }}>
            <label className="small">
              Status
              <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
                <option value="">Todos</option>
                <option value="EDITANDO">{statusLabel("EDITANDO")}</option>
                <option value="EM_REVISAO">{statusLabel("EM_REVISAO")}</option>
                <option value="PRONTO_PARA_PROTOCOLO">{statusLabel("PRONTO_PARA_PROTOCOLO")}</option>
                <option value="PROTOCOLADO">{statusLabel("PROTOCOLADO")}</option>
              </select>
            </label>
            {selectedIds.size > 0 && (
              <span className="selection-bar">
                <span>{selectedIds.size} selecionado(s)</span>
                <button type="button" className="btn btn-small btn-primary" onClick={openMoveModal}>Mover</button>
                <button type="button" className="btn btn-ghost btn-small" onClick={() => setSelectedIds(new Set())}>Limpar</button>
              </span>
            )}
          </div>
          {!selectedFolder ? (
            <p className="muted">Selecione uma pasta na árvore à esquerda.</p>
          ) : filteredDocuments(documents, statusFilter).length === 0 ? (
            <p className="muted">{documents.length === 0 ? "Nenhum documento nesta pasta." : "Nenhum documento com esse status."}</p>
          ) : (
            <table className="data-table">
              <thead>
                <tr>
                  <th className="check-col">
                    <input
                      type="checkbox"
                      aria-label="Selecionar todos"
                      checked={filteredDocuments(documents, statusFilter).length > 0
                        && filteredDocuments(documents, statusFilter).every((item) => selectedIds.has(item.id))}
                      onChange={(event) => toggleAllFilteredSelection(event.target.checked)}
                    />
                  </th>
                  <th>Nome</th>
                  <th>Tipo</th>
                  <th>Status</th>
                  <th>Versão atual</th>
                  <th>Ações</th>
                </tr>
              </thead>
              <tbody>
                {filteredDocuments(documents, statusFilter).map((documentItem) => (
                  <tr
                    key={documentItem.id}
                    draggable
                    title="Arraste para uma pasta na árvore para mover"
                    onDragStart={(event) => rowDragStart(event, documentItem)}
                    onDragEnd={rowDragEnd}
                  >
                    <td className="check-col">
                      <input
                        type="checkbox"
                        checked={selectedIds.has(documentItem.id)}
                        onChange={() => toggleDocumentSelection(documentItem.id)}
                        aria-label={`Selecionar ${documentItem.name}`}
                      />
                    </td>
                    <td><Link to={`/documents/${documentItem.id}`}>{documentItem.name}</Link></td>
                    <td>
                      <span className={`badge badge-${documentItem.kind.toLowerCase()}`}>
                        {kindLabel(documentItem.kind)}
                      </span>
                    </td>
                    <td>
                      <span className={`badge ${String(documentItem.status).toLowerCase()}`}>
                        {statusLabel(documentItem.status)}
                      </span>
                    </td>
                    <td>v{documentItem.currentVersion}</td>
                    <td className="row-actions">
                      <Link className="btn btn-small" to={`/documents/${documentItem.id}`}>
                        Versões e revisões
                      </Link>
                      <button type="button" className="btn btn-small btn-danger" onClick={() => handleDeleteDocument(documentItem)}>
                        Excluir
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </section>
    </div>
  );
}

function filteredDocuments(documents, statusFilter) {
  if (!statusFilter) {
    return documents;
  }
  return documents.filter((item) => item.status === statusFilter);
}

function flattenFolders(folders, ancestors = [], output = []) {
  for (const folder of folders) {
    const label = [...ancestors, folder.name].join(" / ");
    output.push({ id: folder.id, name: folder.name, parentId: folder.parentId, label });
    flattenFolders(folder.children, [...ancestors, folder.name], output);
  }
  return output;
}

function folderLabel(tree, currentFolderId, targetId) {
  if (Number(targetId) === currentFolderId) {
    return null;
  }
  const found = flattenFolders(tree).find((item) => item.id === Number(targetId));
  return found ? found.name : null;
}

function targetLabelRunsThrough(foldersFlat, targetId, ancestorId) {
  const byId = new Map(foldersFlat.map((item) => [item.id, item]));
  for (let step = byId.get(targetId); step; step = byId.get(step.parentId)) {
    if (step.id === ancestorId) {
      return true;
    }
  }
  return false;
}

function pathOf(tree, targetId, ancestors = []) {
  for (const folder of tree) {
    if (folder.id === targetId) {
      return [...ancestors, folder.name];
    }
    const inside = pathOf(folder.children, targetId, [...ancestors, folder.name]);
    if (inside.length > 0) {
      return inside;
    }
  }
  return [];
}
