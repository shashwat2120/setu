import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiFetch } from '../api/client';
import FacultyAttendancePage from '../pages/faculty/FacultyAttendancePage';
import StudentAttendancePage from '../pages/student/StudentAttendancePage';
import { expectAccessible, mockApi, renderPage, signedInAs, TENANT } from './helpers';

// the live board is a WebSocket; a page test has none
vi.mock('../api/useAttendanceLiveUpdates', () => ({ useAttendanceLiveUpdates: () => ({ connected: true, error: null }) }));

const SECTION = '66666666-6666-4666-8666-666666666666';
const ENROLLMENT = '77777777-7777-4777-8777-777777777777';
const MEETING = '88888888-8888-4888-8888-888888888888';
const SESSION = '99999999-9999-4999-8999-999999999999';
const COURSE = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';

function geolocation(impl: 'ok' | 'denied' | 'absent', coords = { latitude: 19.076, longitude: 72.8777, accuracy: 12 }) {
  if (impl === 'absent') {
    Object.defineProperty(navigator, 'geolocation', { value: undefined, configurable: true });
    return;
  }
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: {
      getCurrentPosition: (ok: (p: unknown) => void, fail: (e: unknown) => void) =>
        impl === 'ok' ? ok({ coords }) : fail({ code: 1, message: 'User denied Geolocation' }),
    },
  });
}

const section = { id: SECTION, tenantId: TENANT, courseId: COURSE, periodId: 'p', classId: null, sectionCode: 'CS101-A', capacity: 30, roomId: null, status: 'OPEN', registrationOpenAt: null, registrationCloseAt: null };
const course = { id: COURSE, tenantId: TENANT, departmentId: 'd', code: 'CS101', title: 'Introduction to Programming', credits: 4, courseType: 'CORE', level: null, description: null, status: 'ACTIVE' };

function studentApi(onScan?: (body: unknown) => unknown) {
  mockApi({
    '/enrollment/enrollments': { content: [{ id: ENROLLMENT, tenantId: TENANT, sectionId: SECTION, studentId: 's', status: 'ENROLLED', requestedAt: null, enrolledAt: null, droppedAt: null, finalStatus: null }], page: 0, size: 100, totalElements: 1, totalPages: 1 },
    '/enrollment/course-sections/': section,
    '/catalog/courses/': course,
    '/attendance/enrollments/': { presentOrLate: 3, total: 5, percentage: 60 },
    // the student's own check-in history: one in the room, one far away, one marked by the system with no place
    [`/attendance/enrollments/${ENROLLMENT}/records`]: [
      { id: 'rec-1', attendanceSessionId: 's1', enrollmentId: ENROLLMENT, status: 'PRESENT', markedAt: '2026-10-20T09:05:00Z', source: 'QR_SCAN', correctedBy: null, locationStatus: 'NEAR', locationDistanceMeters: 10, locationAccuracyMeters: 12 },
      { id: 'rec-2', attendanceSessionId: 's2', enrollmentId: ENROLLMENT, status: 'PRESENT', markedAt: '2026-10-19T09:07:00Z', source: 'QR_SCAN', correctedBy: null, locationStatus: 'FAR', locationDistanceMeters: 5560, locationAccuracyMeters: 20 },
      { id: 'rec-3', attendanceSessionId: 's3', enrollmentId: ENROLLMENT, status: 'ABSENT', markedAt: '2026-10-18T10:00:00Z', source: 'SYSTEM', correctedBy: null, locationStatus: null, locationDistanceMeters: null, locationAccuracyMeters: null },
    ],
    '/attendance/scan': (_p: string, init?: { body?: unknown }) => onScan?.(init?.body) ?? { recordId: 'r', status: 'PRESENT', locationStatus: 'NEAR' },
  });
}

async function fillCheckIn(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Session ID'), SESSION);
  await user.type(screen.getByLabelText('Check-in token'), 'token-from-the-instructor');
  await user.click(screen.getByRole('button', { name: 'Check in' }));
}

const requestBody = (path: string) => {
  const call = vi.mocked(apiFetch).mock.calls.find(([p, init]) => p === path && (init as { method?: string } | undefined)?.method === 'POST');
  return (call?.[1] as { body: Record<string, unknown> } | undefined)?.body;
};

describe('student check-in (spec section 13: authentication/QR accessibility, forms and errors)', () => {
  afterEach(() => geolocation('absent'));

  it('has no WCAG 2.2 AA violations an automated engine can find', async () => {
    signedInAs('STUDENT');
    studentApi();
    const { container } = renderPage(<StudentAttendancePage />);
    await screen.findByText('CS101 — Introduction to Programming');
    await expectAccessible(container);
  });

  it('shows the student their own recent check-ins and where each was made from', async () => {
    signedInAs('STUDENT');
    studentApi();
    renderPage(<StudentAttendancePage />);
    const heading = await screen.findByRole('heading', { name: 'Your recent check-ins' });
    const table = within(heading.closest('section') as HTMLElement).getByRole('table');
    expect(within(table).getByRole('columnheader', { name: 'Checked in from' })).toBeTruthy();
    expect(within(table).getByText('In the room (10 m)')).toBeTruthy();
    expect(within(table).getByText(/Away from the room/)).toBeTruthy();
    // a mark the system wrote has no place, and the page does not invent one
    const absentRow = within(table).getByText('ABSENT').closest('tr') as HTMLElement;
    expect(within(absentRow).getByText('—')).toBeTruthy();
  });

  it('shows no history, and does not break, when the server sends none or something that is not a list', async () => {
    signedInAs('STUDENT');
    mockApi({
      '/enrollment/enrollments': { content: [{ id: ENROLLMENT, tenantId: TENANT, sectionId: SECTION, studentId: 's', status: 'ENROLLED', requestedAt: null, enrolledAt: null, droppedAt: null, finalStatus: null }], page: 0, size: 100, totalElements: 1, totalPages: 1 },
      '/enrollment/course-sections/': section,
      '/catalog/courses/': course,
      '/attendance/enrollments/': { presentOrLate: 0, total: 0, percentage: 0 },
    });
    renderPage(<StudentAttendancePage />);
    await screen.findByText('CS101 — Introduction to Programming');
    expect(await screen.findByText('No check-ins yet')).toBeTruthy();
  });

  it('tells the student, before the browser asks, why location is wanted and that declining is fine', async () => {
    signedInAs('STUDENT');
    studentApi();
    renderPage(<StudentAttendancePage />);
    const note = await screen.findByText(/Your device will ask to share your location/);
    expect(note.textContent).toContain('your coordinates are not saved');
    expect(note.textContent).toContain('Declining still lets you check in');
  });

  it('sends the position with the check-in and says, in a live region, how it compared with the classroom', async () => {
    signedInAs('STUDENT');
    geolocation('ok');
    studentApi();
    const user = userEvent.setup();
    renderPage(<StudentAttendancePage />);
    await screen.findByText('CS101 — Introduction to Programming');

    await fillCheckIn(user);

    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('Your location matches the classroom'));
    expect(requestBody('/attendance/scan')).toMatchObject({ sessionId: SESSION, latitude: 19.076, longitude: 72.8777, accuracyMeters: 12 });
  });

  it('checks in all the same when the student declines to share a location', async () => {
    signedInAs('STUDENT');
    geolocation('denied');
    studentApi(() => ({ recordId: 'r', status: 'PRESENT', locationStatus: 'NO_LOCATION' }));
    const user = userEvent.setup();
    renderPage(<StudentAttendancePage />);
    await screen.findByText('CS101 — Introduction to Programming');

    await fillCheckIn(user);

    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('Check-in recorded.'));
    expect(screen.getByRole('status').textContent).toContain('not shared');
    const body = requestBody('/attendance/scan')!;
    expect(body).not.toHaveProperty('latitude');
    expect(body).not.toHaveProperty('longitude');
  });

  it('checks in on a device or page with no location support at all', async () => {
    signedInAs('STUDENT');
    geolocation('absent');
    studentApi();
    const user = userEvent.setup();
    renderPage(<StudentAttendancePage />);
    await screen.findByText('CS101 — Introduction to Programming');

    await fillCheckIn(user);

    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('Check-in recorded.'));
  });

  it('tells the student a far check-in will be seen by the instructor, without blocking it', async () => {
    signedInAs('STUDENT');
    geolocation('ok', { latitude: 19.5, longitude: 72.8777, accuracy: 10 });
    studentApi(() => ({ recordId: 'r', status: 'PRESENT', locationStatus: 'FAR' }));
    const user = userEvent.setup();
    renderPage(<StudentAttendancePage />);
    await screen.findByText('CS101 — Introduction to Programming');

    await fillCheckIn(user);

    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('Check-in recorded.'));
    expect(screen.getByRole('status').textContent).toContain('far from the classroom');
  });
});

describe('instructor live attendance (tables, status, location signal)', () => {
  afterEach(() => geolocation('absent'));

  const record = (id: string, enrollment: string, location: string | null, distance: number | null, accuracy: number | null) => ({
    id, attendanceSessionId: SESSION, enrollmentId: enrollment, status: 'PRESENT', markedAt: '2026-10-19T09:00:00Z', source: 'QR_SCAN', correctedBy: null,
    locationStatus: location, locationDistanceMeters: distance, locationAccuracyMeters: accuracy,
  });

  function facultyApi() {
    mockApi({
      '/enrollment/faculty-assignments': [{ id: 'a', tenantId: TENANT, sectionId: SECTION, facultyId: 'f', role: 'PRIMARY', allocationPct: 100, startsAt: null, endsAt: null, active: true }],
      '/enrollment/course-sections/': section,
      '/catalog/courses/': course,
      '/catalog/meeting-patterns': [{ id: MEETING, tenantId: TENANT, sectionId: SECTION, dayOfWeek: 1, startTime: '09:00', endTime: '10:00', roomId: 'r', effectiveFrom: '2026-01-01', effectiveTo: null }],
      '/attendance/sessions/': [
        record('r1', '11111111-aaaa-4aaa-8aaa-111111111111', 'NEAR', 44, 12),
        record('r2', '22222222-bbbb-4bbb-8bbb-222222222222', 'FAR', 8200, 20),
        record('r3', '33333333-cccc-4ccc-8ccc-333333333333', 'NO_LOCATION', null, null),
      ],
      '/attendance/sessions': { sessionId: SESSION, qrToken: 'rotating-token', qrExpiresAt: '2026-10-19T09:30:00Z' },
    });
  }

  async function openSession(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByRole('button', { name: 'Open attendance session' }));
    await screen.findByText(/SESSION OPEN/);
  }

  it('shares the instructor device position as the classroom position when the session opens, and says so', async () => {
    signedInAs('FACULTY');
    geolocation('ok');
    facultyApi();
    const user = userEvent.setup();
    renderPage(<FacultyAttendancePage />);

    await openSession(user);

    expect(requestBody('/attendance/sessions')).toMatchObject({ sectionId: SECTION, meetingId: MEETING, latitude: 19.076, longitude: 72.8777 });
    expect(await screen.findByText(/Classroom position: taken from this device/)).toBeTruthy();
  });

  it('still opens the session when the instructor declines, and says check-ins will show no distance', async () => {
    signedInAs('FACULTY');
    geolocation('denied');
    facultyApi();
    const user = userEvent.setup();
    renderPage(<FacultyAttendancePage />);

    await openSession(user);

    expect(requestBody('/attendance/sessions')).not.toHaveProperty('latitude');
    expect(await screen.findByText(/not shared from this device/)).toBeTruthy();
  });

  it('says the session is closed once it is, and lets the instructor open another without reloading', async () => {
    signedInAs('FACULTY');
    geolocation('ok');
    mockApi({
      '/enrollment/faculty-assignments': [{ id: 'a', tenantId: TENANT, sectionId: SECTION, facultyId: 'f', role: 'PRIMARY', allocationPct: 100, startsAt: null, endsAt: null, active: true }],
      '/enrollment/course-sections/': section,
      '/catalog/courses/': course,
      '/catalog/meeting-patterns': [{ id: MEETING, tenantId: TENANT, sectionId: SECTION, dayOfWeek: 1, startTime: '09:00', endTime: '10:00', roomId: 'r', effectiveFrom: '2026-01-01', effectiveTo: null }],
      [`/attendance/sessions/${SESSION}/close`]: { absentRecordsGenerated: 4 },
      '/attendance/sessions/': [],
      '/attendance/sessions': { sessionId: SESSION, qrToken: 'rotating-token', qrExpiresAt: '2026-10-19T09:30:00Z' },
    });
    const user = userEvent.setup();
    renderPage(<FacultyAttendancePage />);
    await openSession(user);

    await user.click(await screen.findByRole('button', { name: 'Close session' }));

    expect(await screen.findByText('SESSION CLOSED')).toBeTruthy();
    expect(screen.queryByText('SESSION OPEN')).toBeNull();
    expect(screen.getByText(/4 students marked absent automatically/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Open another session' }));
    expect(await screen.findByRole('button', { name: 'Open attendance session' })).toBeTruthy();
  });

  it('says so when it resumes a session that was opened without a classroom position', async () => {
    signedInAs('FACULTY');
    geolocation('ok');
    mockApi({
      '/enrollment/faculty-assignments': [{ id: 'a', tenantId: TENANT, sectionId: SECTION, facultyId: 'f', role: 'PRIMARY', allocationPct: 100, startsAt: null, endsAt: null, active: true }],
      '/enrollment/course-sections/': section,
      '/catalog/courses/': course,
      '/catalog/meeting-patterns': [{ id: MEETING, tenantId: TENANT, sectionId: SECTION, dayOfWeek: 1, startTime: '09:00', endTime: '10:00', roomId: 'r', effectiveFrom: '2026-01-01', effectiveTo: null }],
      [`/attendance/sessions/${SESSION}/qr`]: { qrToken: 'again', qrExpiresAt: '2026-10-19T09:30:00Z' },
      '/attendance/sessions/': [],
      '/attendance/sessions': (_p: string, init?: { method?: string }) => {
        if (init?.method === 'POST') throw new ApiError(409, 'an attendance session is already open for this meeting');
        return [{ id: SESSION, tenantId: TENANT, sectionId: SECTION, meetingId: MEETING, startedAt: '2026-10-19T09:00:00Z', endsAt: '2026-10-19T10:00:00Z', status: 'OPEN', hasClassroomPosition: false }];
      },
    });
    const user = userEvent.setup();
    renderPage(<FacultyAttendancePage />);

    await user.click(await screen.findByRole('button', { name: 'Open attendance session' }));

    const note = await screen.findByText(/not shared from this device/);
    expect(note.textContent).toContain('opened without it and cannot gain one');
    expect(note.textContent).toContain('close it and open a new session');
  });

  it('shows where each student checked in from, in words, with a table a screen reader can navigate', async () => {
    signedInAs('FACULTY');
    geolocation('ok');
    facultyApi();
    const user = userEvent.setup();
    const { container } = renderPage(<FacultyAttendancePage />);
    await openSession(user);

    const table = await screen.findByRole('table');
    const headers = within(table).getAllByRole('columnheader');
    expect(headers.map((h) => h.textContent)).toEqual(['Enrollment', 'Status', 'Checked in from']);
    for (const h of headers) expect(h.getAttribute('scope')).toBe('col');
    const text = table.textContent!;
    expect(text).toContain('In the room (44 m)');
    expect(text).toContain('Away from the room (8.2 km)');
    expect(text).toContain('Location not shared');
    await expectAccessible(container);
  });
});
