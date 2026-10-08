import axios from "axios";

const TOKEN_KEY = "ged.token";
const USER_KEY = "ged.user";

export const api = axios.create({ baseURL: "/api" });

api.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401 && window.location.pathname !== "/login") {
      clearSession();
      window.location.replace("/login");
    }
    return Promise.reject(error);
  }
);

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

export function storeSession(token, user) {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

export function readStoredUser() {
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

export function apiErrorMessage(error) {
  const data = error.response?.data;
  if (typeof data === "string" && data.trim()) {
    return data;
  }
  return data?.message || data?.detail || data?.error || "Nao foi possivel concluir a operacao.";
}
