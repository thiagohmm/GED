import { searchDocuments, searchFilterOptions } from "../api/ged";
import { apiErrorMessage } from "../api/client";
import { kindLabel, statusLabel } from "./model";
import { useCallback, useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";

export function SearchPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const query = searchParams.get("q") ?? "";
  const [input, setInput] = useState(query);
  const [results, setResults] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [filterOptions, setFilterOptions] = useState({ attorneys: [], reviewers: [] });
  const [filters, setFilters] = useState({ createdFrom: "", createdTo: "", attorneyId: "", reviewerId: "", tag: "" });

  useEffect(() => {
    searchFilterOptions().then(setFilterOptions).catch(() => {});
  }, []);

  const runSearch = useCallback(async (term) => {
    if (!term.trim()) {
      return;
    }
    setLoading(true);
    setError("");
    try {
      setResults(await searchDocuments(term.trim(), Object.fromEntries(
        Object.entries(filters).filter(([, value]) => value)
      )));
    } catch (searchError) {
      setError(apiErrorMessage(searchError));
      setResults([]);
    } finally {
      setLoading(false);
    }
  }, [filters]);

  useEffect(() => {
    runSearch(query);
  }, [query, runSearch]);

  useEffect(() => {
    setInput(query);
  }, [query]);

  function submitSearch(event) {
    event.preventDefault();
    setSearchParams(input.trim() ? { q: input.trim() } : {});
  }

  return (
    <section className="main-panel wide">
      <p className="eyebrow">Pesquisa</p>
      <h1>Busca por texto</h1>
      <p className="muted small">
        Pesquisa o conteudo de documentos Word, Excel e PDF (e o nome/caminho de qualquer arquivo).
      </p>
      <div className="panel-card">
        <form className="inline-form" onSubmit={submitSearch}>
          <input
            className="search-input"
            placeholder="Ex.: clausula de rescisao"
            value={input}
            onChange={(event) => setInput(event.target.value)}
          />
          <button type="submit" className="btn btn-primary" disabled={!input.trim() || loading}>
            {loading ? "Buscando..." : "Buscar"}
          </button>
        </form>
        <div className="search-filters" aria-label="Filtros da busca">
          <label>Documento criado de<input type="date" value={filters.createdFrom} onChange={(event) => setFilters({ ...filters, createdFrom: event.target.value })} /></label>
          <label>Até<input type="date" value={filters.createdTo} onChange={(event) => setFilters({ ...filters, createdTo: event.target.value })} /></label>
          <label>Advogado responsável<select value={filters.attorneyId} onChange={(event) => setFilters({ ...filters, attorneyId: event.target.value })}><option value="">Todos</option>{filterOptions.attorneys.map((item) => <option key={item.id} value={item.id}>{item.username}</option>)}</select></label>
          <label>Participante da revisão<select value={filters.reviewerId} onChange={(event) => setFilters({ ...filters, reviewerId: event.target.value })}><option value="">Todos</option>{filterOptions.reviewers.map((item) => <option key={item.id} value={item.id}>{item.username}</option>)}</select></label>
          <label>Tag<input placeholder="Ex.: contrato" value={filters.tag} onChange={(event) => setFilters({ ...filters, tag: event.target.value })} /></label>
          <button type="button" className="btn" onClick={() => setFilters({ createdFrom: "", createdTo: "", attorneyId: "", reviewerId: "", tag: "" })}>Limpar filtros</button>
        </div>
      </div>
      {error && <div className="error-banner">{error}</div>}
      {results !== null && !error && (
        <div className="panel-card">
          <h2>{results.length === 0 ? "Nenhum resultado" : `Resultados (${results.length})`}</h2>
          {results.length === 0 ? (
            <p className="muted">Tente outros termos ou limpe filtros.</p>
          ) : (
            <ul className="search-results">
              {results.map((item) => (
                <li key={`${item.documentId}-${item.versionNumber}`} className="result-item">
                  <div className="result-header">
                    <Link to={`/documents/${item.documentId}`} className="result-name">
                      {item.documentName}
                    </Link>
                    <span className={`badge ${String(item.status).toLowerCase()}`}>{statusLabel(item.status)}</span>
                    <span className={`badge badge-${String(item.kind).toLowerCase()}`}>{kindLabel(item.kind)}</span>
                  </div>
                  <div className="result-path">
                    <span className="muted small">Caminho: </span>
                    <span className="small">
                      {item.folderPath ? item.folderPath : "Raiz"} &middot; v{item.versionNumber} &middot;{" "}
                      {new Date(item.uploadedAt).toLocaleString("pt-BR")}
                    </span>
                  </div>
                  {item.snippet && <HighlightedSnippet snippet={item.snippet} />}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </section>
  );
}

function HighlightedSnippet({ snippet }) {
  const START = String.fromCharCode(1);
  const END = String.fromCharCode(2);
  const parts = snippet.split(new RegExp(`${START}|${END}`));
  return (
    <p className="result-snippet">
      ...
      {parts.map((part, index) => (
        <em key={index} className={index % 2 === 1 ? "hit" : ""}>{part}</em>
      ))}
      ...
    </p>
  );
}
