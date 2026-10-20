import { Link } from 'react-router-dom';
import { evidenceFor } from '../lib/advisorEvidence';

/**
 * "Based on" under an advisor answer: which of the student's own records the facts in it came from, what each covers, and a link to the page where they can
 * check. An answer with no sources used no records, and says so: it must not look grounded when it is not.
 */
export function AdvisorEvidence({ sources, model }: { sources: readonly string[] | undefined; model?: string }) {
  const evidence = evidenceFor(sources);
  return (
    <div style={{ fontSize: 12, color: 'var(--ink-soft)', marginTop: 6 }}>
      {evidence.length > 0 ? (
        <>
          <span id="evidence-label" style={{ fontWeight: 600 }}>Based on</span>
          <ul aria-labelledby="evidence-label" style={{ listStyle: 'none', margin: '2px 0 0', padding: 0 }}>
            {evidence.map((e) => (
              <li key={e.key} style={{ marginTop: 2 }}>
                {e.to ? <Link to={e.to} style={{ color: 'inherit', textDecoration: 'underline' }}>{e.label}</Link> : <span>{e.label}</span>}
                <span> — {e.covers}</span>
              </li>
            ))}
          </ul>
        </>
      ) : (
        <span>This answer did not use any of your records, so check any figure in it before relying on it.</span>
      )}
      {model && <div style={{ marginTop: 2 }}>Answered by {model}.</div>}
    </div>
  );
}
