import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { apiFetch } from '../api/client';
import { batchYears, fromLocalInput, jobFacts, jobRequirements, plainNumber, toLocalInput } from '../lib/jobTerms';
import PlacementJobsPage from '../pages/placement/PlacementJobsPage';
import StudentCareerPage from '../pages/student/StudentCareerPage';
import { expectAccessible, mockApi, renderPage, signedInAs, TENANT } from './helpers';

const STUDENT = '44444444-4444-4444-8444-444444444444';
const PROGRAM_CS = 'aaaaaaaa-0000-4000-8000-000000000001';
const PROGRAM_ARTS = 'aaaaaaaa-0000-4000-8000-000000000002';

const baseJob = {
  id: 'job-1', tenantId: TENANT, postedBy: 'p', title: 'Software Engineer', employer: 'Acme', description: 'Build things', status: 'OPEN', version: 3,
  minCgpa: null, minAttendancePct: null, applicationDeadline: null, allowedProgramIds: null, graduatingYear: null, packageLpa: null, location: null, openings: null, jobType: null,
};

describe('how a job\'s terms are written for people', () => {
  it('writes figures plainly and lists what a job offers and asks', () => {
    expect(plainNumber(7.5)).toBe('7.5');
    expect(plainNumber('7.50')).toBe('7.5');
    expect(plainNumber(7)).toBe('7');
    expect(jobFacts({ packageLpa: 12.5, location: 'Pune', openings: 3, jobType: 'INTERNSHIP' })).toEqual(['Internship', '₹12.5 LPA', 'Pune', '3 openings']);
    expect(jobFacts({ packageLpa: null, location: null, openings: 1, jobType: null })).toEqual(['1 opening']);
    expect(jobFacts({ packageLpa: null, location: null, openings: null, jobType: null })).toEqual([]);

    const now = new Date('2026-10-21T08:00:00Z');
    const rules = jobRequirements({ minCgpa: 7, minAttendancePct: 75, applicationDeadline: '2026-10-25T08:00:00Z', allowedProgramIds: [PROGRAM_CS] }, now);
    expect(rules.slice(0, 3)).toEqual(['CGPA of at least 7', 'Attendance of at least 75%', 'Open to certain programs only']);
    expect(rules[3]).toMatch(/^Apply by /);
    expect(jobRequirements({ minCgpa: null, minAttendancePct: null, applicationDeadline: '2026-10-20T08:00:00Z', allowedProgramIds: [] }, now)[0]).toMatch(/^Deadline passed/);
    expect(jobRequirements({ minCgpa: null, minAttendancePct: null, applicationDeadline: null, allowedProgramIds: null })).toEqual([]);
  });

  it('says which batch a job is for, and offers a sensible range of batches', () => {
    const rules = jobRequirements({ minCgpa: null, minAttendancePct: null, applicationDeadline: null, allowedProgramIds: null, graduatingYear: 2027 });
    expect(rules).toEqual(['Batch of 2027 only']);
    expect(jobRequirements({ minCgpa: null, minAttendancePct: null, applicationDeadline: null, allowedProgramIds: null, graduatingYear: null })).toEqual([]);

    const now = new Date('2026-10-21T08:00:00Z');
    expect(batchYears(null, now)).toEqual([2025, 2026, 2027, 2028, 2029, 2030, 2031]);
    expect(batchYears(2040, now)).toContain(2040);
    expect(batchYears(2027, now).filter((y) => y === 2027)).toHaveLength(1);
  });

  it('turns a deadline into a form value and back without losing the instant', () => {
    const iso = '2026-12-01T10:30:00.000Z';

    expect(fromLocalInput(toLocalInput(iso))).toBe(iso);
    expect(toLocalInput(null)).toBe('');
    expect(fromLocalInput('')).toBeNull();
  });
});

describe('the student\'s job card says why they cannot apply, and does not offer what the server would refuse', () => {
  function careerApi(job: Record<string, unknown>, eligibility: Record<string, unknown>) {
    mockApi({
      '/career/student-skills': [],
      '/career/skills': [],
      '/career/jobs': [job],
      '/career/applications/eligibility': eligibility,
      '/career/applications': [],
      '/career/placement-records': [],
    });
  }

  it('lists each unmet rule, shows what the job asks and offers, and disables Apply with the reasons attached', async () => {
    signedInAs('STUDENT', { studentId: STUDENT });
    careerApi(
      { ...baseJob, minCgpa: 7.5, minAttendancePct: 80, packageLpa: 12.5, location: 'Pune', openings: 3, jobType: 'FULL_TIME', allowedProgramIds: [PROGRAM_CS], graduatingYear: 2027 },
      {
        eligible: false, missingOrInsufficientSkillIds: ['s1', 's2'],
        unmetRules: [
          { code: 'MIN_CGPA', message: 'Needs a CGPA of at least 7.5; yours is 6.4.' },
          { code: 'MIN_ATTENDANCE', message: 'Needs attendance of at least 80%; yours is 33.3%.' },
          { code: 'GRADUATING_YEAR', message: 'This job is for the batch of 2027; yours is 2028.' },
        ],
      },
    );
    const { container } = renderPage(<StudentCareerPage />);

    const why = await screen.findByText('You cannot apply yet:');
    const list = why.parentElement as HTMLElement;
    expect(within(list).getByText('Needs a CGPA of at least 7.5; yours is 6.4.')).toBeTruthy();
    expect(within(list).getByText('Needs attendance of at least 80%; yours is 33.3%.')).toBeTruthy();
    expect(within(list).getByText('This job is for the batch of 2027; yours is 2028.')).toBeTruthy();
    expect(within(list).getByText(/Missing 2 required skills/)).toBeTruthy();
    expect(screen.getByText('Full time · ₹12.5 LPA · Pune · 3 openings')).toBeTruthy();
    expect(screen.getByText(/CGPA of at least 7\.5 · Attendance of at least 80% · Open to certain programs only · Batch of 2027 only/)).toBeTruthy();
    const apply = screen.getByRole('button', { name: 'Apply' }) as HTMLButtonElement;
    expect(apply.disabled).toBe(true);
    expect(apply.getAttribute('aria-describedby')).toBe(why.parentElement!.id);
    await expectAccessible(container);
  });

  it('offers Apply, and applies, when every rule is met', async () => {
    signedInAs('STUDENT', { studentId: STUDENT });
    careerApi({ ...baseJob, minCgpa: 6 }, { eligible: true, missingOrInsufficientSkillIds: [], unmetRules: [] });
    const user = userEvent.setup();
    renderPage(<StudentCareerPage />);

    expect(await screen.findByText('You meet the requirements for this role.')).toBeTruthy();
    const apply = screen.getByRole('button', { name: 'Apply' }) as HTMLButtonElement;
    expect(apply.disabled).toBe(false);
    await user.click(apply);

    await waitFor(() => expect(vi.mocked(apiFetch).mock.calls.some(([p, init]) => p === '/career/applications' && (init as { method?: string })?.method === 'POST')).toBe(true));
  });

  it('does not offer Apply before the check has answered', async () => {
    signedInAs('STUDENT', { studentId: STUDENT });
    mockApi({
      '/career/student-skills': [], '/career/skills': [], '/career/jobs': [baseJob], '/career/applications': [], '/career/placement-records': [],
      '/career/applications/eligibility': () => new Promise(() => { /* never answers */ }),
    });
    renderPage(<StudentCareerPage />);

    const apply = (await screen.findByRole('button', { name: 'Apply' })) as HTMLButtonElement;
    expect(apply.disabled).toBe(true);
  });

  it('says nothing about rules for a job that has none', async () => {
    signedInAs('STUDENT', { studentId: STUDENT });
    careerApi(baseJob, { eligible: true, missingOrInsufficientSkillIds: [], unmetRules: [] });
    renderPage(<StudentCareerPage />);

    await screen.findByText('You meet the requirements for this role.');
    expect(screen.queryByText(/What this job asks/)).toBeNull();
  });
});

describe('the officer\'s form for a job', () => {
  function officerApi(onSave: (path: string, init: { method?: string; body?: Record<string, unknown> }) => unknown, jobs: unknown[] = []) {
    mockApi({
      '/career/jobs': (path: string, init?: { method?: string; body?: Record<string, unknown> }) => (init?.method === 'POST' || init?.method === 'PUT' ? onSave(path, init) : jobs),
      '/career/skills': [],
      '/catalog/programs': [
        { id: PROGRAM_CS, tenantId: TENANT, departmentId: 'd', code: 'CS', name: 'Computer Science', status: 'ACTIVE' },
        { id: PROGRAM_ARTS, tenantId: TENANT, departmentId: 'd', code: 'AR', name: 'Arts', status: 'ACTIVE' },
      ],
      '/directory/students': { content: [], page: 0, size: 500, totalElements: 0, totalPages: 0 },
      '/career/applications': [],
      '/career/job-skills': [],
    });
  }

  const saveBody = () => {
    const call = vi.mocked(apiFetch).mock.calls.find(([, init]) => (init as { method?: string } | undefined)?.method === 'POST');
    return (call?.[1] as { body: Record<string, unknown> }).body;
  };

  it('posts a job with its rules and details, programs as ids, blanks as nothing', async () => {
    signedInAs('PLACEMENT_OFFICER');
    officerApi(() => ({ ...baseJob }));
    const user = userEvent.setup();
    const { container } = renderPage(<PlacementJobsPage />);

    await user.type(await screen.findByLabelText('Title'), 'Data Analyst');
    await user.type(screen.getByLabelText('Employer'), 'Globex');
    await user.type(screen.getByLabelText('Description'), 'Analyse things');
    await user.type(screen.getByLabelText('Minimum CGPA'), '7.5');
    await user.type(screen.getByLabelText('Minimum attendance (%)'), '80');
    await user.click(await screen.findByLabelText('Computer Science'));
    await user.type(screen.getByLabelText('Package (LPA)'), '9.5');
    await user.type(screen.getByLabelText('Location'), '  Pune ');
    await user.selectOptions(screen.getByLabelText('Type'), 'INTERNSHIP');
    await expectAccessible(container);
    await user.click(screen.getByRole('button', { name: 'Post job' }));

    await waitFor(() => expect(saveBody()).toBeTruthy());
    expect(saveBody()).toMatchObject({
      tenantId: TENANT, title: 'Data Analyst', employer: 'Globex', minCgpa: 7.5, minAttendancePct: 80, packageLpa: 9.5, location: 'Pune', jobType: 'INTERNSHIP',
      allowedProgramIds: [PROGRAM_CS], applicationDeadline: null, openings: null,
    });
    expect(await screen.findByText('Job posted.')).toBeTruthy();
  });

  it('posts the graduating batch the officer picks, and none when left on any batch', async () => {
    signedInAs('PLACEMENT_OFFICER');
    officerApi(() => ({ ...baseJob }));
    const user = userEvent.setup();
    renderPage(<PlacementJobsPage />);

    await user.type(await screen.findByLabelText('Title'), 'Graduate Trainee');
    await user.type(screen.getByLabelText('Employer'), 'Globex');
    await user.type(screen.getByLabelText('Description'), 'For the final year');
    const batch = screen.getByLabelText('Graduating batch') as HTMLSelectElement;
    expect(batch.value).toBe('');
    await user.selectOptions(batch, `Batch of ${new Date().getFullYear() + 1}`);
    await user.click(screen.getByRole('button', { name: 'Post job' }));

    await waitFor(() => expect(saveBody()).toBeTruthy());
    expect(saveBody()).toMatchObject({ graduatingYear: new Date().getFullYear() + 1 });
  });

  it('sends nothing for a rule left blank, so a blank box is never a zero', async () => {
    signedInAs('PLACEMENT_OFFICER');
    officerApi(() => ({ ...baseJob }));
    const user = userEvent.setup();
    renderPage(<PlacementJobsPage />);

    await user.type(await screen.findByLabelText('Title'), 'Intern');
    await user.type(screen.getByLabelText('Employer'), 'Initech');
    await user.type(screen.getByLabelText('Description'), 'Learn');
    await user.click(screen.getByRole('button', { name: 'Post job' }));

    await waitFor(() => expect(saveBody()).toBeTruthy());
    expect(saveBody()).toMatchObject({ minCgpa: null, minAttendancePct: null, packageLpa: null, openings: null, jobType: null, allowedProgramIds: null, applicationDeadline: null, graduatingYear: null });
  });

  it('shows the server\'s reason, as an alert, when it refuses the terms', async () => {
    signedInAs('PLACEMENT_OFFICER');
    officerApi(() => { throw new Error('the application deadline must be in the future'); });
    const user = userEvent.setup();
    renderPage(<PlacementJobsPage />);

    await user.type(await screen.findByLabelText('Title'), 'Intern');
    await user.type(screen.getByLabelText('Employer'), 'Initech');
    await user.type(screen.getByLabelText('Description'), 'Learn');
    await user.click(screen.getByRole('button', { name: 'Post job' }));

    expect((await screen.findByRole('alert')).textContent).toContain('deadline must be in the future');
  });
});
