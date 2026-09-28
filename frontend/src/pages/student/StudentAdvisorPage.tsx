import { useState } from 'react';
import { apiFetch, ApiError } from '../../api/client';
import type { AiChatResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';

interface ChatTurn {
  role: 'user' | 'assistant';
  text: string;
  sources?: string[];
  model?: string;
  error?: boolean;
}

export default function StudentAdvisorPage() {
  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const [message, setMessage] = useState('');
  const [sending, setSending] = useState(false);

  const send = async () => {
    const text = message.trim();
    if (!text) return;
    setTurns((t) => [...t, { role: 'user', text }]);
    setMessage('');
    setSending(true);
    try {
      const result = await apiFetch<AiChatResponse>('/ai/chat', { method: 'POST', body: { message: text } });
      setTurns((t) => [...t, { role: 'assistant', text: result.answer, sources: result.sources, model: result.model }]);
    } catch (e) {
      const msg = e instanceof ApiError
        ? (e.status === 429 ? 'The AI advisor is receiving a lot of requests right now — please try again shortly.'
          : e.status === 503 ? 'The AI advisor is temporarily unavailable. Please try again in a moment.'
          : e.message || 'Something went wrong.')
        : 'Something went wrong.';
      setTurns((t) => [...t, { role: 'assistant', text: msg, error: true }]);
    } finally {
      setSending(false);
    }
  };

  return (
    <div>
      <PageHeader title="AI Advisor" subtitle="Grounded in your own real records — attendance, grades, assignments, and skills — never invented." />

      <div className="card" style={{ minHeight: 320, display: 'flex', flexDirection: 'column', gap: 14, marginBottom: 16 }}>
        {turns.length === 0 && (
          <p style={{ color: 'var(--ink-soft)', fontSize: 14 }}>
            Ask about your attendance, grades, upcoming deadlines, skill gaps, or college policy. The advisor answers only from data you're authorized to see.
          </p>
        )}
        {turns.map((turn, i) => (
          <div key={i} style={{ alignSelf: turn.role === 'user' ? 'flex-end' : 'flex-start', maxWidth: '80%' }}>
            <div
              style={{
                padding: '10px 14px',
                borderRadius: 8,
                background: turn.role === 'user' ? 'var(--accent)' : turn.error ? 'transparent' : 'var(--bg-elev)',
                color: turn.role === 'user' ? 'var(--accent-on)' : turn.error ? 'var(--flag)' : 'var(--ink)',
                border: turn.error ? '1px solid var(--flag)' : turn.role === 'assistant' ? '1px solid var(--rule)' : 'none',
                fontSize: 14,
                whiteSpace: 'pre-wrap',
              }}
            >
              {turn.text}
            </div>
            {turn.sources && turn.sources.length > 0 && (
              <p style={{ fontSize: 11, color: 'var(--ink-soft)', marginTop: 4 }}>
                Sources: {turn.sources.join(', ')}
              </p>
            )}
          </div>
        ))}
        {sending && <div style={{ alignSelf: 'flex-start', fontSize: 13, color: 'var(--ink-soft)' }}>Thinking…</div>}
      </div>

      <form
        onSubmit={(e) => { e.preventDefault(); send(); }}
        style={{ display: 'flex', gap: 8 }}
      >
        <label htmlFor="advisor-message" style={{ display: 'none' }}>Ask the AI Advisor</label>
        <input
          id="advisor-message"
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          placeholder="Ask about your attendance, grades, or upcoming deadlines…"
          disabled={sending}
          style={{ flex: 1, fontSize: 14, padding: '10px 14px', border: '1px solid var(--rule)', borderRadius: 4, background: 'var(--bg-elev)', color: 'var(--ink)' }}
        />
        <button type="submit" className="btn btn-primary" style={{ padding: '10px 20px', fontSize: 14 }} disabled={sending || !message.trim()}>
          {sending ? 'Sending…' : 'Send'}
        </button>
      </form>
    </div>
  );
}
