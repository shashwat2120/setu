import { render } from '@testing-library/react';
import axe from 'axe-core';
import { MemoryRouter } from 'react-router-dom';
import { expect, vi } from 'vitest';
import type { ReactElement } from 'react';
import { ApiError, apiFetch } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import type { CurrentUser, RoleCode } from '../auth/types';
import { installMatchMedia } from './matchMedia';

export const TENANT = '11111111-1111-4111-8111-111111111111';

/** Signs the test in as one person with one role in one college. */
export function signedInAs(role: RoleCode, ids: Partial<Pick<CurrentUser, 'studentId' | 'facultyId' | 'guardianId'>> = {}) {
  const user: CurrentUser = {
    userId: '22222222-2222-4222-8222-222222222222',
    email: `${role.toLowerCase()}@example.test`,
    personId: '33333333-3333-4333-8333-333333333333',
    fullName: 'Test Person',
    roles: [{ roleCode: role, tenantId: TENANT }],
    studentId: ids.studentId ?? (role === 'STUDENT' ? '44444444-4444-4444-8444-444444444444' : null),
    facultyId: ids.facultyId ?? (role === 'FACULTY' ? '55555555-5555-4555-8555-555555555555' : null),
    guardianId: ids.guardianId ?? null,
  };
  vi.mocked(useAuth).mockReturnValue({ user, loading: false, login: vi.fn(), loginWithPasskey: vi.fn(), logout: vi.fn() });
  return user;
}

export function signedOut() {
  vi.mocked(useAuth).mockReturnValue({ user: null, loading: false, login: vi.fn(), loginWithPasskey: vi.fn(), logout: vi.fn() });
}

type Handler = unknown | ((path: string, init?: { method?: string; body?: unknown }) => unknown);

/**
 * Answers the API calls a page makes. Keys are path prefixes (the longest matching prefix wins); a value is the JSON to return or a function of
 * the request. An unlisted path is a 404, as it would be for the real API.
 */
export function mockApi(routes: Record<string, Handler>) {
  const keys = Object.keys(routes).sort((a, b) => b.length - a.length);
  vi.mocked(apiFetch).mockImplementation((async (path: string, init?: { method?: string; body?: unknown }) => {
    const key = keys.find((k) => path.startsWith(k));
    if (key === undefined) throw new ApiError(404, `no route for ${path}`);
    const handler = routes[key];
    return typeof handler === 'function' ? (handler as (p: string, i?: unknown) => unknown)(path, init) : handler;
  }) as typeof apiFetch);
}

export function renderPage(ui: ReactElement) {
  return render(<MemoryRouter>{ui}</MemoryRouter>);
}

export function reduceMotion(on = true) {
  installMatchMedia(on);
}

/** WCAG 2.2 AA, as far as an automated engine can see it. */
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];

/**
 * Fails with the engine's own explanation if the rendered page breaks a WCAG 2.2 AA rule it can check. Two rules are switched off because jsdom cannot
 * answer them, not because they do not matter: colour contrast needs real layout and paint (checked in the browser), and "region" asks that all
 * content sit inside landmarks, which the app shell provides around a page rendered alone.
 */
export async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, {
    runOnly: { type: 'tag', values: TAGS },
    rules: { 'color-contrast': { enabled: false }, region: { enabled: false } },
  });
  const report = result.violations
    .map((v) => `${v.id} (${v.impact}): ${v.help}\n${v.nodes.slice(0, 3).map((n) => `    ${n.html.slice(0, 140)}`).join('\n')}`)
    .join('\n');
  expect(result.violations, `accessibility violations:\n${report}`).toEqual([]);
}
