import { AdminUsersPage } from "./pages/AdminUsersPage";
import { DocumentPage } from "./pages/DocumentPage";
import { ExplorerPage } from "./pages/ExplorerPage";
import { LoginPage } from "./pages/LoginPage";
import { ReviewsPage } from "./pages/ReviewsPage";
import { ReviewPage } from "./pages/ReviewPage";
import { SearchPage } from "./pages/SearchPage";
import { AppFrame } from "./components/Navbar";
import { PrivateRoute } from "./components/PrivateRoute";
import { useAuth } from "./auth/AuthContext";
import { Navigate, Route, Routes } from "react-router-dom";

export function App() {
  const { user } = useAuth();
  return (
    <AppFrame>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/" element={<PrivateRoute><ExplorerPage /></PrivateRoute>} />
          <Route path="/documents/:id" element={<PrivateRoute><DocumentPage /></PrivateRoute>} />
          <Route path="/search" element={<PrivateRoute><SearchPage /></PrivateRoute>} />
          <Route path="/reviews" element={<PrivateRoute roles={["REVISOR", "ADMIN"]}><ReviewsPage /></PrivateRoute>} />
          <Route path="/reviews/:id" element={<PrivateRoute roles={["REVISOR", "ADMIN"]}><ReviewPage /></PrivateRoute>} />
          <Route path="/admin/users" element={<PrivateRoute roles={["ADMIN", "PROTOCOLADOR"]}><AdminUsersPage /></PrivateRoute>} />
          <Route path="*" element={<Navigate to={user ? "/" : "/login"} replace />} />
        </Routes>
    </AppFrame>
  );
}
