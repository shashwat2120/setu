// Usage: node build-diagrams.js <output-folder>   (writes architecture.svg and system-workflow.svg; export PNG with any browser or Chrome --headless --screenshot)
// Generates the Setu architecture and workflow diagrams as SVG (1920x1080), in the project's "ledger" palette.
const fs = require('fs');
const OUT = process.argv[2];

const C = { ink: '#1b2436', soft: '#52596b', bg: '#eae7dd', elev: '#f7f5ee', rule: '#c7c2b0', brass: '#8a5d18', brassSoft: '#efe3c9', green: '#2f6f63', greenSoft: '#dbe6e0', red: '#a93f30', redSoft: '#f1dcd6', navy: '#1b2436', blue: '#2c4a7a', blueSoft: '#dde4ef', white: '#ffffff' };
const F = { display: "Fraunces, Georgia, 'Times New Roman', serif", body: "'Public Sans', 'Segoe UI', Arial, sans-serif", mono: "'IBM Plex Mono', Consolas, 'Courier New', monospace" };
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

class Svg {
  constructor(w, h) { this.w = w; this.h = h; this.defs = []; this.body = []; this.markers = new Set(); }
  marker(color) {
    const id = 'ar' + color.replace('#', '');
    if (!this.markers.has(id)) {
      this.markers.add(id);
      this.defs.push(`<marker id="${id}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="${color}"/></marker>`);
    }
    return id;
  }
  rect(x, y, w, h, o = {}) {
    const { fill = C.elev, stroke = C.rule, r = 10, sw = 1.5, dash } = o;
    this.body.push(`<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" fill="${fill}" stroke="${stroke}" stroke-width="${sw}"${dash ? ` stroke-dasharray="${dash}"` : ''}/>`);
  }
  text(x, y, str, o = {}) {
    const { size = 16, weight = 400, fill = C.ink, anchor = 'start', font = F.body, ls = 0, italic = false } = o;
    this.body.push(`<text x="${x}" y="${y}" font-family="${font}" font-size="${size}" font-weight="${weight}" fill="${fill}" text-anchor="${anchor}"${ls ? ` letter-spacing="${ls}"` : ''}${italic ? ' font-style="italic"' : ''}>${esc(str)}</text>`);
  }
  para(x, y, lines, o = {}) {
    const lh = o.lh || (o.size || 16) * 1.35;
    lines.forEach((l, i) => this.text(x, y + i * lh, l, o));
  }
  line(pts, o = {}) {
    const { color = C.ink, sw = 2, dash, arrow = true } = o;
    const d = pts.map((p, i) => (i ? 'L' : 'M') + p[0] + ',' + p[1]).join(' ');
    this.body.push(`<path d="${d}" fill="none" stroke="${color}" stroke-width="${sw}"${dash ? ` stroke-dasharray="${dash}"` : ''} stroke-linejoin="round"${arrow ? ` marker-end="url(#${this.marker(color)})"` : ''}/>`);
  }
  chip(x, y, w, h, label, o = {}) {
    const { fill = C.white, stroke = C.rule, color = C.ink, size = 15, weight = 600, font = F.body } = o;
    this.rect(x, y, w, h, { fill, stroke, r: h / 2.4, sw: 1.2 });
    this.text(x + w / 2, y + h / 2 + size * 0.35, label, { size, weight, fill: color, anchor: 'middle', font });
  }
  header(title, sub) {
    this.rect(0, 0, this.w, 84, { fill: C.navy, stroke: C.navy, r: 0 });
    this.text(48, 54, title, { size: 34, weight: 600, fill: '#f1eee2', font: F.display });
    this.text(this.w - 48, 52, sub, { size: 15, fill: '#d4a73d', anchor: 'end', font: F.mono, ls: 1.2 });
  }
  out() {
    return `<svg xmlns="http://www.w3.org/2000/svg" width="${this.w}" height="${this.h}" viewBox="0 0 ${this.w} ${this.h}"><defs>${this.defs.join('')}</defs><rect width="${this.w}" height="${this.h}" fill="${C.bg}"/>${this.body.join('')}</svg>`;
  }
}

// ---------------------------------------------------------------- architecture
function architecture() {
  const s = new Svg(1920, 1080);
  s.header('Setu — system architecture', 'MODULAR MONOLITH · MULTI-TENANT · ONE SYSTEM OF RECORD');

  const rail = (y, h, label) => {
    s.rect(30, y, 190, h, { fill: C.navy, stroke: C.navy, r: 8 });
    s.text(125, y + h / 2 + 5, label, { size: 15, weight: 700, fill: '#f1eee2', anchor: 'middle', font: F.mono, ls: 1.5 });
  };
  const X0 = 240, XW = 1650;

  // client
  rail(104, 118, 'CLIENT');
  s.rect(X0, 104, 1120, 118, { fill: C.elev });
  s.text(X0 + 20, 132, 'React + TypeScript + Vite single-page app', { size: 19, weight: 700, font: F.display });
  s.text(X0 + 20, 154, 'Four role dashboards · ledger design system · GSAP motion · WCAG 2.2 AA checked with axe-core', { size: 14, fill: C.soft });
  const roles = [['Super admin', 'colleges · roles · audit'], ['College admin', 'registrar operations'], ['Faculty', 'teaching · live QR · grades'], ['Student', 'learning · success · AI advisor']];
  roles.forEach((r, i) => {
    const x = X0 + 20 + i * 272;
    s.rect(x, 166, 256, 44, { fill: C.white, stroke: C.brass, r: 8, sw: 1.4 });
    s.text(x + 14, 184, r[0], { size: 16, weight: 700, fill: C.brass });
    s.text(x + 14, 202, r[1], { size: 12.5, fill: C.soft });
  });
  s.rect(X0 + 1140, 104, XW - 1140, 118, { fill: C.elev });
  s.text(X0 + 1160, 132, 'Public pages', { size: 19, weight: 700, font: F.display });
  s.para(X0 + 1160, 156, ['Landing · Sign in (passkey or password)', 'Forgot password: emailed 6-digit code'], { size: 14, fill: C.soft, lh: 20 });

  // edge & security
  rail(262, 108, 'EDGE & SECURITY');
  const sec = [
    ['Passkeys / WebAuthn', 'Spring Security 7, step-up'],
    ['Argon2id fallback', 'common-password policy'],
    ['JWT + rotating refresh', 'refresh stored only as hash'],
    ['Rate limiting', 'login · reset · AI · code entry'],
    ['Authorization pipeline', 'tenant · permission · owner · state · audit'],
  ];
  const sw = (XW - 4 * 12) / 5;
  sec.forEach((c, i) => {
    const x = X0 + i * (sw + 12);
    s.rect(x, 262, sw, 108, { fill: i === 4 ? C.brassSoft : C.elev, stroke: i === 4 ? C.brass : C.rule });
    s.text(x + 16, 296, c[0], { size: 17, weight: 700 });
    s.text(x + 16, 320, c[1], { size: 13.5, fill: C.soft });
    if (i === 4) s.para(x + 16, 346, ['Tenant comes from the signed token,', 'never from the client.'], { size: 12.5, fill: C.green, weight: 600, lh: 16 });
  });

  // core
  rail(410, 340, 'APPLICATION CORE');
  s.rect(X0, 410, XW, 340, { fill: '#f3f0e6', stroke: C.ink, sw: 1.8 });
  s.text(X0 + 20, 440, 'Spring Boot 4.1 · Java 21 · Spring Modulith', { size: 20, weight: 700, font: F.display });
  s.text(X0 + 20, 462, '16 modules · each owns its tables · modules call each other only through application-layer interfaces (ModularityTests fails the build otherwise)', { size: 14, fill: C.soft });
  const groups = [
    { t: 'Identity & people', w: 280, mods: [['identity', 'accounts, sessions, passkeys'], ['directory', 'student · faculty profiles'], ['institution', 'colleges · campuses · depts'], ['guardian-access *', 'scoped grants']] },
    { t: 'Academic core', w: 400, mods: [['catalog', 'programs · courses · sections'], ['enrollment', 'waitlist · overrides · actions'], ['attendance', 'QR sessions · staff attendance'], ['learning', 'lessons · assignments'], ['assessment', 'quizzes · versioned grades'], ['record', 'transcripts · GPA · degree']] },
    { t: 'Money & careers', w: 240, mods: [['finance', 'fees · idempotent payments'], ['career *', 'skills · jobs · placement']] },
    { t: 'Insight', w: 320, mods: [['risk', 'Student Success Engine'], ['ai-explain', 'evidence-grounded, no datasource'], ['analytics', 'dashboards']] },
    { t: 'Platform', w: 280, mods: [['notification', 'STOMP realtime push'], ['shared', 'audit log · outbox · tenant']] },
  ];
  let gx = X0 + 20;
  groups.forEach((g, gi) => {
    s.rect(gx, 478, g.w, 252, { fill: C.white, stroke: C.rule, r: 10 });
    s.text(gx + 14, 502, g.t.toUpperCase(), { size: 12.5, weight: 700, fill: C.brass, font: F.mono, ls: 1.2 });
    g.mods.forEach((m, mi) => {
      const my = 514 + mi * 36;
      s.rect(gx + 12, my, g.w - 24, 31, { fill: gi === 3 ? C.greenSoft : C.elev, stroke: C.rule, r: 6, sw: 1 });
      s.text(gx + 22, my + 20, m[0], { size: 14.5, weight: 700 });
      s.text(gx + g.w - 22, my + 20, m[1], { size: 11.5, fill: C.soft, anchor: 'end' });
    });
    gx += g.w + 16;
  });
  s.text(X0 + 20, 744, '* built and tested; their dashboards are hidden in the review build', { size: 12.5, fill: C.soft, italic: true });

  // data & events
  rail(790, 112, 'DATA & EVENTS');
  const data = [
    ['PostgreSQL 16 + pgvector', 'schema per module · Flyway migrations · optimistic locking · append-only audit and grades', C.blueSoft, C.blue],
    ['Redis 7', 'rate limits and QR state; an accelerator, never the source of truth', C.blueSoft, C.blue],
    ['Kafka', 'domain events leave through a transactional outbox, so no event is lost', C.blueSoft, C.blue],
  ];
  const dw = (XW - 2 * 16) / 3;
  data.forEach((d, i) => {
    const x = X0 + i * (dw + 16);
    s.rect(x, 790, dw, 112, { fill: d[2], stroke: d[3], sw: 1.6 });
    s.text(x + 18, 822, d[0], { size: 19, weight: 700, fill: d[3], font: F.display });
    s.para(x + 18, 848, wrap(d[1], 60), { size: 13.5, fill: C.ink, lh: 19 });
  });

  // external
  rail(942, 104, 'EXTERNAL');
  const ext = [
    ['Gemini → local Ollama (fallback)', 'Spring AI. RAG over college policy documents, tenant and document access filtered before retrieval', C.greenSoft, C.green],
    ['SMTP (Gmail app password)', 'Sends the password-reset code; a failed send never changes what the visitor sees', C.greenSoft, C.green],
    ['Observability', 'Actuator health and info today; Prometheus + Grafana planned', C.redSoft, C.red],
  ];
  ext.forEach((d, i) => {
    const x = X0 + i * (dw + 16);
    s.rect(x, 942, dw, 104, { fill: d[2], stroke: d[3], sw: 1.6, dash: i === 2 ? '6 5' : undefined });
    s.text(x + 18, 972, d[0], { size: 18, weight: 700, fill: d[3], font: F.display });
    s.para(x + 18, 996, wrap(d[1], 62), { size: 13.5, fill: C.ink, lh: 19 });
  });

  // arrows between layers
  const mid = (x1, x2) => (x1 + x2) / 2;
  s.line([[X0 + 330, 222], [X0 + 330, 262]], { color: C.brass });
  s.text(X0 + 342, 245, 'HTTPS REST (JWT in memory, refresh cookie)', { size: 12, fill: C.brass, font: F.mono });
  s.line([[X0 + 1000, 222], [X0 + 1000, 262]], { color: C.brass });
  s.text(X0 + 1012, 245, 'WebSocket / STOMP', { size: 12, fill: C.brass, font: F.mono });
  s.line([[X0 + 330, 370], [X0 + 330, 410]], { color: C.ink });
  s.line([[X0 + 1000, 370], [X0 + 1000, 410]], { color: C.ink });
  [X0 + 250, X0 + 810, X0 + 1370].forEach((x) => s.line([[x, 750], [x, 790]], { color: C.blue }));
  s.text(X0 + 262, 774, 'JPA · SQL', { size: 12, fill: C.blue, font: F.mono });
  s.text(X0 + 822, 774, 'counters · QR state', { size: 12, fill: C.blue, font: F.mono });
  s.text(X0 + 1382, 774, 'outbox publisher', { size: 12, fill: C.blue, font: F.mono });
  [X0 + 250, X0 + 810, X0 + 1370].forEach((x) => s.line([[x, 902], [x, 942]], { color: C.green }));
  s.text(X0 + 262, 926, 'RAG + chat', { size: 12, fill: C.green, font: F.mono });
  s.text(X0 + 822, 926, 'reset codes', { size: 12, fill: C.green, font: F.mono });
  s.text(X0 + 1382, 926, 'metrics', { size: 12, fill: C.red, font: F.mono });
  s.text(960, 1068, 'Setu codebase, sprint5-review: four role dashboards in the frontend, all 16 backend modules.', { size: 12.5, fill: C.soft, anchor: 'middle', italic: true });
  return s.out();
}

function wrap(str, max) {
  const words = str.split(' ');
  const out = [];
  let cur = '';
  for (const w of words) {
    if ((cur + ' ' + w).trim().length > max) { out.push(cur.trim()); cur = w; } else cur = (cur + ' ' + w).trim();
  }
  if (cur) out.push(cur);
  return out;
}

// ---------------------------------------------------------------- workflow
function workflow() {
  const s = new Svg(1920, 1080);
  s.header('Setu — end-to-end system workflow', 'THE REVIEW DEMO FLOW · FROM SIGN-IN TO AN EXPLAINED RISK');

  const lanes = [
    { id: 'F', label: 'FACULTY\n& ADMIN', y: 104, h: 176, fill: C.brassSoft, edge: C.brass },
    { id: 'S', label: 'STUDENT', y: 290, h: 176, fill: C.greenSoft, edge: C.green },
    { id: 'P', label: 'SETU\nPLATFORM', y: 476, h: 200, fill: C.blueSoft, edge: C.blue },
    { id: 'I', label: 'INSIGHT\n& AI', y: 686, h: 176, fill: '#e4ece8', edge: C.green },
  ];
  lanes.forEach((l) => {
    s.rect(30, l.y, 1860, l.h, { fill: l.fill, stroke: l.edge, r: 10, sw: 1.2 });
    s.rect(30, l.y, 150, l.h, { fill: l.edge, stroke: l.edge, r: 10 });
    l.label.split('\n').forEach((t, i, a) => s.text(105, l.y + l.h / 2 + 5 + (i - (a.length - 1) / 2) * 20, t, { size: 15, weight: 700, fill: '#ffffff', anchor: 'middle', font: F.mono, ls: 1.5 }));
  });
  const laneY = Object.fromEntries(lanes.map((l) => [l.id, l.y + l.h / 2]));

  const CW = 178, CH = 136, X1 = 204, PITCH = 207;
  const colX = (c) => X1 + (c - 1) * PITCH;
  const cards = {};
  const card = (id, lane, col, num, title, body, o = {}) => {
    const x = colX(col), y = laneY[lane] - CH / 2;
    cards[id] = { x, y, w: CW, h: CH };
    s.rect(x, y, CW, CH, { fill: C.white, stroke: o.edge || C.ink, sw: 1.6, r: 10 });
    if (num) {
      s.rect(x - 8, y - 8, 30, 30, { fill: C.navy, stroke: C.navy, r: 15 });
      s.text(x + 7, y + 13, String(num), { size: 15, weight: 700, fill: '#f1eee2', anchor: 'middle', font: F.mono });
    }
    s.para(x + 14, y + 34, wrap(title, 20), { size: 15.5, weight: 700, lh: 19 });
    const tl = wrap(title, 20).length;
    s.para(x + 14, y + 34 + tl * 19 + 6, wrap(body, 25), { size: 12.5, fill: C.soft, lh: 16 });
  };

  card('F1', 'F', 1, 1, 'Faculty sign in', 'Passkey or password; admins use the same screen.', { edge: C.brass });
  card('S1', 'S', 1, 1, 'Student signs in', 'Passkey or password; reset by emailed code.', { edge: C.green });
  card('P1', 'P', 2, '', 'Authenticate', 'Verify the credential, resolve the tenant from the token, issue JWT + rotating refresh.', { edge: C.blue });
  card('F2', 'F', 3, 2, 'Open attendance session', 'Pick a section and start a live session.', { edge: C.brass });
  card('P2', 'P', 3, '', 'Session + rotating QR', 'Short-lived signed token; it never carries student data.', { edge: C.blue });
  card('S2', 'S', 4, 3, 'Scan the QR', 'From the student dashboard, inside the class.', { edge: C.green });
  card('P3', 'P', 4, '', 'Validate the scan', 'Token, tenant, expiry, enrollment, duplicate check. PRESENT via QR_SCAN.', { edge: C.blue });
  card('F3', 'F', 5, 4, 'Live count appears', 'The attendance screen updates without a refresh.', { edge: C.brass });
  card('P4', 'P', 5, '', 'Push + outbox', 'STOMP topic per section; AttendanceRecorded saved to the outbox.', { edge: C.blue });
  card('F4', 'F', 6, 5, 'Publish a quiz', 'Questions, answer key hidden from students.', { edge: C.brass });
  card('S3', 'S', 6, '', 'Attempt the quiz', 'Multiple choice, true/false, short answer.', { edge: C.green });
  card('P5', 'P', 6, '', 'Auto-grade', 'Versioned, append-only grade; GradePublished to the outbox, then Kafka.', { edge: C.blue });
  card('P6', 'P', 7, '', 'Outbox to Kafka', 'The publisher sends each event and marks it PUBLISHED only after the broker confirms.', { edge: C.blue });
  card('I1', 'I', 7, 6, 'Risk recalculated', 'Attendance, assessment, assignments, learning progress. Versioned rule, reproducible score.', { edge: C.green });
  card('F5', 'F', 8, 7, 'Advisor alert', 'At-risk list with ranked evidence for faculty and admin.', { edge: C.brass });
  card('S4', 'S', 8, 8, 'AI explanation', 'Plain language, sources cited, no more certainty than the evidence.', { edge: C.green });
  card('I2', 'I', 8, '', 'AI explains', 'Tools scoped to the caller; RAG filtered by tenant first; Gemini then Ollama.', { edge: C.green });

  const R = (id) => ({ x: cards[id].x + CW, y: cards[id].y + CH / 2 });
  const L = (id) => ({ x: cards[id].x, y: cards[id].y + CH / 2 });
  const B = (id) => ({ x: cards[id].x + CW / 2, y: cards[id].y + CH });
  const T = (id) => ({ x: cards[id].x + CW / 2, y: cards[id].y });
  const elbow = (a, b, color = C.ink, off = 0) => {
    const p = R(a), q = L(b), mx = (p.x + q.x) / 2 + off;
    s.line([[p.x, p.y], [mx, p.y], [mx, q.y], [q.x, q.y]], { color, sw: 2.2 });
  };
  const vert = (a, b, color = C.ink, down = true) => {
    const p = down ? B(a) : T(a), q = down ? T(b) : B(b);
    s.line([[p.x, p.y], [q.x, q.y]], { color, sw: 2.2 });
  };
  elbow('F1', 'P1', C.brass, -6);
  elbow('S1', 'P1', C.green, 6);
  elbow('P1', 'F2', C.blue);
  vert('F2', 'P2', C.brass);
  elbow('P2', 'S2', C.blue);
  vert('S2', 'P3', C.green);
  s.line([[R('P3').x, R('P3').y], [L('P4').x, L('P4').y]], { color: C.blue, sw: 2.2 });
  vert('P4', 'F3', C.blue, false);
  s.line([[R('F3').x, R('F3').y], [L('F4').x, L('F4').y]], { color: C.brass, sw: 2.2 });
  vert('F4', 'S3', C.brass);
  vert('S3', 'P5', C.green);
  s.line([[R('P5').x, R('P5').y], [L('P6').x, L('P6').y]], { color: C.blue, sw: 2.2 });
  vert('P6', 'I1', C.blue);
  elbow('I1', 'F5', C.green);
  s.line([[R('I1').x, R('I1').y], [L('I2').x, L('I2').y]], { color: C.green, sw: 2.2 });
  vert('I2', 'S4', C.green, false);

  // band: forgot password + always-on
  s.rect(30, 880, 1180, 162, { fill: C.elev, stroke: C.rule, r: 10 });
  s.text(50, 910, 'Side flow: forgot password (emailed code)', { size: 18, weight: 700, font: F.display });
  const fp = [['Enter email', 'same answer for any address'], ['Code by email', '6 digits, 15 min, one use'], ['Enter code + new password', '5 wrong tries, then locked'], ['Password reset', 'every session signed out']];
  fp.forEach((c, i) => {
    const x = 50 + i * 290;
    s.rect(x, 926, 262, 96, { fill: C.white, stroke: C.brass, r: 8, sw: 1.4 });
    s.text(x + 14, 956, c[0], { size: 15.5, weight: 700 });
    s.para(x + 14, 978, wrap(c[1], 30), { size: 12.5, fill: C.soft, lh: 16 });
    if (i < 3) s.line([[x + 262, 974], [x + 290, 974]], { color: C.brass, sw: 2.2 });
  });
  s.rect(1230, 880, 660, 162, { fill: C.navy, stroke: C.navy, r: 10 });
  s.text(1254, 912, 'On every request, in every step', { size: 18, weight: 700, fill: '#f1eee2', font: F.display });
  s.para(1254, 940, [
    'Role + tenant + resource + action + workflow state + ownership',
    'are checked together, then the decision is written to the audit log.',
    'Events are published only after the data change is committed.',
  ], { size: 14, fill: '#d9d5c6', lh: 22 });
  s.text(1254, 1030, 'Super admin: colleges, roles and the platform audit log.', { size: 13, fill: '#d4a73d', font: F.mono });
  return s.out();
}

fs.writeFileSync(OUT + '/architecture.svg', architecture());
fs.writeFileSync(OUT + '/system-workflow.svg', workflow());
console.log('written');
