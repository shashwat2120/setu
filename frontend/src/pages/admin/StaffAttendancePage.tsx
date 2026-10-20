import { useMemo, useState } from 'react';
import { apiFetch, ApiError } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { FacultyResponse, PageResponse, RegularisationResponse, StaffAttendancePolicy, StaffAttendanceResponse, StaffAttendanceStatus, StaffAttendanceToday } from '../../api/types';
import { formatMinutes } from '../../lib/staffTime';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { StaffLocationPolicyCard } from '../../components/staff/StaffLocationPolicyCard';
import { locationLabel } from '../../lib/devicePosition';

interface Row {
  faculty: FacultyResponse;
  record: StaffAttendanceResponse | null;
}

const STATUSES: { value: StaffAttendanceStatus; label: string }[] = [
  { value: 'PRESENT', label: 'Present' },
  { value: 'HALF_DAY', label: 'Half day' },
  { value: 'ABSENT', label: 'Absent' },
  { value: 'ON_LEAVE', label: 'On leave' },
];

const time = (iso: string | null) => (iso ? new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '—');

/** Registrar view of every faculty member's own attendance for a day, with audited marking/correction. The college is always the admin's own; the server rejects future dates and requires a reason. */
export default function StaffAttendancePage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'COLLEGE_ADMIN');
  const serverToday = useApiQuery<StaffAttendanceToday>('/staff-attendance/me/today');
  const [pickedDate, setPickedDate] = useState<string | null>(null);
  const date = pickedDate ?? serverToday.data?.date ?? null;

  const faculty = useApiQuery<PageResponse<FacultyResponse>>(tenantId ? `/directory/faculty?tenantId=${tenantId}&size=500` : null);
  const records = useApiQuery<StaffAttendanceResponse[]>(tenantId && date ? `/staff-attendance?tenantId=${tenantId}&date=${date}` : null);

  const policy = useApiQuery<StaffAttendancePolicy>('/staff-attendance/policy');
  const [pol, setPol] = useState<{ shiftStart: string; shiftEnd: string; grace: string; full: string; half: string } | null>(null);
  const [polBusy, setPolBusy] = useState(false);
  const [polMessage, setPolMessage] = useState<{ ok: boolean; text: string } | null>(null);

  const pending = useApiQuery<RegularisationResponse[]>(tenantId ? `/staff-attendance/regularisations?tenantId=${tenantId}&status=PENDING` : null);
  const [deciding, setDeciding] = useState<{ id: string; mode: 'approve' | 'reject' } | null>(null);
  const [decisionNote, setDecisionNote] = useState('');
  const [decisionBusy, setDecisionBusy] = useState(false);
  const [decisionMessage, setDecisionMessage] = useState<string | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [status, setStatus] = useState<StaffAttendanceStatus>('ABSENT');
  const [reason, setReason] = useState('');
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const rows: Row[] | null = useMemo(() => {
    if (!faculty.data || !records.data) return null;
    const byFaculty = new Map(records.data.map((r) => [r.facultyId, r]));
    return faculty.data.content.map((f) => ({ faculty: f, record: byFaculty.get(f.id) ?? null }));
  }, [faculty.data, records.data]);

  const counts = useMemo(() => {
    const c = { PRESENT: 0, HALF_DAY: 0, ABSENT: 0, ON_LEAVE: 0, none: 0 };
    rows?.forEach((r) => { if (r.record) c[r.record.status] += 1; else c.none += 1; });
    return c;
  }, [rows]);

  // the editor's fields start from the policy currently in force and stay the admin's own edits afterwards
  const draft = pol ?? (policy.data ? {
    shiftStart: policy.data.shiftStart, shiftEnd: policy.data.shiftEnd, grace: String(policy.data.graceMinutes),
    full: String(policy.data.fullDayMinutes), half: String(policy.data.halfDayMinutes),
  } : null);

  const savePolicy = async () => {
    if (!draft) return;
    setPolBusy(true);
    setPolMessage(null);
    try {
      const saved = await apiFetch<StaffAttendancePolicy>('/staff-attendance/policy', {
        method: 'PUT',
        body: {
          shiftStart: draft.shiftStart,
          shiftEnd: draft.shiftEnd,
          graceMinutes: Number(draft.grace),
          fullDayMinutes: Number(draft.full),
          halfDayMinutes: Number(draft.half),
        },
      });
      setPolMessage({ ok: true, text: `Saved as policy version ${saved.version}. It applies from now on; days already recorded keep the version they were judged under.` });
      setPol(null);
      policy.refetch();
    } catch (e) {
      setPolMessage({ ok: false, text: e instanceof ApiError ? e.message : 'Could not save the policy.' });
    } finally {
      setPolBusy(false);
    }
  };

  const startEdit = (row: Row) => {
    setEditing(row.faculty.id);
    setStatus(row.record?.status ?? 'ABSENT');
    setReason('');
    setMessage(null);
  };

  const save = async (facultyId: string) => {
    if (!date) return;
    setSaving(true);
    setMessage(null);
    try {
      await apiFetch<StaffAttendanceResponse>(`/staff-attendance/faculty/${facultyId}/${date}`, {
        method: 'PUT',
        body: { status, reason: reason.trim() },
      });
      setEditing(null);
      records.refetch();
    } catch (e) {
      setMessage(e instanceof ApiError ? e.message : 'Could not save this change.');
    } finally {
      setSaving(false);
    }
  };

  const facultyName = (id: string) => {
    const f = faculty.data?.content.find((x) => x.id === id);
    return f ? (f.fullName ?? f.employeeNo) : `Faculty ${id.slice(0, 8)}…`;
  };

  const decide = async () => {
    if (!deciding) return;
    setDecisionBusy(true);
    setDecisionMessage(null);
    try {
      await apiFetch<RegularisationResponse>(`/staff-attendance/regularisations/${deciding.id}/${deciding.mode}`, {
        method: 'POST',
        body: { note: decisionNote.trim() || null },
      });
      setDeciding(null);
      setDecisionNote('');
      pending.refetch();
      records.refetch();
    } catch (e) {
      setDecisionMessage(e instanceof ApiError ? e.message : 'Could not save this decision.');
      pending.refetch();
    } finally {
      setDecisionBusy(false);
    }
  };

  const requestColumns: Column<RegularisationResponse>[] = [
    { key: 'who', header: 'Faculty', render: (r) => facultyName(r.facultyId) },
    { key: 'day', header: 'Day', render: (r) => <span className="mono">{r.attendanceDate}</span> },
    { key: 'asks', header: 'Asks for', render: (r) => <span><StatusBadge value={r.requestedStatus} />{r.requestedCheckInAt ? <span className="mono"> {time(r.requestedCheckInAt)}–{time(r.requestedCheckOutAt)}</span> : null}</span> },
    { key: 'reason', header: 'Reason', render: (r) => r.reason },
    {
      key: 'decide',
      header: '',
      render: (r) => (deciding?.id === r.id ? (
        <form onSubmit={(e) => { e.preventDefault(); decide(); }} style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
          <label htmlFor={`note-${r.id}`} className="visually-hidden">Note</label>
          <input id={`note-${r.id}`} value={decisionNote} onChange={(e) => setDecisionNote(e.target.value)} maxLength={300}
            placeholder={deciding.mode === 'reject' ? 'Reason for rejecting (required)' : 'Note (optional)'} required={deciding.mode === 'reject'} />
          <button type="submit" className="btn btn-primary" disabled={decisionBusy || (deciding.mode === 'reject' && !decisionNote.trim())}>
            {decisionBusy ? 'Saving…' : deciding.mode === 'approve' ? 'Confirm approval' : 'Confirm rejection'}
          </button>
          <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }} onClick={() => setDeciding(null)} disabled={decisionBusy}>Cancel</button>
        </form>
      ) : (
        <span style={{ display: 'flex', gap: 6 }}>
          <button type="button" className="btn btn-primary" style={{ padding: '6px 12px', fontSize: 12 }} onClick={() => { setDeciding({ id: r.id, mode: 'approve' }); setDecisionNote(''); setDecisionMessage(null); }}>Approve</button>
          <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }} onClick={() => { setDeciding({ id: r.id, mode: 'reject' }); setDecisionNote(''); setDecisionMessage(null); }}>Reject</button>
        </span>
      )),
    },
  ];

  const columns: Column<Row>[] = [
    { key: 'name', header: 'Faculty', render: (r) => <span>{r.faculty.fullName ?? r.faculty.employeeNo}<span style={{ color: 'var(--ink-soft)' }}> · {r.faculty.employeeNo}</span></span> },
    {
      key: 'status',
      header: 'Status',
      render: (r) => (r.record ? (
        <span style={{ display: 'inline-flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
          <StatusBadge value={r.record.status} />
          {r.record.lateMinutes ? <StatusBadge value="PENDING" label={`Late ${formatMinutes(r.record.lateMinutes)}`} /> : null}
          {r.record.shortHours ? <StatusBadge value="PENDING" label="Short hours" /> : null}
          {r.record.checkInAt && !r.record.checkOutAt && date !== null && serverToday.data && date < serverToday.data.date
            ? <StatusBadge value="OVERDUE" label="No check-out" /> : null}
        </span>
      ) : <span style={{ color: 'var(--ink-soft)' }}>Not recorded</span>),
    },
    { key: 'in', header: 'Checked in', render: (r) => time(r.record?.checkInAt ?? null) },
    // only when the college records it, and only for a check-in the person made themselves
    { key: 'where', header: 'Checked in from', render: (r) => (r.record?.source === 'SELF' && r.record.locationStatus ? locationLabel(r.record.locationStatus, r.record.locationDistanceMeters, 'campus') : '—') },
    { key: 'out', header: 'Checked out', render: (r) => time(r.record?.checkOutAt ?? null) },
    { key: 'worked', header: 'Worked', render: (r) => (r.record?.workedMinutes != null ? formatMinutes(r.record.workedMinutes) : '—') },
    {
      key: 'by',
      header: 'Recorded by',
      render: (r) => (r.record
        ? (r.record.source === 'ADMIN' ? `Administrator${r.record.correctionReason ? ` — ${r.record.correctionReason}` : ''}` : 'Faculty (self)')
        : '—'),
    },
    {
      key: 'action',
      header: '',
      render: (r) => (editing === r.faculty.id ? (
        <form
          onSubmit={(e) => { e.preventDefault(); save(r.faculty.id); }}
          style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}
          onClick={(e) => e.stopPropagation()}
        >
          <label htmlFor={`status-${r.faculty.id}`} className="visually-hidden">Status</label>
          <select id={`status-${r.faculty.id}`} value={status} onChange={(e) => setStatus(e.target.value as StaffAttendanceStatus)}>
            {STATUSES.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
          </select>
          <label htmlFor={`reason-${r.faculty.id}`} className="visually-hidden">Reason</label>
          <input id={`reason-${r.faculty.id}`} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Reason (required)" required maxLength={300} />
          <button type="submit" className="btn btn-primary" disabled={saving || !reason.trim()}>{saving ? 'Saving…' : 'Save'}</button>
          <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }} onClick={() => setEditing(null)} disabled={saving}>Cancel</button>
        </form>
      ) : (
        <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }} onClick={() => startEdit(r)}>{r.record ? 'Correct' : 'Mark'}</button>
      )),
    },
  ];

  return (
    <div>
      <PageHeader title="Staff attendance" subtitle="Faculty members' own daily attendance. Every mark or correction needs a reason and is written to the audit log." />

      <StaffLocationPolicyCard />

      <section className="card" style={{ marginBottom: 24 }} aria-label="Attendance policy">
        <h2 style={{ fontSize: 16, marginBottom: 4 }}>Attendance policy</h2>
        <p style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 12 }}>
          {policy.data
            ? (policy.data.version === 0
              ? 'Your college is using the standard rules. Saving creates version 1.'
              : `Current policy: version ${policy.data.version}.`)
            : 'Loading…'}
          {' '}Late means checking in after the shift start plus the grace period. A faculty member who checks out before a full day's hours is recorded as a half day, and under half a day is flagged. Every change is a new version and is audited; days already recorded keep the version they were judged under.
        </p>
        {draft && (
          <form onSubmit={(e) => { e.preventDefault(); savePolicy(); }} style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'flex-end' }}>
            <div className="form-field">
              <label htmlFor="pol-start">Shift starts</label>
              <input id="pol-start" type="time" value={draft.shiftStart} onChange={(e) => setPol({ ...draft, shiftStart: e.target.value })} required />
            </div>
            <div className="form-field">
              <label htmlFor="pol-end">Shift ends</label>
              <input id="pol-end" type="time" value={draft.shiftEnd} onChange={(e) => setPol({ ...draft, shiftEnd: e.target.value })} required />
            </div>
            <div className="form-field">
              <label htmlFor="pol-grace">Grace (minutes)</label>
              <input id="pol-grace" type="number" min={0} max={120} value={draft.grace} onChange={(e) => setPol({ ...draft, grace: e.target.value })} required style={{ width: 110 }} />
            </div>
            <div className="form-field">
              <label htmlFor="pol-full">Full day (minutes) = {formatMinutes(Number(draft.full) || 0)}</label>
              <input id="pol-full" type="number" min={1} max={1440} value={draft.full} onChange={(e) => setPol({ ...draft, full: e.target.value })} required style={{ width: 140 }} />
            </div>
            <div className="form-field">
              <label htmlFor="pol-half">Half day (minutes) = {formatMinutes(Number(draft.half) || 0)}</label>
              <input id="pol-half" type="number" min={1} max={1439} value={draft.half} onChange={(e) => setPol({ ...draft, half: e.target.value })} required style={{ width: 140 }} />
            </div>
            <div>
              <button type="submit" className="btn btn-primary" disabled={polBusy}>{polBusy ? 'Saving…' : 'Save new version'}</button>
            </div>
          </form>
        )}
        {polMessage && <p role={polMessage.ok ? 'status' : 'alert'} className={polMessage.ok ? undefined : 'form-error'} style={{ marginTop: 10 }}>{polMessage.text}</p>}
      </section>

      <section className="card" style={{ marginBottom: 24 }}>
        <h2 style={{ fontSize: 16, marginBottom: 4 }}>Requests to correct a day{pending.data && pending.data.length > 0 ? ` (${pending.data.length} waiting)` : ''}</h2>
        <p style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 12 }}>Faculty who forgot to check in or out ask for a past day to be fixed. Approving applies the correction to that day and is written to the audit log; you cannot decide your own request.</p>
        {decisionMessage && <p role="alert" className="form-error" style={{ marginBottom: 10 }}>{decisionMessage}</p>}
        <DataTable
          columns={requestColumns}
          rows={pending.data}
          loading={pending.loading || faculty.loading}
          error={pending.error}
          onRetry={pending.refetch}
          emptyTitle="No requests waiting"
          emptyDescription="When a faculty member asks for a day to be corrected, it appears here."
          rowKey={(r) => r.id}
        />
      </section>

      <div className="form-field" style={{ maxWidth: 220, marginBottom: 16 }}>
        <label htmlFor="staff-attendance-date">Date</label>
        <input
          id="staff-attendance-date"
          type="date"
          value={date ?? ''}
          max={serverToday.data?.date}
          onChange={(e) => { setPickedDate(e.target.value || null); setEditing(null); setMessage(null); }}
        />
      </div>

      {rows && (
        <div className="card-grid" style={{ marginBottom: 16 }}>
          <div className="card"><div className="stat-label">Present</div><div className="mono" style={{ fontSize: 20 }}>{counts.PRESENT}</div></div>
          <div className="card"><div className="stat-label">Half day</div><div className="mono" style={{ fontSize: 20 }}>{counts.HALF_DAY}</div></div>
          <div className="card"><div className="stat-label">Absent</div><div className="mono" style={{ fontSize: 20 }}>{counts.ABSENT}</div></div>
          <div className="card"><div className="stat-label">On leave</div><div className="mono" style={{ fontSize: 20 }}>{counts.ON_LEAVE}</div></div>
          <div className="card"><div className="stat-label">Not recorded</div><div className="mono" style={{ fontSize: 20 }}>{counts.none}</div></div>
        </div>
      )}

      {message && <p role="alert" className="form-error" style={{ marginBottom: 10 }}>{message}</p>}

      <DataTable
        columns={columns}
        rows={rows}
        loading={faculty.loading || records.loading || (serverToday.loading && !date)}
        error={faculty.error ?? records.error}
        onRetry={() => { faculty.refetch(); records.refetch(); }}
        emptyTitle="No faculty in this college"
        emptyDescription="Add faculty members first; their attendance will appear here."
        rowKey={(r) => r.faculty.id}
      />
    </div>
  );
}
