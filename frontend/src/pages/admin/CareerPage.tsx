import { useMemo, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { useApiQuery } from '../../api/useApiQuery';
import type { JobResponse, SkillResponse, StudentResponse, PageResponse, PlacementRecordResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { SearchInput } from '../../components/ui/SearchInput';

function StudentPlacements({ studentId }: { studentId: string }) {
  const records = useApiQuery<PlacementRecordResponse[]>(`/career/placement-records?studentId=${studentId}`);
  const columns: Column<PlacementRecordResponse>[] = [
    { key: 'employer', header: 'Employer', render: (r) => r.employer },
    { key: 'role', header: 'Role', render: (r) => r.role },
    { key: 'ctc', header: 'CTC', render: (r) => r.ctc },
    { key: 'placedAt', header: 'Placed', render: (r) => r.placedAt },
  ];
  return (
    <div style={{ padding: '4px 0 16px' }}>
      <DataTable
        columns={columns}
        rows={records.data}
        loading={records.loading}
        error={records.error}
        onRetry={records.refetch}
        emptyTitle="No placement records"
        rowKey={(r) => r.id}
      />
    </div>
  );
}

export default function CareerPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'COLLEGE_ADMIN');
  const [search, setSearch] = useState('');
  const [selectedStudent, setSelectedStudent] = useState<{ id: string; name: string } | null>(null);

  const jobs = useApiQuery<JobResponse[]>(tenantId ? `/career/jobs?tenantId=${tenantId}` : null);
  const skills = useApiQuery<SkillResponse[]>(tenantId ? `/career/skills?tenantId=${tenantId}` : null);
  const students = useApiQuery<PageResponse<StudentResponse>>(
    tenantId ? `/directory/students?tenantId=${tenantId}&size=500` : null,
  );
  const filteredStudents = useMemo(() => {
    if (!students.data || !search.trim()) return [];
    const q = search.toLowerCase();
    return students.data.content.filter((s) => s.fullName?.toLowerCase().includes(q) || s.email?.toLowerCase().includes(q)).slice(0, 8);
  }, [students.data, search]);

  const jobColumns: Column<JobResponse>[] = [
    { key: 'title', header: 'Title', render: (j) => j.title },
    { key: 'employer', header: 'Employer', render: (j) => j.employer },
    { key: 'status', header: 'Status', render: (j) => <StatusBadge value={j.status} /> },
  ];

  const skillColumns: Column<SkillResponse>[] = [
    { key: 'code', header: 'Code', render: (s) => <span className="mono">{s.code}</span> },
    { key: 'name', header: 'Name', render: (s) => s.name },
  ];

  return (
    <div>
      <PageHeader title="Career" subtitle="A read-only view of job listings, the skills catalog, and placement records." />

      <h2 style={{ fontSize: 18, marginBottom: 12 }}>Jobs</h2>
      <DataTable
        columns={jobColumns}
        rows={jobs.data}
        loading={jobs.loading || students.loading}
        error={jobs.error}
        onRetry={jobs.refetch}
        emptyTitle="No jobs"
        emptyDescription="No job listings have been posted yet."
        rowKey={(j) => j.id}
      />

      <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>Skills catalog</h2>
      <DataTable
        columns={skillColumns}
        rows={skills.data}
        loading={skills.loading}
        error={skills.error}
        onRetry={skills.refetch}
        emptyTitle="No skills"
        emptyDescription="No skills have been defined yet."
        rowKey={(s) => s.id}
      />

      <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>Placement lookup</h2>
      <SearchInput value={search} onChange={setSearch} label="Find a student" placeholder="Name or email" />
      {filteredStudents.length > 0 && !selectedStudent && (
        <div className="card" style={{ padding: 8, marginBottom: 16 }}>
          {filteredStudents.map((s) => (
            <button key={s.id} type="button" className="btn btn-ghost" style={{ display: 'block', width: '100%', textAlign: 'left', marginBottom: 4 }}
              onClick={() => { setSelectedStudent({ id: s.id, name: s.fullName ?? s.email ?? s.id }); setSearch(''); }}>
              {s.fullName ?? s.email}
            </button>
          ))}
        </div>
      )}
      {selectedStudent && (
        <>
          <p style={{ fontSize: 14, marginBottom: 4 }}>
            <strong>{selectedStudent.name}</strong>{' '}
            <button type="button" className="btn btn-ghost" style={{ padding: '4px 10px', fontSize: 12, marginLeft: 8 }}
              onClick={() => setSelectedStudent(null)}>Change</button>
          </p>
          <StudentPlacements studentId={selectedStudent.id} />
        </>
      )}
    </div>
  );
}
