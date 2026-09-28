import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { apiFetch, setAccessToken, setUnauthorizedHandler } from '../api/client';
import type { CurrentUser } from './types';

interface LoginResponse {
  accessToken: string;
}

interface AuthState {
  user: CurrentUser | null;
  /** True while the initial session-restore attempt (silent refresh + /identity/me) is in flight. */
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

/**
 * Session handling: the access token lives only in memory (this component's state + the module-level variable
 * in api/client.ts); the refresh token is a Secure HttpOnly cookie the browser manages on its own. On mount,
 * we attempt one silent refresh (using whatever refresh cookie the browser already has) to restore a session
 * across a page reload — if that fails, the user is simply logged out, exactly as a real session boundary
 * should behave. Role/permission data always comes from `/identity/me`, never decoded from the JWT and never
 * assumed — the frontend treats it purely as UX information; every actual authorization decision still happens
 * on the backend for every request.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [loading, setLoading] = useState(true);

  const loadCurrentUser = useCallback(async () => {
    const me = await apiFetch<CurrentUser>('/identity/me');
    setUser(me);
    return me;
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await fetch('/auth/refresh', { method: 'POST', credentials: 'include' });
        if (res.ok) {
          const body = (await res.json()) as LoginResponse;
          setAccessToken(body.accessToken);
          const me = await apiFetch<CurrentUser>('/identity/me');
          if (!cancelled) setUser(me);
        }
      } catch {
        // no existing session — stay logged out
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(() => {
      setAccessToken(null);
      setUser(null);
    });
  }, []);

  const login = useCallback(
    async (email: string, password: string) => {
      const res = await apiFetch<LoginResponse>('/auth/login', {
        method: 'POST',
        body: { email, password },
        skipAuthRetry: true,
      });
      setAccessToken(res.accessToken);
      await loadCurrentUser();
    },
    [loadCurrentUser],
  );

  const logout = useCallback(async () => {
    try {
      await apiFetch('/auth/logout', { method: 'POST' });
    } finally {
      setAccessToken(null);
      setUser(null);
    }
  }, []);

  const value = useMemo(() => ({ user, loading, login, logout }), [user, loading, login, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
