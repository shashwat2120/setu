import { useState } from 'react';
import { useApiQuery } from '../../api/useApiQuery';
import { usePagedQuery } from '../../api/usePagedQuery';
import type { CollegeResponse, AuditLogEntryResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { Pagination } from '../../components/ui/Pagination';

export default function SuperAdminAuditLogPage() {
  const [tenantId, setTenantId] = useState('');
  const colleges = useApiQuery<CollegeResponse[]>('/institution/colleges');
  const entries = usePagedQuery<AuditLogEntryResponse>('/governance/audit-log', tenantId ? { tenantId } : {});

  const collegeNameFor = (id: string | null) => {
    if (!id) return 'platform';
    return colleges.data?.find((c) => c.id === id)?.name ?? id;
  };

  const columns: Column<AuditLogEntryResponse>[] = [
    { key: 'createdAt', header: 'When', render: (e) => new Date(e.createdAt).toLocaleString() },
    { key: 'action', header: 'Action', render: (e) => <span className="mono">{e.action}</span> },
    { key: 'resourceType', header: 'Resource', render: (e) => `${e.resourceType}${e.resourceId ? ` · ${e.resourceId.slice(0, 8)}…` : ''}` },
    { key: 'tenant', header: 'Tenant', render: (e) => collegeNameFor(e.tenantId) },
    { key: 'actor', header: 'Actor user ID', render: (e) => e.actorUserId ? <span className="mono">{e.actorUserId.slice(0, 8)}…</span> : <span style={{ color: 'var(--ink-soft)' }}>—</span> },
  ];

  return (
    <div>
      <PageHeader title="Audit log" subtitle="Every sensitive mutation on the platform, append-only." />

      <div style={{ marginBottom: 16, maxWidth: 320 }}>
        <label htmlFor="tenant-filter" style={{ display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 6 }}>
          Filter by college
        </label>
        <select
          id="tenant-filter"
          value={tenantId}
          onChange={(e) => { setTenantId(e.target.value); entries.setPage(0); }}
          style={{
            width: '100%', fontFamily: 'var(--font-body)', fontSize: 14, padding: '9px 12px',
            border: '1px solid var(--rule)', borderRadius: 4, background: 'var(--bg-elev)', color: 'var(--ink)',
          }}
        >
          <option value="">All colleges (platform-wide feed)</option>
          {colleges.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
      </div>

      <DataTable
        columns={columns}
        rows={entries.data?.content ?? null}
        loading={entries.loading}
        error={entries.error}
        onRetry={entries.refetch}
        emptyTitle="No audit entries"
        emptyDescription="No sensitive mutations have been recorded for this scope yet."
        rowKey={(e) => e.id}
      />
      {entries.data && <Pagination page={entries.page} totalPages={entries.data.totalPages} onChange={entries.setPage} />}
    </div>
  );
}
