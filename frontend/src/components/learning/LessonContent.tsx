import { useState, type ReactNode } from 'react';
import { apiFetch } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { LessonResourceResponse } from '../../api/types';
import { Skeleton } from '../ui/EmptyState';
import { displayRef, isHttpsUrl, youtubeEmbedUrl, youtubeId } from '../../lib/lessonContent';

const inputStyle = {
  padding: '8px 10px', fontSize: 13, border: '1px solid var(--rule)', borderRadius: 4,
  background: 'var(--bg-elev)', color: 'var(--ink)',
};

const TYPE_LABEL: Record<string, string> = { VIDEO: 'Video', DOCUMENT: 'Document', IMAGE: 'Image', AUDIO: 'Audio', LINK: 'Link' };

/** Only a well-formed https address becomes a link; anything else (an internal ref://) is shown as plain text. */
function ExternalLink({ href, children }: { href: string; children: ReactNode }) {
  return isHttpsUrl(href)
    ? <a href={href} target="_blank" rel="noopener noreferrer" style={{ color: 'var(--ink)', textDecoration: 'underline', textUnderlineOffset: 2 }}>{children}</a>
    : <span className="mono" style={{ fontSize: 12 }}>{children}</span>;
}

interface Props {
  lessonId: string;
  lessonType: string | null;
  contentRef: string | null;
  /** Faculty who teach the section can attach and remove documents; students only read. */
  canManage?: boolean;
}

/** One lesson's content: the video (embedded when it is a YouTube link), its main link, and the documents attached to it. */
export function LessonContent({ lessonId, lessonType, contentRef, canManage = false }: Props) {
  const resources = useApiQuery<LessonResourceResponse[]>(`/learning/resources?lessonId=${lessonId}`);
  const [mediaRef, setMediaRef] = useState('');
  const [type, setType] = useState('DOCUMENT');
  const [title, setTitle] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const videoId = youtubeId(contentRef);

  const addResource = async () => {
    setSaving(true);
    setError(null);
    try {
      await apiFetch('/learning/resources', { method: 'POST', body: { lessonId, mediaRef: mediaRef.trim(), type, title: title.trim() || null } });
      setMediaRef('');
      setTitle('');
      resources.refetch();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not attach this link.');
    } finally {
      setSaving(false);
    }
  };

  const removeResource = async (id: string) => {
    setError(null);
    try {
      await apiFetch(`/learning/resources/${id}`, { method: 'DELETE' });
      resources.refetch();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not remove this link.');
    }
  };

  return (
    <div style={{ padding: '4px 0 8px' }}>
      {videoId ? (
        <div style={{ maxWidth: 640 }}>
          <div style={{ position: 'relative', paddingTop: '56.25%', background: 'var(--ink)', borderRadius: 4, overflow: 'hidden' }}>
            <iframe
              title={`Lesson video (${videoId})`}
              src={youtubeEmbedUrl(videoId)}
              loading="lazy"
              allow="accelerometer; encrypted-media; gyroscope; picture-in-picture; fullscreen"
              referrerPolicy="strict-origin-when-cross-origin"
              allowFullScreen
              style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', border: 0 }}
            />
          </div>
          <p style={{ fontSize: 12, color: 'var(--ink-soft)', marginTop: 6 }}>
            Not playing? <ExternalLink href={contentRef as string}>Watch on YouTube</ExternalLink>
          </p>
        </div>
      ) : contentRef ? (
        <p style={{ fontSize: 13 }}>
          {lessonType === 'VIDEO' ? 'Video' : 'Lesson material'}: <ExternalLink href={contentRef}>{displayRef(contentRef)}</ExternalLink>
        </p>
      ) : (
        <p style={{ fontSize: 13, color: 'var(--ink-soft)' }}>This lesson has no main link.</p>
      )}

      <h4 style={{ fontSize: 13, margin: '14px 0 6px' }}>Attached documents and links</h4>
      {resources.loading && <Skeleton height={36} />}
      {resources.error && <p style={{ fontSize: 12, color: 'var(--flag)' }}>Could not load the attached documents.</p>}
      {resources.data && resources.data.length === 0 && (
        <p style={{ fontSize: 13, color: 'var(--ink-soft)' }}>Nothing is attached to this lesson yet.</p>
      )}
      {resources.data && resources.data.length > 0 && (
        <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: 6 }}>
          {resources.data.map((r) => (
            <li key={r.id} style={{ display: 'flex', gap: 8, alignItems: 'baseline', flexWrap: 'wrap', fontSize: 13 }}>
              <span className="mono" style={{ fontSize: 11, color: 'var(--ink-soft)', minWidth: 64 }}>{TYPE_LABEL[r.type] ?? r.type}</span>
              <ExternalLink href={r.mediaRef}>{r.title ?? displayRef(r.mediaRef)}</ExternalLink>
              {canManage && (
                <button type="button" className="btn btn-ghost" style={{ padding: '2px 8px', fontSize: 11 }}
                  aria-label={`Remove ${r.title ?? displayRef(r.mediaRef)}`} onClick={() => removeResource(r.id)}>
                  Remove
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {canManage && (
        <div style={{ marginTop: 12 }}>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <input aria-label="Document title" placeholder="Title (optional)" value={title} maxLength={200}
              onChange={(e) => setTitle(e.target.value)} style={{ ...inputStyle, flex: 1, minWidth: 140 }} />
            <select aria-label="Resource type" value={type} onChange={(e) => setType(e.target.value)} style={inputStyle}>
              <option value="DOCUMENT">Document</option>
              <option value="VIDEO">Video</option>
              <option value="LINK">Link</option>
              <option value="IMAGE">Image</option>
              <option value="AUDIO">Audio</option>
            </select>
            <input aria-label="Document link" placeholder="https://…" value={mediaRef}
              onChange={(e) => setMediaRef(e.target.value)} style={{ ...inputStyle, flex: 2, minWidth: 200 }} />
            <button type="button" className="btn btn-primary" style={{ padding: '8px 14px', fontSize: 13 }}
              disabled={saving || !mediaRef.trim()} onClick={addResource}>
              {saving ? 'Attaching…' : 'Attach'}
            </button>
          </div>
          <p style={{ fontSize: 12, color: 'var(--ink-soft)', marginTop: 6 }}>
            Links must start with https://. A YouTube link on the lesson plays inside the lesson page. Documents are linked, not uploaded.
          </p>
          {error && <p role="alert" style={{ fontSize: 12, color: 'var(--flag)', marginTop: 6 }}>{error}</p>}
        </div>
      )}
    </div>
  );
}
