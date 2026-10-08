import { api } from "./client";

export async function folderTree() {
  const { data } = await api.get("/folders/tree");
  return data;
}

export async function createFolder(name, parentId, projectId, assignedAttorneyId) {
  const { data } = await api.post("/folders", { name, parentId, projectId, assignedAttorneyId });
  return data;
}

export async function assignRootFolder(id, projectId, assignedAttorneyId) {
  const { data } = await api.patch(`/folders/${id}/assignment`, { projectId, assignedAttorneyId });
  return data;
}

export async function listProjects() {
  const { data } = await api.get("/projects");
  return data;
}

export async function createProject(name) {
  const { data } = await api.post("/projects", { name });
  return data;
}

export async function renameFolder(id, name) {
  const { data } = await api.patch(`/folders/${id}`, { name });
  return data;
}

export async function moveFolder(id, targetFolderId) {
  const { data } = await api.patch(`/folders/${id}/move`, { targetFolderId });
  return data;
}

export async function deleteFolder(id) {
  await api.delete(`/folders/${id}`);
}

export async function documentsByFolder(folderId) {
  const { data } = await api.get(`/folders/${folderId}/documents`);
  return data;
}

export async function uploadDocument(folderId, file, changeDescription) {
  const form = new FormData();
  form.append("file", file);
  if (changeDescription) {
    form.append("changeDescription", changeDescription);
  }
  const { data } = await api.post(`/folders/${folderId}/documents`, form);
  return data;
}

export async function documentDetail(id) {
  const { data } = await api.get(`/documents/${id}`);
  return data;
}

export async function documentVersions(id) {
  const { data } = await api.get(`/documents/${id}/versions`);
  return data;
}

export async function documentStatusHistory(id) {
  const { data } = await api.get(`/documents/${id}/status-history`);
  return data;
}

export async function documentDiff(id, from, to) {
  const { data } = await api.get(`/documents/${id}/diff`, { params: { from, to } });
  return data;
}

export async function deleteDocument(id) {
  await api.delete(`/documents/${id}`);
}

export async function moveDocument(id, targetFolderId) {
  const { data } = await api.patch(`/documents/${id}/move`, { targetFolderId });
  return data;
}

export async function changeStatus(id, to, note) {
  const { data } = await api.post(`/documents/${id}/status`, { to, note: note || null });
  return data;
}

export async function downloadVersion(versionId, fileName) {
  const { data } = await api.get(`/versions/${versionId}/download`, { responseType: "blob" });
  const objectUrl = URL.createObjectURL(data);
  const link = document.createElement("a");
  link.href = objectUrl;
  link.download = fileName;
  link.click();
  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
}

export async function searchDocuments(query, filters = {}) {
  const { data } = await api.get("/search", { params: { q: query, ...filters } });
  return data;
}

export async function searchFilterOptions() {
  const { data } = await api.get("/search/filters");
  return data;
}

export async function updateDocumentTags(id, tags) {
  const { data } = await api.patch(`/documents/${id}/tags`, { tags });
  return data;
}

export async function editorConfig(id, mode = "view") {
  const { data } = await api.get(`/editor/documents/${id}/config`, { params: { mode } });
  return data;
}

export async function documentPreview(versionId) {
  const { data } = await api.get(`/versions/${versionId}/preview`, { responseType: "blob" });
  return data;
}

export async function changeOwnPassword(currentPassword, newPassword) {
  await api.patch("/account/password", { currentPassword, newPassword });
}

export async function reviewQueue() {
  const { data } = await api.get("/reviews/queue");
  return data;
}

export async function reviewDetail(id) {
  const { data } = await api.get(`/reviews/${id}`);
  return data;
}

export async function addReviewComment(id, body, diffLineIndex, diffLineContent) {
  const { data } = await api.post(`/reviews/${id}/comments`, {
    body,
    diffLineIndex: diffLineIndex ?? null,
    diffLineContent: diffLineContent || null
  });
  return data;
}

export async function decideReview(id, verdict, summary) {
  const { data } = await api.post(`/reviews/${id}/decide`, { verdict, summary: summary || null });
  return data;
}

export async function documentReviews(id) {
  const { data } = await api.get(`/documents/${id}/reviews`);
  return data;
}

export async function listUsers() {
  const { data } = await api.get("/users");
  return data;
}

export async function createUser(payload) {
  const { data } = await api.post("/users", payload);
  return data;
}

export async function updateUser(id, payload) {
  const { data } = await api.patch(`/users/${id}`, payload);
  return data;
}
