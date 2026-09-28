import { useState } from 'react';

/**
 * A button that performs a real backend action, with a native confirm prompt in front of anything destructive
 * or hard to undo, and a disabled/"working…" state so double-submit can't fire the same request twice.
 */
export function ConfirmActionButton({
  label, confirmMessage, onConfirm, variant = 'ghost', disabled,
}: {
  label: string;
  confirmMessage?: string;
  onConfirm: () => Promise<void>;
  variant?: 'ghost' | 'primary' | 'danger';
  disabled?: boolean;
}) {
  const [busy, setBusy] = useState(false);

  const handleClick = async () => {
    if (confirmMessage && !window.confirm(confirmMessage)) return;
    setBusy(true);
    try {
      await onConfirm();
    } finally {
      setBusy(false);
    }
  };

  return (
    <button
      type="button"
      className={`btn btn-${variant}`}
      style={{ padding: '8px 14px', fontSize: 13 }}
      disabled={disabled || busy}
      onClick={handleClick}
    >
      {busy ? 'Working…' : label}
    </button>
  );
}
