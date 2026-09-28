import { useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { useApiQuery } from '../../api/useApiQuery';
import type {
  CollegeResponse,
  CampusResponse,
  OrganizationResponse,
  AcademicYearResponse,
  AcademicPeriodResponse,
} from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { Skeleton, ErrorState } from '../../components/ui/EmptyState';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { apiFetch } from '../../api/client';

function YearPeriods({ yearId }: { yearId: string }) {
  const { data, loading, error, refetch } = useApiQuery<AcademicPeriodResponse[]>(
    `/catalog/academic-periods?academicYearId=${yearId}`,
  );
  const [form, setForm] = useState({ code: '', name: '', sequence: '1', startDate: '', endDate: '', registrationOpenAt: '', registrationCloseAt: '' });
  const [message, setMessage] = useState('');
  async function createPeriod(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setMessage('');
    try { await apiFetch('/catalog/academic-periods', { method: 'POST', body: { ...form, academicYearId: yearId, sequence: Number(form.sequence), registrationOpenAt: form.registrationOpenAt ? new Date(form.registrationOpenAt).toISOString() : null, registrationCloseAt: form.registrationCloseAt ? new Date(form.registrationCloseAt).toISOString() : null } }); setMessage('Academic period created.'); refetch(); }
    catch (e) { setMessage(e instanceof Error ? e.message : 'Could not create period.'); }
  }
  const columns: Column<AcademicPeriodResponse>[] = [
    { key: 'code', header: 'Code', render: (p) => <span className="mono">{p.code}</span> },
    { key: 'name', header: 'Name', render: (p) => p.name },
    { key: 'dates', header: 'Dates', render: (p) => `${p.startDate} → ${p.endDate}` },
    { key: 'status', header: 'Status', render: (p) => <StatusBadge value={p.status} /> },
  ];
  return (
    <div style={{ padding: '12px 16px 16px' }}>
      <form onSubmit={createPeriod} style={{ padding: '0 0 12px' }}><h3 style={{ fontSize: 15, marginBottom: 8 }}>Add period</h3>
        {([['code','Code','text'],['name','Name','text'],['sequence','Sequence','number'],['startDate','Start date','date'],['endDate','End date','date'],['registrationOpenAt','Registration opens','datetime-local'],['registrationCloseAt','Registration closes','datetime-local']] as const).map(([key,label,type]) => <div className="form-field" key={key}><label htmlFor={`period-${key}`}>{label}</label><input id={`period-${key}`} type={type} value={form[key]} onChange={(e) => setForm({ ...form, [key]: e.target.value })} required={!key.startsWith('registration')} /></div>)}
        {message && <p role="status">{message}</p>}<button className="btn btn-primary">Create period</button></form>
      <DataTable
        columns={columns}
        rows={data}
        loading={loading}
        error={error}
        onRetry={refetch}
        emptyTitle="No academic periods"
        emptyDescription="This academic year has no periods yet."
        rowKey={(p) => p.id}
      />
    </div>
  );
}

export default function InstitutionPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'COLLEGE_ADMIN');
  const [expandedYear, setExpandedYear] = useState<string | null>(null);
  const [yearForm, setYearForm] = useState({ code: '', startDate: '', endDate: '' });
  const [yearMessage, setYearMessage] = useState('');

  const college = useApiQuery<CollegeResponse>(tenantId ? `/institution/colleges/${tenantId}` : null);
  const campuses = useApiQuery<CampusResponse[]>(tenantId ? `/institution/campuses?collegeId=${tenantId}` : null);
  const organizations = useApiQuery<OrganizationResponse[]>(
    tenantId ? `/institution/academic-organizations?tenantId=${tenantId}` : null,
  );
  const years = useApiQuery<AcademicYearResponse[]>(tenantId ? `/catalog/academic-years?tenantId=${tenantId}` : null);

  const campusColumns: Column<CampusResponse>[] = [
    { key: 'code', header: 'Code', render: (c) => <span className="mono">{c.code}</span> },
    { key: 'name', header: 'Name', render: (c) => c.name },
  ];

  const orgColumns: Column<OrganizationResponse>[] = [
    { key: 'name', header: 'Name', render: (o) => o.name },
    {
      key: 'parent',
      header: 'Parent organization',
      render: (o) => (o.parentOrganizationId
        ? organizations.data?.find((x) => x.id === o.parentOrganizationId)?.name ?? o.parentOrganizationId
        : '— top level —'),
    },
  ];

  const yearColumns: Column<AcademicYearResponse>[] = [
    { key: 'code', header: 'Code', render: (y) => <span className="mono">{y.code}</span> },
    { key: 'dates', header: 'Dates', render: (y) => `${y.startDate} → ${y.endDate}` },
    { key: 'status', header: 'Status', render: (y) => <StatusBadge value={y.status} /> },
  ];
  async function createYear(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!tenantId) return; setYearMessage('');
    try { await apiFetch('/catalog/academic-years', { method: 'POST', body: { ...yearForm, tenantId } }); setYearMessage('Academic year created.'); years.refetch(); }
    catch (e) { setYearMessage(e instanceof Error ? e.message : 'Could not create year.'); }
  }

  return (
    <div>
      <PageHeader title="Institution" subtitle={college.data?.name ?? undefined} />

      {college.loading && <Skeleton height={100} />}
      {college.error && <div className="card"><ErrorState message="Could not load college information." onRetry={college.refetch} /></div>}
      {college.data && (
        <div className="card-grid" style={{ marginBottom: 24 }}>
          <div className="card">
            <div className="stat-label">College code</div>
            <div className="mono" style={{ marginTop: 6, fontSize: 15 }}>{college.data.code}</div>
          </div>
          <div className="card">
            <div className="stat-label">Timezone</div>
            <div style={{ marginTop: 6, fontSize: 15 }}>{college.data.timezone}</div>
          </div>
          <div className="card">
            <div className="stat-label">Locale</div>
            <div style={{ marginTop: 6, fontSize: 15 }}>{college.data.locale}</div>
          </div>
          <div className="card">
            <div className="stat-label">Status</div>
            <div style={{ marginTop: 6 }}><StatusBadge value={college.data.status} /></div>
          </div>
        </div>
      )}

      <h2 style={{ fontSize: 18, marginBottom: 12 }}>Campuses</h2>
      <DataTable
        columns={campusColumns}
        rows={campuses.data}
        loading={campuses.loading}
        error={campuses.error}
        onRetry={campuses.refetch}
        emptyTitle="No campuses"
        emptyDescription="This college has no campuses yet."
        rowKey={(c) => c.id}
      />

      <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>Academic organizations</h2>
      <DataTable
        columns={orgColumns}
        rows={organizations.data}
        loading={organizations.loading}
        error={organizations.error}
        onRetry={organizations.refetch}
        emptyTitle="No academic organizations"
        emptyDescription="No schools, faculties, or institutes have been created yet."
        rowKey={(o) => o.id}
      />

      <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>Academic years</h2>
      <form className="card" onSubmit={createYear} style={{ marginBottom: 14 }}><h3 style={{ fontSize: 15, marginBottom: 10 }}>Add academic year</h3>
        {([['code','Year code','text'],['startDate','Start date','date'],['endDate','End date','date']] as const).map(([key,label,type]) => <div className="form-field" key={key}><label htmlFor={`year-${key}`}>{label}</label><input id={`year-${key}`} type={type} value={yearForm[key]} onChange={(e) => setYearForm({ ...yearForm, [key]: e.target.value })} required /></div>)}
        {yearMessage && <p role="status">{yearMessage}</p>}<button className="btn btn-primary">Create academic year</button></form>
      <p style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 10 }}>Select a year to view its academic periods.</p>
      <DataTable
        columns={yearColumns}
        rows={years.data}
        loading={years.loading}
        error={years.error}
        onRetry={years.refetch}
        emptyTitle="No academic years"
        emptyDescription="No academic years have been created yet."
        rowKey={(y) => y.id}
        onRowClick={(y) => setExpandedYear(expandedYear === y.id ? null : y.id)}
      />
      {expandedYear && <YearPeriods yearId={expandedYear} />}
    </div>
  );
}
