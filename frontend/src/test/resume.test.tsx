import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { apiFetch } from '../api/client';
import { ResumeCard } from '../components/career/ResumeCard';
import { datesText, isHttpsUrl, recordLine, toRequestFields } from '../lib/resume';
import PlacementJobsPage from '../pages/placement/PlacementJobsPage';
import StudentCareerPage from '../pages/student/StudentCareerPage';
import StudentResumePage from '../pages/student/StudentResumePage';
import { expectAccessible, mockApi, renderPage, signedInAs, TENANT } from './helpers';

const STUDENT = '44444444-4444-4444-8444-444444444444';
const JAVA = 'bbbbbbbb-0000-4000-8000-000000000001';

const content = (over: Record<string, unknown> = {}) => ({
  headline: 'Aspiring analyst', summary: 'Likes data', linkedinUrl: 'https://www.linkedin.com/in/example', githubUrl: null, portfolioUrl: null,
  items: [{ kind: 'PROJECT', title: 'Attendance dashboard', organisation: 'College', description: 'Built a dashboard', startDate: '2026-01-01', endDate: null }], ...over,
});
const record = { cgpa: 2.25, attendancePercentage: 33.33, programId: null, expectedGraduationYear: 2028 };
const view = (over: Record<string, unknown> = {}) => ({ studentId: STUDENT, version: 3, content: content(), updatedAt: '2026-10-20T08:00:00Z', record, skills: [{ skillId: JAVA, level: 'BEGINNER' }], ...over });
const skillList = [{ id: JAVA, tenantId: TENANT, code: 'JAVA', name: 'Java Programming', categoryId: null, version: 1 }];

describe('the resume helpers', () => {
  it('shows only https addresses as links', () => {
    expect(isHttpsUrl('https://github.com/x')).toBe(true);
    for (const bad of ['javascript:alert(1)', 'http://example.com', 'ftp://x.example.com', 'github.com/x', '', null, undefined]) expect(isHttpsUrl(bad as string | null)).toBe(false);
  });

  it('writes dates, the record line and the request the way people and the server want them', () => {
    expect(datesText({ startDate: '2026-01-01', endDate: null })).toBe('Jan 2026 – present');
    expect(datesText({ startDate: '2026-01-01', endDate: '2026-03-31' })).toBe('Jan 2026 – Mar 2026');
    expect(datesText({ startDate: null, endDate: null })).toBe('');
    expect(recordLine(record)).toBe('Batch of 2028 · CGPA 2.25 · Attendance 33.33%');
    expect(recordLine({ cgpa: null, attendancePercentage: null, programId: null, expectedGraduationYear: null })).toBe('');
    const fields = toRequestFields({ headline: '  Hi  ', summary: '', linkedinUrl: ' ', items: [{ kind: 'PROJECT', title: '  ', organisation: 'x' }, { kind: 'PROJECT', title: ' Real ', organisation: '', startDate: '' }] } as never);
    expect(fields).toMatchObject({ headline: 'Hi', summary: null, linkedinUrl: null });
    expect(fields.items).toEqual([{ kind: 'PROJECT', title: 'Real', organisation: null, description: null, startDate: null, endDate: null }]);
  });
});

describe('the resume as a reader sees it', () => {
  it('shows what the student wrote beside the college\'s own records, with links only where they are safe', async () => {
    const { container } = render(
      <ResumeCard content={content({ githubUrl: 'javascript:alert(1)', portfolioUrl: 'http://insecure.example.com' }) as never} record={record} skills={[{ skillId: JAVA, level: 'BEGINNER' }]} skillName={() => 'Java Programming'} />,
    );

    expect(screen.getByText('Aspiring analyst')).toBeTruthy();
    expect(screen.getByText('Batch of 2028 · CGPA 2.25 · Attendance 33.33%')).toBeTruthy();
    expect(screen.getByText('Skills: Java Programming (beginner)')).toBeTruthy();
    expect(screen.getByText('Attendance dashboard')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'LinkedIn' }).getAttribute('href')).toBe('https://www.linkedin.com/in/example');
    expect(screen.queryByRole('link', { name: 'GitHub' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Portfolio' })).toBeNull();
    expect(screen.getByRole('link', { name: 'LinkedIn' }).getAttribute('rel')).toContain('noopener');
    await expectAccessible(container);
  });
});

describe('the student\'s resume page', () => {
  function resumeApi(onSave: (body: unknown) => unknown = () => ({ version: 4 }), resume = view()) {
    mockApi({ '/career/resumes': (_path: string, init?: { method?: string; body?: unknown }) => (init?.method === 'PUT' ? onSave(init.body) : resume), '/career/skills': skillList });
  }

  it('loads the saved resume into the form and saves changes with the version it was editing', async () => {
    signedInAs('STUDENT', { studentId: STUDENT });
    const saves: unknown[] = [];
    resumeApi((b) => { saves.push(b); return { version: 4 }; });
    const user = userEvent.setup();
    const { container } = renderPage(<StudentResumePage />);

    const headline = (await screen.findByLabelText('Headline')) as HTMLInputElement;
    await waitFor(() => expect(headline.value).toBe('Aspiring analyst'));
    await user.clear(headline);
    await user.type(headline, 'Data analyst');
    await expectAccessible(container);
    await user.click(screen.getByRole('button', { name: 'Save resume' }));

    await waitFor(() => expect(saves).toHaveLength(1));
    expect(saves[0]).toMatchObject({ studentId: STUDENT, headline: 'Data analyst', summary: 'Likes data', expectedVersion: 3 });
    expect((saves[0] as { items: unknown[] }).items).toHaveLength(1);
    expect(await screen.findByText('Saved.')).toBeTruthy();
  });

  it('adds and removes entries, and says what the server refused', async () => {
    signedInAs('STUDENT', { studentId: STUDENT });
    resumeApi(() => { throw new Error('the GitHub address must be an https link'); }, view({ content: content({ items: [] }) }));
    const user = userEvent.setup();
    renderPage(<StudentResumePage />);

    await screen.findByLabelText('Headline');
    await user.click(screen.getByRole('button', { name: 'Add certification' }));
    expect(screen.getByText('Certification 1')).toBeTruthy();
    await user.type(screen.getByLabelText('Title'), 'Cloud basics');
    await user.click(screen.getByRole('button', { name: 'Remove this certification' }));
    expect(screen.queryByText('Certification 1')).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Save resume' }));
    expect((await screen.findByRole('alert')).textContent).toContain('https link');
  });

  it('offers to print the resume', async () => {
    signedInAs('STUDENT', { studentId: STUDENT });
    resumeApi();
    const print = vi.spyOn(window, 'print').mockImplementation(() => undefined);
    const user = userEvent.setup();
    renderPage(<StudentResumePage />);

    await user.click(await screen.findByRole('button', { name: 'Print or save as PDF' }));

    expect(print).toHaveBeenCalled();
  });
});

describe('applying with a resume', () => {
  const job = {
    id: 'job-1', tenantId: TENANT, postedBy: 'p', title: 'Analyst', employer: 'Example Co', description: 'd', status: 'OPEN', version: 1, minCgpa: null, minAttendancePct: null,
    applicationDeadline: null, allowedProgramIds: null, graduatingYear: null, packageLpa: null, location: null, openings: null, jobType: null, selectedCount: 0, closedReason: null, requiresResume: false,
  };
  function careerApi(j: Record<string, unknown>, resume: unknown) {
    mockApi({
      '/career/student-skills': [], '/career/skills': [], '/career/jobs': [j], '/career/placement-records': [], '/career/applications': [],
      '/career/resumes': resume as never,
      '/career/applications/eligibility': { eligible: true, missingOrInsufficientSkillIds: [], unmetRules: [] },
    });
  }

  it('says the resume will be sent when the student has one', async () => {
    signedInAs('STUDENT', { studentId: STUDENT });
    careerApi(job, view());
    renderPage(<StudentCareerPage />);

    expect(await screen.findByText('Your resume will be sent with your application.')).toBeTruthy();
  });

  it('says there is none, and where to add one, and that a job needing one needs it', async () => {
    signedInAs('STUDENT', { studentId: STUDENT });
    careerApi({ ...job, requiresResume: true }, view({ version: 0, content: content({ headline: null, items: [] }) }));
    renderPage(<StudentCareerPage />);

    expect(await screen.findByText(/You have no resume yet, and this job needs one/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'add one' }).getAttribute('href')).toBe('/app/student/resume');
    expect(screen.getByText(/Needs a resume/)).toBeTruthy();
  });
});

describe('the placement officer reads the resume an application was made with', () => {
  const app = (id: string, hasResume: boolean) => ({
    id, tenantId: TENANT, jobId: 'job-1', studentId: STUDENT, status: 'SUBMITTED', appliedAt: '2026-10-20T08:00:00Z', notes: null, decisionReason: null, decidedAt: null, hasResume,
  });
  const job = {
    id: 'job-1', tenantId: TENANT, postedBy: 'p', title: 'Analyst', employer: 'Example Co', description: 'd', status: 'OPEN', version: 1, minCgpa: null, minAttendancePct: null,
    applicationDeadline: null, allowedProgramIds: null, graduatingYear: null, packageLpa: null, location: null, openings: null, jobType: null, selectedCount: 0, closedReason: null,
  };

  it('offers View resume only where one was attached, and shows it as submitted', async () => {
    signedInAs('PLACEMENT_OFFICER');
    mockApi({
      '/career/jobs': [job], '/career/skills': skillList, '/catalog/programs': [], '/career/job-skills': [],
      '/directory/students': { content: [{ id: STUDENT, firstName: 'Priya', lastName: 'Nair' }], page: 0, size: 500, totalElements: 1, totalPages: 1 },
      '/career/applications/by-job': [app('a1', true), app('a2', false)],
      '/career/applications/a1/resume': { capturedAt: '2026-10-20T08:00:00Z', resumeVersion: 2, content: content(), record, skills: [{ skillId: JAVA, level: 'BEGINNER' }] },
    });
    const user = userEvent.setup();
    renderPage(<PlacementJobsPage />);

    await user.click(await screen.findByRole('button', { name: 'Applications' }));
    const buttons = await screen.findAllByRole('button', { name: 'View resume' });
    expect(buttons).toHaveLength(1);
    expect(screen.getByText('None attached')).toBeTruthy();
    await user.click(buttons[0]);

    expect(await screen.findByText('Aspiring analyst')).toBeTruthy();
    expect(screen.getByText(/resume, as submitted on/)).toBeTruthy();
    expect(screen.getByText('Skills: Java Programming (beginner)')).toBeTruthy();
    expect(vi.mocked(apiFetch).mock.calls.some(([p]) => p === '/career/applications/a1/resume')).toBe(true);
    await user.click(screen.getByRole('button', { name: 'Close resume' }));
    expect(screen.queryByText('Aspiring analyst')).toBeNull();
  });
});
