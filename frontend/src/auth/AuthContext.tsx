import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { apiFetch, setAccessToken, setUnauthorizedHandler } from '../api/client';
import type { CurrentUser, DirectoryProfiles, IdentityMe } from './types';
import { signInWithPasskey } from './passkeyLogin';

interface LoginResponse {
  accessToken: string;
}

/**
 * The account and roles come from the identity service; the student, faculty and guardian profile ids come from the academic service
 * (they are academic data, and identity must not depend on it). A role's college decides which college's profile to ask for.
 */
async function fetchCurrentUser(): Promise<CurrentUser> {
  const me = await apiFetch<IdentityMe>('/identity/me');
  const profileFor = async (role: 'STUDENT' | 'FACULTY' | 'GUARDIAN'): Promise<DirectoryProfiles | null> => {
    const tenantId = me.roles.find((r) => r.roleCode === role)?.tenantId;
    if (!tenantId) return null;
    try {
      return await apiFetch<DirectoryProfiles>(`/directory/me/profiles?tenantId=${encodeURIComponent(tenantId)}`);
    } catch {
      return null; // a missing profile must not stop sign-in; the page that needs it explains
    }
  };
  const [student, faculty, guardian] = await Promise.all([profileFor('STUDENT'), profileFor('FACULTY'), profileFor('GUARDIAN')]);
  return {
    ...me,
    studentId: student?.studentId ?? null,
    facultyId: faculty?.facultyId ?? null,
    guardianId: guardian?.guardianId ?? null,
  };
}

interface AuthState {
  user: CurrentUser | null;
  /** True while the initial session-restore attempt (silent refresh + /identity/me + /directory/me/profiles) is in flight. */
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  /** Sign in with a registered passkey (WebAuthn) instead of a password; `email` is optional (see signInWithPasskey). */
  loginWithPasskey: (email?: string) => Promise<void>;
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
    const me = await fetchCurrentUser();
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
          const me = await fetchCurrentUser();
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

  const loginWithPasskey = useCallback(
    async (email?: string) => {
      const accessToken = await signInWithPasskey(email);
      setAccessToken(accessToken);
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

  const value = useMemo(
    () => ({ user, loading, login, loginWithPasskey, logout }),
    [user, loading, login, loginWithPasskey, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
