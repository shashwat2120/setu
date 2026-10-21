import type { ResumeContent, ResumeFileInfo, ResumeRecord, ResumeSkill } from '../../api/types';
import { KIND_HEADINGS, ITEM_KINDS, datesText, isHttpsUrl, recordLine, sizeText } from '../../lib/resume';

/**
 * A resume as a reader sees it: what the student wrote, next to what the college's own records say (batch, CGPA, attendance, skills). Links are shown only if they are
 * https addresses. Used for the student's own preview and for the placement officer reading an application.
 */
export function ResumeCard({ content, record, skills, skillName, file, onDownloadFile }: {
  content: ResumeContent; record: ResumeRecord; skills: ResumeSkill[]; skillName: (id: string) => string; file?: ResumeFileInfo | null; onDownloadFile?: () => void;
}) {
  const links = [['LinkedIn', content.linkedinUrl], ['GitHub', content.githubUrl], ['Portfolio', content.portfolioUrl]].filter(([, url]) => isHttpsUrl(url as string | null));
  const facts = recordLine(record);
  return (
    <article className="card resume-card" aria-label="Resume">
      <h3 style={{ fontSize: 20, margin: 0 }}>{content.headline ?? 'No headline yet'}</h3>
      {content.summary && <p style={{ marginTop: 8, whiteSpace: 'pre-wrap' }}>{content.summary}</p>}
      {file && (
        <p className="no-print" style={{ marginTop: 8, fontSize: 14 }}>
          Resume file: <strong>{file.name}</strong> ({sizeText(file.size)}){' '}
          {onDownloadFile && <button type="button" className="btn btn-ghost" style={{ padding: '4px 10px', fontSize: 12 }} onClick={onDownloadFile}>Download</button>}
        </p>
      )}
      {links.length > 0 && (
        <p style={{ marginTop: 8, fontSize: 14 }}>
          {links.map(([label, url], i) => (
            <span key={label}>{i > 0 && ' · '}<a href={url as string} target="_blank" rel="noopener noreferrer">{label}</a></span>
          ))}
        </p>
      )}
      {(facts || skills.length > 0) && (
        <section style={{ marginTop: 12 }} aria-label="From the college's records">
          <h4 style={{ fontSize: 13, textTransform: 'uppercase', letterSpacing: '0.08em', color: 'var(--ink-soft)' }}>From the college&apos;s records</h4>
          {facts && <p style={{ fontSize: 14 }}>{facts}</p>}
          {skills.length > 0 && <p style={{ fontSize: 14 }}>Skills: {skills.map((s) => `${skillName(s.skillId)}${s.level ? ` (${s.level.toLowerCase()})` : ''}`).join(', ')}</p>}
        </section>
      )}
      {ITEM_KINDS.map((kind) => {
        const items = content.items.filter((i) => i.kind === kind);
        return items.length === 0 ? null : (
          <section key={kind} style={{ marginTop: 12 }}>
            <h4 style={{ fontSize: 13, textTransform: 'uppercase', letterSpacing: '0.08em', color: 'var(--ink-soft)' }}>{KIND_HEADINGS[kind]}</h4>
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {items.map((item, i) => (
                <li key={i} style={{ marginBottom: 6 }}>
                  <strong>{item.title}</strong>{item.organisation ? ` — ${item.organisation}` : ''}
                  {datesText(item) && <span style={{ color: 'var(--ink-soft)', fontSize: 13 }}> ({datesText(item)})</span>}
                  {item.description && <div style={{ fontSize: 14, whiteSpace: 'pre-wrap' }}>{item.description}</div>}
                </li>
              ))}
            </ul>
          </section>
        );
      })}
    </article>
  );
}
