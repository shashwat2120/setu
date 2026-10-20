import { useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { AttendanceSessionResponse, AttendanceRecordResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { SectionSelect, useSectionOptions } from '../../components/ui/SectionSelect';
import { locationLabel } from '../../lib/devicePosition';

const RECORD_STATUSES = ['PRESENT', 'LATE', 'ABSENT', 'EXCUSED'] as const;

function SessionRecords({ sessionId, tenantId, sessionOpen, studentNameForEnrollment }: { sessionId: string; tenantId: string; sessionOpen: boolean; studentNameForEnrollment: (enrollmentId: string) => string }) {
  const records = useApiQuery<AttendanceRecordResponse[]>(`/attendance/sessions/${sessionId}/records?tenantId=${tenantId}`);
  const [message, setMessage] = useState('');

  const correct = async (recordId: string, status: typeof RECORD_STATUSES[number]) => {
    setMessage('');
    try {
      await apiFetch(`/attendance/records/${recordId}/correct`, { method: 'POST', body: { status, reason: `Corrected to ${status} by college administrator` } });
      records.refetch();
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Could not correct this record.'); }
  };

  const columns: Column<AttendanceRecordResponse>[] = [
    { key: 'student', header: 'Student (by enrollment)', render: (r) => studentNameForEnrollment(r.enrollmentId) },
    { key: 'status', header: 'Status', render: (r) => <StatusBadge value={r.status} /> },
    { key: 'markedAt', header: 'Marked at', render: (r) => new Date(r.markedAt).toLocaleString() },
    { key: 'source', header: 'Source', render: (r) => r.source },
    // where the student's device said they were at check-in, compared with the classroom; only a check-in the student made themselves has one
    { key: 'where', header: 'Checked in from', render: (r) => (r.source === 'QR_SCAN' ? locationLabel(r.locationStatus, r.locationDistanceMeters) : '—') },
    ...(sessionOpen ? [] : [{
      key: 'correct',
      header: 'Correct',
      render: (r: AttendanceRecordResponse) => (
        <select
          value=""
          onChange={(e) => { if (e.target.value) correct(r.id, e.target.value as typeof RECORD_STATUSES[number]); }}
          style={{ fontSize: 12, padding: '4px 6px' }}
        >
          <option value="">Change to…</option>
          {RECORD_STATUSES.filter((s) => s !== r.status).map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
      ),
    } as Column<AttendanceRecordResponse>]),
  ];
  return (
    <div style={{ padding: '4px 0 16px' }}>
      {sessionOpen && (
        <p style={{ fontSize: 12, color: 'var(--ink-soft)', marginBottom: 8 }}>
          Records can only be corrected once this session is closed.
        </p>
      )}
      {message && <p role="status" style={{ fontSize: 12, color: 'var(--flag)', marginBottom: 8 }}>{message}</p>}
      <DataTable
        columns={columns}
        rows={records.data}
        loading={records.loading}
        error={records.error}
        onRetry={records.refetch}
        emptyTitle="No records"
        emptyDescription="No attendance was recorded for this session."
        rowKey={(r) => r.id}
      />
    </div>
  );
}

export default function AttendancePage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'COLLEGE_ADMIN');
  const { sections, loading: sectionsLoading, courseNameFor } = useSectionOptions(tenantId);
  const [sectionId, setSectionId] = useState('');
  const [expandedSession, setExpandedSession] = useState<string | null>(null);

  const sessions = useApiQuery<AttendanceSessionResponse[]>(sectionId ? `/attendance/sessions?sectionId=${sectionId}` : null);
  // Attendance records are keyed by enrollmentId, not studentId — this admin view shows the enrollment id itself
  // as a stable identifier (resolving it to a student name would need a per-enrollment lookup the backend
  // doesn't expose in bulk; disclosed simplification).
  const studentNameForEnrollment = (enrollmentId: string) => enrollmentId;

  const columns: Column<AttendanceSessionResponse>[] = [
    { key: 'startedAt', header: 'Opened', render: (s) => new Date(s.startedAt).toLocaleString() },
    { key: 'endsAt', header: 'Ends', render: (s) => new Date(s.endsAt).toLocaleString() },
    { key: 'status', header: 'Status', render: (s) => <StatusBadge value={s.status} /> },
    {
      key: 'actions',
      header: '',
      render: (s) => (
        <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }}
          onClick={() => setExpandedSession(expandedSession === s.id ? null : s.id)}>
          {expandedSession === s.id ? 'Hide records' : 'View records'}
        </button>
      ),
    },
  ];

  return (
    <div>
      <PageHeader title="Attendance" subtitle="Live session state, records, and percentages — QR generation and scan validation remain backend-owned." />
      <SectionSelect sections={sections} courseNameFor={courseNameFor} value={sectionId} onChange={setSectionId} />

      {!sectionId && !sectionsLoading && (
        <div className="card"><p style={{ color: 'var(--ink-soft)' }}>Select a section above to view its attendance sessions.</p></div>
      )}

      {sectionId && (
        <>
          <DataTable
            columns={columns}
            rows={sessions.data}
            loading={sessions.loading}
            error={sessions.error}
            onRetry={sessions.refetch}
            emptyTitle="No attendance sessions"
            emptyDescription="No attendance sessions have been opened for this section yet."
            rowKey={(s) => s.id}
          />
          {expandedSession && tenantId && (
            <SessionRecords sessionId={expandedSession} tenantId={tenantId}
              sessionOpen={sessions.data?.find((s) => s.id === expandedSession)?.status === 'OPEN'}
              studentNameForEnrollment={studentNameForEnrollment} />
          )}
        </>
      )}
    </div>
  );
}
