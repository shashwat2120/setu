import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import LoginPage from '../pages/LoginPage';
import { expectAccessible, renderPage, signedOut } from './helpers';

describe('sign-in page (spec section 13: authentication accessibility, forms and errors)', () => {
  it('has no WCAG 2.2 AA violations an automated engine can find', async () => {
    signedOut();
    const { container } = renderPage(<LoginPage />);
    await expectAccessible(container);
  });

  it('gives every field a visible label tied to it, so a screen reader announces what to type', () => {
    signedOut();
    renderPage(<LoginPage />);
    expect(screen.getByLabelText('Email')).toHaveProperty('type', 'email');
    expect(screen.getByLabelText('Password')).toHaveProperty('type', 'password');
    expect(screen.getByLabelText('Email').getAttribute('autocomplete')).toBe('username');
    expect(screen.getByLabelText('Password').getAttribute('autocomplete')).toBe('current-password');
  });

  it('can be completed with the keyboard alone, in reading order', async () => {
    signedOut();
    const user = userEvent.setup();
    renderPage(<LoginPage />);
    await user.tab();
    expect(screen.getByLabelText('Email')).toBe(document.activeElement);
    await user.tab();
    expect(screen.getByLabelText('Password')).toBe(document.activeElement);
    await user.tab();
    expect(screen.getByRole('button', { name: 'Sign in' })).toBe(document.activeElement);
  });

  it('announces a failed sign-in as an alert and ties it to both fields, without saying which one was wrong', async () => {
    const login = vi.fn().mockRejectedValue(new ApiError(401, 'bad credentials'));
    vi.mocked(useAuth).mockReturnValue({ user: null, loading: false, login, loginWithPasskey: vi.fn(), logout: vi.fn() });
    const user = userEvent.setup();
    const { container } = renderPage(<LoginPage />);

    await user.type(screen.getByLabelText('Email'), 'someone@example.test');
    await user.type(screen.getByLabelText('Password'), 'not-the-password');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('Incorrect email or password.');
    expect(screen.getByLabelText('Email').getAttribute('aria-describedby')).toBe(alert.id);
    expect(screen.getByLabelText('Password').getAttribute('aria-describedby')).toBe(alert.id);
    expect(screen.getByLabelText('Email').getAttribute('aria-invalid')).toBe('true');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).toBeTruthy());
    await expectAccessible(container);
  });

  it('says why a locked account is locked and does not leave the person guessing', async () => {
    const login = vi.fn().mockRejectedValue(new ApiError(423, 'locked'));
    vi.mocked(useAuth).mockReturnValue({ user: null, loading: false, login, loginWithPasskey: vi.fn(), logout: vi.fn() });
    const user = userEvent.setup();
    renderPage(<LoginPage />);
    await user.type(screen.getByLabelText('Email'), 'someone@example.test');
    await user.type(screen.getByLabelText('Password'), 'x');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    expect((await screen.findByRole('alert')).textContent).toContain('temporarily locked');
  });
});
