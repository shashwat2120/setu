import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { apiFetch } from '../api/client';
import { applicationsSummary, canWithdraw, nextStep } from '../lib/applicationStatus';
import StudentApplicationsPage from '../pages/student/StudentApplicationsPage';
import { expectAccessible, mockApi, renderPage, signedInAs, TENANT } from './helpers';

const STUDENT = '44444444-4444-4444-8444-444444444444';
const job = (id: string, title: string, employer: string) => ({
  id, tenantId: TENANT, postedBy: 'p', title, employer, description: 'd', status: 'OPEN', version: 1, minCgpa: null, minAttendancePct: null, applicationDeadline: null,
  allowedProgramIds: null, graduatingYear: null, packageLpa: null, location: null, openings: null, jobType: null, selectedCount: 0, closedReason: null,
});
const app = (id: string, jobId: string, status: string, over: Record<string, unknown> = {}) => ({
  id, tenantId: TENANT, jobId, studentId: STUDENT, status, appliedAt: '2026-10-20T08:00:00Z', notes: null, decisionReason: null, decidedAt: null, ...over,
});

describe('what an application says about the next step', () => {
  it('says who is waiting on whom, in each state', () => {
    expect(nextStep({ status: 'SUBMITTED', jobId: 'j' })).toBe('Waiting for the placement officer to review your application.');
    expect(nextStep({ status: 'SHORTLISTED', jobId: 'j' })).toBe('You are shortlisted. The placement officer will decide next.');
    expect(nextStep({ status: 'REJECTED', jobId: 'j' })).toBe('Not taken forward.');
    expect(nextStep({ status: 'WITHDRAWN', jobId: 'j' })).toBe('You withdrew this application.');
    expect(nextStep({ status: 'SELECTED', jobId: 'j' })).toBe('You are selected.');
    expect(nextStep({ status: 'SELECTED', jobId: 'j' }, { ctc: 925000, joiningDate: '2027-07-01' })).toBe('You are selected: package ₹9.25 LPA, joining 2027-07-01.');
    expect(nextStep({ status: 'SELECTED', jobId: 'j' }, { ctc: null, joiningDate: null })).toBe('You are selected.');
  });

  it('lets a student withdraw only before a final decision', () => {
    expect(['SUBMITTED', 'SHORTLISTED'].every(canWithdraw)).toBe(true);
    expect(['SELECTED', 'REJECTED', 'WITHDRAWN'].some(canWithdraw)).toBe(false);
  });

  it('summarises the applications in a sentence', () => {
    expect(applicationsSummary([])).toBe('You have not applied to any job yet.');
    expect(applicationsSummary([{ status: 'SUBMITTED' }])).toBe('1 application: 1 waiting for review.');
    expect(applicationsSummary([{ status: 'SELECTED' }, { status: 'SHORTLISTED' }, { status: 'REJECTED' }, { status: 'WITHDRAWN' }]))
      .toBe('4 applications: 1 selected, 1 shortlisted, 1 not taken forward.');
  });
});

describe('the student\'s applications page', () => {
  function api(applications: unknown[], placements: unknown[] = []) {
    mockApi({
      '/career/applications/a2/withdraw': () => undefined,
      '/career/applications': applications,
      '/career/jobs': [job('j1', 'Data Analyst', 'Example Co'), job('j2', 'Graduate Engineer', 'Acme')],
      '/career/placement-records': placements,
    });
  }

  it('lists each application with its job, status, next step and the officer\'s note, newest first', async () => {
    signedInAs('STUDENT', { studentId: STUDENT });
    api(
      [app('a1', 'j1', 'REJECTED', { decisionReason: 'Attendance below the minimum', appliedAt: '2026-10-18T08:00:00Z' }), app('a2', 'j2', 'SELECTED', { appliedAt: '2026-10-20T08:00:00Z' })],
      [{ id: 'p1', tenantId: TENANT, studentId: STUDENT, jobId: 'j2', employer: 'Acme', role: 'Graduate Engineer', ctc: 650000, placedAt: '2026-10-21', joiningDate: '2027-07-01' }],
    );
    const { container } = renderPage(<StudentApplicationsPage />);

    expect(await screen.findByText('2 applications: 1 selected, 1 not taken forward.')).toBeTruthy();
    const rows = screen.getAllByRole('row').slice(1);
    expect(within(rows[0]).getByText('Graduate Engineer')).toBeTruthy();
    expect(within(rows[0]).getByText('You are selected: package ₹6.5 LPA, joining 2027-07-01.')).toBeTruthy();
    expect(within(rows[1]).getByText('Data Analyst')).toBeTruthy();
    expect(within(rows[1]).getByText('Attendance below the minimum')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Withdraw' })).toBeNull();
    await expectAccessible(container);
  });

  it('offers Withdraw on an open application and sends it', async () => {
    signedInAs('STUDENT', { studentId: STUDENT });
    api([app('a2', 'j2', 'SUBMITTED')]);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const user = userEvent.setup();
    renderPage(<StudentApplicationsPage />);

    await user.click(await screen.findByRole('button', { name: 'Withdraw' }));

    await waitFor(() => expect(vi.mocked(apiFetch).mock.calls.some(([p, init]) => p === '/career/applications/a2/withdraw' && (init as { method?: string })?.method === 'POST')).toBe(true));
  });

  it('says so, and where to look, when there are none', async () => {
    signedInAs('STUDENT', { studentId: STUDENT });
    api([]);
    renderPage(<StudentApplicationsPage />);

    expect(await screen.findByText('You have not applied to any job yet.')).toBeTruthy();
    expect(screen.getByText('No applications yet')).toBeTruthy();
  });
});
