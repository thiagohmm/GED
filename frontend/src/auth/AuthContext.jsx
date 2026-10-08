import { api, clearSession, readStoredUser, storeSession } from "../api/client";
import { createContext, useCallback, useContext, useMemo, useState } from "react";

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(readStoredUser);

  const login = useCallback(async (username, password) => {
    try {
      const { data } = await api.post("/auth/login", { username, password });
      const loggedUser = { username: data.username, role: data.role };
      storeSession(data.token, loggedUser);
      setUser(loggedUser);
      return true;
    } catch {
      return false;
    }
  }, []);

  const logout = useCallback(() => {
    clearSession();
    setUser(null);
  }, []);

  const value = useMemo(() => ({ user, login, logout }), [user, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth deve ser usado dentro de AuthProvider");
  }
  return context;
}
