import { useEffect, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { AssessmentResponse, AssessmentComponentResponse, GradeResponse, PageResponse, EnrollmentResponse, StudentResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { Skeleton, ErrorState } from '../../components/ui/EmptyState';
import { FacultySectionSelect, useFacultySectionOptions } from '../../components/ui/FacultySectionSelect';

const inputStyle = { width: 60, padding: '6px 8px', fontSize: 13, border: '1px solid var(--rule)', borderRadius: 4, background: 'var(--bg-elev)', color: 'var(--ink)' };

function GradeHistory({ enrollmentId, componentId }: { enrollmentId: string; componentId: string }) {
  const history = useApiQuery<GradeResponse[]>(`/assessment/grades/history?enrollmentId=${enrollmentId}&componentId=${componentId}`);
  const columns: Column<GradeResponse>[] = [
    { key: 'version', header: 'Version', render: (g) => `v${g.version}` },
    { key: 'raw', header: 'Raw marks', render: (g) => g.rawMarks },
    { key: 'grade', header: 'Grade', render: (g) => g.gradeCode },
    { key: 'point', header: 'Grade point', render: (g) => g.gradePoint },
    { key: 'status', header: 'Status', render: (g) => <StatusBadge value={g.status} /> },
    { key: 'correction', header: '', render: (g) => (g.isCorrection ? <span style={{ fontSize: 12, color: 'var(--ink-soft)' }}>correction</span> : null) },
  ];
  return (
    <div style={{ padding: '4px 0 16px' }}>
      <p style={{ fontSize: 13, color: 'var(--ink-soft)', marginBottom: 8 }}>Full version history — a correction always adds a new row, never overwrites a prior one.</p>
      <DataTable columns={columns} rows={history.data} loading={history.loading} error={history.error}
        onRetry={history.refetch} emptyTitle="No grades published yet" rowKey={(g) => g.id} />
    </div>
  );
}

function GradeForm({ enrollmentId, studentId, componentId, assessmentId }: { enrollmentId: string; studentId: string; componentId: string; assessmentId: string }) {
  const current = useApiQuery<GradeResponse>(`/assessment/grades?enrollmentId=${enrollmentId}&componentId=${componentId}`);
  const [rawMarks, setRawMarks] = useState('');
  const [gradeCode, setGradeCode] = useState('');
  const [gradePoint, setGradePoint] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setSaving(true);
    setError(null);
    try {
      await apiFetch('/assessment/grades', {
        method: 'POST',
        body: { assessmentId, componentId, studentId, rawMarks: Number(rawMarks), gradeCode, gradePoint: Number(gradePoint) },
      });
      setRawMarks(''); setGradeCode(''); setGradePoint('');
      current.refetch();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not save this grade.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
      {current.loading ? <Skeleton height={16} width={80} /> : current.data ? (
        <span className="mono" style={{ fontSize: 13 }}>{current.data.rawMarks} / {current.data.gradeCode} ({current.data.gradePoint})</span>
      ) : <span style={{ fontSize: 13, color: 'var(--ink-soft)' }}>No grade yet</span>}
      <input aria-label="Raw marks" placeholder="Marks" value={rawMarks} onChange={(e) => setRawMarks(e.target.value)} style={inputStyle} />
      <input aria-label="Grade code" placeholder="Grade" value={gradeCode} onChange={(e) => setGradeCode(e.target.value)} style={inputStyle} />
      <input aria-label="Grade point" placeholder="Point" value={gradePoint} onChange={(e) => setGradePoint(e.target.value)} style={inputStyle} />
      <button type="button" className="btn btn-primary" style={{ padding: '6px 12px', fontSize: 12 }}
        disabled={saving || !rawMarks || !gradeCode || !gradePoint} onClick={submit}>
        {saving ? 'Saving…' : current.data ? 'Correct' : 'Publish'}
      </button>
      {error && <span style={{ fontSize: 12, color: 'var(--flag)' }}>{error}</span>}
    </div>
  );
}

export default function FacultyGradesPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'FACULTY');
  const facultyId = user?.facultyId ?? null;
  const { sections, loading: sectionsLoading } = useFacultySectionOptions(tenantId, facultyId);
  const [sectionId, setSectionId] = useState('');
  const [assessmentId, setAssessmentId] = useState('');
  const [componentId, setComponentId] = useState('');
  const [historyFor, setHistoryFor] = useState<string | null>(null);
  const [students, setStudents] = useState<Record<string, StudentResponse | null>>({});

  const assessments = useApiQuery<AssessmentResponse[]>(
    sectionId && tenantId ? `/assessment/assessments?sectionId=${sectionId}&tenantId=${tenantId}` : null,
  );
  const components = useApiQuery<AssessmentComponentResponse[]>(assessmentId ? `/assessment/components?assessmentId=${assessmentId}` : null);
  const enrollments = useApiQuery<PageResponse<EnrollmentResponse>>(
    sectionId && tenantId ? `/enrollment/enrollments/section/${sectionId}?tenantId=${tenantId}&size=200` : null,
  );
  const enrolledOnly = enrollments.data?.content.filter((e) => e.status === 'ENROLLED') ?? null;

  useEffect(() => {
    if (!enrolledOnly) return;
    let cancelled = false;
    Promise.all(enrolledOnly.map(async (e) => [e.studentId, await apiFetch<StudentResponse>(`/directory/students/${e.studentId}`).catch(() => null)] as const))
      .then((pairs) => { if (!cancelled) setStudents(Object.fromEntries(pairs)); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enrollments.data]);

  const studentNameFor = (studentId: string) => students[studentId]?.fullName ?? students[studentId]?.email ?? studentId;

  const columns: Column<EnrollmentResponse>[] = [
    { key: 'student', header: 'Student', render: (e) => studentNameFor(e.studentId) },
    { key: 'grade', header: 'Grade', render: (e) => (componentId ? <GradeForm enrollmentId={e.id} studentId={e.studentId} componentId={componentId} assessmentId={assessmentId} /> : null) },
    {
      key: 'history', header: '', render: (e) => (componentId ? (
        <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }}
          onClick={() => setHistoryFor(historyFor === e.id ? null : e.id)}>
          {historyFor === e.id ? 'Hide history' : 'History'}
        </button>
      ) : null),
    },
  ];

  return (
    <div>
      <PageHeader title="Grades" subtitle="Component-level grades are versioned — a correction adds a new row, it never overwrites the prior one." />
      <FacultySectionSelect sections={sections} value={sectionId} onChange={(v) => { setSectionId(v); setAssessmentId(''); setComponentId(''); }} />

      {sectionId && (
        <div style={{ display: 'flex', gap: 16, marginBottom: 16, flexWrap: 'wrap' }}>
          <div style={{ minWidth: 260 }}>
            <label htmlFor="assessment-select" style={{ display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 6 }}>Assessment</label>
            <select id="assessment-select" value={assessmentId} onChange={(e) => { setAssessmentId(e.target.value); setComponentId(''); }}
              style={{ width: '100%', fontSize: 14, padding: '9px 12px', border: '1px solid var(--rule)', borderRadius: 4, background: 'var(--bg-elev)', color: 'var(--ink)' }}>
              <option value="">Select an assessment…</option>
              {assessments.data?.map((a) => <option key={a.id} value={a.id}>{a.title}</option>)}
            </select>
          </div>
          <div style={{ minWidth: 220 }}>
            <label htmlFor="component-select" style={{ display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 6 }}>Component</label>
            <select id="component-select" value={componentId} onChange={(e) => setComponentId(e.target.value)} disabled={!assessmentId}
              style={{ width: '100%', fontSize: 14, padding: '9px 12px', border: '1px solid var(--rule)', borderRadius: 4, background: 'var(--bg-elev)', color: 'var(--ink)' }}>
              <option value="">Select a component…</option>
              {components.data?.map((c) => <option key={c.id} value={c.id}>{c.code}</option>)}
            </select>
          </div>
        </div>
      )}

      {!sectionId && !sectionsLoading && (
        <div className="card"><p style={{ color: 'var(--ink-soft)' }}>Select a section, assessment, and component to view or publish grades.</p></div>
      )}
      {sectionId && !componentId && (
        assessments.error ? <div className="card"><ErrorState message="Could not load assessments." onRetry={assessments.refetch} /></div> :
        <div className="card"><p style={{ color: 'var(--ink-soft)' }}>Select an assessment and component above.</p></div>
      )}

      {sectionId && componentId && (
        <>
          <DataTable columns={columns} rows={enrolledOnly} loading={enrollments.loading} error={enrollments.error}
            onRetry={enrollments.refetch} emptyTitle="No enrolled students" emptyDescription="No students are enrolled in this section." rowKey={(e) => e.id} />
          {historyFor && <GradeHistory enrollmentId={historyFor} componentId={componentId} />}
        </>
      )}
    </div>
  );
}
