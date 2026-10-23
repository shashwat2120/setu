#!/usr/bin/env node
// A business flow through the running stack, from the outside, over HTTP, through the gateway only: the spec's section 16 reference flow
// (docs/final-demo.md), against the demo college that the core process seeds. It proves the pieces work TOGETHER as separate processes: tokens
// issued by Identity are accepted by every service, the read models the services decide from are fed by events from Academic and Identity, and the
// consequences of a grade (the enrollment completing, risk recalculating, a notification) arrive by Kafka from one process to another.
//
//   docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.services.yml up -d
//   node infra/scripts/smoke-flow.js            (GATEWAY=http://localhost:8080 by default; AI_CHECK=1 also asks the advisor a question)
//
// Needs Node 18+ (fetch), nothing installed. Exits 1 if any step fails. It writes data (a session, an assessment, a grade) into the demo college, so
// it is meant for the seeded demo database, run once per database: it marks the one final-grade component a section may have.

const GATEWAY = process.env.GATEWAY || 'http://localhost:8080';
const PASSWORD = process.env.DEMO_PASSWORD || 'SetuDemo#2025!';
const t0 = Date.now();
const rows = [];

function note(step, ok, detail) {
  rows.push({ step, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${step}${detail ? '  ' + detail : ''}  [${((Date.now() - t0) / 1000).toFixed(1)}s]`);
  if (!ok) { finish(1); }
}

function finish(code) {
  const failed = rows.filter(r => !r.ok).length;
  console.log(`\n${rows.length - failed} of ${rows.length} steps passed${failed ? ', ' + failed + ' failed' : ''}`);
  process.exit(code);
}

async function call(token, method, path, body) {
  const res = await fetch(GATEWAY + path, {
    method,
    headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(30000),
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
  return { status: res.status, json, text };
}

async function login(email) {
  const r = await call(null, 'POST', '/auth/login', { email, password: PASSWORD });
  if (r.status !== 200 || !r.json?.accessToken) { note(`sign in as ${email}`, false, `HTTP ${r.status} ${r.text.slice(0, 200)}`); }
  return r.json.accessToken;
}

async function until(label, check, timeoutMs = 90000, everyMs = 1500) {
  const started = Date.now();
  let last;
  while (Date.now() - started < timeoutMs) {
    last = await check();
    if (last) return { value: last, ms: Date.now() - started };
    await new Promise(r => setTimeout(r, everyMs));
  }
  return { value: null, ms: Date.now() - started };
}

(async () => {
  // ---- 1. sign in, through the gateway, with the Identity service
  const faculty = await login('faculty.rao@demo.setu.local');
  const student = await login('student.strong@demo.setu.local');
  note('sign in as faculty and student (Identity service, via the gateway)', true);

  const meF = (await call(faculty, 'GET', '/identity/me')).json;
  const tenantId = meF.roles.find(r => r.tenantId)?.tenantId;
  const facultyId = (await call(faculty, 'GET', `/directory/me/profiles?tenantId=${tenantId}`)).json?.facultyId;
  const studentId = (await call(student, 'GET', `/directory/me/profiles?tenantId=${tenantId}`)).json?.studentId;
  note('profiles resolved (Academic service)', !!(tenantId && facultyId && studentId), `tenant ${String(tenantId).slice(0, 8)}`);

  // ---- 2. a section the faculty teaches and the student is enrolled in
  const assignments = (await call(faculty, 'GET', `/enrollment/faculty-assignments?facultyId=${facultyId}&tenantId=${tenantId}`)).json || [];
  const mine = ((await call(student, 'GET', `/enrollment/enrollments?studentId=${studentId}`)).json?.content) || [];
  const taught = new Set(assignments.map(a => a.sectionId));
  const enrollment = mine.find(e => (e.status === 'ENROLLED' || e.status === 'COMPLETED') && taught.has(e.sectionId));
  const sectionId = enrollment?.sectionId; const enrollmentId = enrollment?.id;
  note('found a section the faculty teaches and the student is enrolled in', !!sectionId, sectionId ? `section ${sectionId.slice(0, 8)}` : `${assignments.length} assignments`);

  // ---- 3. live attendance: open a session, the student scans, the faculty sees the record (Attendance service, deciding from its own read models)
  const meetings = (await call(faculty, 'GET', `/catalog/meeting-patterns?sectionId=${sectionId}`)).json || [];
  const meetingId = (Array.isArray(meetings) ? meetings : meetings.content || [])[0]?.id;
  note('meeting found for the section', !!meetingId);
  // a session left open by an earlier run would be in the way: close it, so that the script can be run again
  const stale = ((await call(faculty, 'GET', `/attendance/sessions?sectionId=${sectionId}`)).json || []).filter(x => x.meetingId === meetingId && x.status === 'OPEN');
  for (const x of stale) { await call(faculty, 'POST', `/attendance/sessions/${x.id}/close`); }
  const open = await call(faculty, 'POST', '/attendance/sessions', { sectionId, meetingId, endsAt: new Date(Date.now() + 30 * 60 * 1000).toISOString() });
  note('faculty opens an attendance session (Attendance service)', open.status === 201 || open.status === 200, `HTTP ${open.status} ${open.status >= 300 ? open.text.slice(0, 160) : ''}`);
  const { sessionId, qrToken } = open.json;
  if (enrollment.status === 'COMPLETED') {
    // a rerun: this student's course was completed by an earlier run, and a completed student is no longer in the course
    const refused = await call(student, 'POST', '/attendance/scan', { sessionId, qrToken });
    note('a student whose course is completed can no longer scan attendance for it', refused.status === 403, `HTTP ${refused.status}`);
  } else {
    const scan = await call(student, 'POST', '/attendance/scan', { sessionId, qrToken });
    note('student scans the code', scan.status === 200 || scan.status === 201, `HTTP ${scan.status} ${scan.status >= 300 ? scan.text.slice(0, 160) : ''}`);
    const dup = await call(student, 'POST', '/attendance/scan', { sessionId, qrToken });
    note('a second scan of the same code records nothing new (idempotent: the same record is returned)', dup.status === 200 && dup.json?.recordId === scan.json?.recordId, `HTTP ${dup.status}`);
    const recs = await call(faculty, 'GET', `/attendance/sessions/${sessionId}/records?tenantId=${tenantId}`);
    const recList = Array.isArray(recs.json) ? recs.json : (recs.json?.content || []);
    note('faculty sees the student\'s record', recs.status === 200 && recList.some(r => r.enrollmentId === enrollmentId || r.studentId === studentId), `${recList.length} record(s)`);
  }
  await call(faculty, 'POST', `/attendance/sessions/${sessionId}/close`);

  // ---- 4. an assessment with a final-grade component (Assessment service)
  const created = await call(faculty, 'POST', '/assessment/assessments', {
    sectionId, type: 'EXAM', title: 'Final exam (smoke flow)', totalMarks: 100, durationMinutes: 60,
    startsAt: new Date(Date.now() - 3600e3).toISOString(), endsAt: new Date(Date.now() + 3600e3).toISOString(),
  });
  note('faculty creates an assessment', created.status === 201, `HTTP ${created.status} ${created.status >= 300 ? created.text.slice(0, 160) : ''}`);
  const assessmentId = created.json.id;
  let comp = await call(faculty, 'POST', '/assessment/components', { assessmentId, code: 'FINAL', maxMarks: 100, weight: 100, orderNo: 1, finalGrade: true });
  let gradeAssessmentId = assessmentId; let componentId; let reused = false;
  if (comp.status === 409) {
    // an earlier run of this script already gave the section its one final-grade component: use it, and the grade below is a correction
    const all = (await call(faculty, 'GET', `/assessment/assessments?sectionId=${sectionId}&tenantId=${tenantId}`)).json;
    for (const asmt of (Array.isArray(all) ? all : all?.content || [])) {
      const comps = (await call(faculty, 'GET', `/assessment/components?assessmentId=${asmt.id}`)).json || [];
      const found = (Array.isArray(comps) ? comps : comps.content || []).find(c => c.finalGrade);
      if (found) { gradeAssessmentId = asmt.id; componentId = found.id; reused = true; }
    }
    note('the section already has its final-grade component from an earlier run: reused', reused);
  } else {
    note('faculty adds the final-grade component', comp.status === 201 && comp.json.finalGrade === true, `HTTP ${comp.status} ${comp.status >= 300 ? comp.text.slice(0, 160) : ''}`);
    componentId = comp.json.id;
  }
  const second = await call(faculty, 'POST', '/assessment/components', { assessmentId, code: 'FINAL2', maxMarks: 10, weight: 10, orderNo: 2, finalGrade: true });
  note('a second final-grade component for the section is refused', second.status === 409, `HTTP ${second.status}`);
  if (!reused) {
    const q = await call(faculty, 'POST', '/assessment/questions', { componentId, type: 'MCQ_SINGLE', prompt: 'What is 2+2?', points: 100, difficulty: 'EASY', metadataJson: '{"correctOption": "B"}' });
    note('question added', q.status === 201, `HTTP ${q.status}`);
    const sch = await call(faculty, 'POST', `/assessment/assessments/${assessmentId}/schedule`);
    const opn = await call(faculty, 'POST', `/assessment/assessments/${assessmentId}/open`);
    note('assessment scheduled and opened', sch.status < 300 && opn.status < 300, `HTTP ${sch.status}/${opn.status}`);
  }

  // ---- 5. the grade; everything after this travels through Kafka
  const gradeAt = Date.now();
  const before = (await call(student, 'GET', `/enrollment/enrollments/${enrollmentId}`)).json;
  const gradeCode = before?.finalStatus === 'B' ? 'A' : 'B';
  const grade = await call(faculty, 'POST', '/assessment/grades', { assessmentId: gradeAssessmentId, componentId, studentId, rawMarks: 72, gradeCode, gradePoint: gradeCode === 'A' ? 4.0 : 3.0 });
  note('faculty publishes the final grade (Assessment service)', grade.status === 201 || grade.status === 200, `HTTP ${grade.status} ${grade.status >= 300 ? grade.text.slice(0, 160) : ''}`);

  const completed = await until('enrollment completes', async () => {
    const e = (await call(student, 'GET', `/enrollment/enrollments/${enrollmentId}`)).json;
    return e && e.status === 'COMPLETED' && e.finalStatus === gradeCode ? e : null;
  });
  note('the enrollment completes with the grade as its final status (Assessment -> Kafka -> Academic)',
    !!completed.value && completed.value.finalStatus === gradeCode, completed.value ? `after ${(completed.ms / 1000).toFixed(1)}s` : 'did not complete within 90s');

  // A student whose enrollment was already COMPLETED before this run has no attendance or assessment input left to change, so recalculating gives the same inputs and,
  // by design, no new score row (a score is reproducible from its inputs). For that student the honest check is that a current score is still served.
  const alreadyCompleted = before?.status === 'COMPLETED';
  const risk = await until('risk recalculated', async () => {
    const r = await call(student, 'GET', `/risk/scores/current?studentId=${studentId}`);
    if (!(r.status === 200 && r.json)) return null;
    return alreadyCompleted || new Date(r.json.calculatedAt).getTime() >= gradeAt - 2000 ? r.json : null;
  }, alreadyCompleted ? 15000 : 90000);
  note(alreadyCompleted ? 'risk: the enrollment was already completed, so inputs are unchanged and no new score row is expected; the current score is still served'
                        : 'risk is recalculated by the grade event (Assessment -> Kafka -> core)', !!risk.value,
    risk.value ? `band ${risk.value.band}${alreadyCompleted ? '' : `, after ${(risk.ms / 1000).toFixed(1)}s`}` : 'no usable score');

  const notified = await until('notification', async () => {
    const n = await call(student, 'GET', '/notifications');
    const list = Array.isArray(n.json) ? n.json : (n.json?.items || n.json?.content || []);
    return list.some(x => x.type === 'GRADE_PUBLISHED' && new Date(x.createdAt).getTime() >= gradeAt - 2000) ? list : null;
  });
  note('the student is notified (Assessment -> Kafka -> Notification service)', !!notified.value, notified.value ? `after ${(notified.ms / 1000).toFixed(1)}s` : 'none within 90s');

  const transcript = await until('transcript', async () => {
    const t = await call(student, 'GET', `/record/transcript?studentId=${studentId}&tenantId=${tenantId}`);
    const line = t.json?.lines?.find(l => l.enrollmentId === enrollmentId);
    return line && line.enrollmentStatus === 'COMPLETED' ? line : null;
  });
  note('the transcript shows the completed course and its final grade', !!transcript.value && transcript.value.finalGrade === gradeCode,
    transcript.value ? `grade ${transcript.value.finalGrade}` : 'not completed in the record');

  // ---- 6. the advisor (optional: it needs a model)
  if (process.env.AI_CHECK) {
    const ai = await call(student, 'POST', '/ai/chat', { message: 'How am I doing this term?' });
    note('the advisor answers (AI service -> gateway -> data services, with the student\'s own token)', ai.status === 200 || ai.status === 202, `HTTP ${ai.status} ${ai.text.slice(0, 120)}`);
  }
  finish(0);
})().catch(e => { console.error('ERROR', e); process.exit(2); });
