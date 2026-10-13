// Usage: node build-diagrams.js <output-folder>
// Writes architecture.svg and system-workflow.svg (1920x1080, the Setu "ledger" palette). Export PNG with any browser,
// e.g. Chrome: chrome --headless=new --window-size=1920,1080 --force-device-scale-factor=2 --screenshot=out.png file.svg
const fs = require('fs');
const OUT = process.argv[2] || '.';

const C = { ink: '#1b2436', soft: '#52596b', bg: '#eae7dd', elev: '#f7f5ee', rule: '#c7c2b0', brass: '#8a5d18', brassSoft: '#efe3c9', green: '#2f6f63', greenSoft: '#dbe6e0', blue: '#2c4a7a', blueSoft: '#dde4ef', white: '#ffffff', cream: '#f1eee2', gold: '#d4a73d' };
const F = { display: "Fraunces, Georgia, 'Times New Roman', serif", body: "'Public Sans', 'Segoe UI', Arial, sans-serif", mono: "'IBM Plex Mono', Consolas, 'Courier New', monospace" };
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

function wrap(str, max) {
  const out = [];
  let cur = '';
  for (const w of str.split(' ')) {
    if ((cur + ' ' + w).trim().length > max) { out.push(cur.trim()); cur = w; } else cur = (cur + ' ' + w).trim();
  }
  if (cur) out.push(cur);
  return out;
}

class Svg {
  constructor() { this.defs = []; this.body = []; this.markers = new Set(); }
  marker(color) {
    const id = 'ar' + color.replace('#', '');
    if (!this.markers.has(id)) {
      this.markers.add(id);
      this.defs.push(`<marker id="${id}" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="${color}"/></marker>`);
    }
    return id;
  }
  rect(x, y, w, h, o = {}) {
    const { fill = C.elev, stroke = C.rule, r = 14, sw = 2, dash } = o;
    this.body.push(`<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" fill="${fill}" stroke="${stroke}" stroke-width="${sw}"${dash ? ` stroke-dasharray="${dash}"` : ''}/>`);
  }
  text(x, y, str, o = {}) {
    const { size = 20, weight = 400, fill = C.ink, anchor = 'start', font = F.body, ls = 0 } = o;
    this.body.push(`<text x="${x}" y="${y}" font-family="${font}" font-size="${size}" font-weight="${weight}" fill="${fill}" text-anchor="${anchor}"${ls ? ` letter-spacing="${ls}"` : ''}>${esc(str)}</text>`);
  }
  para(x, y, lines, o = {}) {
    const lh = o.lh || (o.size || 20) * 1.35;
    lines.forEach((l, i) => this.text(x, y + i * lh, l, o));
  }
  arrow(pts, color = C.ink, sw = 4) {
    const d = pts.map((p, i) => (i ? 'L' : 'M') + p[0] + ',' + p[1]).join(' ');
    this.body.push(`<path d="${d}" fill="none" stroke="${color}" stroke-width="${sw}" stroke-linejoin="round" marker-end="url(#${this.marker(color)})"/>`);
  }
  header(title, sub) {
    this.rect(0, 0, 1920, 96, { fill: C.ink, stroke: C.ink, r: 0 });
    this.text(60, 62, title, { size: 42, weight: 600, fill: C.cream, font: F.display });
    this.text(1860, 60, sub, { size: 18, fill: C.gold, anchor: 'end', font: F.mono, ls: 1.5 });
  }
  out() {
    return `<svg xmlns="http://www.w3.org/2000/svg" width="1920" height="1080" viewBox="0 0 1920 1080"><defs>${this.defs.join('')}</defs><rect width="1920" height="1080" fill="${C.bg}"/>${this.body.join('')}</svg>`;
  }
}

// ------------------------------------------------------------------ architecture (simple)
function architecture() {
  const s = new Svg();
  s.header('Setu — how the system fits together', 'ONE SYSTEM OF RECORD FOR A COLLEGE');
  const X = 100, W = 1180;

  // 1. users
  const roles = [['Super admin', 'runs the platform'], ['College admin', 'runs the college'], ['Faculty', 'teaches and grades'], ['Student', 'learns and tracks progress']];
  roles.forEach((r, i) => {
    const x = X + i * 300;
    s.rect(x, 128, 280, 96, { fill: C.brassSoft, stroke: C.brass });
    s.text(x + 140, 168, r[0], { size: 27, weight: 700, fill: C.brass, anchor: 'middle' });
    s.text(x + 140, 200, r[1], { size: 17, fill: C.soft, anchor: 'middle' });
  });
  s.arrow([[X + W / 2, 224], [X + W / 2, 268]], C.ink);

  // 2. web app
  s.rect(X, 268, W, 92, { fill: C.white, stroke: C.ink });
  s.text(X + W / 2, 311, 'Web app (React + TypeScript)', { size: 30, weight: 700, anchor: 'middle', font: F.display });
  s.text(X + W / 2, 342, 'one dashboard per role, live updates without refreshing', { size: 19, fill: C.soft, anchor: 'middle' });
  s.arrow([[X + W / 2, 360], [X + W / 2, 404]], C.ink);

  // 3. backend
  s.rect(X, 404, W, 392, { fill: C.elev, stroke: C.ink, sw: 3 });
  s.text(X + 30, 450, 'Setu backend (Spring Boot)', { size: 32, weight: 700, font: F.display });
  s.text(X + 30, 482, 'one application, split into 16 modules with strict boundaries', { size: 19, fill: C.soft });
  s.rect(X + 24, 504, W - 48, 58, { fill: C.greenSoft, stroke: C.green });
  s.text(X + W / 2, 541, 'Security on every request: passkey sign-in · tenant isolation · permission check · audit log', { size: 21, weight: 600, fill: C.green, anchor: 'middle' });
  const groups = [
    ['People', 'identity, directory, institution'],
    ['Academic', 'courses, enrollment, attendance, learning, grades'],
    ['Money & careers', 'fees and payments, jobs'],
    ['Insight', 'risk engine, AI explanations'],
    ['Platform', 'live updates, events'],
  ];
  groups.forEach((g, i) => {
    const x = X + 24 + i * 224;
    s.rect(x, 584, 208, 188, { fill: C.white, stroke: C.rule });
    s.text(x + 104, 622, g[0], { size: 22, weight: 700, fill: C.brass, anchor: 'middle' });
    s.para(x + 104, 656, wrap(g[1], 19), { size: 17, fill: C.ink, anchor: 'middle', lh: 24 });
  });
  s.arrow([[X + W / 2, 796], [X + W / 2, 836]], C.ink);

  // 4. data
  const data = [['PostgreSQL', 'stores every record'], ['Redis', 'fast counters and limits'], ['Kafka', 'carries events to other parts']];
  data.forEach((d, i) => {
    const x = X + i * 405;
    s.rect(x, 836, 370, 104, { fill: C.blueSoft, stroke: C.blue });
    s.text(x + 185, 880, d[0], { size: 28, weight: 700, fill: C.blue, anchor: 'middle', font: F.display });
    s.text(x + 185, 914, d[1], { size: 18, fill: C.ink, anchor: 'middle' });
  });

  // right: outside services
  s.text(1380, 440, 'OUTSIDE SERVICES', { size: 17, weight: 700, fill: C.soft, font: F.mono, ls: 2 });
  s.rect(1380, 458, 460, 150, { fill: C.greenSoft, stroke: C.green });
  s.text(1410, 506, 'AI explanations', { size: 28, weight: 700, fill: C.green, font: F.display });
  s.para(1410, 540, ['Gemini, with a local Ollama', 'model as the backup'], { size: 19, lh: 26 });
  s.rect(1380, 648, 460, 150, { fill: C.greenSoft, stroke: C.green });
  s.text(1410, 696, 'Email', { size: 28, weight: 700, fill: C.green, font: F.display });
  s.para(1410, 730, ['Gmail sends the 6-digit', 'password-reset code'], { size: 19, lh: 26 });
  s.arrow([[X + W, 533], [1380, 533]], C.green);
  s.arrow([[X + W, 723], [1380, 723]], C.green);

  s.text(960, 1010, 'This build shows four role dashboards. Guardian and placement features are built in the backend but hidden here.', { size: 18, fill: C.soft, anchor: 'middle' });
  return s.out();
}

// ------------------------------------------------------------------ workflow (simple)
function workflow() {
  const s = new Svg();
  s.header('Setu — from sign-in to an explained risk', 'THE DEMO FLOW IN SEVEN STEPS');
  const steps = [
    ['Everyone', C.ink, 'Sign in', 'Passkey or password. The college is identified automatically.'],
    ['Faculty', C.brass, 'Start attendance', 'A QR code appears and changes every few seconds.'],
    ['Student', C.green, 'Scan the QR', 'The scan is checked and the student is marked present.'],
    ['System', C.blue, 'Live update', 'The faculty screen updates instantly, with no refresh.'],
    ['Faculty + System', C.brass, 'Quiz and grade', 'The quiz is auto-graded and saved as a new grade version.'],
    ['System', C.blue, 'Risk check', 'Attendance, grades and progress become one reproducible score.'],
    ['AI', C.green, 'Plain explanation', 'The student and advisor see the reasons, with sources.'],
  ];
  const CW = 222, GAP = 36, X0 = 66, Y = 150, H = 340;
  steps.forEach((st, i) => {
    const x = X0 + i * (CW + GAP);
    s.rect(x, Y, CW, H, { fill: C.white, stroke: st[1], sw: 3 });
    s.rect(x, Y, CW, 44, { fill: st[1], stroke: st[1], r: 14 });
    s.rect(x, Y + 24, CW, 20, { fill: st[1], stroke: st[1], r: 0, sw: 0 });
    s.text(x + CW / 2, Y + 30, st[0].toUpperCase(), { size: 14, weight: 700, fill: C.white, anchor: 'middle', font: F.mono, ls: 1.2 });
    s.rect(x + CW / 2 - 28, Y + 62, 56, 56, { fill: C.ink, stroke: C.ink, r: 28 });
    s.text(x + CW / 2, Y + 100, String(i + 1), { size: 30, weight: 700, fill: C.cream, anchor: 'middle', font: F.mono });
    s.para(x + CW / 2, Y + 156, wrap(st[2], 12), { size: 26, weight: 700, anchor: 'middle', lh: 31, font: F.display });
    const tl = wrap(st[2], 12).length;
    s.para(x + CW / 2, Y + 156 + tl * 31 + 8, wrap(st[3], 20), { size: 17, fill: C.soft, anchor: 'middle', lh: 23 });
    if (i < steps.length - 1) s.arrow([[x + CW + 4, Y + H / 2], [x + CW + GAP - 4, Y + H / 2]], C.ink, 4);
  });

  s.rect(66, 540, 1788, 96, { fill: C.ink, stroke: C.ink });
  s.text(960, 582, 'On every step', { size: 20, weight: 700, fill: C.gold, anchor: 'middle', font: F.mono, ls: 2 });
  s.text(960, 616, 'permission is checked · each college’s data stays separate · every action is logged', { size: 26, fill: C.cream, anchor: 'middle', font: F.display });

  s.text(66, 706, 'If a password is forgotten', { size: 30, weight: 700, font: F.display });
  const side = [['Enter your email', 'the answer is the same for any address'], ['Get a 6-digit code', 'valid 15 minutes, works once'], ['Set a new password', '5 wrong tries and the code locks'], ['All sessions end', 'the old sign-ins stop working']];
  side.forEach((c, i) => {
    const x = 66 + i * 456;
    s.rect(x, 736, 400, 140, { fill: C.brassSoft, stroke: C.brass, sw: 2.5 });
    s.text(x + 24, 790, c[0], { size: 26, weight: 700, font: F.display });
    s.para(x + 24, 826, wrap(c[1], 34), { size: 18, fill: C.soft, lh: 25 });
    if (i < 3) s.arrow([[x + 404, 806], [x + 452, 806]], C.brass, 4);
  });
  s.text(960, 1010, 'Super admin manages colleges, roles and the platform audit log.', { size: 18, fill: C.soft, anchor: 'middle' });
  return s.out();
}

fs.writeFileSync(OUT + '/architecture.svg', architecture());
fs.writeFileSync(OUT + '/system-workflow.svg', workflow());
console.log('written');
