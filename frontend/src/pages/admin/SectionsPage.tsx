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
import type {
  CourseResponse, AcademicYearResponse, AcademicPeriodResponse, BuildingResponse, RoomResponse,
  MeetingPatternResponse, FacultyResponse, FacultyAssignmentResponse,
} from '../../api/types';

const DAY_NAMES = ['', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
const ASSIGNMENT_ROLES = ['PRIMARY', 'CO_INSTRUCTOR', 'TA'] as const;

function SectionScheduling({ sectionId, tenantId }: { sectionId: string; tenantId: string }) {
  const buildings = useApiQuery<BuildingResponse[]>(`/catalog/buildings?tenantId=${tenantId}`);
  const [rooms, setRooms] = useState<RoomResponse[]>([]);
  useEffect(() => {
    if (!buildings.data) return;
    Promise.all(buildings.data.map((b) => apiFetch<RoomResponse[]>(`/catalog/rooms?buildingId=${b.id}&tenantId=${tenantId}`).catch(() => []))).then((xs) => setRooms(xs.flat()));
  }, [buildings.data, tenantId]);
  const roomLabelFor = (id: string) => {
    const room = rooms.find((r) => r.id === id);
    if (!room) return id;
    const building = buildings.data?.find((b) => b.id === room.buildingId);
    return `${building?.code ?? ''} ${room.code}`.trim();
  };

  const patterns = useApiQuery<MeetingPatternResponse[]>(`/catalog/meeting-patterns?sectionId=${sectionId}`);
  const [patternForm, setPatternForm] = useState({ dayOfWeek: '1', startTime: '09:00', endTime: '10:00', roomId: '', effectiveFrom: '' });
  const [patternMessage, setPatternMessage] = useState('');
  async function createPattern(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setPatternMessage('');
    try {
      await apiFetch('/catalog/meeting-patterns', {
        method: 'POST',
        body: {
          sectionId, dayOfWeek: Number(patternForm.dayOfWeek), startTime: patternForm.startTime, endTime: patternForm.endTime,
          roomId: patternForm.roomId, effectiveFrom: patternForm.effectiveFrom, effectiveTo: null,
        },
      });
      setPatternMessage('Meeting pattern added.'); patterns.refetch();
    } catch (e) { setPatternMessage(e instanceof Error ? e.message : 'Could not add the meeting pattern.'); }
  }
  const patternColumns: Column<MeetingPatternResponse>[] = [
    { key: 'day', header: 'Day', render: (p) => DAY_NAMES[p.dayOfWeek] },
    { key: 'time', header: 'Time', render: (p) => `${p.startTime} – ${p.endTime}` },
    { key: 'room', header: 'Room', render: (p) => roomLabelFor(p.roomId) },
  ];

  const faculty = useApiQuery<FacultyResponse[]>(`/directory/faculty?tenantId=${tenantId}&size=500`);
  const assignments = useApiQuery<FacultyAssignmentResponse[]>(`/enrollment/faculty-assignments?sectionId=${sectionId}&tenantId=${tenantId}`);
  const [assignForm, setAssignForm] = useState({ facultyId: '', role: 'PRIMARY' as typeof ASSIGNMENT_ROLES[number], allocationPct: '100', startsAt: '' });
  const [assignMessage, setAssignMessage] = useState('');
  const facultyNameFor = (id: string) => faculty.data?.find((f) => f.id === id)?.fullName ?? id;
  async function createAssignment(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setAssignMessage('');
    try {
      await apiFetch('/enrollment/faculty-assignments', {
        method: 'POST',
        body: {
          sectionId, facultyId: assignForm.facultyId, role: assignForm.role,
          allocationPct: Number(assignForm.allocationPct), startsAt: assignForm.startsAt, endsAt: null,
        },
      });
      setAssignMessage('Faculty assigned to this section.'); assignments.refetch();
    } catch (e) { setAssignMessage(e instanceof Error ? e.message : 'Could not assign faculty.'); }
  }
  const endAssignment = async (assignmentId: string) => {
    await apiFetch(`/enrollment/faculty-assignments/${assignmentId}/end`, { method: 'POST', body: { endsAt: new Date().toISOString().slice(0, 10) } });
    assignments.refetch();
  };
  const assignmentColumns: Column<FacultyAssignmentResponse>[] = [
    { key: 'faculty', header: 'Faculty', render: (a) => facultyNameFor(a.facultyId) },
    { key: 'role', header: 'Role', render: (a) => a.role },
    { key: 'allocation', header: 'Allocation', render: (a) => `${a.allocationPct}%` },
    {
      key: 'actions', header: '', render: (a) => (a.active ? (
        <button type="button" className="btn btn-ghost" style={{ padding: '4px 10px', fontSize: 12 }} onClick={() => endAssignment(a.id)}>End</button>
      ) : <span style={{ fontSize: 12, color: 'var(--ink-soft)' }}>ended</span>),
    },
  ];

  return (
    <div style={{ padding: '10px 16px 20px' }}>
      <h3 style={{ fontSize: 15, marginBottom: 10 }}>Meeting pattern</h3>
      <form onSubmit={createPattern} style={{ display: 'flex', gap: 10, alignItems: 'flex-end', flexWrap: 'wrap', marginBottom: 12 }}>
        <div className="form-field" style={{ margin: 0 }}>
          <label htmlFor="pattern-day">Day</label>
          <select id="pattern-day" value={patternForm.dayOfWeek} onChange={(e) => setPatternForm({ ...patternForm, dayOfWeek: e.target.value })}>
            {DAY_NAMES.slice(1).map((d, i) => <option key={d} value={i + 1}>{d}</option>)}
          </select>
        </div>
        <div className="form-field" style={{ margin: 0 }}><label htmlFor="pattern-start">Start</label><input id="pattern-start" type="time" value={patternForm.startTime} onChange={(e) => setPatternForm({ ...patternForm, startTime: e.target.value })} required /></div>
        <div className="form-field" style={{ margin: 0 }}><label htmlFor="pattern-end">End</label><input id="pattern-end" type="time" value={patternForm.endTime} onChange={(e) => setPatternForm({ ...patternForm, endTime: e.target.value })} required /></div>
        <div className="form-field" style={{ margin: 0 }}>
          <label htmlFor="pattern-room">Room</label>
          <select id="pattern-room" value={patternForm.roomId} onChange={(e) => setPatternForm({ ...patternForm, roomId: e.target.value })} required>
            <option value="">Choose room</option>
            {rooms.map((r) => <option key={r.id} value={r.id}>{roomLabelFor(r.id)}</option>)}
          </select>
        </div>
        <div className="form-field" style={{ margin: 0 }}><label htmlFor="pattern-from">Effective from</label><input id="pattern-from" type="date" value={patternForm.effectiveFrom} onChange={(e) => setPatternForm({ ...patternForm, effectiveFrom: e.target.value })} required /></div>
        <button className="btn btn-primary" style={{ padding: '9px 16px' }}>Add meeting time</button>
      </form>
      {patternMessage && <p role="status" style={{ fontSize: 13, marginBottom: 10 }}>{patternMessage}</p>}
      <DataTable columns={patternColumns} rows={patterns.data} loading={patterns.loading} error={patterns.error} onRetry={patterns.refetch}
        emptyTitle="No meeting times" emptyDescription="This section has no scheduled meeting times yet." rowKey={(p) => p.id} />

      <h3 style={{ fontSize: 15, margin: '20px 0 10px' }}>Faculty assignment</h3>
      <form onSubmit={createAssignment} style={{ display: 'flex', gap: 10, alignItems: 'flex-end', flexWrap: 'wrap', marginBottom: 12 }}>
        <div className="form-field" style={{ margin: 0 }}>
          <label htmlFor="assign-faculty">Faculty</label>
          <select id="assign-faculty" value={assignForm.facultyId} onChange={(e) => setAssignForm({ ...assignForm, facultyId: e.target.value })} required>
            <option value="">Choose faculty</option>
            {faculty.data?.map((f) => <option key={f.id} value={f.id}>{f.fullName ?? f.email}</option>)}
          </select>
        </div>
        <div className="form-field" style={{ margin: 0 }}>
          <label htmlFor="assign-role">Role</label>
          <select id="assign-role" value={assignForm.role} onChange={(e) => setAssignForm({ ...assignForm, role: e.target.value as typeof ASSIGNMENT_ROLES[number] })}>
            {ASSIGNMENT_ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
          </select>
        </div>
        <div className="form-field" style={{ margin: 0 }}><label htmlFor="assign-pct">Allocation %</label><input id="assign-pct" type="number" min="1" max="100" value={assignForm.allocationPct} onChange={(e) => setAssignForm({ ...assignForm, allocationPct: e.target.value })} required /></div>
        <div className="form-field" style={{ margin: 0 }}><label htmlFor="assign-starts">Starts</label><input id="assign-starts" type="date" value={assignForm.startsAt} onChange={(e) => setAssignForm({ ...assignForm, startsAt: e.target.value })} required /></div>
        <button className="btn btn-primary" style={{ padding: '9px 16px' }}>Assign</button>
      </form>
      {assignMessage && <p role="status" style={{ fontSize: 13, marginBottom: 10 }}>{assignMessage}</p>}
      <DataTable columns={assignmentColumns} rows={assignments.data} loading={assignments.loading || faculty.loading} error={assignments.error} onRetry={assignments.refetch}
        emptyTitle="No faculty assigned" emptyDescription="This section has no faculty assigned yet — attendance, assignments, and grades cannot be managed for it until it does." rowKey={(a) => a.id} />
    </div>
  );
}

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
  const [expandedSection, setExpandedSection] = useState<string | null>(null);
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
    {
      key: 'actions', header: '', render: (s) => (
        <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }}
          onClick={() => setExpandedSection(expandedSection === s.id ? null : s.id)}>
          {expandedSection === s.id ? 'Hide scheduling' : 'Scheduling'}
        </button>
      ),
    },
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
      {expandedSection && tenantId && <SectionScheduling sectionId={expandedSection} tenantId={tenantId} />}
    </div>
  );
}
