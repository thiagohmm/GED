import { useState } from "react";
import { FolderIcon, GearIcon, PencilIcon, TrashIcon } from "./icons";

export const DOC_MIME = "application/x-ged-documents";
export const FOLDER_MIME = "application/x-ged-folder";

export function FolderTree({ folders, selectedId, onSelect, onRename, onDelete, onAssignRoot, onMoveDocuments, onMoveFolder, canManageRoots }) {
  return (
    <aside className="sidebar">
      <div className="sidebar-head">
        <p className="eyebrow">Arquivo</p>
        <strong>Pastas</strong>
      </div>
      {folders.length === 0 && <p className="tree-empty">Nenhuma pasta criada ainda.</p>}
      <ul className="tree">
        {folders.map((folder) => (
          <TreeItem
            key={folder.id}
            folder={folder}
            selectedId={selectedId}
            onSelect={onSelect}
            onRename={onRename}
            onDelete={onDelete}
            onAssignRoot={onAssignRoot}
            onMoveDocuments={onMoveDocuments}
            onMoveFolder={onMoveFolder}
            canManageRoots={canManageRoots}
          />
        ))}
      </ul>
    </aside>
  );
}

function TreeItem({ folder, selectedId, onSelect, onRename, onDelete, onAssignRoot, onMoveDocuments, onMoveFolder, canManageRoots, level = 0 }) {
  const isSelected = selectedId === folder.id;
  const [dropActive, setDropActive] = useState(false);

  function acceptsDrop(event) {
    return [...event.dataTransfer.types].some((type) => type === DOC_MIME || type === FOLDER_MIME);
  }

  function handleDragStart(event) {
    event.stopPropagation();
    event.dataTransfer.setData(FOLDER_MIME, String(folder.id));
    event.dataTransfer.effectAllowed = "move";
  }

  function handleDragOver(event) {
    if (!acceptsDrop(event)) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    event.dataTransfer.dropEffect = "move";
    setDropActive(true);
  }

  function handleDragLeave(event) {
    if (event.currentTarget.contains(event.relatedTarget)) {
      return;
    }
    setDropActive(false);
  }

  function handleDrop(event) {
    if (!acceptsDrop(event)) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    setDropActive(false);
    const docsData = event.dataTransfer.getData(DOC_MIME);
    const folderData = event.dataTransfer.getData(FOLDER_MIME);
    if (docsData) {
      try {
        const ids = JSON.parse(docsData);
        if (Array.isArray(ids) && ids.length > 0) {
          onMoveDocuments(ids, folder.id);
        }
      } catch { /* payload de drop invalido: ignora */ }
      return;
    }
    if (folderData) {
      const draggedId = Number(folderData);
      if (draggedId && draggedId !== folder.id) {
        onMoveFolder(draggedId, folder.id);
      }
    }
  }

  return (
    <li>
      <div
        className={`tree-item ${isSelected ? "selected" : ""} ${dropActive ? "drop-target" : ""}`}
        style={{ paddingLeft: level * 16 + 8 }}
        draggable
        onDragStart={handleDragStart}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
      >
        <button type="button" className="tree-name" onClick={() => onSelect(folder)} title="Arraste para mover esta pasta para dentro de outra pasta.">
          <FolderIcon />
          {folder.name}
        </button>
        {level === 0 && <span className="tree-project"><strong>{folder.projectName || "Projeto pendente"}</strong><small>{folder.assignedAttorneyUsername || "Sem advogado"}</small></span>}
        <span className="tree-actions">
          {level === 0 && canManageRoots && <button type="button" title="Projeto e advogado" onClick={() => onAssignRoot(folder)}><GearIcon /></button>}
          <button type="button" title="Renomear" onClick={() => onRename(folder)}><PencilIcon /></button>
          <button type="button" title="Excluir" onClick={() => onDelete(folder)}><TrashIcon /></button>
        </span>
      </div>
      {folder.children.length > 0 && (
        <ul className="tree">
          {folder.children.map((child) => (
            <TreeItem
              key={child.id}
              folder={child}
              selectedId={selectedId}
              onSelect={onSelect}
              onRename={onRename}
              onDelete={onDelete}
              onAssignRoot={onAssignRoot}
              onMoveDocuments={onMoveDocuments}
              onMoveFolder={onMoveFolder}
              canManageRoots={canManageRoots}
              level={level + 1}
            />
          ))}
        </ul>
      )}
    </li>
  );
}
