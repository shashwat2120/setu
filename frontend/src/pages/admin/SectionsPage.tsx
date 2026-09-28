import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { usePagedQuery } from '../../api/usePagedQuery';
import type { CourseSectionResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { Pagination } from '../../components/ui/Pagination';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import { useEffect, useState } from 'react';
import type { CourseResponse, AcademicYearResponse, AcademicPeriodResponse } from '../../api/types';

export default function SectionsPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'COLLEGE_ADMIN');

  const { data, loading, error, refetch, page, setPage } = usePagedQuery<CourseSectionResponse>(
    tenantId ? '/enrollment/course-sections' : null,
    tenantId ? { tenantId } : {},
  );
  const courses = usePagedQuery<CourseResponse>(tenantId ? '/catalog/courses' : null, tenantId ? { tenantId, size: '100' } : {});
  const years = useApiQuery<AcademicYearResponse[]>(tenantId ? `/catalog/academic-years?tenantId=${tenantId}` : null);
  const [periods, setPeriods] = useState<AcademicPeriodResponse[]>([]);
  const [form, setForm] = useState({ courseId: '', periodId: '', sectionCode: '', capacity: '30', registrationOpenAt: '', registrationCloseAt: '' });
  const [message, setMessage] = useState('');
  useEffect(() => { if (years.data) Promise.all(years.data.map((y) => apiFetch<AcademicPeriodResponse[]>(`/catalog/academic-periods?academicYearId=${y.id}`).catch(() => []))).then((xs) => setPeriods(xs.flat())); }, [years.data]);
  async function createSection(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setMessage('');
    try { await apiFetch('/enrollment/course-sections', { method: 'POST', body: { courseId: form.courseId, periodId: form.periodId, classId: null, sectionCode: form.sectionCode, capacity: Number(form.capacity), roomId: null, registrationOpenAt: form.registrationOpenAt ? new Date(form.registrationOpenAt).toISOString() : null, registrationCloseAt: form.registrationCloseAt ? new Date(form.registrationCloseAt).toISOString() : null } }); setMessage('Section created. Add its meeting pattern and faculty assignment to complete scheduling.'); refetch(); }
    catch (e) { setMessage(e instanceof Error ? e.message : 'Could not create section.'); }
  }

  const columns: Column<CourseSectionResponse>[] = [
    { key: 'code', header: 'Section', render: (s) => <span className="mono">{s.sectionCode}</span> },
    { key: 'capacity', header: 'Capacity', render: (s) => s.capacity },
    { key: 'status', header: 'Status', render: (s) => <StatusBadge value={s.status} /> },
  ];

  return (
    <div>
      <PageHeader title="Sections" subtitle={data ? `${data.totalElements} offered` : undefined} />
      <form className="card" onSubmit={createSection} style={{ marginBottom: 18 }}><h2 style={{ fontSize: 17, marginBottom: 12 }}>Create section</h2>
        <div className="form-field"><label htmlFor="section-course">Course</label><select id="section-course" value={form.courseId} onChange={(e) => setForm({ ...form, courseId: e.target.value })} required><option value="">Choose course</option>{courses.data?.content.map((c) => <option key={c.id} value={c.id}>{c.code} · {c.title}</option>)}</select></div>
        <div className="form-field"><label htmlFor="section-period">Academic period</label><select id="section-period" value={form.periodId} onChange={(e) => setForm({ ...form, periodId: e.target.value })} required><option value="">Choose period</option>{periods.map((p) => <option key={p.id} value={p.id}>{p.name} ({p.code})</option>)}</select></div>
        {([['sectionCode','Section code','text'],['capacity','Capacity','number'],['registrationOpenAt','Registration opens','datetime-local'],['registrationCloseAt','Registration closes','datetime-local']] as const).map(([key,label,type]) => <div className="form-field" key={key}><label htmlFor={`section-${key}`}>{label}</label><input id={`section-${key}`} type={type} min={type === 'number' ? '1' : undefined} value={form[key]} onChange={(e) => setForm({ ...form, [key]: e.target.value })} required={!key.startsWith('registration')} /></div>)}
        {message && <p role="status">{message}</p>}<button className="btn btn-primary">Create section</button></form>
      <DataTable
        columns={columns}
        rows={data?.content ?? null}
        loading={loading}
        error={error}
        onRetry={refetch}
        emptyTitle="No sections found"
        emptyDescription="No sections have been scheduled yet."
        rowKey={(s) => s.id}
      />
      {data && <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />}
    </div>
  );
}
