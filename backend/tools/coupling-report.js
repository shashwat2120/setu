#!/usr/bin/env node
// Measures actual coupling in backend/src/main from the sources, for the Sprint 6 extraction audit (docs/extraction-audit.md).
//
//   node backend/tools/coupling-report.js
//
// Prints: module -> module dependencies (classes involved, distinct types used, layers touched), the shared contracts and which module
// implements them (a hidden dependency: consumer -> implementer), the dependencies between the candidate SERVICES (modules grouped as in
// docs/sprint6-candidate-services.md, with Record folded into Assessment as the audit recommends), and any cycles between them.
//
// The permanent guard is ModuleContractFreezeTests (measures from the compiled classes and fails on any new coupling); this script is the
// readable report and the service-level view the tests do not produce. It reads source text, so it is approximate on the type counts but
// exact on which module references which.
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '../src/main/java/com/setu');
const SERVICE = {
  identity: 'Identity',
  institution: 'Academic', catalog: 'Academic', enrollment: 'Academic', directory: 'Academic', guardianaccess: 'Academic',
  learning: 'Learning', assessment: 'Assessment', record: 'Assessment', attendance: 'Attendance', risk: 'StudentSuccess',
  career: 'Career', finance: 'Finance', notification: 'Notification', aiexplain: 'AI', analytics: 'Analytics',
};

const MODULES = fs.readdirSync(root, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name).concat(['shared.admin', 'shared.seed']);
const files = [];
(function walk(d) {
  for (const f of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, f.name);
    if (f.isDirectory()) walk(p);
    else if (f.name.endsWith('.java')) files.push(p);
  }
})(root);

const moduleOf = (p) => {
  const parts = path.relative(root, p).split(path.sep);
  return parts[0] === 'shared' && (parts[1] === 'admin' || parts[1] === 'seed') ? 'shared.' + parts[1] : parts[0];
};
const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');

// ---- module -> module dependencies
const edges = {};
const fqnRe = /com\.setu\.([a-z]+)\.((?:[a-z]+\.)*)([A-Z][A-Za-z0-9_]*)/g;
for (const f of files) {
  const from = moduleOf(f);
  const src = strip(fs.readFileSync(f, 'utf8'));
  const seen = new Set();
  let m;
  fqnRe.lastIndex = 0;
  while ((m = fqnRe.exec(src))) {
    let to = m[1];
    const sub = (m[2] || '').split('.')[0];
    if (to === 'shared' && (sub === 'admin' || sub === 'seed')) to = 'shared.' + sub;
    if (to === from || !MODULES.includes(to) || to === 'shared') continue;
    const e = (edges[`${from}->${to}`] ??= { classes: new Set(), types: new Set(), layers: {} });
    e.classes.add(path.basename(f, '.java'));
    const type = `${m[2]}${m[3]}`;
    if (!seen.has(`${to}.${type}`)) {
      seen.add(`${to}.${type}`);
      e.types.add(type);
      const layer = sub || '(root)';
      e.layers[layer] = (e.layers[layer] || 0) + 1;
    }
  }
}
const moduleEdges = Object.entries(edges).map(([k, e]) => {
  const [from, to] = k.split('->');
  return { from, to, classes: e.classes.size, types: e.types.size, layers: e.layers };
}).sort((a, b) => a.from.localeCompare(b.from) || b.types - a.types);

console.log('MODULE DEPENDENCIES (from -> to: classes in from / distinct types used / layers)');
moduleEdges.forEach((e) => console.log(`${e.from.padEnd(15)} -> ${e.to.padEnd(15)} ${String(e.classes).padStart(3)} ${String(e.types).padStart(3)}  ${JSON.stringify(e.layers)}`));

// ---- shared contracts and their implementers
const contracts = {};
for (const f of files) {
  const rel = path.relative(root, f).split(path.sep);
  if (rel[0] !== 'shared' || ['admin', 'seed', 'governance', 'web'].includes(rel[1])) continue;
  const m = /public\s+(?:interface|abstract class|sealed interface)\s+([A-Z]\w+)/.exec(fs.readFileSync(f, 'utf8'));
  if (m) contracts[m[1]] = { implementers: new Set(), consumers: new Set() };
}
for (const f of files) {
  const mod = moduleOf(f);
  if (mod === 'shared') continue;
  const src = strip(fs.readFileSync(f, 'utf8'));
  for (const name of Object.keys(contracts)) {
    if (new RegExp(`implements[^{]*\\b${name}\\b`).test(src)) contracts[name].implementers.add(mod);
    else if (new RegExp(`\\b${name}\\b`).test(src)) contracts[name].consumers.add(mod);
  }
}
console.log('\nSHARED CONTRACTS (implemented by a module, used by others)');
for (const [n, c] of Object.entries(contracts)) if (c.implementers.size) console.log(`${n.padEnd(30)} implemented by ${[...c.implementers]}; used by ${[...c.consumers]}`);

// ---- service-level graph
const edgesBetween = {};
const add = (from, to, weight) => {
  const a = SERVICE[from], b = SERVICE[to];
  if (!a || !b || a === b) return;
  edgesBetween[`${a}->${b}`] = (edgesBetween[`${a}->${b}`] || 0) + weight;
};
moduleEdges.forEach((e) => add(e.from, e.to, e.types));
for (const c of Object.values(contracts)) for (const impl of c.implementers) for (const user of c.consumers) add(user, impl, 1);

const nodes = [...new Set(Object.values(SERVICE))];
const adj = {};
Object.keys(edgesBetween).forEach((k) => { const [a, b] = k.split('->'); (adj[a] ??= []).push(b); });
const out = {}, into = {};
Object.keys(edgesBetween).forEach((k) => { const [a, b] = k.split('->'); out[a] = (out[a] || 0) + 1; into[b] = (into[b] || 0) + 1; });

console.log('\nSERVICE DEPENDENCIES (weight = distinct types used across modules + contracts)');
Object.entries(edgesBetween).sort((a, b) => b[1] - a[1]).forEach(([k, w]) => console.log(`${k.replace('->', ' -> ').padEnd(30)} ${w}`));
console.log('\nSERVICE        fan-out fan-in');
nodes.forEach((n) => console.log(`${n.padEnd(15)} ${String(out[n] || 0).padStart(6)} ${String(into[n] || 0).padStart(7)}`));

let idx = 0; const stack = []; const on = new Set(); const index = {}; const low = {}; const comps = [];
const strong = (v) => {
  index[v] = low[v] = idx++; stack.push(v); on.add(v);
  for (const w of adj[v] || []) { if (index[w] === undefined) { strong(w); low[v] = Math.min(low[v], low[w]); } else if (on.has(w)) low[v] = Math.min(low[v], index[w]); }
  if (low[v] === index[v]) { const c = []; let w; do { w = stack.pop(); on.delete(w); c.push(w); } while (w !== v); comps.push(c); }
};
nodes.forEach((v) => { if (index[v] === undefined) strong(v); });
console.log('\nCYCLES BETWEEN SERVICES:', JSON.stringify(comps.filter((c) => c.length > 1)));
