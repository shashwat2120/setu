import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { usePagedQuery } from '../../api/usePagedQuery';
import type { CourseResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { Pagination } from '../../components/ui/Pagination';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import { useState } from 'react';
import type { DepartmentResponse } from '../../api/types';

export default function CoursesPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'COLLEGE_ADMIN');

  const { data, loading, error, refetch, page, setPage } = usePagedQuery<CourseResponse>(
    tenantId ? '/catalog/courses' : null,
    tenantId ? { tenantId } : {},
  );
  const departments = useApiQuery<DepartmentResponse[]>(tenantId ? `/institution/departments?tenantId=${tenantId}` : null);
  const [form, setForm] = useState({ departmentId: '', code: '', title: '', credits: '3', courseType: 'CORE', level: 'UNDERGRADUATE', description: '' });
  const [message, setMessage] = useState('');
  async function createCourse(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setMessage('');
    try { await apiFetch('/catalog/courses', { method: 'POST', body: { ...form, credits: Number(form.credits), description: form.description || null } }); setMessage('Course created.'); setForm({ ...form, code: '', title: '' }); refetch(); }
    catch (e) { setMessage(e instanceof Error ? e.message : 'Could not create course.'); }
  }

  const columns: Column<CourseResponse>[] = [
    { key: 'code', header: 'Code', render: (c) => <span className="mono">{c.code}</span> },
    { key: 'title', header: 'Title', render: (c) => c.title },
    { key: 'credits', header: 'Credits', render: (c) => c.credits },
    { key: 'type', header: 'Type', render: (c) => c.courseType },
    { key: 'status', header: 'Status', render: (c) => <StatusBadge value={c.status} /> },
  ];

  return (
    <div>
      <PageHeader title="Courses" subtitle={data ? `${data.totalElements} in the catalog` : undefined} />
      <form className="card" onSubmit={createCourse} style={{ marginBottom: 18 }}><h2 style={{ fontSize: 17, marginBottom: 12 }}>Add course to catalog</h2>
        <div className="form-field"><label htmlFor="course-department">Department</label><select id="course-department" value={form.departmentId} onChange={(e) => setForm({ ...form, departmentId: e.target.value })} required><option value="">Choose department</option>{departments.data?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select></div>
        {([['code','Course code','text'],['title','Title','text'],['credits','Credits','number'],['courseType','Course type','text'],['level','Level','text'],['description','Description','text']] as const).map(([key,label,type]) => <div className="form-field" key={key}><label htmlFor={`course-${key}`}>{label}</label><input id={`course-${key}`} type={type} min={type === 'number' ? '0.5' : undefined} step={type === 'number' ? '0.5' : undefined} value={form[key]} onChange={(e) => setForm({ ...form, [key]: e.target.value })} required={!['description'].includes(key)} /></div>)}
        {message && <p role="status">{message}</p>}<button className="btn btn-primary">Create course</button></form>
      <DataTable
        columns={columns}
        rows={data?.content ?? null}
        loading={loading}
        error={error}
        onRetry={refetch}
        emptyTitle="No courses found"
        emptyDescription="No courses have been added to the catalog yet."
        rowKey={(c) => c.id}
      />
      {data && <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />}
    </div>
  );
}
