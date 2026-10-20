import { useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type {
  JobResponse, ApplicationResponse, SkillResponse, JobSkillResponse, StudentResponse, PageResponse, ProgramResponse,
} from '../../api/types';
import { JOB_TYPE_LABELS, fromLocalInput, toLocalInput } from '../../lib/jobTerms';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { ConfirmActionButton } from '../../components/ui/ConfirmActionButton';

const inputStyle: React.CSSProperties = {
  width: '100%', fontFamily: 'var(--font-body)', fontSize: 14, padding: '9px 12px',
  border: '1px solid var(--rule)', borderRadius: 4, background: 'var(--bg-elev)', color: 'var(--ink)',
};
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 6 };

const PROFICIENCY_LEVELS = ['BEGINNER', 'INTERMEDIATE', 'ADVANCED', 'EXPERT'] as const;

const smallInput: React.CSSProperties = { ...inputStyle, width: 150 };

function CreateJobForm({ tenantId, onCreated, job, onCancel }: { tenantId: string; onCreated: () => void; job?: JobResponse; onCancel?: () => void }) {
  const programs = useApiQuery<ProgramResponse[]>(`/catalog/programs?tenantId=${tenantId}`);
  const [minCgpa, setMinCgpa] = useState(job?.minCgpa != null ? String(job.minCgpa) : '');
  const [minAttendance, setMinAttendance] = useState(job?.minAttendancePct != null ? String(job.minAttendancePct) : '');
  const [deadline, setDeadline] = useState(toLocalInput(job?.applicationDeadline));
  const [allowedPrograms, setAllowedPrograms] = useState<string[]>(job?.allowedProgramIds ?? []);
  const [packageLpa, setPackageLpa] = useState(job?.packageLpa != null ? String(job.packageLpa) : '');
  const [location, setLocation] = useState(job?.location ?? '');
  const [openings, setOpenings] = useState(job?.openings != null ? String(job.openings) : '');
  const [jobType, setJobType] = useState(job?.jobType ?? '');
  const [title, setTitle] = useState(job?.title ?? '');
  const [employer, setEmployer] = useState(job?.employer ?? '');
  const [description, setDescription] = useState(job?.description ?? '');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    setSuccess(false);
    try {
      // an empty box means no such rule: a number box left blank is sent as null, never as 0
      const terms = {
        minCgpa: minCgpa.trim() === '' ? null : Number(minCgpa),
        minAttendancePct: minAttendance.trim() === '' ? null : Number(minAttendance),
        applicationDeadline: fromLocalInput(deadline),
        packageLpa: packageLpa.trim() === '' ? null : Number(packageLpa),
        location: location.trim() === '' ? null : location.trim(),
        openings: openings.trim() === '' ? null : Number(openings),
        jobType: jobType === '' ? null : jobType,
        allowedProgramIds: allowedPrograms.length > 0 ? allowedPrograms : null,
      };
      await apiFetch(job ? `/career/jobs/${job.id}` : '/career/jobs', {
        method: job ? 'PUT' : 'POST',
        body: job ? { title, employer, description, expectedVersion: job.version, terms } : { tenantId, title, employer, description, ...terms },
      });
      if (!job) {
        setMinCgpa(''); setMinAttendance(''); setDeadline(''); setAllowedPrograms([]); setPackageLpa(''); setLocation(''); setOpenings(''); setJobType('');
      }
      setTitle(''); setEmployer(''); setDescription('');
      setSuccess(true);
      onCreated();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create the job listing.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={submit} className="card" style={{ marginBottom: 24, maxWidth: 620 }}>
      <h2 style={{ fontSize: 16, marginBottom: 14 }}>{job ? 'Edit job listing' : 'Post a job'}</h2>
      <div style={{ marginBottom: 12 }}>
        <label htmlFor="job-title" style={labelStyle}>Title</label>
        <input id="job-title" required value={title} onChange={(e) => setTitle(e.target.value)} style={inputStyle} placeholder="e.g. Software Engineer Intern" />
      </div>
      <div style={{ marginBottom: 12 }}>
        <label htmlFor="job-employer" style={labelStyle}>Employer</label>
        <input id="job-employer" required value={employer} onChange={(e) => setEmployer(e.target.value)} style={inputStyle} placeholder="Employer name" />
      </div>
      <div style={{ marginBottom: 16 }}>
        <label htmlFor="job-desc" style={labelStyle}>Description</label>
        <textarea id="job-desc" required value={description} onChange={(e) => setDescription(e.target.value)}
          style={{ ...inputStyle, minHeight: 80, fontFamily: 'var(--font-body)' }} placeholder="Role description" />
      </div>
      <fieldset style={{ border: '1px solid var(--rule)', borderRadius: 4, padding: '10px 12px', marginBottom: 16 }}>
        <legend style={{ fontSize: 13, fontWeight: 600, padding: '0 6px' }}>Who can apply (leave a box empty for no rule)</legend>
        <p style={{ fontSize: 12, color: 'var(--ink-soft)', marginBottom: 10 }}>
          Students are checked automatically against their real attendance, grades and program, and told which rule they miss. The CGPA is on your college&apos;s grade-point scale.
        </p>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 12 }}>
          <div>
            <label htmlFor="job-min-cgpa" style={labelStyle}>Minimum CGPA</label>
            <input id="job-min-cgpa" type="number" min={0} max={10} step="0.01" value={minCgpa} onChange={(e) => setMinCgpa(e.target.value)} style={smallInput} />
          </div>
          <div>
            <label htmlFor="job-min-att" style={labelStyle}>Minimum attendance (%)</label>
            <input id="job-min-att" type="number" min={0} max={100} step="0.1" value={minAttendance} onChange={(e) => setMinAttendance(e.target.value)} style={smallInput} />
          </div>
          <div>
            <label htmlFor="job-deadline" style={labelStyle}>Apply by</label>
            <input id="job-deadline" type="datetime-local" value={deadline} onChange={(e) => setDeadline(e.target.value)} style={{ ...inputStyle, width: 220 }} />
          </div>
        </div>
        <div role="group" aria-labelledby="job-programs-label">
          <div id="job-programs-label" style={labelStyle}>Open to these programs (none ticked: any program)</div>
          {programs.data && programs.data.length > 0 ? (
            <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap' }}>
              {programs.data.map((p) => (
                <label key={p.id} style={{ fontSize: 13, display: 'flex', alignItems: 'center', gap: 6 }}>
                  <input type="checkbox" checked={allowedPrograms.includes(p.id)}
                    onChange={(e) => setAllowedPrograms((cur) => (e.target.checked ? [...cur, p.id] : cur.filter((id) => id !== p.id)))} />
                  {p.name}
                </label>
              ))}
            </div>
          ) : (
            <p style={{ fontSize: 12, color: 'var(--ink-soft)' }}>{programs.loading ? 'Loading programs…' : 'No programs found for your college.'}</p>
          )}
        </div>
      </fieldset>
      <fieldset style={{ border: '1px solid var(--rule)', borderRadius: 4, padding: '10px 12px', marginBottom: 16 }}>
        <legend style={{ fontSize: 13, fontWeight: 600, padding: '0 6px' }}>About the job (shown to students, not enforced)</legend>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
          <div>
            <label htmlFor="job-package" style={labelStyle}>Package (LPA)</label>
            <input id="job-package" type="number" min={0} step="0.01" value={packageLpa} onChange={(e) => setPackageLpa(e.target.value)} style={smallInput} />
          </div>
          <div>
            <label htmlFor="job-location" style={labelStyle}>Location</label>
            <input id="job-location" value={location} onChange={(e) => setLocation(e.target.value)} style={{ ...inputStyle, width: 180 }} />
          </div>
          <div>
            <label htmlFor="job-openings" style={labelStyle}>Openings</label>
            <input id="job-openings" type="number" min={1} step={1} value={openings} onChange={(e) => setOpenings(e.target.value)} style={{ ...inputStyle, width: 100 }} />
          </div>
          <div>
            <label htmlFor="job-type" style={labelStyle}>Type</label>
            <select id="job-type" value={jobType} onChange={(e) => setJobType(e.target.value)} style={{ ...inputStyle, width: 150 }}>
              <option value="">Not stated</option>
              {Object.entries(JOB_TYPE_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
          </div>
        </div>
      </fieldset>
      {error && <p role="alert" style={{ color: 'var(--flag)', fontSize: 13, marginBottom: 12 }}>{error}</p>}
      {success && <p role="status" style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 12 }}>Job posted.</p>}
      <div style={{ display: 'flex', gap: 8 }}>
        <button type="submit" className="btn btn-primary" disabled={submitting}>{submitting ? 'Saving…' : job ? 'Save changes' : 'Post job'}</button>
        {job && <button type="button" className="btn btn-ghost" onClick={onCancel}>Cancel</button>}
      </div>
    </form>
  );
}

function RequiredSkills({ jobId, skills }: { jobId: string; skills: SkillResponse[] | null }) {
  const jobSkills = useApiQuery<JobSkillResponse[]>(`/career/job-skills?jobId=${jobId}`);
  const [skillId, setSkillId] = useState('');
  const [level, setLevel] = useState<typeof PROFICIENCY_LEVELS[number]>('BEGINNER');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const skillNameFor = (id: string) => skills?.find((s) => s.id === id)?.name ?? id;

  const removeRequirement = async (id: string) => {
    setError(null);
    try { await apiFetch(`/career/job-skills/${id}`, { method: 'DELETE' }); jobSkills.refetch(); }
    catch (err) { setError(err instanceof Error ? err.message : 'Could not remove the requirement.'); }
  };

  const addRequirement = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!skillId) return;
    setSubmitting(true);
    setError(null);
    try {
      await apiFetch('/career/job-skills', { method: 'POST', body: { jobId, skillId, requiredLevel: level } });
      jobSkills.refetch();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not add the skill requirement.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div style={{ padding: '10px 0 16px' }}>
      <h3 style={{ fontSize: 14, marginBottom: 8 }}>Required skills</h3>
      {jobSkills.data && jobSkills.data.length > 0 ? (
        <ul style={{ marginBottom: 12, paddingLeft: 18, fontSize: 13 }}>
          {jobSkills.data.map((js) => <li key={js.id} style={{ marginBottom: 5 }}>{skillNameFor(js.skillId)} — <span className="mono">{js.requiredLevel}</span> <ConfirmActionButton label="Remove" variant="danger" confirmMessage={`Remove ${skillNameFor(js.skillId)} from this job?`} onConfirm={() => removeRequirement(js.id)} /></li>)}
        </ul>
      ) : <p style={{ fontSize: 13, color: 'var(--ink-soft)', marginBottom: 12 }}>No skill requirements set.</p>}
      <form onSubmit={addRequirement} style={{ display: 'flex', gap: 8, alignItems: 'flex-end', flexWrap: 'wrap' }}>
        <div>
          <label htmlFor={`skill-${jobId}`} style={{ ...labelStyle, fontSize: 12 }}>Skill</label>
          <select id={`skill-${jobId}`} value={skillId} onChange={(e) => setSkillId(e.target.value)} style={{ ...inputStyle, width: 180 }}>
            <option value="">Select…</option>
            {skills?.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </div>
        <div>
          <label htmlFor={`level-${jobId}`} style={{ ...labelStyle, fontSize: 12 }}>Required level</label>
          <select id={`level-${jobId}`} value={level} onChange={(e) => setLevel(e.target.value as typeof PROFICIENCY_LEVELS[number])} style={{ ...inputStyle, width: 140 }}>
            {PROFICIENCY_LEVELS.map((l) => <option key={l} value={l}>{l}</option>)}
          </select>
        </div>
        <button type="submit" className="btn btn-ghost" style={{ padding: '9px 14px', fontSize: 13 }} disabled={submitting || !skillId}>
          {submitting ? 'Adding…' : 'Add'}
        </button>
      </form>
      {error && <p style={{ color: 'var(--flag)', fontSize: 12, marginTop: 8 }}>{error}</p>}
    </div>
  );
}

function JobApplications({ jobId, studentNameFor }: { jobId: string; studentNameFor: (id: string) => string }) {
  const applications = useApiQuery<ApplicationResponse[]>(`/career/applications/by-job?jobId=${jobId}`);
  const [actionError, setActionError] = useState<string | null>(null);

  const act = async (applicationId: string, action: string) => {
    setActionError(null);
    try {
      await apiFetch(`/career/applications/${applicationId}/${action}`, { method: 'POST' });
      applications.refetch();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : `Could not ${action} this application.`);
    }
  };

  const columns: Column<ApplicationResponse>[] = [
    { key: 'student', header: 'Student', render: (a) => studentNameFor(a.studentId) },
    { key: 'status', header: 'Status', render: (a) => <StatusBadge value={a.status} /> },
    { key: 'appliedAt', header: 'Applied', render: (a) => new Date(a.appliedAt).toLocaleDateString() },
    {
      key: 'actions',
      header: 'Actions',
      render: (a) => (a.status === 'SUBMITTED' || a.status === 'SHORTLISTED') ? (
        <div style={{ display: 'flex', gap: 8 }}>
          {a.status === 'SUBMITTED' && (
            <ConfirmActionButton label="Shortlist" confirmMessage="Shortlist this application?" onConfirm={() => act(a.id, 'shortlist')} />
          )}
          {a.status === 'SHORTLISTED' && (
            <ConfirmActionButton label="Select" confirmMessage="Select this candidate?" onConfirm={() => act(a.id, 'select')} />
          )}
          <ConfirmActionButton label="Reject" variant="danger" confirmMessage="Reject this application?" onConfirm={() => act(a.id, 'reject')} />
        </div>
      ) : null,
    },
  ];

  return (
    <div style={{ padding: '4px 0 16px' }}>
      {actionError && <p style={{ color: 'var(--flag)', fontSize: 12, marginBottom: 8 }}>{actionError}</p>}
      <DataTable columns={columns} rows={applications.data} loading={applications.loading} error={applications.error}
        onRetry={applications.refetch} emptyTitle="No applications" emptyDescription="No students have applied to this job yet." rowKey={(a) => a.id} />
    </div>
  );
}

export default function PlacementJobsPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'PLACEMENT_OFFICER');
  const [expandedJob, setExpandedJob] = useState<{ id: string; panel: 'skills' | 'applications' } | null>(null);
  const [editingJob, setEditingJob] = useState<JobResponse | null>(null);
  const [statusError, setStatusError] = useState<string | null>(null);

  const jobs = useApiQuery<JobResponse[]>(tenantId ? `/career/jobs?tenantId=${tenantId}` : null);
  const skills = useApiQuery<SkillResponse[]>(tenantId ? `/career/skills?tenantId=${tenantId}` : null);
  const students = useApiQuery<PageResponse<StudentResponse>>(tenantId ? `/directory/students?tenantId=${tenantId}&size=500` : null);
  const studentNameFor = (studentId: string) =>
    students.data?.content.find((s) => s.id === studentId)?.fullName
    ?? students.data?.content.find((s) => s.id === studentId)?.email
    ?? studentId;

  const closeJob = async (jobId: string) => {
    setStatusError(null);
    try {
      await apiFetch(`/career/jobs/${jobId}/close`, { method: 'POST' });
      jobs.refetch();
    } catch (err) {
      setStatusError(err instanceof Error ? err.message : 'Could not close the job.');
    }
  };

  const toggle = (jobId: string, panel: 'skills' | 'applications') => {
    setExpandedJob((cur) => (cur?.id === jobId && cur.panel === panel ? null : { id: jobId, panel }));
  };

  const columns: Column<JobResponse>[] = [
    { key: 'title', header: 'Title', render: (j) => j.title },
    { key: 'employer', header: 'Employer', render: (j) => j.employer },
    { key: 'status', header: 'Status', render: (j) => <StatusBadge value={j.status} /> },
    {
      key: 'actions',
      header: 'Actions',
      render: (j) => (
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }}
            onClick={() => toggle(j.id, 'skills')}>
            {expandedJob?.id === j.id && expandedJob.panel === 'skills' ? 'Hide skills' : 'Required skills'}
          </button>
          <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }}
            onClick={() => toggle(j.id, 'applications')}>
            {expandedJob?.id === j.id && expandedJob.panel === 'applications' ? 'Hide applications' : 'Applications'}
          </button>
          {j.status === 'OPEN' && (
            <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }} onClick={() => setEditingJob(j)}>Edit</button>
          )}
          {j.status === 'OPEN' && (
            <ConfirmActionButton label="Close" variant="danger" confirmMessage={`Close "${j.title}"? No further applications will be accepted.`} onConfirm={() => closeJob(j.id)} />
          )}
        </div>
      ),
    },
  ];

  return (
    <div>
      <PageHeader title="Jobs" subtitle="Post listings, define required skills, and review applications." />
      {tenantId && !editingJob && <CreateJobForm tenantId={tenantId} onCreated={jobs.refetch} />}
      {tenantId && editingJob && <CreateJobForm key={editingJob.id} tenantId={tenantId} job={editingJob} onCreated={() => { setEditingJob(null); jobs.refetch(); }} onCancel={() => setEditingJob(null)} />}
      {statusError && <p style={{ color: 'var(--flag)', fontSize: 13, marginBottom: 12 }}>{statusError}</p>}
      <DataTable
        columns={columns}
        rows={jobs.data}
        loading={jobs.loading || students.loading}
        error={jobs.error}
        onRetry={jobs.refetch}
        emptyTitle="No jobs posted"
        emptyDescription="No job listings have been posted yet."
        rowKey={(j) => j.id}
      />
      {expandedJob?.panel === 'skills' && <RequiredSkills jobId={expandedJob.id} skills={skills.data} />}
      {expandedJob?.panel === 'applications' && <JobApplications jobId={expandedJob.id} studentNameFor={studentNameFor} />}
    </div>
  );
}
