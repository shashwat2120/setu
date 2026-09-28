import { useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { CourseModuleResponse, LessonResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { FacultySectionSelect, useFacultySectionOptions } from '../../components/ui/FacultySectionSelect';

const inputStyle = {
  padding: '8px 10px', fontSize: 13, border: '1px solid var(--rule)', borderRadius: 4,
  background: 'var(--bg-elev)', color: 'var(--ink)',
};

function ModuleLessons({ moduleId }: { moduleId: string }) {
  const lessons = useApiQuery<LessonResponse[]>(`/learning/lessons?moduleId=${moduleId}`);
  const [title, setTitle] = useState('');
  const [type, setType] = useState('TEXT');
  const [contentRef, setContentRef] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const addLesson = async () => {
    setSaving(true);
    setError(null);
    try {
      await apiFetch('/learning/lessons', {
        method: 'POST',
        body: { moduleId, title, type, contentRef, sequence: (lessons.data?.length ?? 0) + 1, durationMinutes: null },
      });
      setTitle('');
      setContentRef('');
      lessons.refetch();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not add this lesson.');
    } finally {
      setSaving(false);
    }
  };

  const columns: Column<LessonResponse>[] = [
    { key: 'title', header: 'Lesson', render: (l) => l.title },
    { key: 'type', header: 'Type', render: (l) => l.type ?? '—' },
    { key: 'ref', header: 'Content reference', render: (l) => <span className="mono" style={{ fontSize: 12 }}>{l.contentRef ?? '—'}</span> },
  ];

  return (
    <div style={{ padding: '8px 0 16px' }}>
      <DataTable columns={columns} rows={lessons.data} loading={lessons.loading} error={lessons.error}
        onRetry={lessons.refetch} emptyTitle="No lessons" emptyDescription="This module has no lessons yet." rowKey={(l) => l.id} />
      <div style={{ display: 'flex', gap: 8, marginTop: 10, flexWrap: 'wrap' }}>
        <input aria-label="Lesson title" placeholder="New lesson title" value={title} onChange={(e) => setTitle(e.target.value)} style={{ ...inputStyle, flex: 1, minWidth: 160 }} />
        <select aria-label="Lesson type" value={type} onChange={(e) => setType(e.target.value)} style={inputStyle}>
          <option value="TEXT">TEXT</option>
          <option value="VIDEO">VIDEO</option>
          <option value="DOCUMENT">DOCUMENT</option>
          <option value="LINK">LINK</option>
        </select>
        <input aria-label="Content reference" placeholder="ref://…" value={contentRef} onChange={(e) => setContentRef(e.target.value)} style={{ ...inputStyle, flex: 1, minWidth: 160 }} />
        <button type="button" className="btn btn-primary" style={{ padding: '8px 14px', fontSize: 13 }}
          disabled={saving || !title || !contentRef} onClick={addLesson}>
          {saving ? 'Adding…' : 'Add lesson'}
        </button>
      </div>
      {error && <p style={{ fontSize: 12, color: 'var(--flag)', marginTop: 6 }}>{error}</p>}
    </div>
  );
}

export default function FacultyCoursesPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'FACULTY');
  const facultyId = user?.facultyId ?? null;
  const { sections, loading: sectionsLoading } = useFacultySectionOptions(tenantId, facultyId);
  const [sectionId, setSectionId] = useState('');
  const [expanded, setExpanded] = useState<string | null>(null);
  const [newModuleTitle, setNewModuleTitle] = useState('');
  const [addingModule, setAddingModule] = useState(false);

  const modules = useApiQuery<CourseModuleResponse[]>(
    sectionId && tenantId ? `/learning/modules?sectionId=${sectionId}&tenantId=${tenantId}` : null,
  );

  const addModule = async () => {
    setAddingModule(true);
    try {
      await apiFetch('/learning/modules', {
        method: 'POST',
        body: { sectionId, title: newModuleTitle, sequence: (modules.data?.length ?? 0) + 1 },
      });
      setNewModuleTitle('');
      modules.refetch();
    } finally {
      setAddingModule(false);
    }
  };

  const columns: Column<CourseModuleResponse>[] = [
    { key: 'title', header: 'Module', render: (m) => m.title },
    { key: 'sequence', header: 'Order', render: (m) => m.sequence },
    { key: 'status', header: 'Status', render: (m) => <StatusBadge value={m.status} /> },
    {
      key: 'actions', header: '', render: (m) => (
        <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }}
          onClick={() => setExpanded(expanded === m.id ? null : m.id)}>
          {expanded === m.id ? 'Hide lessons' : 'Lessons'}
        </button>
      ),
    },
  ];

  return (
    <div>
      <PageHeader title="Courses" subtitle="Modules and lessons for the sections you teach." />
      <FacultySectionSelect sections={sections} value={sectionId} onChange={setSectionId} />

      {!sectionId && !sectionsLoading && (
        <div className="card"><p style={{ color: 'var(--ink-soft)' }}>Select a section above to manage its course content.</p></div>
      )}

      {sectionId && (
        <>
          <DataTable columns={columns} rows={modules.data} loading={modules.loading} error={modules.error}
            onRetry={modules.refetch} emptyTitle="No modules" emptyDescription="This section has no course modules yet." rowKey={(m) => m.id} />
          <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
            <input aria-label="New module title" placeholder="New module title" value={newModuleTitle}
              onChange={(e) => setNewModuleTitle(e.target.value)} style={{ ...inputStyle, flex: 1, maxWidth: 320 }} />
            <button type="button" className="btn btn-primary" style={{ padding: '8px 14px', fontSize: 13 }}
              disabled={addingModule || !newModuleTitle} onClick={addModule}>
              {addingModule ? 'Adding…' : 'Add module'}
            </button>
          </div>
          {expanded && <ModuleLessons moduleId={expanded} />}
        </>
      )}
    </div>
  );
}
