import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { usePagedQuery } from '../../api/usePagedQuery';
import type { AuditLogEntryResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { Pagination } from '../../components/ui/Pagination';

/** Tenant-scoped audit feed — always this admin's own college, never a cross-tenant selector (unlike the Super Admin platform-wide view). The backend itself enforces the scoping (VIEW_TENANT_DATA is resource-owned to this tenant), this page just never offers a different one. */
export default function AuditLogPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'COLLEGE_ADMIN');
  const entries = usePagedQuery<AuditLogEntryResponse>(tenantId ? '/governance/audit-log' : null, tenantId ? { tenantId } : {});

  const columns: Column<AuditLogEntryResponse>[] = [
    { key: 'createdAt', header: 'When', render: (e) => new Date(e.createdAt).toLocaleString() },
    { key: 'action', header: 'Action', render: (e) => <span className="mono">{e.action}</span> },
    { key: 'resourceType', header: 'Resource', render: (e) => `${e.resourceType}${e.resourceId ? ` · ${e.resourceId.slice(0, 8)}…` : ''}` },
    { key: 'actor', header: 'Actor user ID', render: (e) => e.actorUserId ? <span className="mono">{e.actorUserId.slice(0, 8)}…</span> : <span style={{ color: 'var(--ink-soft)' }}>—</span> },
  ];

  return (
    <div>
      <PageHeader title="Audit log" subtitle="Every sensitive mutation for your college, append-only." />
      <DataTable
        columns={columns}
        rows={entries.data?.content ?? null}
        loading={entries.loading}
        error={entries.error}
        onRetry={entries.refetch}
        emptyTitle="No audit entries"
        emptyDescription="No sensitive mutations have been recorded for your college yet."
        rowKey={(e) => e.id}
      />
      {entries.data && <Pagination page={entries.page} totalPages={entries.data.totalPages} onChange={entries.setPage} />}
    </div>
  );
}
