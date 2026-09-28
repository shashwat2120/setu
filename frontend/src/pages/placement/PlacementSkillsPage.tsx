import { useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { SkillCategoryResponse, SkillResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';

const inputStyle: React.CSSProperties = {
  width: '100%', fontFamily: 'var(--font-body)', fontSize: 14, padding: '9px 12px',
  border: '1px solid var(--rule)', borderRadius: 4, background: 'var(--bg-elev)', color: 'var(--ink)',
};
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 6 };

function EditSkillForm({ skill, categories, onSaved, onCancel }: { skill: SkillResponse; categories: SkillCategoryResponse[] | null; onSaved: () => void; onCancel: () => void }) {
  const [code, setCode] = useState(skill.code);
  const [name, setName] = useState(skill.name);
  const [categoryId, setCategoryId] = useState(skill.categoryId ?? '');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const submit = async (event: React.FormEvent) => {
    event.preventDefault(); setSaving(true); setError(null);
    try {
      await apiFetch(`/career/skills/${skill.id}`, { method: 'PUT', body: { code, name, categoryId: categoryId || null, expectedVersion: skill.version } });
      onSaved();
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not update skill.'); }
    finally { setSaving(false); }
  };
  return <form onSubmit={submit} className="card" style={{ margin: '12px 0', display: 'flex', gap: 8, alignItems: 'flex-end', flexWrap: 'wrap' }}>
    <div><label htmlFor="edit-skill-code" style={labelStyle}>Code</label><input id="edit-skill-code" required value={code} onChange={(e) => setCode(e.target.value)} style={{ ...inputStyle, width: 120 }} /></div>
    <div><label htmlFor="edit-skill-name" style={labelStyle}>Name</label><input id="edit-skill-name" required value={name} onChange={(e) => setName(e.target.value)} style={{ ...inputStyle, width: 180 }} /></div>
    <div><label htmlFor="edit-skill-category" style={labelStyle}>Category</label><select id="edit-skill-category" value={categoryId} onChange={(e) => setCategoryId(e.target.value)} style={{ ...inputStyle, width: 180 }}><option value="">None</option>{categories?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</select></div>
    <button className="btn btn-primary" disabled={saving}>{saving ? 'Saving…' : 'Save'}</button><button type="button" className="btn btn-ghost" onClick={onCancel}>Cancel</button>
    {error && <p role="alert" style={{ color: 'var(--flag)', width: '100%', fontSize: 13 }}>{error}</p>}
  </form>;
}

function EditCategoryForm({ category, onSaved, onCancel }: { category: SkillCategoryResponse; onSaved: () => void; onCancel: () => void }) {
  const [code, setCode] = useState(category.code);
  const [name, setName] = useState(category.name);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const submit = async (event: React.FormEvent) => {
    event.preventDefault(); setSaving(true); setError(null);
    try {
      await apiFetch(`/career/skill-categories/${category.id}`, { method: 'PUT', body: { code, name, expectedVersion: category.version } });
      onSaved();
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not update category.'); }
    finally { setSaving(false); }
  };
  return <form onSubmit={submit} className="card" style={{ margin: '12px 0', display: 'flex', gap: 8, alignItems: 'flex-end', flexWrap: 'wrap' }}>
    <div><label htmlFor="edit-category-code" style={labelStyle}>Code</label><input id="edit-category-code" required value={code} onChange={(e) => setCode(e.target.value)} style={{ ...inputStyle, width: 120 }} /></div>
    <div><label htmlFor="edit-category-name" style={labelStyle}>Name</label><input id="edit-category-name" required value={name} onChange={(e) => setName(e.target.value)} style={{ ...inputStyle, width: 200 }} /></div>
    <button className="btn btn-primary" disabled={saving}>{saving ? 'Saving…' : 'Save'}</button><button type="button" className="btn btn-ghost" onClick={onCancel}>Cancel</button>
    {error && <p role="alert" style={{ color: 'var(--flag)', width: '100%', fontSize: 13 }}>{error}</p>}
  </form>;
}

function CreateCategoryForm({ tenantId, onCreated }: { tenantId: string; onCreated: () => void }) {
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await apiFetch('/career/skill-categories', { method: 'POST', body: { tenantId, code, name } });
      setCode(''); setName('');
      onCreated();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create the category.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={submit} className="card" style={{ marginBottom: 16, maxWidth: 420 }}>
      <h3 style={{ fontSize: 14, marginBottom: 12 }}>New skill category</h3>
      <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end', flexWrap: 'wrap' }}>
        <div>
          <label htmlFor="cat-code" style={{ ...labelStyle, fontSize: 12 }}>Code</label>
          <input id="cat-code" required value={code} onChange={(e) => setCode(e.target.value)} style={{ ...inputStyle, width: 100 }} placeholder="TECH" />
        </div>
        <div>
          <label htmlFor="cat-name" style={{ ...labelStyle, fontSize: 12 }}>Name</label>
          <input id="cat-name" required value={name} onChange={(e) => setName(e.target.value)} style={{ ...inputStyle, width: 200 }} placeholder="Technical" />
        </div>
        <button type="submit" className="btn btn-ghost" style={{ padding: '9px 14px', fontSize: 13 }} disabled={submitting}>
          {submitting ? 'Adding…' : 'Add category'}
        </button>
      </div>
      {error && <p style={{ color: 'var(--flag)', fontSize: 12, marginTop: 8 }}>{error}</p>}
    </form>
  );
}

function CreateSkillForm({ tenantId, categories, onCreated }: { tenantId: string; categories: SkillCategoryResponse[] | null; onCreated: () => void }) {
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await apiFetch('/career/skills', { method: 'POST', body: { tenantId, code, name, categoryId: categoryId || null } });
      setCode(''); setName('');
      onCreated();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create the skill.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={submit} className="card" style={{ marginBottom: 24, maxWidth: 480 }}>
      <h3 style={{ fontSize: 14, marginBottom: 12 }}>New skill</h3>
      <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end', flexWrap: 'wrap' }}>
        <div>
          <label htmlFor="skill-code" style={{ ...labelStyle, fontSize: 12 }}>Code</label>
          <input id="skill-code" required value={code} onChange={(e) => setCode(e.target.value)} style={{ ...inputStyle, width: 100 }} placeholder="REACT" />
        </div>
        <div>
          <label htmlFor="skill-name" style={{ ...labelStyle, fontSize: 12 }}>Name</label>
          <input id="skill-name" required value={name} onChange={(e) => setName(e.target.value)} style={{ ...inputStyle, width: 160 }} placeholder="React" />
        </div>
        <div>
          <label htmlFor="skill-cat" style={{ ...labelStyle, fontSize: 12 }}>Category</label>
          <select id="skill-cat" value={categoryId} onChange={(e) => setCategoryId(e.target.value)} style={{ ...inputStyle, width: 160 }}>
            <option value="">None</option>
            {categories?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </div>
        <button type="submit" className="btn btn-ghost" style={{ padding: '9px 14px', fontSize: 13 }} disabled={submitting}>
          {submitting ? 'Adding…' : 'Add skill'}
        </button>
      </div>
      {error && <p style={{ color: 'var(--flag)', fontSize: 12, marginTop: 8 }}>{error}</p>}
    </form>
  );
}

export default function PlacementSkillsPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'PLACEMENT_OFFICER');

  const categories = useApiQuery<SkillCategoryResponse[]>(tenantId ? `/career/skill-categories?tenantId=${tenantId}` : null);
  const skills = useApiQuery<SkillResponse[]>(tenantId ? `/career/skills?tenantId=${tenantId}` : null);
  const categoryNameFor = (id: string | null) => (id ? categories.data?.find((c) => c.id === id)?.name ?? id : '—');
  const [editingSkill, setEditingSkill] = useState<string | null>(null);
  const [editingCategory, setEditingCategory] = useState<string | null>(null);

  const skillColumns: Column<SkillResponse>[] = [
    { key: 'code', header: 'Code', render: (s) => <span className="mono">{s.code}</span> },
    { key: 'name', header: 'Name', render: (s) => s.name },
    { key: 'category', header: 'Category', render: (s) => categoryNameFor(s.categoryId) },
    { key: 'actions', header: 'Actions', render: (s) => <button type="button" className="btn btn-ghost" onClick={() => setEditingSkill(s.id)}>Edit</button> },
  ];

  const categoryColumns: Column<SkillCategoryResponse>[] = [
    { key: 'code', header: 'Code', render: (c) => <span className="mono">{c.code}</span> },
    { key: 'name', header: 'Name', render: (c) => c.name },
    { key: 'actions', header: 'Actions', render: (c) => <button type="button" className="btn btn-ghost" onClick={() => setEditingCategory(c.id)}>Edit</button> },
  ];

  return (
    <div>
      <PageHeader title="Skills catalog" subtitle="Skill categories and skills used to define job requirements and student profiles." />
      {tenantId && (
        <>
          <CreateCategoryForm tenantId={tenantId} onCreated={categories.refetch} />
          <CreateSkillForm tenantId={tenantId} categories={categories.data} onCreated={skills.refetch} />
        </>
      )}
      <DataTable
        columns={skillColumns}
        rows={skills.data}
        loading={skills.loading || categories.loading}
        error={skills.error}
        onRetry={skills.refetch}
        emptyTitle="No skills"
        emptyDescription="No skills have been defined yet."
        rowKey={(s) => s.id}
      />
      {editingSkill && skills.data?.find((s) => s.id === editingSkill) && <EditSkillForm key={editingSkill} skill={skills.data.find((s) => s.id === editingSkill)!} categories={categories.data} onSaved={() => { setEditingSkill(null); skills.refetch(); }} onCancel={() => setEditingSkill(null)} />}
      <h2 style={{ fontSize: 16, margin: '28px 0 12px' }}>Skill categories</h2>
      <DataTable columns={categoryColumns} rows={categories.data} loading={categories.loading} error={categories.error} onRetry={categories.refetch} emptyTitle="No categories" emptyDescription="Create categories to organize the skills catalog." rowKey={(c) => c.id} />
      {editingCategory && categories.data?.find((c) => c.id === editingCategory) && <EditCategoryForm key={editingCategory} category={categories.data.find((c) => c.id === editingCategory)!} onSaved={() => { setEditingCategory(null); categories.refetch(); }} onCancel={() => setEditingCategory(null)} />}
    </div>
  );
}
