/**
 * Centralized API layer. Every backend call goes through `apiFetch` — no page ever calls `fetch` directly.
 * The access token lives in memory only (never localStorage — a real JWT in localStorage is readable by any
 * injected script); the refresh token is a Secure HttpOnly cookie the browser sends automatically and the
 * frontend never reads. On a 401, `apiFetch` attempts exactly one silent refresh-and-retry before giving up,
 * matching how a real session should behave without ever inventing client-side authorization state.
 */

let accessToken: string | null = null;
let onUnauthorized: (() => void) | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export function getAccessToken() {
  return accessToken;
}

/** Registered once by AuthProvider — called when a refresh attempt itself fails, meaning the session is truly over. */
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

export class ApiError extends Error {
  status: number;
  code?: string;
  requestId?: string;
  fieldErrors?: Record<string, string>;

  constructor(status: number, message: string, code?: string, requestId?: string, fieldErrors?: Record<string, string>) {
    super(message);
    this.status = status;
    this.code = code;
    this.requestId = requestId;
    this.fieldErrors = fieldErrors;
  }
}

interface ErrorEnvelope {
  message?: string;
  code?: string;
  requestId?: string;
  errors?: Record<string, string>;
}

let refreshInFlight: Promise<boolean> | null = null;

async function refreshAccessToken(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      try {
        const res = await fetch('/auth/refresh', { method: 'POST', credentials: 'include' });
        if (!res.ok) return false;
        const body = (await res.json()) as { accessToken: string };
        setAccessToken(body.accessToken);
        return true;
      } catch {
        return false;
      } finally {
        refreshInFlight = null;
      }
    })();
  }
  return refreshInFlight;
}

export interface ApiFetchOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  /** Skip the automatic refresh-and-retry on 401 — used only by auth endpoints themselves to avoid loops. */
  skipAuthRetry?: boolean;
  /** Extra request headers — e.g. the Idempotency-Key a payment initiation must send. Never used for auth (that's always the in-memory bearer token). */
  headers?: Record<string, string>;
}

export async function apiFetch<T>(path: string, options: ApiFetchOptions = {}): Promise<T> {
  const { method = 'GET', body, skipAuthRetry, headers } = options;

  const doFetch = async (): Promise<Response> =>
    fetch(path, {
      method,
      credentials: 'include',
      headers: {
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
        ...headers,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });

  let response = await doFetch();

  if (response.status === 401 && !skipAuthRetry) {
    const refreshed = await refreshAccessToken();
    if (refreshed) {
      response = await doFetch();
    } else {
      onUnauthorized?.();
      throw new ApiError(401, 'Session expired');
    }
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  const json = text ? safeJsonParse(text) : undefined;

  if (!response.ok) {
    const envelope = (json ?? {}) as ErrorEnvelope;
    if (response.status === 401) {
      onUnauthorized?.();
    }
    throw new ApiError(
      response.status,
      envelope.message ?? response.statusText,
      envelope.code,
      envelope.requestId,
      envelope.errors,
    );
  }

  return json as T;
}

function safeJsonParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}
