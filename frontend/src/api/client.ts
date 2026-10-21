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
  /**
   * A 401 from this endpoint is a normal answer, not proof that the session ended (for example the server rejecting a passkey
   * assertion during step-up). No refresh-and-retry and no sign-out: the caller gets the error and the person stays signed in.
   */
  unauthorizedKeepsSession?: boolean;
  /** Extra request headers — e.g. the Idempotency-Key a payment initiation must send. Never used for auth (that's always the in-memory bearer token). */
  headers?: Record<string, string>;
}

export async function apiFetch<T>(path: string, options: ApiFetchOptions = {}): Promise<T> {
  const { method = 'GET', body, skipAuthRetry, unauthorizedKeepsSession, headers } = options;

  // a FormData body is sent as it is (the browser sets its multipart type and boundary); anything else is JSON
  const isForm = typeof FormData !== 'undefined' && body instanceof FormData;
  const doFetch = async (): Promise<Response> =>
    fetch(path, {
      method,
      credentials: 'include',
      headers: {
        ...(body !== undefined && !isForm ? { 'Content-Type': 'application/json' } : {}),
        ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
        ...headers,
      },
      body: body === undefined ? undefined : isForm ? (body as FormData) : JSON.stringify(body),
    });

  let response = await doFetch();

  if (response.status === 401 && !skipAuthRetry && !unauthorizedKeepsSession) {
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
    if (response.status === 401 && !unauthorizedKeepsSession) {
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

/**
 * Fetches a file with the signed-in person's token (a plain link would carry none) and hands it to the browser to save under the name the server offered it as.
 * Same auth behaviour as {@link apiFetch}: one silent refresh-and-retry on a 401. A refusal is thrown as an {@link ApiError} carrying the server's message.
 */
export async function apiDownload(path: string): Promise<void> {
  const doFetch = async (): Promise<Response> =>
    fetch(path, { credentials: 'include', headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {} });

  let response = await doFetch();
  if (response.status === 401) {
    if (await refreshAccessToken()) {
      response = await doFetch();
    } else {
      onUnauthorized?.();
      throw new ApiError(401, 'Session expired');
    }
  }
  if (!response.ok) {
    const text = await response.text();
    const envelope = (text ? safeJsonParse(text) : {}) as ErrorEnvelope | undefined;
    throw new ApiError(response.status, envelope?.message ?? response.statusText, envelope?.code, envelope?.requestId, envelope?.errors);
  }
  const blob = await response.blob();
  const named = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(response.headers.get('Content-Disposition') ?? '');
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = named ? decodeURIComponent(named[1]) : 'download';
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

export interface SseHandlers {
  onEvent: (event: string, data: unknown) => void;
}

/**
 * POSTs `body` and reads the response as server-sent events, calling `onEvent` for each one as it arrives.
 * Same auth behavior as `apiFetch` (bearer token, one silent refresh-and-retry on 401). A non-2xx answer is thrown
 * as an `ApiError` before any event is delivered; a stream that ends or breaks mid-way is the caller's to detect
 * (it never saw a terminal event).
 */
export async function apiStream(path: string, body: unknown, handlers: SseHandlers, signal?: AbortSignal): Promise<void> {
  const doFetch = async (): Promise<Response> =>
    fetch(path, {
      method: 'POST',
      credentials: 'include',
      signal,
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      },
      body: JSON.stringify(body),
    });

  let response = await doFetch();
  if (response.status === 401) {
    if (await refreshAccessToken()) {
      response = await doFetch();
    } else {
      onUnauthorized?.();
      throw new ApiError(401, 'Session expired');
    }
  }
  if (!response.ok) {
    const text = await response.text();
    const json = (text ? safeJsonParse(text) : undefined) as (ErrorEnvelope & { reason?: string }) | undefined;
    throw new ApiError(response.status, json?.message ?? json?.reason ?? response.statusText, json?.code, json?.requestId, json?.errors);
  }
  if (!response.body) {
    throw new ApiError(502, 'Streaming is not supported by this connection');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  const dispatch = (block: string) => {
    let event = 'message';
    const dataLines: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
    }
    if (dataLines.length === 0) return;
    const raw = dataLines.join('\n');
    handlers.onEvent(event, safeJsonParse(raw) ?? raw);
  };
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
    let sep: number;
    while ((sep = buffer.indexOf('\n\n')) >= 0) {
      dispatch(buffer.slice(0, sep));
      buffer = buffer.slice(sep + 2);
    }
  }
  if (buffer.trim()) dispatch(buffer);
}

function safeJsonParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}
