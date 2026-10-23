/**
 * A small, safe Markdown reader for the advisor's answers. The advisor writes the way chat assistants do (short paragraphs, bold figures, bullet and numbered lists, a table when
 * it compares courses), and this turns that into a structure the page renders as real elements. It never produces HTML: the page builds React elements from this tree, so
 * nothing the model (or a policy document it quoted) writes can inject markup or script. Links are limited to the app's own pages and https addresses.
 *
 * It is written for text that is still arriving. Half a sentence, an unclosed ** or a table whose separator row has not come yet is shown as the plain text it is, then
 * takes its shape as the rest arrives; it never throws and never drops text.
 */

export type Inline =
  | { t: 'text'; v: string }
  | { t: 'strong'; c: Inline[] }
  | { t: 'em'; c: Inline[] }
  | { t: 'code'; v: string }
  | { t: 'link'; href: string; c: Inline[] }
  | { t: 'br' };

export interface ListItem {
  c: Inline[];
  /** A list nested under this item. */
  children: Block[];
}

export type Align = 'left' | 'right' | 'center' | null;

export type Block =
  | { t: 'p'; c: Inline[] }
  | { t: 'h'; level: 3 | 4 | 5; c: Inline[] }
  | { t: 'list'; ordered: boolean; start: number; items: ListItem[] }
  | { t: 'table'; head: Inline[][]; align: Align[]; rows: Inline[][][] }
  | { t: 'quote'; c: Block[] }
  | { t: 'pre'; v: string }
  | { t: 'hr' };

/** Where a link may point: one of the app's own pages, or an https address. Anything else is shown as its text, not as a link. */
export function safeHref(href: string): { href: string; internal: boolean } | null {
  const h = href.trim();
  if (/^\/app\/[A-Za-z0-9/_\-?=&#.%]*$/.test(h) && !h.includes('..')) return { href: h, internal: true };
  if (/^https:\/\/[^\s<>"']+$/i.test(h)) return { href: h, internal: false };
  return null;
}

// ---- inline ----------------------------------------------------------------------------------

const isWordChar = (ch: string | undefined) => ch !== undefined && /[A-Za-z0-9]/.test(ch);

function pushText(out: Inline[], v: string) {
  if (!v) return;
  const last = out[out.length - 1];
  if (last && last.t === 'text') last.v += v;
  else out.push({ t: 'text', v });
}

/** Finds the closing {@code marker} for an opener at {@code from}, skipping code spans and escapes; -1 when it is not (yet) there. */
function findClose(s: string, marker: string, from: number): number {
  let i = from;
  while (i < s.length) {
    const ch = s[i];
    if (ch === '\\') { i += 2; continue; }
    if (ch === '`') {
      const end = s.indexOf('`', i + 1);
      if (end < 0) return -1;
      i = end + 1;
      continue;
    }
    if (s.startsWith(marker, i)) {
      // **bold** must not close on the first star of a ***
      if (marker.length === 1 && s[i + 1] === marker) { i += 2; continue; }
      return i;
    }
    i += 1;
  }
  return -1;
}

export function parseInline(s: string): Inline[] {
  const out: Inline[] = [];
  let i = 0;
  while (i < s.length) {
    const ch = s[i];

    if (ch === '\\' && i + 1 < s.length && /[\\`*_{}[\]()#+\-.!|>~]/.test(s[i + 1])) {
      pushText(out, s[i + 1]);
      i += 2;
      continue;
    }
    if (ch === '\n') {
      out.push({ t: 'br' });
      i += 1;
      continue;
    }
    if (ch === '`') {
      const end = s.indexOf('`', i + 1);
      if (end > i + 1) {
        out.push({ t: 'code', v: s.slice(i + 1, end) });
        i = end + 1;
        continue;
      }
      pushText(out, ch);
      i += 1;
      continue;
    }
    if ((s.startsWith('**', i) || s.startsWith('__', i)) && !/\s/.test(s[i + 2] ?? ' ')) {
      const marker = s.slice(i, i + 2);
      const end = findClose(s, marker, i + 2);
      if (end > i + 2 && !/\s/.test(s[end - 1])) {
        out.push({ t: 'strong', c: parseInline(s.slice(i + 2, end)) });
        i = end + 2;
        continue;
      }
    }
    if ((ch === '*' || ch === '_') && !/\s/.test(s[i + 1] ?? ' ') && !(ch === '_' && isWordChar(s[i - 1]))) {
      const end = findClose(s, ch, i + 1);
      if (end > i + 1 && !/\s/.test(s[end - 1]) && !(ch === '_' && isWordChar(s[end + 1]))) {
        out.push({ t: 'em', c: parseInline(s.slice(i + 1, end)) });
        i = end + 1;
        continue;
      }
    }
    if (ch === '[') {
      const close = s.indexOf('](', i + 1);
      const end = close < 0 ? -1 : s.indexOf(')', close + 2);
      if (close > i && end > close) {
        const label = s.slice(i + 1, close);
        const target = safeHref(s.slice(close + 2, end));
        if (target && !label.includes('\n')) {
          out.push({ t: 'link', href: target.href, c: parseInline(label) });
          i = end + 1;
          continue;
        }
        if (!label.includes('\n')) {
          // a link the page will not follow: its words stay, the address does not become a control
          out.push(...parseInline(label));
          i = end + 1;
          continue;
        }
      }
    }
    pushText(out, ch);
    i += 1;
  }
  return out;
}

// ---- blocks ----------------------------------------------------------------------------------

const FENCE = /^\s*```/;
const HEADING = /^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$/;
const HR = /^\s{0,3}([-*_])(\s*\1){2,}\s*$/;
const LIST_ITEM = /^(\s*)([-*+•]|\d{1,3}[.)])\s+(.*)$/;
const QUOTE = /^\s{0,3}>\s?(.*)$/;
/** A delimiter row: cells of dashes (with optional alignment colons) and at least one pipe, so a lone --- stays a rule. */
const TABLE_SEPARATOR = /^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$/;

function splitRow(line: string): string[] {
  let s = line.trim();
  if (s.startsWith('|')) s = s.slice(1);
  if (s.endsWith('|') && !s.endsWith('\\|')) s = s.slice(0, -1);
  const cells: string[] = [];
  let cur = '';
  for (let i = 0; i < s.length; i++) {
    if (s[i] === '\\' && s[i + 1] === '|') { cur += '|'; i += 1; continue; }
    if (s[i] === '|') { cells.push(cur.trim()); cur = ''; continue; }
    cur += s[i];
  }
  cells.push(cur.trim());
  return cells;
}

function alignOf(cell: string): Align {
  const left = cell.startsWith(':');
  const right = cell.endsWith(':');
  if (left && right) return 'center';
  if (right) return 'right';
  if (left) return 'left';
  return null;
}

const indentOf = (line: string) => line.length - line.trimStart().length;

function startsBlock(line: string, next: string | undefined): boolean {
  return FENCE.test(line) || HEADING.test(line) || HR.test(line) || LIST_ITEM.test(line) || QUOTE.test(line)
    || (line.includes('|') && next !== undefined && TABLE_SEPARATOR.test(next) && next.includes('|'));
}

/** Reads a list that starts at {@code lines[start]}; returns the block and the index of the first line after it. */
function readList(lines: string[], start: number): [Block, number] {
  const first = LIST_ITEM.exec(lines[start])!;
  const baseIndent = first[1].length;
  const ordered = /\d/.test(first[2]);
  const startNumber = ordered ? parseInt(first[2], 10) : 1;
  const items: ListItem[] = [];
  let i = start;
  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) {
      // a blank line ends the list unless the list goes on right after it
      const after = lines[i + 1];
      const m = after === undefined ? null : LIST_ITEM.exec(after);
      if (m && m[1].length >= baseIndent) { i += 1; continue; }
      break;
    }
    const m = LIST_ITEM.exec(line);
    if (m && m[1].length <= baseIndent + 1) {
      if (ordered !== /\d/.test(m[2]) && items.length > 0) break;
      items.push({ c: parseInline(m[3]), children: [] });
      i += 1;
      continue;
    }
    if (m && items.length > 0) {
      // deeper than this list: it belongs to the item above, as a nested list
      const nestedLines: string[] = [];
      while (i < lines.length && lines[i].trim() && indentOf(lines[i]) > baseIndent + 1) {
        nestedLines.push(lines[i]);
        i += 1;
      }
      items[items.length - 1].children.push(...parseBlocks(nestedLines));
      continue;
    }
    if (items.length > 0 && indentOf(line) > baseIndent && !startsBlock(line, lines[i + 1])) {
      // a wrapped line of the item above
      items[items.length - 1].c.push({ t: 'br' }, ...parseInline(line.trim()));
      i += 1;
      continue;
    }
    break;
  }
  return [{ t: 'list', ordered, start: startNumber, items }, i];
}

export function parseBlocks(lines: string[]): Block[] {
  const blocks: Block[] = [];
  let i = 0;
  let para: string[] = [];
  const flush = () => {
    if (para.length) {
      blocks.push({ t: 'p', c: parseInline(para.join('\n')) });
      para = [];
    }
  };

  while (i < lines.length) {
    const line = lines[i];
    const next = lines[i + 1];

    if (!line.trim()) { flush(); i += 1; continue; }

    if (FENCE.test(line)) {
      flush();
      const body: string[] = [];
      i += 1;
      while (i < lines.length && !FENCE.test(lines[i])) { body.push(lines[i]); i += 1; }
      i += 1;   // the closing fence, if it has arrived yet
      blocks.push({ t: 'pre', v: body.join('\n') });
      continue;
    }

    const heading = HEADING.exec(line);
    if (heading) {
      flush();
      const n = heading[1].length;
      blocks.push({ t: 'h', level: n <= 2 ? 3 : n === 3 ? 4 : 5, c: parseInline(heading[2]) });
      i += 1;
      continue;
    }

    if (HR.test(line) && !LIST_ITEM.test(line)) { flush(); blocks.push({ t: 'hr' }); i += 1; continue; }

    if (line.includes('|') && next !== undefined && TABLE_SEPARATOR.test(next) && next.includes('|')) {
      const head = splitRow(line);
      const separators = splitRow(next);
      if (head.length === separators.length) {
        flush();
        const rows: Inline[][][] = [];
        i += 2;
        while (i < lines.length && lines[i].trim() && lines[i].includes('|')) {
          const cells = splitRow(lines[i]);
          // a row shorter or longer than the header is made to fit, not dropped
          rows.push(head.map((_, c) => parseInline(cells[c] ?? '')));
          i += 1;
        }
        blocks.push({ t: 'table', head: head.map(parseInline), align: separators.map(alignOf), rows });
        continue;
      }
    }

    if (LIST_ITEM.test(line)) {
      flush();
      const [list, end] = readList(lines, i);
      blocks.push(list);
      i = end;
      continue;
    }

    if (QUOTE.test(line)) {
      flush();
      const body: string[] = [];
      while (i < lines.length && QUOTE.test(lines[i])) { body.push(QUOTE.exec(lines[i])![1]); i += 1; }
      blocks.push({ t: 'quote', c: parseBlocks(body) });
      continue;
    }

    para.push(line);
    i += 1;
  }
  flush();
  return blocks;
}

export function parseChatMarkdown(text: string): Block[] {
  return parseBlocks(text.replace(/\r\n?/g, '\n').split('\n'));
}

/** The words of an inline run without any formatting, for places that need plain text (a table cell's name, a test). */
export function inlineText(inline: Inline[]): string {
  return inline.map((n) => {
    switch (n.t) {
      case 'text': case 'code': return n.v;
      case 'br': return ' ';
      default: return inlineText(n.c);
    }
  }).join('');
}
