import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { usePagedQuery } from '../../api/usePagedQuery';
import type { CourseResponse, PrerequisiteResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { Pagination } from '../../components/ui/Pagination';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import { useState } from 'react';
import type { DepartmentResponse } from '../../api/types';

const RULE_TYPES = ['REQUIRED', 'RECOMMENDED', 'COREQUISITE'] as const;

function CoursePrerequisites({ courseId, allCourses }: { courseId: string; allCourses: CourseResponse[] }) {
  const prereqs = useApiQuery<PrerequisiteResponse[]>(`/catalog/courses/${courseId}/prerequisites`, [courseId]);
  const [prereqCourseId, setPrereqCourseId] = useState('');
  const [ruleType, setRuleType] = useState<typeof RULE_TYPES[number]>('REQUIRED');
  const [minimumGrade, setMinimumGrade] = useState('');
  const [message, setMessage] = useState('');
  const courseLabel = (id: string) => { const c = allCourses.find((x) => x.id === id); return c ? `${c.code} · ${c.title}` : id; };
  async function addPrereq(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setMessage('');
    try {
      await apiFetch(`/catalog/courses/${courseId}/prerequisites`, { method: 'POST', body: { prerequisiteCourseId: prereqCourseId, ruleType, minimumGrade: minimumGrade || null } });
      setMessage('Prerequisite added.'); setPrereqCourseId(''); setMinimumGrade(''); prereqs.refetch();
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Could not add prerequisite.'); }
  }
  async function removePrereq(prerequisiteCourseId: string) {
    await apiFetch(`/catalog/courses/${courseId}/prerequisites/${prerequisiteCourseId}`, { method: 'DELETE' });
    prereqs.refetch();
  }
  const columns: Column<PrerequisiteResponse>[] = [
    { key: 'course', header: 'Prerequisite', render: (p) => courseLabel(p.prerequisiteCourseId) },
    { key: 'rule', header: 'Rule', render: (p) => p.ruleType },
    { key: 'minGrade', header: 'Min. grade', render: (p) => p.minimumGrade ?? '—' },
    { key: 'actions', header: '', render: (p) => <button type="button" className="btn btn-ghost" style={{ padding: '4px 10px', fontSize: 12 }} onClick={() => removePrereq(p.prerequisiteCourseId)}>Remove</button> },
  ];
  return (
    <div style={{ padding: '10px 16px 16px' }}>
      <form onSubmit={addPrereq} style={{ display: 'flex', gap: 10, alignItems: 'flex-end', flexWrap: 'wrap', marginBottom: 12 }}>
        <div className="form-field" style={{ margin: 0 }}>
          <label htmlFor={`prereq-course-${courseId}`}>Prerequisite course</label>
          <select id={`prereq-course-${courseId}`} value={prereqCourseId} onChange={(e) => setPrereqCourseId(e.target.value)} required>
            <option value="">Choose course</option>
            {allCourses.filter((c) => c.id !== courseId).map((c) => <option key={c.id} value={c.id}>{c.code} · {c.title}</option>)}
          </select>
        </div>
        <div className="form-field" style={{ margin: 0 }}>
          <label htmlFor={`prereq-rule-${courseId}`}>Rule</label>
          <select id={`prereq-rule-${courseId}`} value={ruleType} onChange={(e) => setRuleType(e.target.value as typeof RULE_TYPES[number])}>
            {RULE_TYPES.map((r) => <option key={r} value={r}>{r}</option>)}
          </select>
        </div>
        <div className="form-field" style={{ margin: 0 }}><label htmlFor={`prereq-grade-${courseId}`}>Min. grade (optional)</label><input id={`prereq-grade-${courseId}`} value={minimumGrade} onChange={(e) => setMinimumGrade(e.target.value)} placeholder="e.g. C" /></div>
        <button className="btn btn-primary" style={{ padding: '9px 16px' }}>Add</button>
      </form>
      {message && <p role="status" style={{ fontSize: 13, marginBottom: 10 }}>{message}</p>}
      <DataTable columns={columns} rows={prereqs.data} loading={prereqs.loading} error={prereqs.error} onRetry={prereqs.refetch}
        emptyTitle="No prerequisites" emptyDescription="This course has no prerequisites." rowKey={(p) => p.id} />
    </div>
  );
}

export default function CoursesPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'COLLEGE_ADMIN');
  const [expandedCourse, setExpandedCourse] = useState<string | null>(null);

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
    { key: 'actions', header: '', render: (c) => <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }} onClick={() => setExpandedCourse(expandedCourse === c.id ? null : c.id)}>{expandedCourse === c.id ? 'Hide prerequisites' : 'Prerequisites'}</button> },
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
      {expandedCourse && data && <CoursePrerequisites courseId={expandedCourse} allCourses={data.content} />}
    </div>
  );
}
