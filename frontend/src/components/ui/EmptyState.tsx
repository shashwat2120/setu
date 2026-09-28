export function EmptyState({ title, description }: { title: string; description?: string }) {
  return (
    <div className="empty-state" role="status">
      <p style={{ fontFamily: 'var(--font-display)', fontSize: 18, fontWeight: 600, color: 'var(--ink)' }}>{title}</p>
      {description && <p style={{ marginTop: 6 }}>{description}</p>}
    </div>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="empty-state" role="alert">
      <p style={{ fontFamily: 'var(--font-display)', fontSize: 18, fontWeight: 600, color: 'var(--flag)' }}>Something went wrong</p>
      <p style={{ marginTop: 6 }}>{message}</p>
      {onRetry && (
        <button type="button" className="btn btn-ghost" style={{ marginTop: 14 }} onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  );
}

export function Skeleton({ height = 16, width = '100%' }: { height?: number; width?: number | string }) {
  return <div className="skeleton" style={{ height, width }} aria-hidden="true" />;
}
