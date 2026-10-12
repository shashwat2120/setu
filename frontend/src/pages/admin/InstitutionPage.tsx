import { useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { tenantForRole } from '../../auth/types';
import { useApiQuery } from '../../api/useApiQuery';
import type {
  CollegeResponse,
  CampusResponse,
  OrganizationResponse,
  AcademicYearResponse,
  AcademicPeriodResponse,
  BuildingResponse,
  RoomResponse,
} from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { DataTable, type Column } from '../../components/ui/DataTable';
import { Skeleton, ErrorState } from '../../components/ui/EmptyState';
import { StatusBadge } from '../../components/ui/StatusBadge';
import { apiFetch } from '../../api/client';

function BuildingRooms({ buildingId, tenantId }: { buildingId: string; tenantId: string }) {
  const rooms = useApiQuery<RoomResponse[]>(`/catalog/rooms?buildingId=${buildingId}&tenantId=${tenantId}`, [buildingId]);
  const [form, setForm] = useState({ code: '', capacity: '30' });
  const [message, setMessage] = useState('');
  async function createRoom(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setMessage('');
    try {
      await apiFetch('/catalog/rooms', { method: 'POST', body: { buildingId, code: form.code, capacity: Number(form.capacity), featuresJson: null } });
      setMessage('Room created.'); setForm({ code: '', capacity: '30' }); rooms.refetch();
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Could not create room.'); }
  }
  const columns: Column<RoomResponse>[] = [
    { key: 'code', header: 'Room', render: (r) => <span className="mono">{r.code}</span> },
    { key: 'capacity', header: 'Capacity', render: (r) => r.capacity },
  ];
  return (
    <div style={{ padding: '10px 16px 16px' }}>
      <form onSubmit={createRoom} style={{ padding: '0 0 12px', display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap' }}>
        <div className="form-field" style={{ margin: 0 }}><label htmlFor="room-code">Room code</label><input id="room-code" value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} required /></div>
        <div className="form-field" style={{ margin: 0 }}><label htmlFor="room-capacity">Capacity</label><input id="room-capacity" type="number" min="1" value={form.capacity} onChange={(e) => setForm({ ...form, capacity: e.target.value })} required /></div>
        <button className="btn btn-primary" style={{ padding: '9px 16px' }}>Add room</button>
        {message && <p role="status" style={{ margin: 0, fontSize: 13 }}>{message}</p>}
      </form>
      <DataTable columns={columns} rows={rooms.data} loading={rooms.loading} error={rooms.error} onRetry={rooms.refetch}
        emptyTitle="No rooms" emptyDescription="This building has no rooms yet." rowKey={(r) => r.id} />
    </div>
  );
}

function BuildingsAndRooms({ tenantId }: { tenantId: string }) {
  const buildings = useApiQuery<BuildingResponse[]>(`/catalog/buildings?tenantId=${tenantId}`);
  const [form, setForm] = useState({ code: '', name: '' });
  const [message, setMessage] = useState('');
  const [expanded, setExpanded] = useState<string | null>(null);
  async function createBuilding(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setMessage('');
    try {
      await apiFetch('/catalog/buildings', { method: 'POST', body: { tenantId, code: form.code, name: form.name } });
      setMessage('Building created.'); setForm({ code: '', name: '' }); buildings.refetch();
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Could not create building.'); }
  }
  const columns: Column<BuildingResponse>[] = [
    { key: 'code', header: 'Code', render: (b) => <span className="mono">{b.code}</span> },
    { key: 'name', header: 'Name', render: (b) => b.name },
    {
      key: 'actions', header: '', render: (b) => (
        <button type="button" className="btn btn-ghost" style={{ padding: '6px 10px', fontSize: 12 }}
          onClick={() => setExpanded(expanded === b.id ? null : b.id)}>{expanded === b.id ? 'Hide rooms' : 'Rooms'}</button>
      ),
    },
  ];
  return (
    <>
      <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>Buildings &amp; rooms</h2>
      <form className="card" onSubmit={createBuilding} style={{ marginBottom: 14 }}>
        <h3 style={{ fontSize: 15, marginBottom: 10 }}>Add building</h3>
        <div className="form-field"><label htmlFor="building-code">Code</label><input id="building-code" value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} required /></div>
        <div className="form-field"><label htmlFor="building-name">Name</label><input id="building-name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required /></div>
        {message && <p role="status">{message}</p>}
        <button className="btn btn-primary">Create building</button>
      </form>
      <DataTable columns={columns} rows={buildings.data} loading={buildings.loading} error={buildings.error} onRetry={buildings.refetch}
        emptyTitle="No buildings" emptyDescription="No buildings have been created yet." rowKey={(b) => b.id} />
      {expanded && <BuildingRooms buildingId={expanded} tenantId={tenantId} />}
    </>
  );
}

function YearPeriods({ yearId }: { yearId: string }) {
  const { data, loading, error, refetch } = useApiQuery<AcademicPeriodResponse[]>(
    `/catalog/academic-periods?academicYearId=${yearId}`,
  );
  const [form, setForm] = useState({ code: '', name: '', sequence: '1', startDate: '', endDate: '', registrationOpenAt: '', registrationCloseAt: '' });
  const [message, setMessage] = useState('');
  async function createPeriod(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setMessage('');
    try { await apiFetch('/catalog/academic-periods', { method: 'POST', body: { ...form, academicYearId: yearId, sequence: Number(form.sequence), registrationOpenAt: form.registrationOpenAt ? new Date(form.registrationOpenAt).toISOString() : null, registrationCloseAt: form.registrationCloseAt ? new Date(form.registrationCloseAt).toISOString() : null } }); setMessage('Academic period created.'); refetch(); }
    catch (e) { setMessage(e instanceof Error ? e.message : 'Could not create period.'); }
  }
  const columns: Column<AcademicPeriodResponse>[] = [
    { key: 'code', header: 'Code', render: (p) => <span className="mono">{p.code}</span> },
    { key: 'name', header: 'Name', render: (p) => p.name },
    { key: 'dates', header: 'Dates', render: (p) => `${p.startDate} → ${p.endDate}` },
    { key: 'status', header: 'Status', render: (p) => <StatusBadge value={p.status} /> },
  ];
  return (
    <div style={{ padding: '12px 16px 16px' }}>
      <form onSubmit={createPeriod} style={{ padding: '0 0 12px' }}><h3 style={{ fontSize: 15, marginBottom: 8 }}>Add period</h3>
        {([['code','Code','text'],['name','Name','text'],['sequence','Sequence','number'],['startDate','Start date','date'],['endDate','End date','date'],['registrationOpenAt','Registration opens','datetime-local'],['registrationCloseAt','Registration closes','datetime-local']] as const).map(([key,label,type]) => <div className="form-field" key={key}><label htmlFor={`period-${key}`}>{label}</label><input id={`period-${key}`} type={type} value={form[key]} onChange={(e) => setForm({ ...form, [key]: e.target.value })} required={!key.startsWith('registration')} /></div>)}
        {message && <p role="status">{message}</p>}<button className="btn btn-primary">Create period</button></form>
      <DataTable
        columns={columns}
        rows={data}
        loading={loading}
        error={error}
        onRetry={refetch}
        emptyTitle="No academic periods"
        emptyDescription="This academic year has no periods yet."
        rowKey={(p) => p.id}
      />
    </div>
  );
}

export default function InstitutionPage() {
  const { user } = useAuth();
  const tenantId = tenantForRole(user, 'COLLEGE_ADMIN');
  const [expandedYear, setExpandedYear] = useState<string | null>(null);
  const [yearForm, setYearForm] = useState({ code: '', startDate: '', endDate: '' });
  const [yearMessage, setYearMessage] = useState('');

  const college = useApiQuery<CollegeResponse>(tenantId ? `/institution/colleges/${tenantId}` : null);
  const campuses = useApiQuery<CampusResponse[]>(tenantId ? `/institution/campuses?collegeId=${tenantId}` : null);
  const organizations = useApiQuery<OrganizationResponse[]>(
    tenantId ? `/institution/academic-organizations?tenantId=${tenantId}` : null,
  );
  const years = useApiQuery<AcademicYearResponse[]>(tenantId ? `/catalog/academic-years?tenantId=${tenantId}` : null);

  const [campusForm, setCampusForm] = useState({ code: '', name: '' });
  const [campusMessage, setCampusMessage] = useState('');
  async function createCampus(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!tenantId) return; setCampusMessage('');
    try {
      await apiFetch('/institution/campuses', { method: 'POST', body: { collegeId: tenantId, code: campusForm.code, name: campusForm.name } });
      setCampusMessage('Campus created.'); setCampusForm({ code: '', name: '' }); campuses.refetch();
    } catch (e) { setCampusMessage(e instanceof Error ? e.message : 'Could not create campus.'); }
  }

  const [orgForm, setOrgForm] = useState({ campusId: '', parentOrganizationId: '', name: '', type: 'SCHOOL' });
  const [orgMessage, setOrgMessage] = useState('');
  async function createOrganization(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setOrgMessage('');
    try {
      await apiFetch('/institution/academic-organizations', {
        method: 'POST',
        body: { campusId: orgForm.campusId, parentOrganizationId: orgForm.parentOrganizationId || null, name: orgForm.name, type: orgForm.type },
      });
      setOrgMessage('Academic organization created.'); setOrgForm({ ...orgForm, name: '' }); organizations.refetch();
    } catch (e) { setOrgMessage(e instanceof Error ? e.message : 'Could not create the organization.'); }
  }

  const campusColumns: Column<CampusResponse>[] = [
    { key: 'code', header: 'Code', render: (c) => <span className="mono">{c.code}</span> },
    { key: 'name', header: 'Name', render: (c) => c.name },
  ];

  const orgColumns: Column<OrganizationResponse>[] = [
    { key: 'name', header: 'Name', render: (o) => o.name },
    {
      key: 'parent',
      header: 'Parent organization',
      render: (o) => (o.parentOrganizationId
        ? organizations.data?.find((x) => x.id === o.parentOrganizationId)?.name ?? o.parentOrganizationId
        : '— top level —'),
    },
  ];

  const yearColumns: Column<AcademicYearResponse>[] = [
    { key: 'code', header: 'Code', render: (y) => <span className="mono">{y.code}</span> },
    { key: 'dates', header: 'Dates', render: (y) => `${y.startDate} → ${y.endDate}` },
    { key: 'status', header: 'Status', render: (y) => <StatusBadge value={y.status} /> },
  ];
  async function createYear(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!tenantId) return; setYearMessage('');
    try { await apiFetch('/catalog/academic-years', { method: 'POST', body: { ...yearForm, tenantId } }); setYearMessage('Academic year created.'); years.refetch(); }
    catch (e) { setYearMessage(e instanceof Error ? e.message : 'Could not create year.'); }
  }

  return (
    <div>
      <PageHeader title="Institution" subtitle={college.data?.name ?? undefined} />

      {college.loading && <Skeleton height={100} />}
      {college.error && <div className="card"><ErrorState message="Could not load college information." onRetry={college.refetch} /></div>}
      {college.data && (
        <div className="card-grid" style={{ marginBottom: 24 }}>
          <div className="card">
            <div className="stat-label">College code</div>
            <div className="mono" style={{ marginTop: 6, fontSize: 15 }}>{college.data.code}</div>
          </div>
          <div className="card">
            <div className="stat-label">Timezone</div>
            <div style={{ marginTop: 6, fontSize: 15 }}>{college.data.timezone}</div>
          </div>
          <div className="card">
            <div className="stat-label">Locale</div>
            <div style={{ marginTop: 6, fontSize: 15 }}>{college.data.locale}</div>
          </div>
          <div className="card">
            <div className="stat-label">Status</div>
            <div style={{ marginTop: 6 }}><StatusBadge value={college.data.status} /></div>
          </div>
        </div>
      )}

      <h2 style={{ fontSize: 18, marginBottom: 12 }}>Campuses</h2>
      <form className="card" onSubmit={createCampus} style={{ marginBottom: 14 }}>
        <h3 style={{ fontSize: 15, marginBottom: 10 }}>Add campus</h3>
        <div className="form-field"><label htmlFor="campus-code">Code</label><input id="campus-code" value={campusForm.code} onChange={(e) => setCampusForm({ ...campusForm, code: e.target.value })} required /></div>
        <div className="form-field"><label htmlFor="campus-name">Name</label><input id="campus-name" value={campusForm.name} onChange={(e) => setCampusForm({ ...campusForm, name: e.target.value })} required /></div>
        {campusMessage && <p role="status">{campusMessage}</p>}
        <button className="btn btn-primary">Create campus</button>
      </form>
      <DataTable
        columns={campusColumns}
        rows={campuses.data}
        loading={campuses.loading}
        error={campuses.error}
        onRetry={campuses.refetch}
        emptyTitle="No campuses"
        emptyDescription="This college has no campuses yet."
        rowKey={(c) => c.id}
      />

      <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>Academic organizations</h2>
      <form className="card" onSubmit={createOrganization} style={{ marginBottom: 14 }}>
        <h3 style={{ fontSize: 15, marginBottom: 10 }}>Add school / faculty / institute</h3>
        <div className="form-field">
          <label htmlFor="org-campus">Campus</label>
          <select id="org-campus" value={orgForm.campusId} onChange={(e) => setOrgForm({ ...orgForm, campusId: e.target.value })} required>
            <option value="">Choose campus</option>
            {campuses.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </div>
        <div className="form-field">
          <label htmlFor="org-parent">Parent organization (optional)</label>
          <select id="org-parent" value={orgForm.parentOrganizationId} onChange={(e) => setOrgForm({ ...orgForm, parentOrganizationId: e.target.value })}>
            <option value="">— top level —</option>
            {organizations.data?.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
          </select>
        </div>
        <div className="form-field"><label htmlFor="org-name">Name</label><input id="org-name" value={orgForm.name} onChange={(e) => setOrgForm({ ...orgForm, name: e.target.value })} required /></div>
        <div className="form-field"><label htmlFor="org-type">Type</label><input id="org-type" value={orgForm.type} onChange={(e) => setOrgForm({ ...orgForm, type: e.target.value })} placeholder="SCHOOL, FACULTY, INSTITUTE, …" /></div>
        {orgMessage && <p role="status">{orgMessage}</p>}
        <button className="btn btn-primary">Create organization</button>
      </form>
      <DataTable
        columns={orgColumns}
        rows={organizations.data}
        loading={organizations.loading}
        error={organizations.error}
        onRetry={organizations.refetch}
        emptyTitle="No academic organizations"
        emptyDescription="No schools, faculties, or institutes have been created yet."
        rowKey={(o) => o.id}
      />

      {tenantId && <BuildingsAndRooms tenantId={tenantId} />}

      <h2 style={{ fontSize: 18, margin: '24px 0 12px' }}>Academic years</h2>
      <form className="card" onSubmit={createYear} style={{ marginBottom: 14 }}><h3 style={{ fontSize: 15, marginBottom: 10 }}>Add academic year</h3>
        {([['code','Year code','text'],['startDate','Start date','date'],['endDate','End date','date']] as const).map(([key,label,type]) => <div className="form-field" key={key}><label htmlFor={`year-${key}`}>{label}</label><input id={`year-${key}`} type={type} value={yearForm[key]} onChange={(e) => setYearForm({ ...yearForm, [key]: e.target.value })} required /></div>)}
        {yearMessage && <p role="status">{yearMessage}</p>}<button className="btn btn-primary">Create academic year</button></form>
      <p style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 10 }}>Select a year to view its academic periods.</p>
      <DataTable
        columns={yearColumns}
        rows={years.data}
        loading={years.loading}
        error={years.error}
        onRetry={years.refetch}
        emptyTitle="No academic years"
        emptyDescription="No academic years have been created yet."
        rowKey={(y) => y.id}
        onRowClick={(y) => setExpandedYear(expandedYear === y.id ? null : y.id)}
      />
      {expandedYear && <YearPeriods yearId={expandedYear} />}
    </div>
  );
}
