import { useCallback, useEffect, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { useApiQuery } from '../../api/useApiQuery';
import { apiFetch, ApiError } from '../../api/client';
import { useAttendanceLiveUpdates } from '../../api/useAttendanceLiveUpdates';
import type {
  FacultyAssignmentResponse, CourseSectionResponse, CourseResponse, MeetingPatternResponse,
  AttendanceOpenResponse, AttendanceRecordResponse, AttendanceSessionResponse,
} from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { Skeleton, EmptyState } from '../../components/ui/EmptyState';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { locationLabel, positionHint, requestDevicePosition } from '../../lib/devicePosition';

interface SectionOption {
  assignment: FacultyAssignmentResponse;
  section: CourseSectionResponse;
  course: CourseResponse | null;
}

export default function FacultyAttendancePage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'FACULTY');
  const facultyId = user?.facultyId ?? null;

  const { data: assignments } = useApiQuery<FacultyAssignmentResponse[]>(
    facultyId && tenantId ? `/enrollment/faculty-assignments?facultyId=${facultyId}&tenantId=${tenantId}` : null,
  );

  const [options, setOptions] = useState<SectionOption[] | null>(null);
  const [selectedSectionId, setSelectedSectionId] = useState<string>('');
  const [meetings, setMeetings] = useState<MeetingPatternResponse[] | null>(null);
  const [selectedMeetingId, setSelectedMeetingId] = useState<string>('');
  const [session, setSession] = useState<AttendanceOpenResponse | null>(null);
  const [records, setRecords] = useState<AttendanceRecordResponse[] | null>(null);
  const [closedCount, setClosedCount] = useState<number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  /** Whether this device's position was sent as the classroom position when the session was opened (null: no session opened by this page yet). */
  const [classroomShared, setClassroomShared] = useState<boolean | null>(null);
  const [classroomHint, setClassroomHint] = useState<string | null>(null);

  useEffect(() => {
    const active = assignments?.filter((a) => a.active) ?? [];
    if (!assignments) return;
    if (active.length === 0) {
      setOptions([]);
      return;
    }
    Promise.all(
      active.map(async (assignment) => {
        const section = await apiFetch<CourseSectionResponse>(`/enrollment/course-sections/${assignment.sectionId}`);
        const course = await apiFetch<CourseResponse>(`/catalog/courses/${section.courseId}`).catch(() => null);
        return { assignment, section, course };
      }),
    ).then((rows) => {
      setOptions(rows);
      if (rows.length > 0) setSelectedSectionId(rows[0].section.id);
    });
  }, [assignments]);

  useEffect(() => {
    if (!selectedSectionId) return;
    setMeetings(null);
    setSelectedMeetingId('');
    apiFetch<MeetingPatternResponse[]>(`/catalog/meeting-patterns?sectionId=${selectedSectionId}`).then((m) => {
      setMeetings(m);
      if (m.length > 0) setSelectedMeetingId(m[0].id);
    });
    setSession(null);
    setRecords(null);
    setClosedCount(null);
  }, [selectedSectionId]);

  async function openSession() {
    if (!selectedSectionId || !selectedMeetingId) return;
    setBusy(true);
    setActionError(null);
    try {
      const endsAt = new Date(Date.now() + 30 * 60 * 1000).toISOString();
      // this device's position becomes the classroom position students are compared with; optional, so a refusal opens the session all the same
      const found = await requestDevicePosition(10000);
      const position = found.ok ? found.position : null;
      const result = await apiFetch<AttendanceOpenResponse>('/attendance/sessions', {
        method: 'POST',
        body: { sectionId: selectedSectionId, meetingId: selectedMeetingId, endsAt, ...(position ? { latitude: position.latitude, longitude: position.longitude } : {}) },
      });
      setClassroomShared(position !== null);
      setClassroomHint(found.ok ? null : positionHint(found.problem));
      setSession(result);
      setClosedCount(null);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        // A session for this meeting is already open (e.g. from an earlier page load) — resume into it
        // rather than dead-ending with an error the faculty member has no way to act on.
        try {
          const existing = await apiFetch<AttendanceSessionResponse[]>(`/attendance/sessions?sectionId=${selectedSectionId}`);
          const open = existing.find((s) => s.meetingId === selectedMeetingId && s.status === 'OPEN');
          if (open) {
            const qr = await apiFetch<{ qrToken: string; qrExpiresAt: string }>(`/attendance/sessions/${open.id}/qr`);
            setSession({ sessionId: open.id, qrToken: qr.qrToken, qrExpiresAt: qr.qrExpiresAt });
            setClosedCount(null);
            // a resumed session keeps whatever it was opened with: say so, so an instructor is not left wondering why students show no distance
            setClassroomShared(open.hasClassroomPosition ?? null);
            setClassroomHint(open.hasClassroomPosition === false ? 'This session was opened without it and cannot gain one: close it and open a new session, allowing location, to compare check-ins with the room.' : null);
          } else {
            setActionError('An attendance session is already open for this meeting, but it could not be found to resume.');
          }
        } catch {
          setActionError('An attendance session is already open for this meeting, but it could not be resumed.');
        }
      } else {
        setActionError(e instanceof ApiError ? e.message : 'Could not open the session.');
      }
    } finally {
      setBusy(false);
    }
  }

  async function rotateQr() {
    if (!session) return;
    setBusy(true);
    setActionError(null);
    try {
      const result = await apiFetch<{ qrToken: string; qrExpiresAt: string }>(
        `/attendance/sessions/${session.sessionId}/qr/rotate`,
        { method: 'POST' },
      );
      setSession({ ...session, qrToken: result.qrToken, qrExpiresAt: result.qrExpiresAt });
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : 'Could not rotate the QR token.');
    } finally {
      setBusy(false);
    }
  }

  const refetchRecords = useCallback(() => {
    if (!session) return;
    apiFetch<AttendanceRecordResponse[]>(`/attendance/sessions/${session.sessionId}/records?tenantId=${tenantId}`).then(setRecords).catch(() => {});
  }, [session, tenantId]);

  const { connected: liveConnected, error: liveError } = useAttendanceLiveUpdates(
    session && closedCount === null ? selectedSectionId : null,
    refetchRecords,
  );

  useEffect(() => {
    if (session && closedCount === null) refetchRecords();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session?.sessionId]);

  async function closeSession() {
    if (!session) return;
    setBusy(true);
    setActionError(null);
    try {
      const result = await apiFetch<{ absentRecordsGenerated: number }>(
        `/attendance/sessions/${session.sessionId}/close`,
        { method: 'POST' },
      );
      setClosedCount(result.absentRecordsGenerated);
      const recs = await apiFetch<AttendanceRecordResponse[]>(`/attendance/sessions/${session.sessionId}/records?tenantId=${tenantId}`);
      setRecords(recs);
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : 'Could not close the session.');
    } finally {
      setBusy(false);
    }
  }

  if (!options) {
    return (
      <div>
        <PageHeader title="Attendance" />
        <Skeleton height={200} />
      </div>
    );
  }

  if (options.length === 0) {
    return (
      <div>
        <PageHeader title="Attendance" />
        <div className="card">
          <EmptyState title="No sections assigned" description="You must be assigned to a section to run attendance." />
        </div>
      </div>
    );
  }

  return (
    <div>
      <PageHeader title="Attendance" subtitle="Open a live QR attendance session for one of your sections" />

      <div className="card" style={{ marginBottom: 16 }}>
        <div className="form-field">
          <label htmlFor="section">Section</label>
          <select id="section" value={selectedSectionId} onChange={(e) => setSelectedSectionId(e.target.value)} disabled={!!session}>
            {options.map((o) => (
              <option key={o.section.id} value={o.section.id}>
                {o.course ? `${o.course.code} — ` : ''}
                {o.section.sectionCode}
              </option>
            ))}
          </select>
        </div>

        {meetings && meetings.length > 1 && (
          <div className="form-field">
            <label htmlFor="meeting">Meeting</label>
            <select id="meeting" value={selectedMeetingId} onChange={(e) => setSelectedMeetingId(e.target.value)} disabled={!!session}>
              {meetings.map((m) => (
                <option key={m.id} value={m.id}>
                  Day {m.dayOfWeek} · {m.startTime}–{m.endTime}
                </option>
              ))}
            </select>
          </div>
        )}

        {meetings && meetings.length === 0 && (
          <p style={{ color: 'var(--ink-soft)', fontSize: 13 }}>This section has no scheduled meeting pattern yet.</p>
        )}

        {actionError && <p role="alert" className="form-error" style={{ marginBottom: 12 }}>{actionError}</p>}

        {!session ? (
          <button type="button" className="btn btn-primary" onClick={openSession} disabled={busy || !selectedMeetingId}>
            {busy ? 'Opening…' : 'Open attendance session'}
          </button>
        ) : (
          <div>
            <div className={closedCount === null ? 'badge badge-active' : 'badge'} style={{ marginBottom: 12 }}>{closedCount === null ? 'SESSION OPEN' : 'SESSION CLOSED'}</div>
            {closedCount === null ? (
              <>
                <p style={{ marginBottom: 8 }}>Session ID: <span className="mono" style={{ fontWeight: 700 }}>{session.sessionId}</span></p>
                <p style={{ marginBottom: 8 }}>
                  Scan token: <span className="mono" style={{ fontWeight: 700 }}>{session.qrToken}</span>
                </p>
                <p style={{ marginBottom: 16, fontSize: 13, color: 'var(--ink-soft)' }}>
                  Expires at {new Date(session.qrExpiresAt).toLocaleTimeString()}. Students scan this token from
                  their own device to mark themselves present.
                </p>
                <div style={{ display: 'flex', gap: 10, alignItems: 'center', marginBottom: 12, flexWrap: 'wrap' }}>
                  <button type="button" className="btn btn-ghost" onClick={rotateQr} disabled={busy}>Rotate token</button>
                  <button type="button" className="btn btn-primary" onClick={closeSession} disabled={busy}>
                    {busy ? 'Closing…' : 'Close session'}
                  </button>
                  {!liveConnected && (
                    <button type="button" className="btn btn-ghost" onClick={refetchRecords} style={{ padding: '8px 14px', fontSize: 13 }}>
                      Refresh records
                    </button>
                  )}
                </div>
                {classroomShared !== null && (
                  <p style={{ fontSize: 12, color: 'var(--ink-soft)', marginBottom: 6 }}>
                    {classroomShared
                      ? 'Classroom position: taken from this device, so each check-in shows how close the student was.'
                      : `Classroom position: not shared from this device, so check-ins show no distance. ${classroomHint ?? 'Allow location and open a new session to enable it.'}`}
                  </p>
                )}
                <p style={{ fontSize: 12, color: liveConnected ? 'var(--sage, #4a7c59)' : 'var(--ink-soft)' }}>
                  {liveConnected ? '● Live — records below update automatically as students scan.' : liveError ?? 'Connecting to realtime updates…'}
                </p>
              </>
            ) : (
              <>
                <p>
                  Session closed. {closedCount} student{closedCount === 1 ? '' : 's'} marked absent automatically for not scanning.
                </p>
                <button
                  type="button"
                  className="btn btn-primary"
                  style={{ marginTop: 10 }}
                  onClick={() => { setSession(null); setClosedCount(null); setClassroomShared(null); setClassroomHint(null); setActionError(null); }}
                >
                  Open another session
                </button>
              </>
            )}
          </div>
        )}
      </div>

      {records && (
        <section className="card">
          <h2 style={{ fontSize: 16, marginBottom: 12 }}>Attendance records</h2>
          {records.length === 0 ? (
            <EmptyState title="No records" description="No students scanned or were recorded for this session." />
          ) : (
            <table className="data-table">
              <thead>
                <tr><th scope="col">Enrollment</th><th scope="col">Status</th><th scope="col">Checked in from</th></tr>
              </thead>
              <tbody>
                {records.map((r) => (
                  <tr key={r.id}>
                    <td data-label="Enrollment" className="mono">{r.enrollmentId.slice(0, 8)}…</td>
                    <td data-label="Status"><StatusBadge value={r.status} /></td>
                    <td data-label="Checked in from" style={{ fontSize: 13, color: r.locationStatus === 'FAR' ? 'var(--brass-deep, #8a5a14)' : 'var(--ink-soft)', fontWeight: r.locationStatus === 'FAR' ? 600 : 400 }}>
                      {r.source === 'QR_SCAN' ? locationLabel(r.locationStatus, r.locationDistanceMeters) : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}
    </div>
  );
}
