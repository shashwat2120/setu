#!/usr/bin/env node
// The live attendance board over a real WebSocket (STOMP), against the running stack: the faculty member subscribes to a section's topic, a
// student scans the code, and the update must arrive; and the same topic must refuse someone who may not see it. The connection goes through the
// gateway, which relays the WebSocket to the Attendance service (a path that does not carry live connections answers an upgrade with 501).
//
//   node infra/scripts/smoke-websocket.js        (GATEWAY=http://localhost:8080  LIVE=ws://localhost:8080/ws)
//
// LIVE=ws://localhost:8086/ws connects to the Attendance service directly instead, to tell a gateway problem from a service problem.
// Needs Node 22+ (a global WebSocket) and nothing installed. Writes one attendance session and one record into the demo college, so it needs a
// student who is still in the course: students whose course was completed by smoke-flow.js can no longer scan.

const GATEWAY = process.env.GATEWAY || 'http://localhost:8080';
const WS = process.env.LIVE || process.env.ATTENDANCE || GATEWAY.replace(/^http/, 'ws') + '/ws';
const PASSWORD = process.env.DEMO_PASSWORD || 'SetuDemo#2025!';
const t0 = Date.now();
let failed = 0;
function note(step, ok, detail) {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${step}${detail ? '  ' + detail : ''}  [${((Date.now() - t0) / 1000).toFixed(1)}s]`);
  if (!ok) failed++;
}
async function call(token, method, path, body) {
  const res = await fetch(GATEWAY + path, {
    method, headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(30000),
  });
  const text = await res.text(); let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* */ }
  return { status: res.status, json, text };
}
const login = async email => (await call(null, 'POST', '/auth/login', { email, password: PASSWORD })).json?.accessToken;

/** The status the gateway answers to a WebSocket upgrade request for a path (101 means it was accepted). */
function upgradeStatus(base, path) {
  return new Promise(resolve => {
    const req = require('node:http').request({
      host: base.hostname, port: base.port || 80, path, method: 'GET',
      headers: { Connection: 'Upgrade', Upgrade: 'websocket', 'Sec-WebSocket-Version': '13', 'Sec-WebSocket-Key': 'dGhlIHNhbXBsZSBub25jZQ==' },
    });
    req.on('response', res => { res.resume(); resolve(res.statusCode); });
    req.on('upgrade', (res, socket) => { socket.destroy(); resolve(101); });
    req.on('error', () => resolve(0));
    req.setTimeout(10000, () => { req.destroy(); resolve(0); });
    req.end();
  });
}

/** A minimal STOMP 1.2 client: just enough to connect with a bearer token, subscribe, and collect frames. */
function stomp(token) {
  return new Promise(resolve => {
    const ws = new WebSocket(WS);
    const frames = []; const waiters = [];
    const api = {
      frames,
      send: (command, headers, body = '') => ws.send(command + '\n' + Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n') + '\n\n' + body + '\0'),
      waitFor: (pred, ms) => new Promise(res => {
        const hit = frames.find(pred); if (hit) return res(hit);
        const timer = setTimeout(() => res(null), ms);
        waiters.push(f => { if (pred(f)) { clearTimeout(timer); res(f); return true; } return false; });
      }),
      close: () => ws.close(),
      opened: false, closedEarly: false,
    };
    ws.onmessage = ev => {
      for (const raw of String(ev.data).split('\0').filter(s => s.trim())) {
        const [head, ...rest] = raw.replace(/^\n+/, '').split('\n\n');
        const lines = head.split('\n'); const f = { command: lines[0], headers: Object.fromEntries(lines.slice(1).map(l => [l.slice(0, l.indexOf(':')), l.slice(l.indexOf(':') + 1)])), body: rest.join('\n\n') };
        frames.push(f);
        for (let i = waiters.length - 1; i >= 0; i--) if (waiters[i](f)) waiters.splice(i, 1);
      }
    };
    ws.onopen = () => { api.opened = true; api.send('CONNECT', { 'accept-version': '1.2', host: 'localhost', ...(token ? { Authorization: 'Bearer ' + token } : {}) }); resolve(api); };
    ws.onerror = () => { if (!api.opened) resolve(api); };
    ws.onclose = () => { api.closedEarly = true; };
  });
}

(async () => {
  const faculty = await login('faculty.rao@demo.setu.local');
  const me = (await call(faculty, 'GET', '/identity/me')).json; const tenantId = me.roles[0].tenantId;
  const facultyId = (await call(faculty, 'GET', `/directory/me/profiles?tenantId=${tenantId}`)).json.facultyId;
  const assignments = (await call(faculty, 'GET', `/enrollment/faculty-assignments?facultyId=${facultyId}&tenantId=${tenantId}`)).json;
  const sectionId = assignments[0].sectionId;

  // a student who is still in this course
  let student; let studentToken;
  for (const who of ['student.average', 'student.assessment.risk', 'student.intervention', 'student.learning.risk', 'student.weak', 'student.strong']) {
    const token = await login(`${who}@demo.setu.local`); if (!token) continue;
    const sid = (await call(token, 'GET', `/directory/me/profiles?tenantId=${tenantId}`)).json?.studentId;
    const mine = (await call(token, 'GET', `/enrollment/enrollments?studentId=${sid}`)).json?.content || [];
    if (mine.some(e => e.sectionId === sectionId && e.status === 'ENROLLED')) { student = who; studentToken = token; break; }
  }
  note('a student still enrolled in the section was found', !!student, student || 'none of the demo students');
  if (!student) { process.exit(1); }

  const meetingId = ((await call(faculty, 'GET', `/catalog/meeting-patterns?sectionId=${sectionId}`)).json || [])[0]?.id;
  const stale = ((await call(faculty, 'GET', `/attendance/sessions?sectionId=${sectionId}`)).json || []).filter(x => x.meetingId === meetingId && x.status === 'OPEN');
  for (const x of stale) await call(faculty, 'POST', `/attendance/sessions/${x.id}/close`);

  // 0. the gateway relays live connections only on the live route
  if (WS.startsWith(GATEWAY.replace(/^http/, 'ws'))) {
    const upgrade = await upgradeStatus(new URL(GATEWAY), '/notifications/live');
    note('an upgrade on a path that does not carry live connections is refused with 501', upgrade === 501, `HTTP ${upgrade}`);
  }

  // 1. no token: refused at CONNECT
  const anonymous = await stomp(null);
  const refused = await anonymous.waitFor(f => f.command === 'ERROR', 5000);
  note('a STOMP connection without a token is refused', !!refused || anonymous.closedEarly, refused ? refused.headers.message : 'connection closed');

  // 2. the student may not subscribe to the section's board
  const s = await stomp(studentToken);
  const sConnected = await s.waitFor(f => f.command === 'CONNECTED' || f.command === 'ERROR', 5000);
  note('a student connects with their own token', sConnected?.command === 'CONNECTED');
  s.send('SUBSCRIBE', { id: 'sub-1', destination: `/topic/attendance/section/${sectionId}` });
  const denied = await s.waitFor(f => f.command === 'ERROR', 4000);
  note('a student may not subscribe to the section\'s live board', !!denied || s.closedEarly, denied ? denied.headers.message : 'connection closed');

  // 3. the faculty subscribes, a student scans, the update arrives
  const f = await stomp(faculty);
  const connected = await f.waitFor(x => x.command === 'CONNECTED' || x.command === 'ERROR', 5000);
  note('the faculty member connects with their token', connected?.command === 'CONNECTED');
  f.send('SUBSCRIBE', { id: 'sub-1', destination: `/topic/attendance/section/${sectionId}` });
  await new Promise(r => setTimeout(r, 800));
  const open = await call(faculty, 'POST', '/attendance/sessions', { sectionId, meetingId, endsAt: new Date(Date.now() + 1800e3).toISOString() });
  const { sessionId, qrToken } = open.json;
  const scanAt = Date.now();
  const scan = await call(studentToken, 'POST', '/attendance/scan', { sessionId, qrToken });
  note('the student scans the code', scan.status === 201, `HTTP ${scan.status}`);
  const update = await f.waitFor(x => x.command === 'MESSAGE', 8000);
  note('the faculty member\'s board receives the update over the WebSocket', !!update, update ? `after ${Date.now() - scanAt} ms: ${update.body.slice(0, 110)}` : 'nothing within 8s');
  await call(faculty, 'POST', `/attendance/sessions/${sessionId}/close`);
  for (const c of [f, s, anonymous]) c.close();
  console.log(`\n${failed ? failed + ' step(s) failed' : 'all steps passed'}`);
  process.exit(failed ? 1 : 0);
})().catch(e => { console.error('ERROR', e); process.exit(2); });
