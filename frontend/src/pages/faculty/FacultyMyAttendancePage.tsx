import { useState } from 'react';
import { apiFetch, ApiError } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { RegularisationResponse, StaffAttendancePolicy, StaffAttendanceResponse, StaffAttendanceToday, StaffLocationPolicy } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { ClockCard } from '../../components/staff/ClockCard';
import { CorrectionForm, type CorrectionValues } from '../../components/staff/CorrectionForm';
import { MonthSummaryCard } from '../../components/staff/MonthSummaryCard';
import { RequestsList } from '../../components/staff/RequestsList';
import { WeekStrip } from '../../components/staff/WeekStrip';
import { addDays, mondayOf, monthStart } from '../../lib/staffCalendar';
import { formatHm, formatMinutes, formatShiftTime, useServerNow } from '../../lib/staffTime';
import { positionHint, requestDevicePosition } from '../../lib/devicePosition';

const time = (iso: string | null) => (iso ? new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '—');

/** Past days only, as many as the server's window allows (30 by default). */
const REQUEST_WINDOW_DAYS = 30;

const EMPTY_FORM: CorrectionValues = { date: '', status: 'PRESENT', inTime: '', outTime: '', reason: '' };

/**
 * A faculty member's OWN attendance dashboard: the live clock with one check-in/check-out button, the month so far, this week, and a
 * request to correct a day they missed. Date, time and timezone all come from the server, so a wrong or tampered browser clock changes
 * nothing; every figure shown is computed from the member's real records.
 */
export default function FacultyMyAttendancePage() {
  const { user } = useAuth();
  const facultyId = user?.facultyId ?? null;
  const today = useApiQuery<StaffAttendanceToday>('/staff-attendance/me/today');
  const requests = useApiQuery<RegularisationResponse[]>('/staff-attendance/regularisations/me');
  const policy = useApiQuery<StaffAttendancePolicy>('/staff-attendance/policy');
  // whether the college records where staff check in from: nothing about the device is asked unless it is on, and the page says so first
  const locationPolicy = useApiQuery<StaffLocationPolicy>('/staff-attendance/location-policy');
  const locationOn = locationPolicy.data?.enabled === true;
  const [showPolicy, setShowPolicy] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [form, setForm] = useState<CorrectionValues>(EMPTY_FORM);
  const [formBusy, setFormBusy] = useState(false);
  const [formMessage, setFormMessage] = useState<{ ok: boolean; text: string } | null>(null);

  const date = today.data?.date ?? null;
  // one query covers the month so far, this week and the 30-day table
  const from = date ? [monthStart(date), mondayOf(date), addDays(date, -29)].sort()[0] : null;
  const history = useApiQuery<StaffAttendanceResponse[]>(
    facultyId && date && from ? `/staff-attendance/faculty/${facultyId}?from=${from}&to=${date}` : null,
    [today.data?.record?.id, today.data?.record?.checkOutAt, requests.data?.filter((r) => r.status === 'APPROVED').length],
  );
  const now = useServerNow(today.data?.serverTime);

  const act = async (path: string) => {
    setBusy(true);
    setMessage(null);
    try {
      // location is a signal for the administrator, never a requirement: declining, or a device without it, checks in the same
      const found = locationOn && path.endsWith('/check-in') ? await requestDevicePosition() : null;
      const position = found?.ok ? found.position : null;
      const saved = await apiFetch<StaffAttendanceResponse>(path, {
        method: 'POST',
        ...(position ? { body: { latitude: position.latitude, longitude: position.longitude, accuracyMeters: position.accuracyMeters } } : {}),
      });
      if (path.endsWith('/check-in') && locationOn) {
        setMessage(saved.locationStatus === 'NEAR' ? 'Checked in. Your location matches the campus.'
          : saved.locationStatus === 'FAR' ? 'Checked in. Your location looks far from the campus; your administrator will see that.'
          : saved.locationStatus === 'NO_LOCATION' ? `Checked in. Your location was not shared, so your administrator sees none.${found && !found.ok ? ` ${positionHint(found.problem)}` : ''}` : null);
      }
      today.refetch();
    } catch (e) {
      setMessage(e instanceof ApiError ? e.message : 'Something went wrong. Please try again.');
      today.refetch();
    } finally {
      setBusy(false);
    }
  };

  const submitRequest = async () => {
    setFormBusy(true);
    setFormMessage(null);
    try {
      await apiFetch<RegularisationResponse>('/staff-attendance/regularisations/me', {
        method: 'POST',
        body: {
          date: form.date,
          status: form.status,
          checkInTime: form.status === 'ON_LEAVE' ? null : form.inTime || null,
          checkOutTime: form.status === 'ON_LEAVE' ? null : form.outTime || null,
          reason: form.reason.trim(),
        },
      });
      setFormMessage({ ok: true, text: 'Sent. An administrator will approve or reject it.' });
      setForm(EMPTY_FORM);
      requests.refetch();
    } catch (e) {
      setFormMessage({ ok: false, text: e instanceof ApiError ? e.message : 'Could not send the request. Please try again.' });
    } finally {
      setFormBusy(false);
    }
  };

  const withdraw = async (id: string) => {
    setFormMessage(null);
    try {
      await apiFetch<RegularisationResponse>(`/staff-attendance/regularisations/${id}/withdraw`, { method: 'POST' });
    } catch (e) {
      setFormMessage({ ok: false, text: e instanceof ApiError ? e.message : 'Could not withdraw the request.' });
    }
    requests.refetch();
  };

  const fixDay = (iso: string) => {
    setForm((f) => ({ ...f, date: iso, status: 'PRESENT' }));
    document.getElementById('correction-form')?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };

  const openHistory = () => {
    setShowHistory(true);
    window.setTimeout(() => document.getElementById('full-history')?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 50);
  };

  const columns: Column<StaffAttendanceResponse>[] = [
    { key: 'date', header: 'Date', render: (r) => <span className="mono">{r.attendanceDate}</span> },
    {
      key: 'status',
      header: 'Status',
      render: (r) => (
        <span style={{ display: 'inline-flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
          <StatusBadge value={r.status} />
          {r.lateMinutes ? <StatusBadge value="PENDING" label={`Late ${formatMinutes(r.lateMinutes)}`} /> : null}
          {r.shortHours ? <StatusBadge value="PENDING" label="Short hours" /> : null}
          {r.checkInAt && !r.checkOutAt && date !== null && r.attendanceDate < date ? <StatusBadge value="OVERDUE" label="No check-out" /> : null}
        </span>
      ),
    },
    { key: 'in', header: 'Checked in', render: (r) => time(r.checkInAt) },
    { key: 'out', header: 'Checked out', render: (r) => time(r.checkOutAt) },
    { key: 'worked', header: 'Worked', render: (r) => (r.workedMinutes !== null ? formatMinutes(r.workedMinutes) : '—') },
    {
      key: 'source',
      header: 'Recorded by',
      render: (r) => (r.source === 'ADMIN'
        ? <span title={r.correctionReason ?? undefined}>Administrator{r.correctionReason ? ` — ${r.correctionReason}` : ''}</span>
        : 'You'),
    },
  ];

  const thirtyDays = (history.data ?? []).filter((r) => date !== null && r.attendanceDate >= addDays(date, -29));

  return (
    <div style={{ maxWidth: 1040 }}>
      <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12, marginBottom: 16 }}>
        <div>
          <div className="eyebrow" style={{ marginBottom: 6 }}>STAFF · {(user?.fullName ?? 'You').toUpperCase()}</div>
          <h1 style={{ fontSize: 24, letterSpacing: '-0.01em' }}>My attendance</h1>
          <p className="staff-muted" style={{ marginTop: 4, fontSize: 13 }}>
            Your own check-in and check-out. Marking students happens in Class attendance.
          </p>
        </div>
        <button type="button" className="staff-link" onClick={() => setShowPolicy((v) => !v)} aria-expanded={showPolicy} style={{ marginTop: 4 }}>
          Attendance policy
        </button>
      </header>

      {showPolicy && (
        <section className="staff-card" style={{ marginBottom: 14 }} aria-label="Attendance policy">
          <div className="staff-card-head"><h2>Attendance policy</h2></div>
          {policy.data ? (
            <>
              <ul style={{ paddingLeft: 18, lineHeight: 1.65, fontSize: 13 }}>
                <li>Your shift is {formatShiftTime(policy.data.shiftStart)} to {formatShiftTime(policy.data.shiftEnd)}.</li>
                <li>You are on time up to {policy.data.graceMinutes} minutes after the start. After that you are marked <strong>late</strong>, counted from the shift start.</li>
                <li>Work at least {formatHm(policy.data.fullDayMinutes)} for a <strong>full day</strong>. Fewer hours at check-out is recorded as a <strong>half day</strong>.</li>
                <li>Under {formatHm(policy.data.halfDayMinutes)} is flagged as <strong>short hours</strong> for an administrator to review.</li>
                <li>Forgot to check in or out? Use <em>Fix a missed check-in or check-out</em> below; an administrator decides.</li>
              </ul>
              <p className="staff-muted" style={{ marginTop: 6 }}>
                {policy.data.version === 0 ? 'These are the standard rules; your college has not customised them.' : `Your college's policy, version ${policy.data.version}.`}
                {' '}Each day is judged under the version in force on that day.
              </p>
            </>
          ) : policy.error ? (
            <p role="alert" className="form-error">Could not load the policy. {policy.error.message}</p>
          ) : (
            <p className="staff-muted">Loading…</p>
          )}
        </section>
      )}

      <div className="staff-grid">
        {today.error ? (
          <section className="staff-card"><p role="alert" className="form-error">Could not load today's record. {today.error.message}</p></section>
        ) : (
          <>
            {locationOn && (
              <p className="staff-muted" role="note" style={{ fontSize: 12, marginBottom: 8 }}>
                Your college records whether you are on campus when you check in. Your device will ask to share its location once; only how close you were is kept,
                never your coordinates, and your administrator can see it. Declining still lets you check in.
              </p>
            )}
            <ClockCard today={today.data} policy={policy.data} now={now} busy={busy} message={message} onAct={act} />
          </>
        )}
        <MonthSummaryCard records={history.data} todayIso={date} />
        <WeekStrip records={history.data} todayIso={date} onFullHistory={openHistory} onFixDay={fixDay} />
        <CorrectionForm
          values={form}
          onChange={(patch) => setForm((f) => ({ ...f, ...patch }))}
          onSubmit={submitRequest}
          busy={formBusy}
          message={formMessage}
          minDate={date ? addDays(date, -REQUEST_WINDOW_DAYS) : undefined}
          maxDate={date ? addDays(date, -1) : undefined}
          windowDays={REQUEST_WINDOW_DAYS}
        />
        <RequestsList requests={requests.data} error={requests.error} onWithdraw={withdraw} />
      </div>

      {showHistory && (
        <section id="full-history" className="staff-card" style={{ marginBottom: 14 }} aria-label="Full history">
          <div className="staff-card-head">
            <h2>Last 30 days</h2>
            <button type="button" className="staff-link" onClick={() => setShowHistory(false)}>Hide</button>
          </div>
          <DataTable
            columns={columns}
            rows={history.data ? thirtyDays : null}
            loading={history.loading}
            error={history.error}
            onRetry={history.refetch}
            emptyTitle="No attendance recorded yet"
            emptyDescription="Days you check in (or that an administrator records) will appear here."
            rowKey={(r) => r.id}
          />
        </section>
      )}
    </div>
  );
}
