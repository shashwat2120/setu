import { useEffect, useRef, useState } from 'react';
import { apiFetch, apiStream, ApiError } from '../../api/client';
import type { AiChatResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';
import { AdvisorEvidence } from '../../components/AdvisorEvidence';
import { ChatMarkdown } from '../../components/ChatMarkdown';
import { followUpsFor } from '../../lib/advisorFollowUps';

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
  getTranscript: 'Reading your transcript',
  getFeeStatus: 'Checking your fees',
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

/** The advisor's mark beside its answers: decorative, the words carry the meaning. */
function Avatar() {
  return <span className="adv-avatar" aria-hidden="true">S</span>;
}

export default function StudentAdvisorPage() {
  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const [message, setMessage] = useState('');
  const [sending, setSending] = useState(false);
  const [activity, setActivity] = useState<string | null>(null);
  const [copied, setCopied] = useState<number | null>(null);
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

  /** Asks the last question again, in place of its answer. */
  const retry = () => {
    const lastQuestion = [...turns].reverse().find((t) => t.role === 'user')?.text;
    if (!lastQuestion || sending) return;
    setTurns((t) => t.slice(0, -2));
    void send(lastQuestion);
  };

  const copy = async (index: number, text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(index);
      window.setTimeout(() => setCopied((c) => (c === index ? null : c)), 2000);
    } catch {
      // the browser refused (no permission, or not a secure page): the answer is still on screen to select
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

  const last = turns.length - 1;
  const asked = turns.filter((t) => t.role === 'user').map((t) => t.text);

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

      <div className="card adv-chat">
        {turns.length === 0 && (
          <div className="adv-welcome">
            <Avatar />
            <div>
              <p className="adv-welcome-title">Hi, I'm your Setu advisor.</p>
              <p className="adv-welcome-text">
                Ask about your attendance, grades, upcoming deadlines, skill gaps, or college policy. I answer only from data you're authorized to see, and I remember the last few questions of this conversation.
              </p>
              <div className="adv-chips">
                {SUGGESTIONS.map((s) => (
                  <button key={s} type="button" className="adv-chip" onClick={() => send(s)} disabled={sending}>
                    {s}
                  </button>
                ))}
              </div>
            </div>
          </div>
        )}
        {/* the conversation is a live region: a new answer is announced when it arrives, and not word by word while it is still being written */}
        <div role="log" aria-label="Conversation with the AI Advisor" aria-live="polite" aria-relevant="additions text" aria-busy={sending} className="adv-log">
          {turns.map((turn, i) => {
            if (turn.role === 'assistant' && turn.streaming && !turn.text) return null; // shown as the typing indicator instead
            if (turn.role === 'user') {
              return (
                <div key={i} className="adv-row adv-row--user">
                  <div className="adv-bubble adv-bubble--user">{turn.text}</div>
                </div>
              );
            }
            const done = !turn.streaming && !turn.error;
            const followUps = i === last && done && !sending ? followUpsFor(turn.sources, asked) : [];
            return (
              <div key={i} className="adv-row adv-row--assistant">
                <Avatar />
                <div className="adv-body">
                  <div className={`adv-bubble adv-bubble--assistant${turn.error ? ' adv-bubble--error' : ''}`}>
                    {turn.error ? turn.text : <ChatMarkdown text={turn.text} />}
                    {turn.streaming && <span className="adv-cursor" aria-hidden="true" />}
                  </div>
                  {done && <AdvisorEvidence sources={turn.sources} model={turn.model} />}
                  {(done || turn.error) && (
                    <div className="adv-actions">
                      {done && (
                        <button type="button" className="adv-action" onClick={() => copy(i, turn.text)} aria-label="Copy answer">
                          {copied === i ? 'Copied' : 'Copy'}
                        </button>
                      )}
                      {i === last && !sending && (
                        <button type="button" className="adv-action" onClick={retry}>Try again</button>
                      )}
                    </div>
                  )}
                  {followUps.length > 0 && (
                    <div className="adv-followups">
                      <span className="adv-followups-label" id={`followups-${i}`}>You could ask next</span>
                      <div className="adv-chips" role="group" aria-labelledby={`followups-${i}`}>
                        {followUps.map((q) => (
                          <button key={q} type="button" className="adv-chip" onClick={() => send(q)}>{q}</button>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
        {activity && (
          <div className="adv-row adv-row--assistant">
            <Avatar />
            <div className="adv-bubble adv-bubble--assistant adv-typing">
              <span className="adv-dots" aria-hidden="true"><i /><i /><i /></span>
              <span role="status">{activity}…</span>
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      <form onSubmit={(e) => { e.preventDefault(); send(); }} className="adv-form">
        <label htmlFor="advisor-message" className="visually-hidden">Ask the AI Advisor</label>
        <input
          id="advisor-message"
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          placeholder="Ask about your attendance, grades, or upcoming deadlines…"
          disabled={sending}
          maxLength={2000}
          autoComplete="off"
        />
        <button type="submit" className="btn btn-primary" style={{ padding: '10px 20px', fontSize: 14 }} disabled={sending || !message.trim()}>
          {sending ? 'Sending…' : 'Send'}
        </button>
      </form>
    </div>
  );
}
