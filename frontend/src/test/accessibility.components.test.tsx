import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ConfirmActionButton } from '../components/ui/ConfirmActionButton';
import { DataTable, type Column } from '../components/ui/DataTable';
import { EmptyState, ErrorState, Skeleton } from '../components/ui/EmptyState';
import { prefersReducedMotion } from '../lib/gsap';
import { expectAccessible, reduceMotion } from './helpers';

interface Row { id: string; name: string }
const columns: Column<Row>[] = [{ key: 'name', header: 'Name', render: (r) => r.name }];
const rows: Row[] = [{ id: '1', name: 'Ananya' }, { id: '2', name: 'Priya' }];

describe('shared table (spec section 13: dialogs, tables, keyboard and focus)', () => {
  it('is accessible as a plain data table, with column headers scoped to their columns', async () => {
    const { container } = render(<DataTable columns={columns} rows={rows} loading={false} error={null} emptyTitle="None" rowKey={(r) => r.id} />);
    expect(screen.getAllByRole('columnheader')[0].getAttribute('scope')).toBe('col');
    await expectAccessible(container);
  });

  it('lets a clickable row be reached and activated from the keyboard (Enter and Space), not only by mouse', async () => {
    const open = vi.fn();
    const user = userEvent.setup();
    render(<DataTable columns={columns} rows={rows} loading={false} error={null} emptyTitle="None" rowKey={(r) => r.id} onRowClick={open} />);
    await user.tab();
    const first = document.activeElement as HTMLElement;
    expect(first.textContent).toContain('Ananya');
    await user.keyboard('{Enter}');
    await user.tab();
    await user.keyboard(' ');
    expect(open).toHaveBeenCalledTimes(2);
    expect(open.mock.calls.map((c) => (c[0] as Row).name)).toEqual(['Ananya', 'Priya']);
  });

  it('is still accessible when its rows are clickable', async () => {
    const { container } = render(<DataTable columns={columns} rows={rows} loading={false} error={null} emptyTitle="None" rowKey={(r) => r.id} onRowClick={() => {}} />);
    await expectAccessible(container);
  });

  it('announces an empty table and an error as a status and an alert, and offers a retry the keyboard can reach', async () => {
    const retry = vi.fn();
    const user = userEvent.setup();
    const { container } = render(
      <>
        <EmptyState title="No records" description="Nothing yet." />
        <ErrorState message="Could not load this data right now." onRetry={retry} />
      </>,
    );
    expect(screen.getByRole('status').textContent).toContain('No records');
    expect(screen.getByRole('alert').textContent).toContain('Could not load');
    await user.tab();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Try again' }));
    await user.keyboard('{Enter}');
    expect(retry).toHaveBeenCalledOnce();
    await expectAccessible(container);
  });

  it('hides loading placeholders from assistive technology instead of reading out empty boxes', () => {
    render(<Skeleton height={20} />);
    expect(document.querySelector('.skeleton')!.getAttribute('aria-hidden')).toBe('true');
  });
});

describe('confirm prompt before something hard to undo', () => {
  it('asks first, and does nothing when the person says no', async () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    const user = userEvent.setup();
    render(<ConfirmActionButton label="Delete" confirmMessage="Really delete this?" onConfirm={onConfirm} variant="danger" />);
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    expect(window.confirm).toHaveBeenCalledWith('Really delete this?');
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('acts once on yes, and cannot be pressed again while it works', async () => {
    let finish: () => void = () => {};
    const onConfirm = vi.fn(() => new Promise<void>((resolve) => { finish = resolve; }));
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const user = userEvent.setup();
    const { container } = render(<ConfirmActionButton label="Close session" confirmMessage="Close?" onConfirm={onConfirm} />);
    await user.click(screen.getByRole('button', { name: 'Close session' }));
    const working = screen.getByRole('button', { name: 'Working…' });
    expect((working as HTMLButtonElement).disabled).toBe(true);
    await user.click(working);
    expect(onConfirm).toHaveBeenCalledOnce();
    finish();
    await expectAccessible(container);
  });
});

describe('reduced motion (spec section 13)', () => {
  it('is detected from the system preference', () => {
    reduceMotion(false);
    expect(prefersReducedMotion()).toBe(false);
    reduceMotion(true);
    expect(prefersReducedMotion()).toBe(true);
  });
});
