import { Navigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

export function PrivateRoute({ roles = null, children }) {
  const { user } = useAuth();

  if (!user) {
    return <Navigate to="/login" replace />;
  }
  if (roles && !roles.includes(user.role)) {
    return <Navigate to="/" replace />;
  }
  return children;
}
