import { afterEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';
import { installMatchMedia } from './matchMedia';

// Every test replaces the two things a page reaches outside itself for: the API client and the signed-in user (see helpers.tsx).
vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>();
  return { ...actual, apiFetch: vi.fn(), apiStream: vi.fn() };
});
vi.mock('../auth/AuthContext', () => ({
  useAuth: vi.fn(),
  AuthProvider: ({ children }: { children: unknown }) => children,
}));

// What jsdom does not provide and the pages touch.
class NoopObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() { return []; }
}
vi.stubGlobal('ResizeObserver', NoopObserver);
vi.stubGlobal('IntersectionObserver', NoopObserver);
window.scrollTo = vi.fn() as unknown as typeof window.scrollTo;

installMatchMedia(false);

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  installMatchMedia(false);
});
