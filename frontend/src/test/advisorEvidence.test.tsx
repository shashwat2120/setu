import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { apiStream } from '../api/client';
import { evidenceFor } from '../lib/advisorEvidence';
import StudentAdvisorPage from '../pages/student/StudentAdvisorPage';
import { expectAccessible, renderPage, signedInAs } from './helpers';

type Handlers = { onEvent: (event: string, data: unknown) => void };

function advisorSays(events: Array<[string, unknown]>) {
  vi.mocked(apiStream).mockImplementation((async (_path: string, _body: unknown, handlers: Handlers) => {
    for (const [event, data] of events) handlers.onEvent(event, data);
  }) as unknown as typeof apiStream);
}

async function ask(text = 'How am I doing?') {
  const user = userEvent.setup();
  const view = renderPage(<StudentAdvisorPage />);
  await user.type(screen.getByLabelText('Ask the AI Advisor'), text);
  await user.click(screen.getByRole('button', { name: 'Send' }));
  return view;
}

describe('what an advisor answer is based on', () => {
  it('turns each tool into a plain label, what it covers and a link to the same records, once each and in order', () => {
    const evidence = evidenceFor(['getStudentAttendance', 'getAssessmentResults', 'getStudentAttendance', 'searchCollegePolicy']);

    expect(evidence.map((e) => e.key)).toEqual(['getStudentAttendance', 'getAssessmentResults', 'searchCollegePolicy']);
    expect(evidence[0]).toMatchObject({ label: 'Your attendance records', to: '/app/student/attendance' });
    expect(evidence[1].to).toBe('/app/student/grades');
    expect(evidence[2].to).toBeNull();
  });

  it('never shows a raw function name for a tool it does not know', () => {
    const [e] = evidenceFor(['someFutureTool']);

    expect(e.label).toBe('Your records');
    expect(e.label).not.toContain('someFutureTool');
    expect(e.covers).not.toContain('someFutureTool');
  });

  it('shows "Based on" with a link the student can follow, and which model answered', async () => {
    signedInAs('STUDENT');
    advisorSays([['done', { answer: 'Your attendance is 33.3%.', sources: ['getStudentAttendance'], model: 'gemini-3.5-flash' }]]);
    const { container } = await ask();

    const heading = await screen.findByText('Based on');
    const block = heading.parentElement as HTMLElement;
    const link = within(block).getByRole('link', { name: 'Your attendance records' });
    expect(link.getAttribute('href')).toBe('/app/student/attendance');
    expect(block.textContent).toContain('across your enrolled sections');
    expect(block.textContent).toContain('Answered by gemini-3.5-flash.');
    await expectAccessible(container);
  });

  it('says plainly when an answer used none of the student\'s records', async () => {
    signedInAs('STUDENT');
    advisorSays([['done', { answer: 'Hello!', sources: [], model: 'm' }]]);
    await ask('Hi');

    expect(await screen.findByText(/did not use any of your records, so check any figure in it/)).toBeTruthy();
    expect(screen.queryByText('Based on')).toBeNull();
  });

  it('shows no evidence on an error, because there is no answer to ground', async () => {
    signedInAs('STUDENT');
    advisorSays([['error', { status: 503, reason: 'down' }]]);
    await ask();

    expect(await screen.findByText(/temporarily unavailable/)).toBeTruthy();
    expect(screen.queryByText('Based on')).toBeNull();
    expect(screen.queryByText(/did not use any of your records/)).toBeNull();
  });
});
