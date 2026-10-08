const KIND_LABELS = {
  WORD: "Word",
  EXCEL: "Excel",
  PDF: "PDF",
  IMAGE: "Imagem"
};

const STATUS_LABELS = {
  EDITANDO: "Editando",
  EM_REVISAO: "Em revisão",
  PRONTO_PARA_PROTOCOLO: "Pronto para protocolar",
  PROTOCOLADO: "Protocolado"
};

const VERDICT_LABELS = {
  PENDENTE: "Pendente",
  APROVADO: "Aprovado",
  CORRECOES_SOLICITADAS: "Correções solicitadas",
  SUPERADA: "Fechada (superada)"
};

export function kindLabel(kind) {
  return KIND_LABELS[kind] ?? kind;
}

export function statusLabel(status) {
  return STATUS_LABELS[status] ?? status;
}

export function verdictLabel(verdict) {
  return VERDICT_LABELS[verdict] ?? verdict;
}

export function isExcelOrWord(kind) {
  return kind === "WORD" || kind === "EXCEL";
}

export function canManageUsers(role) {
  return role === "ADMIN" || role === "PROTOCOLADOR";
}

export function canReview(role) {
  return role === "REVISOR" || role === "ADMIN";
}

export const ALL_STATUSES = Object.keys(STATUS_LABELS);
