import { useEffect, useRef, useState } from 'react';
import { apiFetch, apiStream, ApiError } from '../../api/client';
import type { AiChatResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';

interface ChatTurn {
  role: 'user' | 'assistant';
  text: string;
  sources?: string[];
  model?: string;
  error?: boolean;
  /** Still receiving tokens. */
  streaming?: boolean;
}

/** Plain-language labels for the raw tool names the backend reports while it works. */
const TOOL_LABELS: Record<string, string> = {
  getStudentAttendance: 'Checking your attendance',
  getAcademicProgress: 'Looking at your academic progress',
  getAssessmentResults: 'Looking at your assessment results',
  getUpcomingDeadlines: 'Checking your upcoming deadlines',
  getSkillGap: 'Comparing your skills with job requirements',
  searchCollegePolicy: 'Searching college policy',
  recommendLearningResources: 'Finding learning resources',
};

const SUGGESTIONS = [
  'How is my attendance across my courses?',
  'What assignments or assessments are due soon?',
  'What is my current academic progress?',
  'What does the college policy say about attendance?',
];

function errorText(status: number, message: string): string {
  if (status === 429) return 'The AI advisor is receiving a lot of requests right now — please try again shortly.';
  if (status === 503) return 'The AI advisor is temporarily unavailable. Please try again in a moment.';
  return message || 'Something went wrong.';
}

export default function StudentAdvisorPage() {
  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const [message, setMessage] = useState('');
  const [sending, setSending] = useState(false);
  const [activity, setActivity] = useState<string | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView?.({ block: 'end' });
  }, [turns, activity]);

  useEffect(() => () => abortRef.current?.abort(), []);

  /** Patches the assistant turn currently being streamed (always the last one). */
  const patchLast = (patch: (turn: ChatTurn) => ChatTurn) =>
    setTurns((t) => (t.length === 0 ? t : [...t.slice(0, -1), patch(t[t.length - 1])]));

  const send = async (override?: string) => {
    const text = (override ?? message).trim();
    if (!text || sending) return;
    setTurns((t) => [...t, { role: 'user', text }, { role: 'assistant', text: '', streaming: true }]);
    setMessage('');
    setSending(true);
    setActivity('Thinking');
    const controller = new AbortController();
    abortRef.current = controller;
    let finished = false;
    try {
      await apiStream('/ai/chat/stream', { message: text }, {
        onEvent: (event, data) => {
          const d = data as Record<string, unknown>;
          if (event === 'tool') {
            const name = String(d.name ?? '');
            setActivity(TOOL_LABELS[name] ?? 'Looking up your records');
          } else if (event === 'token') {
            setActivity(null);
            patchLast((turn) => ({ ...turn, text: turn.text + String(d.text ?? '') }));
          } else if (event === 'done') {
            finished = true;
            const result = data as AiChatResponse;
            patchLast((turn) => ({ ...turn, text: result.answer, sources: result.sources, model: result.model, streaming: false }));
          } else if (event === 'error') {
            finished = true;
            patchLast((turn) => ({
              ...turn,
              text: errorText(Number(d.status ?? 503), String(d.reason ?? '')),
              streaming: false,
              error: true,
              sources: undefined,
            }));
          }
        },
      }, controller.signal);
      if (!finished) {
        patchLast((turn) => ({ ...turn, text: turn.text || errorText(503, ''), streaming: false, error: !turn.text }));
      }
    } catch (e) {
      if (controller.signal.aborted) return;
      const msg = e instanceof ApiError ? errorText(e.status, e.message) : 'Something went wrong.';
      patchLast((turn) => ({ ...turn, text: msg, streaming: false, error: true }));
    } finally {
      setSending(false);
      setActivity(null);
    }
  };

  const newConversation = async () => {
    abortRef.current?.abort();
    setTurns([]);
    setActivity(null);
    setSending(false);
    try {
      await apiFetch<void>('/ai/chat/history', { method: 'DELETE' });
    } catch {
      // the on-screen conversation is already cleared; a failed server-side clear only means older context may linger until it expires
    }
  };

  return (
    <div>
      <PageHeader title="AI Advisor" subtitle="Grounded in your own real records — attendance, grades, assignments, and skills — never invented." />

      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 8 }}>
        <button
          type="button"
          className="btn btn-ghost"
          style={{ fontSize: 13, padding: '6px 12px' }}
          onClick={newConversation}
          disabled={turns.length === 0}
        >
          New conversation
        </button>
      </div>

      <div className="card" style={{ minHeight: 320, display: 'flex', flexDirection: 'column', gap: 14, marginBottom: 16 }}>
        {turns.length === 0 && (
          <div>
            <p style={{ color: 'var(--ink-soft)', fontSize: 14, marginBottom: 12 }}>
              Ask about your attendance, grades, upcoming deadlines, skill gaps, or college policy. The advisor answers only from data you're authorized to see, and remembers the last few questions of this conversation.
            </p>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
              {SUGGESTIONS.map((s) => (
                <button key={s} type="button" className="btn btn-ghost" style={{ fontSize: 13, padding: '6px 12px' }} onClick={() => send(s)} disabled={sending}>
                  {s}
                </button>
              ))}
            </div>
          </div>
        )}
        {/* the conversation is a live region: a new answer is announced when it arrives, and not word by word while it is still being written */}
        <div role="log" aria-label="Conversation with the AI Advisor" aria-live="polite" aria-relevant="additions text" aria-busy={sending} style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        {turns.map((turn, i) => {
          if (turn.role === 'assistant' && turn.streaming && !turn.text) return null; // shown as the activity line instead
          return (
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
                {turn.streaming && <span aria-hidden="true"> ▍</span>}
              </div>
              {turn.sources && turn.sources.length > 0 && (
                <p style={{ fontSize: 11, color: 'var(--ink-soft)', marginTop: 4 }}>
                  Sources: {turn.sources.join(', ')}
                </p>
              )}
            </div>
          );
        })}
        </div>
        {activity && (
          <div role="status" style={{ alignSelf: 'flex-start', fontSize: 13, color: 'var(--ink-soft)' }}>{activity}…</div>
        )}
        <div ref={bottomRef} />
      </div>

      <form
        onSubmit={(e) => { e.preventDefault(); send(); }}
        style={{ display: 'flex', gap: 8 }}
      >
        <label htmlFor="advisor-message" className="visually-hidden">Ask the AI Advisor</label>
        <input
          id="advisor-message"
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          placeholder="Ask about your attendance, grades, or upcoming deadlines…"
          disabled={sending}
          maxLength={2000}
          style={{ flex: 1, fontSize: 14, padding: '10px 14px', border: '1px solid var(--rule)', borderRadius: 4, background: 'var(--bg-elev)', color: 'var(--ink)' }}
        />
        <button type="submit" className="btn btn-primary" style={{ padding: '10px 20px', fontSize: 14 }} disabled={sending || !message.trim()}>
          {sending ? 'Sending…' : 'Send'}
        </button>
      </form>
    </div>
  );
}
