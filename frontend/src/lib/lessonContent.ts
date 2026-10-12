/** Helpers for lesson content references: real https links, with YouTube addresses shown as an embedded player. */

const YOUTUBE_HOSTS = new Set(['youtube.com', 'www.youtube.com', 'm.youtube.com', 'music.youtube.com', 'youtu.be', 'www.youtu.be']);
const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;

/** True only for a well-formed https:// address, the only kind rendered as a clickable link. */
export function isHttpsUrl(ref: string | null | undefined): boolean {
  if (!ref) return false;
  try {
    return new URL(ref).protocol === 'https:';
  } catch {
    return false;
  }
}

/** The 11-character video id of a YouTube watch / share / embed / shorts address, or null for anything else. */
export function youtubeId(ref: string | null | undefined): string | null {
  if (!ref || !isHttpsUrl(ref)) return null;
  const url = new URL(ref);
  const host = url.hostname.toLowerCase();
  if (!YOUTUBE_HOSTS.has(host)) return null;
  let id: string | null = null;
  if (host.endsWith('youtu.be')) {
    id = url.pathname.split('/')[1] ?? null;
  } else if (url.pathname === '/watch') {
    id = url.searchParams.get('v');
  } else {
    const m = /^\/(embed|shorts|live)\/([^/?#]+)/.exec(url.pathname);
    id = m ? m[2] : null;
  }
  return id && VIDEO_ID.test(id) ? id : null;
}

/** Privacy-enhanced embed address (no tracking cookies until the viewer presses play). */
export function youtubeEmbedUrl(id: string): string {
  return `https://www.youtube-nocookie.com/embed/${id}`;
}

/** A short readable label for a link with no title, e.g. "docs.python.org/3/tutorial/introduction.html". */
export function displayRef(ref: string): string {
  try {
    const u = new URL(ref);
    if (u.protocol === 'https:') return `${u.hostname}${u.pathname === '/' ? '' : u.pathname}`;
  } catch {
    /* fall through to the raw reference */
  }
  return ref;
}
