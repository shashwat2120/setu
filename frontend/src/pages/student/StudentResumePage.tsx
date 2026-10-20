import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { ResumeContent, ResumeItem, ResumeViewResponse, SkillResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { Skeleton } from '../../components/ui/EmptyState';
import { ResumeCard } from '../../components/career/ResumeCard';
import { ITEM_KINDS, KIND_LABELS, emptyContent, toRequestFields } from '../../lib/resume';

const inputStyle: React.CSSProperties = {
  width: '100%', fontFamily: 'var(--font-body)', fontSize: 14, padding: '9px 12px', border: '1px solid var(--rule)', borderRadius: 4, background: 'var(--bg-elev)', color: 'var(--ink)',
};
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 6 };

/** The student's own resume: what they write, next to what the college's records add, and a print view. Placement officers read it only through an application. */
export default function StudentResumePage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'STUDENT');
  const studentId = user?.studentId ?? null;
  const resume = useApiQuery<ResumeViewResponse>(studentId ? `/career/resumes?studentId=${studentId}` : null);
  const skills = useApiQuery<SkillResponse[]>(tenantId ? `/career/skills?tenantId=${tenantId}` : null);

  const [form, setForm] = useState<ResumeContent>(emptyContent());
  const [version, setVersion] = useState(0);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (resume.data) {
      const c = resume.data.content;
      setForm({ headline: c.headline ?? '', summary: c.summary ?? '', linkedinUrl: c.linkedinUrl ?? '', githubUrl: c.githubUrl ?? '', portfolioUrl: c.portfolioUrl ?? '', items: c.items ?? [] });
      setVersion(resume.data.version);
    }
  }, [resume.data]);

  const set = (patch: Partial<ResumeContent>) => { setSaved(false); setForm((f) => ({ ...f, ...patch })); };
  const setItem = (index: number, patch: Partial<ResumeItem>) => set({ items: form.items.map((it, i) => (i === index ? { ...it, ...patch } : it)) });
  const addItem = (kind: string) => set({ items: [...form.items, { kind, title: '', organisation: '', description: '', startDate: '', endDate: '' }] });
  const removeItem = (index: number) => set({ items: form.items.filter((_, i) => i !== index) });

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!studentId) return;
    setSaving(true);
    setError(null);
    try {
      const result = await apiFetch<{ version: number }>('/career/resumes', { method: 'PUT', body: { studentId, ...toRequestFields(form), expectedVersion: version } });
      setVersion(result.version);
      setSaved(true);
      resume.refetch();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save your resume.');
    } finally {
      setSaving(false);
    }
  };

  const skillName = (id: string) => skills.data?.find((s) => s.id === id)?.name ?? 'A skill';
  const preview = toRequestFields(form);

  return (
    <div>
      <PageHeader title="Resume" subtitle="What placement officers read when you apply. Your skills, CGPA and attendance are added from the college's records." />
      {resume.loading && <Skeleton height={120} />}
      {resume.error && !resume.loading && <div className="card" role="alert"><p>Could not load your resume. <button type="button" className="btn btn-ghost" onClick={resume.refetch}>Try again</button></p></div>}

      {resume.data && (
        <>
          <form onSubmit={save} className="card no-print" style={{ marginBottom: 20 }}>
            <p style={{ fontSize: 13, color: 'var(--ink-soft)', marginBottom: 12 }}>
              When you apply to a job, a copy of your resume is sent with the application, so the placement officer reads what you submitted even if you edit it later. A headline is the least a resume needs.
              {' '}Your skills are kept under <Link to="/app/student/career">Career</Link>.
            </p>
            <div style={{ marginBottom: 12 }}>
              <label htmlFor="resume-headline" style={labelStyle}>Headline</label>
              <input id="resume-headline" value={form.headline ?? ''} maxLength={120} onChange={(e) => set({ headline: e.target.value })} style={inputStyle} placeholder="e.g. Third-year computer science student interested in data" />
            </div>
            <div style={{ marginBottom: 12 }}>
              <label htmlFor="resume-summary" style={labelStyle}>Summary</label>
              <textarea id="resume-summary" value={form.summary ?? ''} maxLength={1000} rows={4} onChange={(e) => set({ summary: e.target.value })} style={inputStyle} />
            </div>
            <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 12 }}>
              {([['resume-linkedin', 'LinkedIn address', 'linkedinUrl'], ['resume-github', 'GitHub address', 'githubUrl'], ['resume-portfolio', 'Portfolio address', 'portfolioUrl']] as const).map(([id, label, key]) => (
                <div key={id} style={{ flex: '1 1 220px' }}>
                  <label htmlFor={id} style={labelStyle}>{label}</label>
                  <input id={id} type="url" value={form[key] ?? ''} maxLength={300} onChange={(e) => set({ [key]: e.target.value })} style={inputStyle} placeholder="https://" />
                </div>
              ))}
            </div>

            {form.items.map((item, i) => (
              <fieldset key={i} style={{ border: '1px solid var(--rule)', borderRadius: 4, padding: 12, marginBottom: 12 }}>
                <legend style={{ fontSize: 13, fontWeight: 600 }}>{KIND_LABELS[item.kind] ?? 'Entry'} {i + 1}</legend>
                <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                  <div style={{ flex: '2 1 200px' }}>
                    <label htmlFor={`item-title-${i}`} style={labelStyle}>Title</label>
                    <input id={`item-title-${i}`} value={item.title} maxLength={120} onChange={(e) => setItem(i, { title: e.target.value })} style={inputStyle} />
                  </div>
                  <div style={{ flex: '2 1 200px' }}>
                    <label htmlFor={`item-org-${i}`} style={labelStyle}>Organisation</label>
                    <input id={`item-org-${i}`} value={item.organisation ?? ''} maxLength={120} onChange={(e) => setItem(i, { organisation: e.target.value })} style={inputStyle} />
                  </div>
                  <div style={{ flex: '1 1 140px' }}>
                    <label htmlFor={`item-start-${i}`} style={labelStyle}>From</label>
                    <input id={`item-start-${i}`} type="date" value={item.startDate ?? ''} onChange={(e) => setItem(i, { startDate: e.target.value })} style={inputStyle} />
                  </div>
                  <div style={{ flex: '1 1 140px' }}>
                    <label htmlFor={`item-end-${i}`} style={labelStyle}>To (empty: ongoing)</label>
                    <input id={`item-end-${i}`} type="date" value={item.endDate ?? ''} onChange={(e) => setItem(i, { endDate: e.target.value })} style={inputStyle} />
                  </div>
                </div>
                <div style={{ marginTop: 10 }}>
                  <label htmlFor={`item-desc-${i}`} style={labelStyle}>Description</label>
                  <textarea id={`item-desc-${i}`} value={item.description ?? ''} maxLength={600} rows={3} onChange={(e) => setItem(i, { description: e.target.value })} style={inputStyle} />
                </div>
                <button type="button" className="btn btn-ghost" style={{ marginTop: 8, padding: '6px 10px', fontSize: 12 }} onClick={() => removeItem(i)}>Remove this {KIND_LABELS[item.kind]?.toLowerCase() ?? 'entry'}</button>
              </fieldset>
            ))}

            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
              {ITEM_KINDS.map((kind) => (
                <button key={kind} type="button" className="btn btn-ghost" style={{ padding: '8px 14px', fontSize: 13 }} disabled={form.items.length >= 20} onClick={() => addItem(kind)}>
                  Add {KIND_LABELS[kind].toLowerCase()}
                </button>
              ))}
            </div>

            <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
              <button type="submit" className="btn btn-primary" disabled={saving}>{saving ? 'Saving…' : 'Save resume'}</button>
              {saved && <span role="status" style={{ fontSize: 13 }}>Saved.</span>}
            </div>
            {error && <p role="alert" style={{ color: 'var(--flag)', fontSize: 13, marginTop: 8 }}>{error}</p>}
          </form>

          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', margin: '0 0 8px' }}>
            <h2 style={{ fontSize: 18, margin: 0 }}>How it reads</h2>
            <button type="button" className="btn btn-ghost no-print" style={{ padding: '8px 14px', fontSize: 13 }} onClick={() => window.print()}>Print or save as PDF</button>
          </div>
          <ResumeCard content={{ ...preview, items: preview.items as ResumeItem[] }} record={resume.data.record} skills={resume.data.skills} skillName={skillName} />
        </>
      )}
    </div>
  );
}
