import { useState } from 'react';
import { apiFetch, ApiError } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { CollegeResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { ConfirmActionButton } from '../../components/ui/ConfirmActionButton';

const inputStyle: React.CSSProperties = {
  width: '100%',
  fontFamily: 'var(--font-body)',
  fontSize: 14,
  padding: '9px 12px',
  border: '1px solid var(--rule)',
  borderRadius: 4,
  background: 'var(--bg-elev)',
  color: 'var(--ink)',
};

function CreateCollegeForm({ onCreated }: { onCreated: () => void }) {
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [timezone, setTimezone] = useState('Asia/Kolkata');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    setSuccess(false);
    try {
      await apiFetch('/institution/colleges', { method: 'POST', body: { code, name, timezone } });
      setCode('');
      setName('');
      setSuccess(true);
      onCreated();
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError('That college code is already in use.');
      } else {
        setError(err instanceof Error ? err.message : 'Could not create the college.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={submit} className="card" style={{ marginBottom: 24, maxWidth: 480 }}>
      <h2 style={{ fontSize: 16, marginBottom: 14 }}>Provision a new college</h2>
      <div style={{ marginBottom: 12 }}>
        <label htmlFor="college-code" style={{ display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 6 }}>Code</label>
        <input id="college-code" required value={code} onChange={(e) => setCode(e.target.value)} style={inputStyle} placeholder="e.g. GVEC" />
      </div>
      <div style={{ marginBottom: 12 }}>
        <label htmlFor="college-name" style={{ display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 6 }}>Name</label>
        <input id="college-name" required value={name} onChange={(e) => setName(e.target.value)} style={inputStyle} placeholder="Full college name" />
      </div>
      <div style={{ marginBottom: 16 }}>
        <label htmlFor="college-tz" style={{ display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 6 }}>Timezone</label>
        <input id="college-tz" required value={timezone} onChange={(e) => setTimezone(e.target.value)} style={inputStyle} placeholder="Asia/Kolkata" />
      </div>
      {error && <p style={{ color: 'var(--flag)', fontSize: 13, marginBottom: 12 }}>{error}</p>}
      {success && <p style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 12 }}>College created.</p>}
      <button type="submit" className="btn btn-primary" disabled={submitting}>
        {submitting ? 'Creating…' : 'Create college'}
      </button>
    </form>
  );
}

export default function SuperAdminCollegesPage() {
  const colleges = useApiQuery<CollegeResponse[]>('/institution/colleges');
  const [actionError, setActionError] = useState<string | null>(null);

  const changeStatus = async (collegeId: string, status: string, reason: string) => {
    setActionError(null);
    try {
      await apiFetch(`/institution/colleges/${collegeId}/status`, { method: 'POST', body: { status, reason } });
      colleges.refetch();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : 'Could not change college status.');
    }
  };

  const columns: Column<CollegeResponse>[] = [
    { key: 'code', header: 'Code', render: (c) => <span className="mono">{c.code}</span> },
    { key: 'name', header: 'Name', render: (c) => c.name },
    { key: 'timezone', header: 'Timezone', render: (c) => c.timezone },
    { key: 'status', header: 'Status', render: (c) => <StatusBadge value={c.status} /> },
    {
      key: 'actions',
      header: 'Actions',
      render: (c) => (
        <div style={{ display: 'flex', gap: 8 }}>
          {c.status === 'ACTIVE' && (
            <ConfirmActionButton
              label="Suspend"
              variant="danger"
              confirmMessage={`Suspend "${c.name}"? Its students and faculty will lose access until reactivated.`}
              onConfirm={() => changeStatus(c.id, 'SUSPENDED', 'Suspended by platform admin')}
            />
          )}
          {c.status === 'SUSPENDED' && (
            <ConfirmActionButton
              label="Reactivate"
              confirmMessage={`Reactivate "${c.name}"?`}
              onConfirm={() => changeStatus(c.id, 'ACTIVE', 'Reactivated by platform admin')}
            />
          )}
          {c.status !== 'ARCHIVED' && (
            <ConfirmActionButton
              label="Archive"
              variant="danger"
              confirmMessage={`Archive "${c.name}"? This is only possible when no academic period is mid-term.`}
              onConfirm={() => changeStatus(c.id, 'ARCHIVED', 'Archived by platform admin')}
            />
          )}
        </div>
      ),
    },
  ];

  return (
    <div>
      <PageHeader title="Colleges" subtitle="Every tenant on the platform." />
      <CreateCollegeForm onCreated={colleges.refetch} />
      {actionError && <p style={{ color: 'var(--flag)', fontSize: 13, marginBottom: 12 }}>{actionError}</p>}
      <DataTable
        columns={columns}
        rows={colleges.data}
        loading={colleges.loading}
        error={colleges.error}
        onRetry={colleges.refetch}
        emptyTitle="No colleges"
        emptyDescription="No colleges have been provisioned on the platform yet."
        rowKey={(c) => c.id}
      />
    </div>
  );
}
