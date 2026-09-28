import { useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { SkillResponse, JobResponse, ApplicationResponse, PlacementRecordResponse, JobEligibilityResponse, StudentSkillResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { ConfirmActionButton } from '../../components/ui/ConfirmActionButton';
import { Skeleton } from '../../components/ui/EmptyState';

function MySkills({ studentId, tenantId }: { studentId: string; tenantId: string }) {
  const mySkills = useApiQuery<StudentSkillResponse[]>(`/career/student-skills?studentId=${studentId}`);
  const catalog = useApiQuery<SkillResponse[]>(`/career/skills?tenantId=${tenantId}`);
  const [skillId, setSkillId] = useState('');
  const [level, setLevel] = useState('BEGINNER');
  const [adding, setAdding] = useState(false);

  const skillNameFor = (id: string) => catalog.data?.find((s) => s.id === id)?.name ?? id;

  const add = async () => {
    setAdding(true);
    try {
      await apiFetch('/career/student-skills', { method: 'POST', body: { studentId, skillId, proficiencyLevel: level } });
      setSkillId('');
      mySkills.refetch();
    } finally {
      setAdding(false);
    }
  };

  const remove = async (studentSkillId: string) => {
    await apiFetch(`/career/student-skills/${studentSkillId}`, { method: 'DELETE' });
    mySkills.refetch();
  };

  const columns: Column<StudentSkillResponse>[] = [
    { key: 'name', header: 'Skill', render: (s) => skillNameFor(s.skillId) },
    { key: 'level', header: 'Proficiency', render: (s) => s.proficiencyLevel },
    {
      key: 'actions', header: '', render: (s) => (
        <ConfirmActionButton label="Remove" variant="danger" confirmMessage="Remove this skill from your profile?" onConfirm={() => remove(s.id)} />
      ),
    },
  ];

  return (
    <div>
      <DataTable columns={columns} rows={mySkills.data} loading={mySkills.loading || catalog.loading} error={mySkills.error}
        onRetry={mySkills.refetch} emptyTitle="No skills added" emptyDescription="Add skills to your profile to see matching jobs." rowKey={(s) => s.id} />
      <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
        <select aria-label="Skill" value={skillId} onChange={(e) => setSkillId(e.target.value)}
          style={{ padding: '8px 10px', fontSize: 13, border: '1px solid var(--rule)', borderRadius: 4, background: 'var(--bg-elev)', color: 'var(--ink)', flex: 1, minWidth: 160 }}>
          <option value="">Select a skill…</option>
          {catalog.data?.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
        </select>
        <select aria-label="Proficiency" value={level} onChange={(e) => setLevel(e.target.value)}
          style={{ padding: '8px 10px', fontSize: 13, border: '1px solid var(--rule)', borderRadius: 4, background: 'var(--bg-elev)', color: 'var(--ink)' }}>
          <option value="BEGINNER">BEGINNER</option>
          <option value="INTERMEDIATE">INTERMEDIATE</option>
          <option value="ADVANCED">ADVANCED</option>
          <option value="EXPERT">EXPERT</option>
        </select>
        <button type="button" className="btn btn-primary" style={{ padding: '8px 14px', fontSize: 13 }} disabled={adding || !skillId} onClick={add}>
          {adding ? 'Adding…' : 'Add skill'}
        </button>
      </div>
    </div>
  );
}

function JobCard({ job, studentId }: { job: JobResponse; studentId: string }) {
  const eligibility = useApiQuery<JobEligibilityResponse>(
    `/career/applications/eligibility?jobId=${job.id}&studentId=${studentId}`,
  );
  const applications = useApiQuery<ApplicationResponse[]>(`/career/applications?studentId=${studentId}`);
  const [applying, setApplying] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const myApplication = applications.data?.find((a) => a.jobId === job.id);

  const apply = async () => {
    setApplying(true);
    setError(null);
    try {
      await apiFetch('/career/applications', { method: 'POST', body: { jobId: job.id, studentId } });
      applications.refetch();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not apply.');
    } finally {
      setApplying(false);
    }
  };

  const withdraw = async () => {
    if (!myApplication) return;
    await apiFetch(`/career/applications/${myApplication.id}/withdraw`, { method: 'POST' });
    applications.refetch();
  };

  return (
    <div className="card" style={{ marginBottom: 12 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', flexWrap: 'wrap', gap: 8 }}>
        <div>
          <strong>{job.title}</strong>
          <div style={{ fontSize: 13, color: 'var(--ink-soft)' }}>{job.employer}</div>
        </div>
        <StatusBadge value={job.status} />
      </div>
      {eligibility.loading ? <Skeleton height={16} width={120} /> : eligibility.data && (
        <p style={{ fontSize: 13, marginTop: 8, color: eligibility.data.eligible ? 'var(--ink)' : 'var(--flag)' }}>
          {eligibility.data.eligible ? 'You meet the skill requirements for this role.' : `Missing ${eligibility.data.missingOrInsufficientSkillIds.length} required skill(s).`}
        </p>
      )}
      <div style={{ marginTop: 10 }}>
        {myApplication ? (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <StatusBadge value={myApplication.status} />
            {myApplication.status === 'SUBMITTED' && (
              <ConfirmActionButton label="Withdraw" variant="danger" confirmMessage="Withdraw your application?" onConfirm={withdraw} />
            )}
          </div>
        ) : job.status === 'OPEN' && (
          <button type="button" className="btn btn-primary" style={{ padding: '8px 14px', fontSize: 13 }} disabled={applying} onClick={apply}>
            {applying ? 'Applying…' : 'Apply'}
          </button>
        )}
      </div>
      {error && <p style={{ fontSize: 12, color: 'var(--flag)', marginTop: 6 }}>{error}</p>}
    </div>
  );
}

export default function StudentCareerPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'STUDENT');
  const studentId = user?.studentId ?? null;

  const jobs = useApiQuery<JobResponse[]>(tenantId ? `/career/jobs?tenantId=${tenantId}` : null);
  const placements = useApiQuery<PlacementRecordResponse[]>(studentId ? `/career/placement-records?studentId=${studentId}` : null);

  return (
    <div>
      <PageHeader title="Career" subtitle="Skills, job listings, and applications." />

      <h2 style={{ fontSize: 18, marginBottom: 12 }}>My skills</h2>
      {tenantId && studentId && <MySkills studentId={studentId} tenantId={tenantId} />}

      <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>Job listings</h2>
      {jobs.loading && <Skeleton height={80} />}
      {jobs.data && jobs.data.length === 0 && <div className="card"><p style={{ color: 'var(--ink-soft)' }}>No jobs posted yet.</p></div>}
      {jobs.data?.map((j) => studentId && <JobCard key={j.id} job={j} studentId={studentId} />)}

      {placements.data && placements.data.length > 0 && (
        <>
          <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>Placement record</h2>
          {placements.data.map((p) => (
            <div key={p.id} className="card" style={{ marginBottom: 12 }}>
              <strong>{p.employer}</strong> — {p.role}
              <div style={{ fontSize: 13, color: 'var(--ink-soft)', marginTop: 4 }}>CTC {p.ctc} · Placed {p.placedAt}</div>
            </div>
          ))}
        </>
      )}
    </div>
  );
}
