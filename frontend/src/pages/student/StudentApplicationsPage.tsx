import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { ApplicationResponse, JobResponse, PlacementRecordResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { ConfirmActionButton } from '../../components/ui/ConfirmActionButton';
import { applicationsSummary, canWithdraw, nextStep } from '../../lib/applicationStatus';

/** Every job the student has applied to, with where it stands, what happens next, and what the placement officer said. */
export default function StudentApplicationsPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'STUDENT');
  const studentId = user?.studentId ?? null;

  const applications = useApiQuery<ApplicationResponse[]>(studentId ? `/career/applications?studentId=${studentId}` : null);
  const jobs = useApiQuery<JobResponse[]>(tenantId ? `/career/jobs?tenantId=${tenantId}` : null);
  const placements = useApiQuery<PlacementRecordResponse[]>(studentId ? `/career/placement-records?studentId=${studentId}` : null);

  const jobFor = (id: string) => jobs.data?.find((j) => j.id === id);
  const placementFor = (jobId: string) => placements.data?.find((p) => p.jobId === jobId) ?? null;
  const newestFirst = applications.data ? [...applications.data].sort((a, b) => b.appliedAt.localeCompare(a.appliedAt)) : null;

  const withdraw = async (id: string) => {
    await apiFetch(`/career/applications/${id}/withdraw`, { method: 'POST' });
    applications.refetch();
  };

  const columns: Column<ApplicationResponse>[] = [
    {
      key: 'job',
      header: 'Job',
      render: (a) => {
        const job = jobFor(a.jobId);
        return job ? <span><strong>{job.title}</strong><br /><span style={{ color: 'var(--ink-soft)', fontSize: 13 }}>{job.employer}</span></span> : 'A job';
      },
    },
    { key: 'status', header: 'Status', render: (a) => <StatusBadge value={a.status} /> },
    { key: 'appliedAt', header: 'Applied', render: (a) => new Date(a.appliedAt).toLocaleDateString() },
    { key: 'next', header: 'What happens next', render: (a) => nextStep(a, placementFor(a.jobId)) },
    { key: 'note', header: "Placement officer's note", render: (a) => a.decisionReason ?? '' },
    {
      key: 'actions',
      header: 'Actions',
      render: (a) => canWithdraw(a.status)
        ? <ConfirmActionButton label="Withdraw" variant="danger" confirmMessage="Withdraw your application?" onConfirm={() => withdraw(a.id)} />
        : null,
    },
  ];

  return (
    <div>
      <PageHeader title="My applications" subtitle="Every job you have applied to, and where each one stands." />
      {applications.data && <p style={{ fontSize: 14, marginBottom: 12 }}>{applicationsSummary(applications.data)}</p>}
      <DataTable columns={columns} rows={newestFirst} loading={applications.loading} error={applications.error} onRetry={applications.refetch}
        emptyTitle="No applications yet" emptyDescription="Jobs you apply to appear here. Find them under Career." rowKey={(a) => a.id} />
    </div>
  );
}
