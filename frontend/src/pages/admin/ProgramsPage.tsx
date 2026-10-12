import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { useApiQuery } from '../../api/useApiQuery';
import type { ProgramResponse, CurriculumResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { apiFetch } from '../../api/client';
import { useState } from 'react';
import type { DepartmentResponse } from '../../api/types';

function ProgramCurricula({ programId, tenantId }: { programId: string; tenantId: string }) {
  const curricula = useApiQuery<CurriculumResponse[]>(`/catalog/curricula?programId=${programId}&tenantId=${tenantId}`, [programId]);
  const [form, setForm] = useState({ name: '', versionLabel: '', effectiveFrom: '', effectiveTo: '' });
  const [message, setMessage] = useState('');
  async function createCurriculum(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setMessage('');
    try {
      await apiFetch('/catalog/curricula', {
        method: 'POST',
        body: { programId, name: form.name, versionLabel: form.versionLabel, effectiveFrom: form.effectiveFrom, effectiveTo: form.effectiveTo || null },
      });
      setMessage('Curriculum version created.'); setForm({ name: '', versionLabel: '', effectiveFrom: '', effectiveTo: '' }); curricula.refetch();
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Could not create curriculum.'); }
  }
  const columns: Column<CurriculumResponse>[] = [
    { key: 'name', header: 'Name', render: (c) => c.name },
    { key: 'version', header: 'Version', render: (c) => <span className="mono">{c.versionLabel}</span> },
    { key: 'effective', header: 'Effective', render: (c) => `${c.effectiveFrom} → ${c.effectiveTo ?? 'open'}` },
  ];
  return (
    <div style={{ padding: '10px 16px 16px' }}>
      <form onSubmit={createCurriculum} style={{ padding: '0 0 12px' }}>
        <h3 style={{ fontSize: 15, marginBottom: 8 }}>Add curriculum version</h3>
        {([['name', 'Name', 'text'], ['versionLabel', 'Version label', 'text'], ['effectiveFrom', 'Effective from', 'date'], ['effectiveTo', 'Effective to (optional)', 'date']] as const).map(([key, label, type]) => (
          <div className="form-field" key={key}>
            <label htmlFor={`curriculum-${key}`}>{label}</label>
            <input id={`curriculum-${key}`} type={type} value={form[key]} onChange={(e) => setForm({ ...form, [key]: e.target.value })} required={key !== 'effectiveTo'} />
          </div>
        ))}
        {message && <p role="status">{message}</p>}
        <button className="btn btn-primary">Create curriculum version</button>
      </form>
      <DataTable
        columns={columns}
        rows={curricula.data}
        loading={curricula.loading}
        error={curricula.error}
        onRetry={curricula.refetch}
        emptyTitle="No curriculum versions"
        emptyDescription="This program has no curriculum versions yet — students cannot be assigned to it until one exists."
        rowKey={(c) => c.id}
      />
    </div>
  );
}

export default function ProgramsPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'COLLEGE_ADMIN');
  const [expandedProgram, setExpandedProgram] = useState<string | null>(null);

  const { data, loading, error, refetch } = useApiQuery<ProgramResponse[]>(
    tenantId ? `/catalog/programs?tenantId=${tenantId}` : null,
  );
  const departments = useApiQuery<DepartmentResponse[]>(tenantId ? `/institution/departments?tenantId=${tenantId}` : null);
  const [form, setForm] = useState({ departmentId: '', code: '', name: '', degreeType: 'BACHELOR', durationTerms: '8', creditsRequired: '120' });
  const [message, setMessage] = useState('');
  async function createProgram(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setMessage('');
    try { await apiFetch('/catalog/programs', { method: 'POST', body: { ...form, durationTerms: Number(form.durationTerms), creditsRequired: Number(form.creditsRequired) } }); setMessage('Program created.'); setForm({ ...form, code: '', name: '' }); refetch(); }
    catch (e) { setMessage(e instanceof Error ? e.message : 'Could not create program.'); }
  }

  const columns: Column<ProgramResponse>[] = [
    { key: 'code', header: 'Code', render: (p) => <span className="mono">{p.code}</span> },
    { key: 'name', header: 'Name', render: (p) => p.name },
    { key: 'status', header: 'Status', render: (p) => <StatusBadge value={p.status} /> },
    {
      key: 'actions',
      header: '',
      render: (p) => (
        <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }}
          onClick={() => setExpandedProgram(expandedProgram === p.id ? null : p.id)}>
          {expandedProgram === p.id ? 'Hide curricula' : 'Curricula'}
        </button>
      ),
    },
  ];

  return (
    <div>
      <PageHeader title="Programs" subtitle={data ? `${data.length} offered` : undefined} />
      <form className="card" onSubmit={createProgram} style={{ marginBottom: 18 }}><h2 style={{ fontSize: 17, marginBottom: 12 }}>Create program</h2>
        <div className="form-field"><label htmlFor="program-department">Department</label><select id="program-department" value={form.departmentId} onChange={(e) => setForm({ ...form, departmentId: e.target.value })} required><option value="">Choose department</option>{departments.data?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select></div>
        {([['code','Program code'],['name','Program name'],['degreeType','Degree type'],['durationTerms','Duration (terms)'],['creditsRequired','Credits required']] as const).map(([key,label]) => <div className="form-field" key={key}><label htmlFor={`program-${key}`}>{label}</label><input id={`program-${key}`} type={key === 'durationTerms' || key === 'creditsRequired' ? 'number' : 'text'} min={key === 'degreeType' ? undefined : '1'} value={form[key]} onChange={(e) => setForm({ ...form, [key]: e.target.value })} required /></div>)}
        {message && <p role="status">{message}</p>}<button className="btn btn-primary">Create program</button></form>
      <DataTable
        columns={columns}
        rows={data}
        loading={loading}
        error={error}
        onRetry={refetch}
        emptyTitle="No programs found"
        emptyDescription="No programs have been added yet."
        rowKey={(p) => p.id}
      />
      {expandedProgram && tenantId && <ProgramCurricula programId={expandedProgram} tenantId={tenantId} />}
    </div>
  );
}
