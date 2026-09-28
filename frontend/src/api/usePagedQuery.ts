import { useState } from 'react';
import { useApiQuery } from './useApiQuery';
import type { PageResponse } from './types';

/** For endpoints returning the shared `{content, page, size, totalElements, totalPages}` envelope. */
export function usePagedQuery<T>(basePath: string | null, extraParams: Record<string, string> = {}) {
  const [page, setPage] = useState(0);
  const params = new URLSearchParams({ page: String(page), size: '20', ...extraParams });
  const path = basePath ? `${basePath}?${params.toString()}` : null;
  const query = useApiQuery<PageResponse<T>>(path, [page, JSON.stringify(extraParams)]);
  return { ...query, page, setPage };
}
