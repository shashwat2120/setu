import { useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import { usePagedQuery } from '../../api/usePagedQuery';
import type { EnrollmentResponse, WaitlistEntryResponse, EnrollmentOverrideResponse, StudentResponse, PageResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { Pagination } from '../../components/ui/Pagination';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { ConfirmActionButton } from '../../components/ui/ConfirmActionButton';
import { SectionSelect, useSectionOptions } from '../../components/ui/SectionSelect';

function StudentOverrides({ studentId }: { studentId: string }) {
  const overrides = useApiQuery<EnrollmentOverrideResponse[]>(`/enrollment/overrides?studentId=${studentId}`);
  const columns: Column<EnrollmentOverrideResponse>[] = [
    { key: 'type', header: 'Type', render: (o) => o.type },
    { key: 'status', header: 'Status', render: (o) => <StatusBadge value={o.status} /> },
    { key: 'reason', header: 'Reason', render: (o) => o.reason ?? '—' },
    { key: 'expires', header: 'Expires', render: (o) => o.expiresAt ?? 'never' },
  ];
  return (
    <div style={{ padding: '4px 0 16px' }}>
      <DataTable
        columns={columns}
        rows={overrides.data}
        loading={overrides.loading}
        error={overrides.error}
        onRetry={overrides.refetch}
        emptyTitle="No overrides"
        emptyDescription="This student has no enrollment overrides on record."
        rowKey={(o) => o.id}
      />
    </div>
  );
}

export default function EnrollmentPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'COLLEGE_ADMIN');
  const { sections, loading: sectionsLoading, courseNameFor } = useSectionOptions(tenantId);
  const [sectionId, setSectionId] = useState('');
  const [selectedStudent, setSelectedStudent] = useState<{ id: string; name: string } | null>(null);

  const students = useApiQuery<PageResponse<StudentResponse>>(
    tenantId ? `/directory/students?tenantId=${tenantId}&size=500` : null,
  );
  const studentNameFor = (studentId: string) =>
    students.data?.content.find((s) => s.id === studentId)?.fullName
    ?? students.data?.content.find((s) => s.id === studentId)?.email
    ?? studentId;

  const enrollments = usePagedQuery<EnrollmentResponse>(
    sectionId ? `/enrollment/enrollments/section/${sectionId}` : null,
    tenantId ? { tenantId } : {},
  );
  const waitlist = useApiQuery<WaitlistEntryResponse[]>(
    sectionId && tenantId ? `/enrollment/waitlist/section/${sectionId}?tenantId=${tenantId}` : null,
  );

  const dropEnrollment = async (enrollmentId: string) => {
    await apiFetch(`/enrollment/enrollments/${enrollmentId}/drop`, { method: 'POST', body: { reason: 'Dropped by college admin' } });
    enrollments.refetch();
  };

  const withdrawWaitlist = async (studentId: string) => {
    if (!sectionId) return;
    await apiFetch(`/enrollment/waitlist/section/${sectionId}/student/${studentId}/withdraw`, { method: 'POST' });
    waitlist.refetch();
  };

  const enrollmentColumns: Column<EnrollmentResponse>[] = [
    { key: 'student', header: 'Student', render: (e) => studentNameFor(e.studentId) },
    { key: 'status', header: 'Status', render: (e) => <StatusBadge value={e.status} /> },
    { key: 'requestedAt', header: 'Requested', render: (e) => (e.requestedAt ? new Date(e.requestedAt).toLocaleDateString() : '—') },
    {
      key: 'actions',
      header: 'Actions',
      render: (e) => (
        <div style={{ display: 'flex', gap: 8 }}>
          <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }}
            onClick={() => setSelectedStudent({ id: e.studentId, name: studentNameFor(e.studentId) })}>
            Overrides
          </button>
          {e.status === 'ENROLLED' && (
            <ConfirmActionButton
              label="Drop"
              variant="danger"
              confirmMessage={`Drop ${studentNameFor(e.studentId)} from this section? This cannot be undone.`}
              onConfirm={() => dropEnrollment(e.id)}
            />
          )}
        </div>
      ),
    },
  ];

  const waitlistColumns: Column<WaitlistEntryResponse>[] = [
    { key: 'position', header: 'Position', render: (w) => w.position },
    { key: 'student', header: 'Student', render: (w) => studentNameFor(w.studentId) },
    { key: 'status', header: 'Status', render: (w) => <StatusBadge value={w.status} /> },
    {
      key: 'actions',
      header: 'Actions',
      render: (w) => (w.status === 'WAITING' ? (
        <ConfirmActionButton
          label="Withdraw"
          variant="danger"
          confirmMessage={`Withdraw ${studentNameFor(w.studentId)} from the waitlist?`}
          onConfirm={() => withdrawWaitlist(w.studentId)}
        />
      ) : null),
    },
  ];

  return (
    <div>
      <PageHeader title="Enrollment" subtitle="Enrollment queue, waitlist, and overrides — enforced entirely by the backend." />
      <SectionSelect sections={sections} courseNameFor={courseNameFor} value={sectionId} onChange={setSectionId} />

      {!sectionId && !sectionsLoading && (
        <div className="card"><p style={{ color: 'var(--ink-soft)' }}>Select a section above to view its enrollment queue.</p></div>
      )}

      {sectionId && (
        <>
          <h2 style={{ fontSize: 18, marginBottom: 12 }}>Enrollments</h2>
          <DataTable
            columns={enrollmentColumns}
            rows={enrollments.data?.content ?? null}
            loading={enrollments.loading || students.loading}
            error={enrollments.error}
            onRetry={enrollments.refetch}
            emptyTitle="No enrollments"
            emptyDescription="No students have enrolled in this section yet."
            rowKey={(e) => e.id}
          />
          {enrollments.data && <Pagination page={enrollments.page} totalPages={enrollments.data.totalPages} onChange={enrollments.setPage} />}

          <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>Waitlist</h2>
          <DataTable
            columns={waitlistColumns}
            rows={waitlist.data}
            loading={waitlist.loading}
            error={waitlist.error}
            onRetry={waitlist.refetch}
            emptyTitle="No one is waitlisted"
            emptyDescription="This section has no waitlist entries."
            rowKey={(w) => w.id}
          />

          {selectedStudent && (
            <>
              <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>Overrides — {selectedStudent.name}</h2>
              <StudentOverrides studentId={selectedStudent.id} />
            </>
          )}
        </>
      )}
    </div>
  );
}
