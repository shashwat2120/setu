import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { apiFetch, apiStream } from '../api/client';
import StudentAdvisorPage from '../pages/student/StudentAdvisorPage';
import { expectAccessible, renderPage, signedInAs } from './helpers';

type Handlers = { onEvent: (event: string, data: unknown) => void };

/** The advisor answers over a stream of events; a test plays one back. */
function advisorSays(events: Array<[string, unknown]>) {
  vi.mocked(apiStream).mockImplementation((async (_path: string, _body: unknown, handlers: Handlers) => {
    for (const [event, data] of events) handlers.onEvent(event, data);
  }) as unknown as typeof apiStream);
}

describe('AI advisor chat (spec section 13: forms and errors, live content)', () => {
  it('has no WCAG 2.2 AA violations an automated engine can find, before and after a question', async () => {
    signedInAs('STUDENT');
    advisorSays([['done', { answer: 'Your attendance is 50% (4 of 8 sessions).', sources: ['getStudentAttendance'], model: 'm' }]]);
    const user = userEvent.setup();
    const { container } = renderPage(<StudentAdvisorPage />);
    await expectAccessible(container);

    await user.type(screen.getByLabelText('Ask the AI Advisor'), 'How is my attendance?');
    await user.click(screen.getByRole('button', { name: 'Send' }));
    await screen.findByText(/Your attendance is 50%/);
    await expectAccessible(container);
  });

  it('names the message field with a label that is present for assistive technology, not hidden from it', () => {
    signedInAs('STUDENT');
    renderPage(<StudentAdvisorPage />);
    const label = document.querySelector('label[for="advisor-message"]') as HTMLElement;
    expect(label.textContent).toBe('Ask the AI Advisor');
    expect(label.style.display).not.toBe('none');
    expect(label.classList.contains('visually-hidden')).toBe(true);
    expect(screen.getByLabelText('Ask the AI Advisor')).toBe(document.getElementById('advisor-message'));
  });

  it('puts the conversation in a polite live region, so a new answer is announced when it arrives', async () => {
    signedInAs('STUDENT');
    advisorSays([['done', { answer: 'Your attendance is 50%.', sources: [], model: 'm' }]]);
    const user = userEvent.setup();
    renderPage(<StudentAdvisorPage />);

    const log = screen.getByRole('log', { name: 'Conversation with the AI Advisor' });
    expect(log.getAttribute('aria-live')).toBe('polite');

    await user.type(screen.getByLabelText('Ask the AI Advisor'), 'How is my attendance?');
    await user.keyboard('{Enter}');

    await waitFor(() => expect(within(log).getByText('Your attendance is 50%.')).toBeTruthy());
    expect(within(log).getByText('How is my attendance?')).toBeTruthy();
  });

  it('says what the advisor is doing while it works, as a status, in plain words', async () => {
    signedInAs('STUDENT');
    let release: () => void = () => {};
    vi.mocked(apiStream).mockImplementation((async (_p: string, _b: unknown, handlers: Handlers) => {
      handlers.onEvent('tool', { name: 'getStudentAttendance' });
      await new Promise<void>((resolve) => { release = resolve; });
      handlers.onEvent('done', { answer: 'Done.', sources: [], model: 'm' });
    }) as unknown as typeof apiStream);
    const user = userEvent.setup();
    renderPage(<StudentAdvisorPage />);

    await user.type(screen.getByLabelText('Ask the AI Advisor'), 'Attendance?');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    expect((await screen.findByRole('status')).textContent).toBe('Checking your attendance…');
    expect(screen.getByRole('log').getAttribute('aria-busy')).toBe('true');
    release();
    await waitFor(() => expect(screen.getByRole('log').getAttribute('aria-busy')).toBe('false'));
  });

  it('tells the student in words, inside the conversation, when the advisor is unavailable, and lets them try again', async () => {
    signedInAs('STUDENT');
    advisorSays([['error', { status: 503, reason: '' }]]);
    const user = userEvent.setup();
    renderPage(<StudentAdvisorPage />);

    await user.type(screen.getByLabelText('Ask the AI Advisor'), 'Hello?');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    const log = screen.getByRole('log');
    await waitFor(() => expect(log.textContent).toContain('temporarily unavailable'));
    expect((screen.getByLabelText('Ask the AI Advisor') as HTMLInputElement).disabled).toBe(false);
  });

  it('offers its suggestions as real buttons the keyboard can reach, and clears the conversation on request', async () => {
    signedInAs('STUDENT');
    advisorSays([['done', { answer: 'An answer.', sources: [], model: 'm' }]]);
    vi.mocked(apiFetch).mockResolvedValue(undefined as never);
    const user = userEvent.setup();
    renderPage(<StudentAdvisorPage />);

    await user.tab();
    await user.tab();   // past "New conversation" (disabled until there is one) to the first suggestion
    const suggestion = document.activeElement as HTMLElement;
    expect(suggestion.tagName).toBe('BUTTON');
    await user.keyboard('{Enter}');
    await screen.findByText('An answer.');

    await user.click(screen.getByRole('button', { name: 'New conversation' }));
    expect(screen.queryByText('An answer.')).toBeNull();
    expect(vi.mocked(apiFetch)).toHaveBeenCalledWith('/ai/chat/history', { method: 'DELETE' });
  });
});
