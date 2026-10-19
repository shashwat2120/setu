import { useEffect, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { EnrollmentResponse, PageResponse, CourseSectionResponse, CourseResponse, AttendancePercentageResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { Skeleton, ErrorState } from '../../components/ui/EmptyState';
import { getDevicePosition } from '../../lib/devicePosition';

interface Row {
  enrollment: EnrollmentResponse;
  section: CourseSectionResponse | null;
  course: CourseResponse | null;
  percentage: AttendancePercentageResponse | null;
}

function bandFor(pct: number): string {
  if (pct >= 75) return 'ACTIVE';
  if (pct >= 50) return 'MEDIUM';
  return 'HIGH';
}

export default function StudentAttendancePage() {
  const { user } = useAuth();
  const studentId = user?.studentId ?? null;

  const enrollments = useApiQuery<PageResponse<EnrollmentResponse>>(studentId ? `/enrollment/enrollments?studentId=${studentId}&size=100` : null);
  const [rows, setRows] = useState<Row[] | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [sessionId, setSessionId] = useState('');
  const [qrToken, setQrToken] = useState('');
  const [checkInMessage, setCheckInMessage] = useState('');
  const [checkingIn, setCheckingIn] = useState(false);

  async function checkIn(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setCheckingIn(true); setCheckInMessage('Asking your device for your location… you can decline and still check in.');
    try {
      // location is a signal for the instructor, never a requirement: declining, or a device without it, checks in the same
      const position = await getDevicePosition();
      setCheckInMessage('Checking in…');
      const result = await apiFetch<{ status: string; locationStatus?: string | null }>('/attendance/scan', {
        method: 'POST',
        body: { sessionId, qrToken, ...(position ? { latitude: position.latitude, longitude: position.longitude, accuracyMeters: position.accuracyMeters } : {}) },
      });
      const where = result.locationStatus === 'NEAR' ? ' Your location matches the classroom.'
        : result.locationStatus === 'FAR' ? ' Your location looks far from the classroom; your instructor will see that.'
        : result.locationStatus === 'NO_LOCATION' ? ' Your location was not shared, so your instructor sees none.' : '';
      setCheckInMessage(`Check-in recorded.${where}`); setSessionId(''); setQrToken('');
      enrollments.refetch();
    } catch (e) { setCheckInMessage(e instanceof Error ? e.message : 'Check-in failed.'); }
    finally { setCheckingIn(false); }
  }

  useEffect(() => {
    if (!enrollments.data) return;
    const active = enrollments.data.content.filter((e) => e.status === 'ENROLLED');
    if (active.length === 0) {
      setRows([]);
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const result = await Promise.all(active.map(async (enrollment) => {
          const section = await apiFetch<CourseSectionResponse>(`/enrollment/course-sections/${enrollment.sectionId}`).catch(() => null);
          const course = section ? await apiFetch<CourseResponse>(`/catalog/courses/${section.courseId}`).catch(() => null) : null;
          const percentage = await apiFetch<AttendancePercentageResponse>(`/attendance/enrollments/${enrollment.id}/percentage`).catch(() => null);
          return { enrollment, section, course, percentage };
        }));
        if (!cancelled) setRows(result);
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e : new Error(String(e)));
      }
    })();
    return () => { cancelled = true; };
  }, [enrollments.data]);

  const columns: Column<Row>[] = [
    { key: 'course', header: 'Course', render: (r) => (r.course ? `${r.course.code} — ${r.course.title}` : '—') },
    { key: 'section', header: 'Section', render: (r) => <span className="mono">{r.section?.sectionCode ?? '—'}</span> },
    {
      key: 'percentage',
      header: 'Attendance',
      render: (r) => (r.percentage ? (
        <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="mono">{r.percentage.percentage.toFixed(1)}%</span>
          <StatusBadge value={bandFor(r.percentage.percentage)} label={r.percentage.percentage >= 75 ? 'Good' : r.percentage.percentage >= 50 ? 'Watch' : 'Low'} />
        </span>
      ) : '—'),
    },
    { key: 'sessions', header: 'Sessions', render: (r) => (r.percentage ? `${r.percentage.presentOrLate} / ${r.percentage.total}` : '—') },
  ];

  return (
    <div>
      <PageHeader title="Attendance" subtitle="Percentage is computed live from your attendance records — never a stored number." />
      <form className="card" onSubmit={checkIn} style={{ marginBottom: 18 }}>
        <h2 style={{ fontSize: 17, marginBottom: 8 }}>Check in to a session</h2>
        <p style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 12 }}>Enter the session ID and current rotating token shown by your faculty member.</p>
        <p style={{ color: 'var(--ink-soft)', fontSize: 12, marginBottom: 12 }}>Your device will ask to share your location. It is used once, to show your instructor whether you are in the room; your coordinates are not saved, only how close you were. Declining still lets you check in.</p>
        <div className="form-field"><label htmlFor="attendance-session">Session ID</label><input id="attendance-session" value={sessionId} onChange={(e) => setSessionId(e.target.value)} required /></div>
        <div className="form-field"><label htmlFor="attendance-token">Check-in token</label><input id="attendance-token" value={qrToken} onChange={(e) => setQrToken(e.target.value)} required autoComplete="off" /></div>
        {checkInMessage && <p role="status" style={{ marginBottom: 10 }}>{checkInMessage}</p>}
        <button className="btn btn-primary" disabled={checkingIn}>{checkingIn ? 'Checking in…' : 'Check in'}</button>
      </form>
      {!rows && !error ? (
        <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}><Skeleton height={20} /><Skeleton height={20} /></div>
      ) : error ? (
        <div className="card"><ErrorState message="Could not load your attendance." /></div>
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          loading={false}
          error={null}
          emptyTitle="No attendance records"
          emptyDescription="You are not enrolled in any sections yet."
          rowKey={(r) => r.enrollment.id}
        />
      )}
    </div>
  );
}
