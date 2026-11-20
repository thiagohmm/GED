import { useEffect, useRef, useState } from "react";
import { documentDetail, editorConfig } from "../api/ged";
import { apiErrorMessage } from "../api/client";

const RETRY_INTERVAL_MS = 5000;
const MAX_RETRIES = 36;
let loadedDocsApiOrigin = null;

function buildSafeDocsApiUrl(documentServerUrl) {
  let parsed;
  try {
    parsed = new URL(documentServerUrl, window.location.origin);
  } catch {
    throw new Error("url-invalida");
  }
  if ((parsed.protocol !== "http:" && parsed.protocol !== "https:") || parsed.username || parsed.password) {
    throw new Error("url-invalida");
  }
  const base = `${parsed.origin}${parsed.pathname.replace(/\/$/, "")}`;
  return { origin: parsed.origin, scriptUrl: `${base}/web-apps/apps/api/documents/api.js` };
}

export function OnlyOfficeEditor({ documentId, currentVersion, mode = "view", onSaved, onError }) {
  const placeholderId = `onlyoffice-${mode}-${documentId}`;
  const editor = useRef(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [failed, setFailed] = useState(false);
  const [status, setStatus] = useState("Preparando documento...");

  useEffect(() => {
    let disposed = false;
    let poll;
    let pollStop;
    let script;

    function removeScript() {
      if (script?.parentNode) {
        script.parentNode.removeChild(script);
      }
      script = null;
    }

    async function loadDocsApi(origin, scriptUrl) {
      if (window.DocsAPI && loadedDocsApiOrigin === origin) {
        return;
      }
      removeScript();
      script = document.createElement("script");
      script.src = scriptUrl;
      script.async = true;
      document.head.appendChild(script);
      await new Promise((resolve, reject) => {
        script.addEventListener("load", () => {
          loadedDocsApiOrigin = origin;
          resolve();
        }, { once: true });
        script.addEventListener("error", () => reject(new Error("script-indisponivel")), { once: true });
      });
    }

    const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

    async function start(attempt = 0) {
      try {
        const config = await editorConfig(documentId, mode);
        if (disposed) return;
        setStatus("Conectando ao editor integrado...");
        const { origin, scriptUrl } = buildSafeDocsApiUrl(config.documentServerUrl);
        try {
          await loadDocsApi(origin, scriptUrl);
        } catch {
          if (attempt < MAX_RETRIES) {
            if (!disposed) {
              setStatus(`Aguardando o servidor de documentos iniciar (tentativa ${attempt + 1} de ${MAX_RETRIES})...`);
            }
            await sleep(RETRY_INTERVAL_MS);
            if (!disposed) await start(attempt + 1);
            return;
          }
          throw new Error("servidor-indisponivel");
        }
        if (disposed) return;
        editor.current = new window.DocsAPI.DocEditor(placeholderId, {
          ...config.config,
          events: {
            onDocumentReady: () => {
              if (!disposed) {
                setStatus(mode === "edit"
                  ? "Editor pronto. Ao fechar, as alterações serão salvas como uma nova versão."
                  : "Documento pronto.");
              }
            },
            onError: (event) => {
              if (!disposed) {
                setStatus(`O editor reportou um erro${event?.data ? ` (${event.data})` : ""}.`);
              }
            }
          }
        });
        if (mode === "edit") {
          poll = window.setInterval(async () => {
            try {
              const latest = await documentDetail(documentId);
              if (disposed) return;
              if (latest.currentVersion > currentVersion) {
                window.clearInterval(poll);
                setStatus("Alterações salvas em uma nova versão.");
                onSaved?.();
              }
            } catch { /* Mantém o pool enquanto a sessão do editor existir. */ }
          }, 4000);
          pollStop = window.setTimeout(() => window.clearInterval(poll), 10 * 60 * 1000);
        }
      } catch (error) {
        if (disposed) return;
        setFailed(true);
        if (String(error?.message).includes("servidor-indisponivel")) {
          setStatus("Servidor de documentos indisponível.");
          onError?.("Verifique se o servidor ONLYOFFICE está disponível.");
        } else if (String(error?.message).includes("url-invalida")) {
          setStatus("URL do servidor de documentos inválida.");
          onError?.("Configuração inválida do servidor ONLYOFFICE.");
        } else {
          setStatus("Não foi possível abrir o editor integrado.");
          onError?.(apiErrorMessage(error) || "Verifique se o servidor ONLYOFFICE está disponível.");
        }
      }
    }

    start();
    return () => {
      disposed = true;
      window.clearInterval(poll);
      window.clearTimeout(pollStop);
      editor.current?.destroyEditor?.();
      editor.current = null;
      removeScript();
    };
  }, [documentId, currentVersion, mode, reloadKey, placeholderId, onSaved, onError]);

  function retry() {
    setFailed(false);
    setStatus("Preparando documento...");
    setReloadKey((key) => key + 1);
  }

  return (
    <div className="editor-shell">
      {failed ? (
        <div className="editor-fallback">
          <p>{status}</p>
          <button type="button" className="btn" onClick={retry}>Tentar novamente</button>
        </div>
      ) : (
        <div className="onlyoffice-frame">
          <div id={placeholderId} className="onlyoffice-slot" />
        </div>
      )}
      <p className="muted small editor-status">{status}</p>
    </div>
  );
}
