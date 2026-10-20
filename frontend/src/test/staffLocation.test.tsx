import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiFetch } from '../api/client';
import FacultyMyAttendancePage from '../pages/faculty/FacultyMyAttendancePage';
import { StaffLocationPolicyCard } from '../components/staff/StaffLocationPolicyCard';
import { expectAccessible, mockApi, renderPage, signedInAs } from './helpers';

const TODAY = { date: '2026-10-20', serverTime: '2026-10-20T04:00:00Z', timeZone: 'Asia/Kolkata', record: null };
const POLICY = { version: 0, shiftStart: '09:00', shiftEnd: '17:00', graceMinutes: 15, fullDayMinutes: 420, halfDayMinutes: 240 };
const savedRecord = (locationStatus: string | null) => ({
  id: 'r1', tenantId: 't', facultyId: 'f', attendanceDate: '2026-10-20', status: 'PRESENT', checkInAt: '2026-10-20T04:00:00Z', checkOutAt: null,
  source: 'SELF', correctedBy: null, correctionReason: null, lateMinutes: null, workedMinutes: null, policyVersion: 0, shortHours: false,
  locationStatus, locationDistanceMeters: locationStatus === 'NEAR' ? 40 : null, locationAccuracyMeters: null,
});

function geolocation(impl: 'ok' | 'absent') {
  if (impl === 'absent') {
    Object.defineProperty(navigator, 'geolocation', { value: undefined, configurable: true });
    return;
  }
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: { getCurrentPosition: (ok: (p: unknown) => void) => ok({ coords: { latitude: 12.9716, longitude: 77.5946, accuracy: 15 } }) },
  });
}

function facultyApi(enabled: boolean, answerFor: (body: unknown) => unknown) {
  mockApi({
    '/staff-attendance/me/today': TODAY,
    '/staff-attendance/regularisations/me': [],
    '/staff-attendance/policy': POLICY,
    '/staff-attendance/location-policy': { enabled, campusConfigured: enabled, radiusMeters: 300, campusLatitude: null, campusLongitude: null },
    '/staff-attendance/faculty/': [],
    '/staff-attendance/me/check-in': (_p: string, init?: { body?: unknown }) => answerFor(init?.body),
  });
}

const checkInBody = () => {
  const call = vi.mocked(apiFetch).mock.calls.find(([p]) => p === '/staff-attendance/me/check-in');
  return (call?.[1] as { body?: Record<string, unknown> } | undefined)?.body;
};

describe('staff check-in location', () => {
  afterEach(() => geolocation('absent'));

  it('tells staff before their device is asked, sends the position, and says how it compared with the campus', async () => {
    signedInAs('FACULTY');
    geolocation('ok');
    facultyApi(true, () => savedRecord('NEAR'));
    const user = userEvent.setup();
    renderPage(<FacultyMyAttendancePage />);

    const note = await screen.findByRole('note');
    expect(note.textContent).toContain('never your coordinates');
    expect(note.textContent).toContain('Declining still lets you check in');
    await user.click(await screen.findByRole('button', { name: 'Check in' }));

    await waitFor(() => expect(checkInBody()).toMatchObject({ latitude: 12.9716, longitude: 77.5946, accuracyMeters: 15 }));
    expect(await screen.findByText('Checked in. Your location matches the campus.')).toBeTruthy();
  });

  it('asks nothing of the device, and says nothing about location, when the college has not switched it on', async () => {
    signedInAs('FACULTY');
    geolocation('ok');
    facultyApi(false, () => savedRecord(null));
    const user = userEvent.setup();
    renderPage(<FacultyMyAttendancePage />);

    await user.click(await screen.findByRole('button', { name: 'Check in' }));

    await waitFor(() => expect(vi.mocked(apiFetch).mock.calls.some(([p]) => p === '/staff-attendance/me/check-in')).toBe(true));
    expect(checkInBody()).toBeUndefined();
    expect(screen.queryByRole('note')).toBeNull();
  });

  it('still checks in when the device will not share a position', async () => {
    signedInAs('FACULTY');
    geolocation('absent');
    facultyApi(true, () => savedRecord('NO_LOCATION'));
    const user = userEvent.setup();
    renderPage(<FacultyMyAttendancePage />);

    await user.click(await screen.findByRole('button', { name: 'Check in' }));

    expect(await screen.findByText(/Your location was not shared/)).toBeTruthy();
  });
});

describe('the administrator\'s setting for it', () => {
  afterEach(() => geolocation('absent'));

  it('is off at first, saves a campus position and radius, and has no accessibility violations', async () => {
    signedInAs('COLLEGE_ADMIN');
    const saved: unknown[] = [];
    mockApi({
      '/staff-attendance/location-policy': (_p: string, init?: { method?: string; body?: unknown }) => {
        if (init?.method === 'PUT') {
          saved.push(init.body);
          return { enabled: true, campusConfigured: true, radiusMeters: 400, campusLatitude: 12.9716, campusLongitude: 77.5946 };
        }
        return { enabled: false, campusConfigured: false, radiusMeters: 300, campusLatitude: null, campusLongitude: null };
      },
    });
    const user = userEvent.setup();
    const { container } = renderPage(<StaffLocationPolicyCard />);
    const toggle = await screen.findByLabelText('Record where staff check in from');
    expect((toggle as HTMLInputElement).checked).toBe(false);
    await expectAccessible(container);

    await user.click(toggle);
    await user.type(screen.getByLabelText('Campus latitude'), '12.9716');
    await user.type(screen.getByLabelText('Campus longitude'), '77.5946');
    await user.clear(screen.getByLabelText('Counts as on campus within (metres)'));
    await user.type(screen.getByLabelText('Counts as on campus within (metres)'), '400');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(saved).toHaveLength(1));
    expect(saved[0]).toEqual({ enabled: true, campusLatitude: 12.9716, campusLongitude: 77.5946, radiusMeters: 400 });
    expect(await screen.findByText('Saved. Staff check-ins now record where they were made from.')).toBeTruthy();
  });

  it('can fill the campus position from the device the administrator is on', async () => {
    signedInAs('COLLEGE_ADMIN');
    geolocation('ok');
    mockApi({ '/staff-attendance/location-policy': { enabled: false, campusConfigured: false, radiusMeters: 300, campusLatitude: null, campusLongitude: null } });
    const user = userEvent.setup();
    renderPage(<StaffLocationPolicyCard />);

    await user.click(await screen.findByRole('button', { name: "Use this device's position" }));

    await waitFor(() => expect((screen.getByLabelText('Campus latitude') as HTMLInputElement).value).toBe('12.971600'));
    expect((screen.getByLabelText('Campus longitude') as HTMLInputElement).value).toBe('77.594600');
  });

  it('shows the server\'s reason when it refuses a setting, as an alert', async () => {
    signedInAs('COLLEGE_ADMIN');
    mockApi({
      '/staff-attendance/location-policy': (_p: string, init?: { method?: string }) => {
        if (init?.method === 'PUT') throw new (class extends Error { status = 400; })('the campus position is needed to switch location on');
        return { enabled: false, campusConfigured: false, radiusMeters: 300, campusLatitude: null, campusLongitude: null };
      },
    });
    const user = userEvent.setup();
    renderPage(<StaffLocationPolicyCard />);

    await user.click(await screen.findByLabelText('Record where staff check in from'));
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByRole('alert')).toBeTruthy();
  });
});

describe('the words for where a check-in came from', () => {
  it('speak of the campus for staff and of the room for students, and never invent a place', async () => {
    const { locationLabel } = await import('../lib/devicePosition');
    expect(locationLabel('NEAR', 33, 'campus')).toBe('On campus (33 m)');
    expect(locationLabel('FAR', 14277, 'campus')).toBe('Away from campus (14.3 km)');
    expect(locationLabel('NO_REFERENCE', null, 'campus')).toBe('No campus position');
    expect(locationLabel('NEAR', 10)).toBe('In the room (10 m)');
    expect(locationLabel('NO_REFERENCE')).toBe('No classroom position');
    expect(locationLabel('NO_LOCATION', null, 'campus')).toBe('Location not shared');
    expect(locationLabel(null, null, 'campus')).toBe('—');
  });
});

describe('why a position could not be had', () => {
  afterEach(() => geolocation('absent'));

  const withError = (code: number) =>
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: { getCurrentPosition: (_ok: unknown, fail: (e: { code: number }) => void) => fail({ code }) },
    });

  it('names the reason for each way it can fail', async () => {
    const { requestDevicePosition, positionHint } = await import('../lib/devicePosition');
    withError(1);
    expect(await requestDevicePosition()).toEqual({ ok: false, problem: 'denied' });
    withError(2);
    expect(await requestDevicePosition()).toEqual({ ok: false, problem: 'unavailable' });
    withError(3);
    expect(await requestDevicePosition()).toEqual({ ok: false, problem: 'timeout' });
    geolocation('absent');
    expect(await requestDevicePosition()).toEqual({ ok: false, problem: 'unsupported' });
    geolocation('ok');
    expect(await requestDevicePosition()).toEqual({ ok: true, position: { latitude: 12.9716, longitude: 77.5946, accuracyMeters: 15 } });
    expect(positionHint('denied')).toContain('Click the pin icon in the address bar');
  });

  it('tells a staff member whose browser blocked location what to do, and still checks them in', async () => {
    signedInAs('FACULTY');
    withError(1);
    facultyApi(true, () => savedRecord('NO_LOCATION'));
    const user = userEvent.setup();
    renderPage(<FacultyMyAttendancePage />);

    await user.click(await screen.findByRole('button', { name: 'Check in' }));

    const message = await screen.findByText(/Your location was not shared/);
    expect(message.textContent).toContain('Your browser has blocked location for this site');
    expect(message.textContent).toContain('pin icon in the address bar');
  });
});

describe('the accuracy of the position the administrator fills in', () => {
  afterEach(() => geolocation('absent'));

  const fixWithAccuracy = (accuracy: number) =>
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: { getCurrentPosition: (ok: (p: unknown) => void) => ok({ coords: { latitude: 18.5516, longitude: 73.8224, accuracy } }) },
    });
  const policy = { enabled: false, campusConfigured: false, radiusMeters: 300, campusLatitude: null, campusLongitude: null };

  it('says how accurate a good fix is', async () => {
    signedInAs('COLLEGE_ADMIN');
    fixWithAccuracy(40);
    mockApi({ '/staff-attendance/location-policy': policy });
    const user = userEvent.setup();
    renderPage(<StaffLocationPolicyCard />);

    await user.click(await screen.findByRole('button', { name: "Use this device's position" }));

    expect(await screen.findByText(/accurate to about 40 m\. Check it, then Save/)).toBeTruthy();
  });

  it('warns, as an alert, that a rough fix should be replaced by coordinates from a map', async () => {
    signedInAs('COLLEGE_ADMIN');
    fixWithAccuracy(1500);
    mockApi({ '/staff-attendance/location-policy': policy });
    const user = userEvent.setup();
    renderPage(<StaffLocationPolicyCard />);

    await user.click(await screen.findByRole('button', { name: "Use this device's position" }));

    const warning = await screen.findByRole('alert');
    expect(warning.textContent).toContain('only accurate to about 1500 m');
    expect(warning.textContent).toContain('type the latitude and longitude from a map');
  });
});
