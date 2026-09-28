import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from './AuthContext';
import { hasRole, type RoleCode } from './types';

/**
 * Route-level UX guard only — hiding a route here is convenience, not security. Every request this app makes
 * still goes through the backend's real authorization pipeline, which is the only thing that can actually deny
 * access; this component just avoids flashing a screen the user has no permission-bearing reason to see.
 */
export function ProtectedRoute({ allow }: { allow?: RoleCode[] }) {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) {
    return <FullPageLoading />;
  }
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }
  if (allow && !allow.some((role) => hasRole(user, role))) {
    return <Navigate to="/app" replace />;
  }
  return <Outlet />;
}

export function FullPageLoading() {
  return (
    <div style={{ display: 'grid', placeItems: 'center', minHeight: '60vh', color: 'var(--ink-soft)' }}>
      <div role="status" aria-live="polite">
        Loading…
      </div>
    </div>
  );
}
