import { act, render } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import type { ComponentType } from 'react';
import { ApiError } from '../api/client';
import { apiFetch } from '../api/client';
import type { RoleCode } from '../auth/types';
import { expectAccessible, signedInAs } from './helpers';

// the live board is a WebSocket; a page test has none
vi.mock('../api/useAttendanceLiveUpdates', () => ({ useAttendanceLiveUpdates: () => ({ connected: false, error: null }) }));

type PageModule = { default?: ComponentType };
const modules = import.meta.glob('../pages/**/*.tsx', { eager: true }) as Record<string, PageModule>;

const ROLE_BY_FOLDER: Record<string, RoleCode> = {
  admin: 'COLLEGE_ADMIN',
  faculty: 'FACULTY',
  student: 'STUDENT',
  guardian: 'GUARDIAN',
  placement: 'PLACEMENT_OFFICER',
  superadmin: 'SUPER_ADMIN',
};

/** Pages that take an id from the address, and the route pattern that gives them one. */
const ROUTE_PATTERN: Record<string, string> = {
  FacultyDetailPage: '/x/:id',
  StudentDetailPage: '/x/:id',
  GuardianStudentPage: '/x/:studentId',
  StudentCourseDetailPage: '/x/:enrollmentId',
};

const pages = Object.entries(modules)
  .filter(([, m]) => typeof m.default === 'function')
  .map(([path, m]) => {
    const parts = path.replace('../pages/', '').replace('.tsx', '').split('/');
    const name = parts[parts.length - 1];
    const folder = parts.length > 1 ? parts[0] : 'top';
    return { name, folder, Page: m.default as ComponentType };
  })
  .filter((p) => !['LoginPage'].includes(p.name)); // LoginPage has its own tests (accessibility.login.test.tsx)

async function settle() {
  // let the page's own effects and its failed requests finish, so the error or empty state is what is checked
  await act(async () => {
    await new Promise((r) => setTimeout(r, 60));
  });
}

describe('every page of the app, in the state it shows when its data cannot be loaded (spec section 13)', () => {
  it('finds the pages it is meant to check', () => {
    expect(pages.length).toBeGreaterThanOrEqual(45);
    for (const folder of Object.keys(ROLE_BY_FOLDER)) expect(pages.some((p) => p.folder === folder), folder).toBe(true);
  });

  it.each(pages.map((p) => [`${p.folder}/${p.name}`, p] as const))('%s has no WCAG 2.2 AA violations an automated engine can find', async (_label, { name, folder, Page }) => {
    signedInAs(ROLE_BY_FOLDER[folder] ?? 'STUDENT');
    vi.mocked(apiFetch).mockImplementation((async (path: string) => {
      throw new ApiError(404, `no route for ${path}`);
    }) as typeof apiFetch);
    const pattern = ROUTE_PATTERN[name] ?? '/x';
    const { container } = render(
      <MemoryRouter initialEntries={['/x/00000000-0000-4000-8000-000000000000']}>
        <Routes>
          <Route path={pattern} element={<Page />} />
          <Route path="*" element={<Page />} />
        </Routes>
      </MemoryRouter>,
    );
    await settle();
    await expectAccessible(container);
  });
});

describe('the accessibility check itself', () => {
  it('fails on a page that breaks a rule, so a pass means something', async () => {
    const { container } = render(
      <div>
        <button type="button" />
        <img src="x.png" />
        <input />
      </div>,
    );
    await expect(expectAccessible(container)).rejects.toThrow(/button-name|image-alt|label/);
  });
});
