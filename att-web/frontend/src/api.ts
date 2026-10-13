import { ApiError, type ApiEnvelope, type ApiErrorBody, type JobSummary, type PackageSummary, type ServerVersion } from './types';

/** Resolve the API below the current UI context (for example /att/ui/ -> /att/api/v1/). */
export function apiBaseUrl(): URL {
  return new URL('../api/v1/', document.baseURI);
}

function parseBody(text: string): unknown {
  if (!text) return undefined;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return undefined;
  }
}

/** Same-origin JSON transport shared by the Server UI feature modules. */
export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const base = apiBaseUrl();
  const relativePath = path.replace(/^\/+/, '');
  if (!relativePath || path.startsWith('//') || /^[a-z][a-z\d+.-]*:/i.test(path)) {
    throw new ApiError('The API request path must be relative to /api/v1/.', { status: 0 });
  }
  const url = new URL(relativePath, base);
  if (url.origin !== base.origin || !url.pathname.startsWith(base.pathname)) {
    throw new ApiError('The API request must stay within the same /api/v1/ origin.', { status: 0 });
  }

  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (init.body !== undefined && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const response = await fetch(url, { ...init, headers, credentials: 'same-origin' });
  const body = parseBody(await response.text());
  if (!response.ok) {
    const payload = body && typeof body === 'object' ? body as { error?: ApiErrorBody; requestId?: string } : {};
    const detail = payload.error;
    throw new ApiError(detail?.summary || ('ATT API request failed (' + response.status + ').'), {
      status: response.status,
      code: detail?.code,
      requestId: payload.requestId || response.headers.get('X-Request-Id') || undefined,
      details: detail?.detail,
    });
  }
  if (body === undefined && response.status !== 204) {
    throw new ApiError('The ATT API returned an empty or invalid JSON response.', {
      status: response.status,
      requestId: response.headers.get('X-Request-Id') || undefined,
    });
  }
  return body as T;
}

export const api = {
  getVersion(signal?: AbortSignal): Promise<ServerVersion> {
    return request<ServerVersion>('version', { signal });
  },
  getPackages(signal?: AbortSignal): Promise<ApiEnvelope<PackageSummary>> {
    return request<ApiEnvelope<PackageSummary>>('packages', { signal });
  },
  getJobs(signal?: AbortSignal): Promise<ApiEnvelope<JobSummary>> {
    return request<ApiEnvelope<JobSummary>>('jobs', { signal });
  },
};
