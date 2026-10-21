import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiDownload, apiFetch } from '../api/client';
import { fileProblem, sizeText } from '../lib/resume';
import PlacementJobsPage from '../pages/placement/PlacementJobsPage';
import StudentResumePage from '../pages/student/StudentResumePage';
import { expectAccessible, mockApi, renderPage, signedInAs, TENANT } from './helpers';

const STUDENT = '44444444-4444-4444-8444-444444444444';
const FILE = { name: 'My Resume.pdf', size: 250 * 1024, sha256: 'a'.repeat(64) };
const content = { headline: 'Aspiring analyst', summary: null, linkedinUrl: null, githubUrl: null, portfolioUrl: null, items: [] };
const record = { cgpa: 2.25, attendancePercentage: 33.33, programId: null, expectedGraduationYear: 2028 };
const view = (over: Record<string, unknown> = {}) => ({ studentId: STUDENT, version: 1, content, updatedAt: '2026-10-20T08:00:00Z', record, skills: [], file: null, ...over });
const pdf = (name = 'cv.pdf', size = 1000) => new File([new Uint8Array(size)], name, { type: 'application/pdf' });

describe('what the browser can say about a file before sending it', () => {
  it('writes sizes and refuses the obvious problems, leaving the content to the server', () => {
    expect(sizeText(900)).toBe('900 bytes');
    expect(sizeText(250 * 1024)).toBe('250 KB');
    expect(sizeText(1.5 * 1024 * 1024)).toBe('1.5 MB');
    expect(fileProblem({ name: 'cv.pdf', size: 1000 })).toBeNull();
    expect(fileProblem({ name: 'CV.PDF', size: 1000 })).toBeNull();
    expect(fileProblem({ name: 'cv.pdf', size: 0 })).toBe('That file is empty.');
    expect(fileProblem({ name: 'cv.pdf', size: 3 * 1024 * 1024 })).toBe('That file is 3.0 MB; the limit is 2 MB.');
    expect(fileProblem({ name: 'cv.docx', size: 1000 })).toBe('Only PDF files are accepted.');
  });
});

describe('the student\'s resume file', () => {
  function api(resume = view(), onUpload: (body: unknown) => unknown = () => ({ file: FILE, version: 2 })) {
    mockApi({
      '/career/resumes/file': (_path: string, init?: { method?: string; body?: unknown }) => (init?.method === 'POST' ? onUpload(init.body) : undefined),
      '/career/resumes': resume,
      '/career/skills': [],
    });
  }

  it('uploads a chosen PDF as a form, shows it, and keeps what was being typed', async () => {
    signedInAs('STUDENT', { studentId: STUDENT });
    const uploads: unknown[] = [];
    api(view(), (b) => { uploads.push(b); return { file: FILE, version: 2 }; });
    const user = userEvent.setup();
    const { container } = renderPage(<StudentResumePage />);

    const headline = (await screen.findByLabelText('Headline')) as HTMLInputElement;
    await waitFor(() => expect(headline.value).toBe('Aspiring analyst'));
    await user.type(headline, ' (typed, not yet saved)');
    await user.upload(screen.getByLabelText('Choose a PDF'), pdf('My Resume.pdf'));

    expect(await screen.findByText('Your PDF is saved and will be sent with your applications.')).toBeTruthy();
    expect(uploads).toHaveLength(1);
    expect(uploads[0]).toBeInstanceOf(FormData);
    expect((uploads[0] as FormData).get('studentId')).toBe(STUDENT);
    expect(((uploads[0] as FormData).get('file') as File).name).toBe('My Resume.pdf');
    expect(screen.getAllByText('My Resume.pdf').length).toBeGreaterThan(0);
    expect((screen.getByLabelText('Headline') as HTMLInputElement).value).toBe('Aspiring analyst (typed, not yet saved)');
    expect(screen.getByLabelText('Replace with another PDF')).toBeTruthy();
    await expectAccessible(container);
  });

  it('refuses a Word file or an oversized one in the browser, without sending anything', async () => {
    signedInAs('STUDENT', { studentId: STUDENT });
    const uploads: unknown[] = [];
    api(view(), (b) => { uploads.push(b); return { file: FILE, version: 2 }; });
    renderPage(<StudentResumePage />);
    const input = (await screen.findByLabelText('Choose a PDF')) as HTMLInputElement;

    fireEvent.change(input, { target: { files: [pdf('cv.docx', 100)] } });
    expect((await screen.findByRole('alert')).textContent).toBe('Only PDF files are accepted.');
    fireEvent.change(input, { target: { files: [pdf('cv.pdf', 3 * 1024 * 1024)] } });
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('the limit is 2 MB'));

    expect(uploads).toEqual([]);
  });

  it('says what the server refused', async () => {
    signedInAs('STUDENT', { studentId: STUDENT });
    api(view(), () => { throw new Error('this PDF is using features a resume must not (scripts, attachments or links to other files); save a plain copy and upload that'); });
    const user = userEvent.setup();
    renderPage(<StudentResumePage />);

    await user.upload(await screen.findByLabelText('Choose a PDF'), pdf());

    expect((await screen.findByRole('alert')).textContent).toContain('features a resume must not');
  });

  it('shows the file already saved, downloads it with the sign-in token, and removes it on confirmation', async () => {
    signedInAs('STUDENT', { studentId: STUDENT });
    api(view({ file: FILE }));
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const user = userEvent.setup();
    renderPage(<StudentResumePage />);

    await screen.findAllByText('My Resume.pdf');
    expect(screen.getByLabelText('Replace with another PDF')).toBeTruthy();
    await user.click(screen.getAllByRole('button', { name: 'Download' })[0]);
    expect(vi.mocked(apiDownload)).toHaveBeenCalledWith(`/career/resumes/file?studentId=${STUDENT}`);

    await user.click(screen.getByRole('button', { name: 'Remove' }));
    await waitFor(() => expect(vi.mocked(apiFetch).mock.calls.some(([p, init]) => p === `/career/resumes/file?studentId=${STUDENT}` && (init as { method?: string })?.method === 'DELETE')).toBe(true));
    expect(await screen.findByText('Your PDF was removed.')).toBeTruthy();
  });
});

describe('the officer downloads the file an application was made with', () => {
  const job = {
    id: 'job-1', tenantId: TENANT, postedBy: 'p', title: 'Analyst', employer: 'Example Co', description: 'd', status: 'OPEN', version: 1, minCgpa: null, minAttendancePct: null,
    applicationDeadline: null, allowedProgramIds: null, graduatingYear: null, packageLpa: null, location: null, openings: null, jobType: null, selectedCount: 0, closedReason: null,
  };

  it('offers the file inside the submitted resume and fetches it through the application', async () => {
    signedInAs('PLACEMENT_OFFICER');
    mockApi({
      '/career/jobs': [job], '/career/skills': [], '/catalog/programs': [], '/career/job-skills': [],
      '/directory/students': { content: [{ id: STUDENT, firstName: 'Priya', lastName: 'Nair' }], page: 0, size: 500, totalElements: 1, totalPages: 1 },
      '/career/applications/by-job': [{ id: 'a1', tenantId: TENANT, jobId: 'job-1', studentId: STUDENT, status: 'SUBMITTED', appliedAt: '2026-10-20T08:00:00Z', notes: null, decisionReason: null, decidedAt: null, hasResume: true }],
      '/career/applications/a1/resume': { capturedAt: '2026-10-20T08:00:00Z', resumeVersion: 2, content, record, skills: [], file: FILE },
    });
    const user = userEvent.setup();
    renderPage(<PlacementJobsPage />);

    await user.click(await screen.findByRole('button', { name: 'Applications' }));
    await user.click(await screen.findByRole('button', { name: 'View resume' }));
    await screen.findByText(/Resume file:/);
    await user.click(screen.getByRole('button', { name: 'Download' }));

    expect(vi.mocked(apiDownload)).toHaveBeenCalledWith('/career/applications/a1/resume/file');
  });
});

describe('the real client', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('sends a form as a form (no JSON type, no JSON text) and a JSON body as JSON', async () => {
    const { apiFetch: real } = await vi.importActual<typeof import('../api/client')>('../api/client');
    const fetchSpy = vi.fn(async () => new Response('{"ok":true}', { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchSpy);
    const form = new FormData();
    form.append('file', pdf());

    await real('/career/resumes/file', { method: 'POST', body: form });
    await real('/career/resumes', { method: 'PUT', body: { headline: 'h' } });

    const [, formInit] = fetchSpy.mock.calls[0] as unknown as [string, RequestInit];
    expect(formInit.body).toBe(form);
    expect((formInit.headers as Record<string, string>)['Content-Type']).toBeUndefined();
    const [, jsonInit] = fetchSpy.mock.calls[1] as unknown as [string, RequestInit];
    expect(jsonInit.body).toBe('{"headline":"h"}');
    expect((jsonInit.headers as Record<string, string>)['Content-Type']).toBe('application/json');
  });

  it('saves a download under the name the server offered, and throws the server\'s words when it refuses', async () => {
    const { apiDownload: real } = await vi.importActual<typeof import('../api/client')>('../api/client');
    const saved: string[] = [];
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:x', revokeObjectURL: () => undefined }));
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { saved.push(this.download); });
    vi.stubGlobal('fetch', vi.fn(async () => new Response('%PDF-1.4', { status: 200, headers: { 'Content-Disposition': 'attachment; filename="My Resume.pdf"' } })));

    await real('/career/resumes/file?studentId=x');
    expect(saved).toEqual(['My Resume.pdf']);

    vi.stubGlobal('fetch', vi.fn(async () => new Response('{"message":"this application was made without a resume file"}', { status: 404, headers: { 'Content-Type': 'application/json' } })));
    await expect(real('/career/applications/a/resume/file')).rejects.toThrow('this application was made without a resume file');
    click.mockRestore();
  });
});
