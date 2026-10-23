import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { apiStream } from '../api/client';
import { ChatMarkdown } from '../components/ChatMarkdown';
import { followUpsFor } from '../lib/advisorFollowUps';
import { inlineText, parseChatMarkdown, safeHref } from '../lib/chatMarkdown';
import StudentAdvisorPage from '../pages/student/StudentAdvisorPage';
import { expectAccessible, renderPage, signedInAs } from './helpers';

type Handlers = { onEvent: (event: string, data: unknown) => void };

function advisorSays(events: Array<[string, unknown]>) {
  vi.mocked(apiStream).mockImplementation((async (_path: string, _body: unknown, handlers: Handlers) => {
    for (const [event, data] of events) handlers.onEvent(event, data);
  }) as unknown as typeof apiStream);
}

describe('the advisor answer reader (lib/chatMarkdown)', () => {
  it('reads paragraphs, bold figures, emphasis and code', () => {
    const [p] = parseChatMarkdown('Your attendance is **74.1%** across *3 courses*, see `CS101`.');
    expect(p.t).toBe('p');
    const kinds = (p as { c: { t: string }[] }).c.map((n) => n.t);
    expect(kinds).toEqual(['text', 'strong', 'text', 'em', 'text', 'code', 'text']);
  });

  it('reads bullet and numbered lists, including a nested list and a numbered list that starts later', () => {
    const blocks = parseChatMarkdown('- one\n- two\n  - nested\n\n3. third\n4. fourth');
    expect(blocks).toHaveLength(2);
    const bullets = blocks[0] as { t: 'list'; items: { c: unknown[]; children: { t: string }[] }[] };
    expect(bullets.items).toHaveLength(2);
    expect(bullets.items[1].children[0].t).toBe('list');
    const numbered = blocks[1] as { t: 'list'; ordered: boolean; start: number };
    expect(numbered.ordered).toBe(true);
    expect(numbered.start).toBe(3);
  });

  it('reads a table with its alignment, and fits a short row to the header instead of dropping it', () => {
    const [table] = parseChatMarkdown('| Course | Present | % |\n|:--|--:|:-:|\n| CS101 | 2 | 7.4 |\n| CS201 | 5 |');
    expect(table.t).toBe('table');
    const t = table as { head: unknown[][]; align: (string | null)[]; rows: unknown[][][] };
    expect(t.head).toHaveLength(3);
    expect(t.align).toEqual(['left', 'right', 'center']);
    expect(t.rows).toHaveLength(2);
    expect(t.rows[1]).toHaveLength(3);
  });

  it('shows a table that has not finished arriving as plain text, then as a table once its separator comes', () => {
    expect(parseChatMarkdown('| Course | Present |')[0].t).toBe('p');
    expect(parseChatMarkdown('| Course | Present |\n|---|---|\n| CS101 | 2 |')[0].t).toBe('table');
  });

  it('does not mistake a rule under a line with a pipe in it for a table', () => {
    expect(parseChatMarkdown('a | b\n---').map((b) => b.t)).toEqual(['p', 'hr']);
  });

  it('keeps an unclosed marker as the text it is and never throws on half-written input', () => {
    for (const partial of ['Your attendance is **74', 'a `code', 'see [the page](/app/stu', '* ', '#', '|', '```js\nlet x', '> ', '1.']) {
      expect(() => parseChatMarkdown(partial)).not.toThrow();
    }
    const [p] = parseChatMarkdown('Your attendance is **74');
    expect(inlineText((p as unknown as { c: never[] }).c)).toBe('Your attendance is **74');
  });

  it('leaves snake_case and a lone asterisk alone', () => {
    const [p] = parseChatMarkdown('the student_id field and 5 * 3 = 15');
    expect(inlineText((p as unknown as { c: never[] }).c)).toBe('the student_id field and 5 * 3 = 15');
  });

  it('allows links only to the app\'s own pages and https addresses', () => {
    expect(safeHref('/app/student/grades')).toEqual({ href: '/app/student/grades', internal: true });
    expect(safeHref('https://example.com/policy')).toEqual({ href: 'https://example.com/policy', internal: false });
    for (const bad of ['javascript:alert(1)', 'http://example.com', '//evil.example', '/app/../admin', 'data:text/html,x', '/other', ' vbscript:x']) {
      expect(safeHref(bad), bad).toBeNull();
    }
  });
});

describe('the formatted answer on the page', () => {
  it('draws a table, a list and bold text as elements, and never as markup from the model', () => {
    const text = [
      'Your attendance is **74.1%**.',
      '',
      '| Course | Present | Total |',
      '|---|--:|--:|',
      '| CS101 | 2 | 27 |',
      '',
      '- first point',
      '- second point',
      '',
      '<img src=x onerror=alert(1)> and <script>alert(2)</script>',
    ].join('\n');
    const { container } = renderPage(<ChatMarkdown text={text} />);

    expect(container.querySelector('strong')?.textContent).toBe('74.1%');
    expect(container.querySelectorAll('table th')).toHaveLength(3);
    expect(container.querySelector('tbody td')?.textContent).toBe('CS101');
    expect(container.querySelectorAll('ul li')).toHaveLength(2);
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('script')).toBeNull();
    expect(container.textContent).toContain('<img src=x onerror=alert(1)>');
  });

  it('turns an app link into a router link and refuses a script link', () => {
    const { container } = renderPage(<ChatMarkdown text="See [your grades](/app/student/grades) or [this](javascript:alert(1))." />);
    const links = container.querySelectorAll('a');
    expect(links).toHaveLength(1);
    expect(links[0].getAttribute('href')).toBe('/app/student/grades');
    expect(container.textContent).toContain('this');
  });

  it('renders the advisor\'s answer formatted in the conversation, with copy and follow-up questions that really ask', async () => {
    signedInAs('STUDENT');
    advisorSays([['done', {
      answer: 'Your attendance is **50%**.\n\n| Course | Present |\n|---|--:|\n| CS101 | 4 |\n\n1. Attend the next class',
      sources: ['getStudentAttendance'],
      model: 'm',
    }]]);
    const user = userEvent.setup();
    const { container } = renderPage(<StudentAdvisorPage />);

    await user.type(screen.getByLabelText('Ask the AI Advisor'), 'How is my attendance?');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    const log = screen.getByRole('log');
    await waitFor(() => expect(within(log).getByText('50%').tagName).toBe('STRONG'));
    expect(container.querySelector('table')).not.toBeNull();
    expect(container.querySelector('ol li')?.textContent).toBe('Attend the next class');
    expect(screen.getByRole('button', { name: 'Copy answer' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();

    advisorSays([['done', { answer: 'CS101 is lowest.', sources: [], model: 'm' }]]);
    await user.click(screen.getByRole('button', { name: 'Which of my courses has the lowest attendance?' }));
    await waitFor(() => expect(within(log).getByText('CS101 is lowest.')).toBeTruthy());
    expect(vi.mocked(apiStream)).toHaveBeenLastCalledWith('/ai/chat/stream', { message: 'Which of my courses has the lowest attendance?' }, expect.anything(), expect.anything());
    await expectAccessible(container);
  });

  it('asks the last question again in place of its answer when "Try again" is chosen', async () => {
    signedInAs('STUDENT');
    advisorSays([['done', { answer: 'First answer.', sources: [], model: 'm' }]]);
    const user = userEvent.setup();
    renderPage(<StudentAdvisorPage />);
    await user.type(screen.getByLabelText('Ask the AI Advisor'), 'What is due?');
    await user.click(screen.getByRole('button', { name: 'Send' }));
    await screen.findByText('First answer.');

    advisorSays([['done', { answer: 'Second answer.', sources: [], model: 'm' }]]);
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    await screen.findByText('Second answer.');
    expect(screen.queryByText('First answer.')).toBeNull();
    expect(screen.getAllByText('What is due?')).toHaveLength(1);
  });
});

describe('follow-up questions (lib/advisorFollowUps)', () => {
  it('come from the records the answer was based on, up to three, and never repeat a question already asked', () => {
    const next = followUpsFor(['getStudentAttendance', 'getFeeStatus'], ['Which of my courses has the lowest attendance?']);
    expect(next).toHaveLength(3);
    expect(next).not.toContain('Which of my courses has the lowest attendance?');
    expect(next[0]).toBe('What does the college policy say about attendance?');
  });

  it('offer nothing for an answer that used no records or a source the page does not know', () => {
    expect(followUpsFor([], [])).toEqual([]);
    expect(followUpsFor(undefined, [])).toEqual([]);
    expect(followUpsFor(['someFutureTool'], [])).toEqual([]);
  });
});
