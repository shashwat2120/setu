import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { apiFetch } from '../api/client';
import PlacementJobsPage from '../pages/placement/PlacementJobsPage';
import StudentCareerPage from '../pages/student/StudentCareerPage';
import { expectAccessible, mockApi, renderPage, signedInAs, TENANT } from './helpers';

const STUDENT = '44444444-4444-4444-8444-444444444444';

const job = {
  id: 'job-1', tenantId: TENANT, postedBy: 'p', title: 'Analyst', employer: 'Example Co', description: 'd', status: 'OPEN', version: 1,
  minCgpa: null, minAttendancePct: null, applicationDeadline: null, allowedProgramIds: null, packageLpa: null, location: null, openings: null, jobType: null,
};
const application = (over: Record<string, unknown> = {}) => ({
  id: 'app-1', tenantId: TENANT, jobId: 'job-1', studentId: STUDENT, status: 'SUBMITTED', appliedAt: '2026-10-20T08:00:00Z', notes: null,
  decisionReason: null, decidedAt: null, ...over,
});

describe('the officer decides an application and says why', () => {
  function officerApi(decisions: Array<{ path: string; body: unknown }>) {
    mockApi({
      '/career/jobs': [job],
      '/career/skills': [],
      '/catalog/programs': [],
      '/directory/students': { content: [{ id: STUDENT, firstName: 'Priya', lastName: 'Nair' }], page: 0, size: 500, totalElements: 1, totalPages: 1 },
      '/career/job-skills': [],
      '/career/applications/by-job': [application()],
      '/career/applications/app-1': (path: string, init?: { method?: string; body?: unknown }) => { decisions.push({ path, body: init?.body }); return undefined; },
    });
  }

  async function openApplications(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByRole('button', { name: 'Applications' }));
    return screen.findByRole('button', { name: 'Reject' });
  }

  it('will not send a rejection without a reason, and says so', async () => {
    signedInAs('PLACEMENT_OFFICER');
    const decisions: Array<{ path: string; body: unknown }> = [];
    officerApi(decisions);
    const user = userEvent.setup();
    renderPage(<PlacementJobsPage />);

    await user.click(await openApplications(user));
    await user.click(screen.getByRole('button', { name: 'Reject' }));

    expect(screen.getByText('A rejection needs a reason, which the student is shown.')).toBeTruthy();
    expect(decisions).toEqual([]);
  });

  it('sends the reason with a rejection, trimmed', async () => {
    signedInAs('PLACEMENT_OFFICER');
    const decisions: Array<{ path: string; body: unknown }> = [];
    officerApi(decisions);
    const user = userEvent.setup();
    const { container } = renderPage(<PlacementJobsPage />);

    await user.click(await openApplications(user));
    const reject = screen.getByRole('button', { name: 'Reject' });
    await user.click(reject);
    await user.type(screen.getByLabelText('Reason shown to the student (required)'), '  Attendance below the minimum ');
    await expectAccessible(container);
    await user.click(screen.getByRole('button', { name: 'Reject' }));

    await waitFor(() => expect(decisions).toEqual([{ path: '/career/applications/app-1/reject', body: { reason: 'Attendance below the minimum' } }]));
  });

  it('lets a shortlist go with no note, sending nothing for it', async () => {
    signedInAs('PLACEMENT_OFFICER');
    const decisions: Array<{ path: string; body: unknown }> = [];
    officerApi(decisions);
    const user = userEvent.setup();
    renderPage(<PlacementJobsPage />);

    await user.click(await screen.findByRole('button', { name: 'Applications' }));
    await user.click(await screen.findByRole('button', { name: 'Shortlist' }));
    expect(screen.getByLabelText('Note shown to the student (optional)')).toBeTruthy();
    await user.click(within(screen.getByLabelText('Note shown to the student (optional)').parentElement as HTMLElement).getByRole('button', { name: 'Shortlist' }));

    await waitFor(() => expect(decisions).toEqual([{ path: '/career/applications/app-1/shortlist', body: { reason: null } }]));
  });
});

describe('the student reads the decision and the reason', () => {
  it('shows the officer\'s note on the application', async () => {
    signedInAs('STUDENT', { studentId: STUDENT });
    vi.mocked(apiFetch);
    mockApi({
      '/career/student-skills': [], '/career/skills': [], '/career/jobs': [job], '/career/placement-records': [],
      '/career/applications/eligibility': { eligible: true, missingOrInsufficientSkillIds: [], unmetRules: [] },
      '/career/applications': [application({ status: 'REJECTED', decisionReason: 'Attendance below the minimum', decidedAt: '2026-10-21T08:00:00Z' })],
    });
    const { container } = renderPage(<StudentCareerPage />);

    expect(await screen.findByText('Attendance below the minimum')).toBeTruthy();
    expect(screen.getByText(/Placement officer's note/)).toBeTruthy();
    await expectAccessible(container);
  });

  it('shows no note when none was given', async () => {
    signedInAs('STUDENT', { studentId: STUDENT });
    mockApi({
      '/career/student-skills': [], '/career/skills': [], '/career/jobs': [job], '/career/placement-records': [],
      '/career/applications/eligibility': { eligible: true, missingOrInsufficientSkillIds: [], unmetRules: [] },
      '/career/applications': [application({ status: 'SHORTLISTED' })],
    });
    renderPage(<StudentCareerPage />);

    await screen.findByText('SHORTLISTED');
    expect(screen.queryByText(/Placement officer's note/)).toBeNull();
  });
});
