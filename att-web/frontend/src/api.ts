import { ApiError, type ApiEnvelope, type ApiErrorBody, type JobSummary, type PackageSummary, type ServerVersion } from './types';
import type {
  AcceptedJob, ConfigurationComparisonQuery, ConfigurationResponse, DebugDraftRequest, DraftResponse,
  DebugFormResponse, EffectiveConfigurationQuery, ResourceDetail, ResourceIdentity,
  ResourcePage, ResourceQuery, SourceProjection,
} from './features/explorer/types';

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

function queryString(values: Record<string, string | number | undefined>): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined && String(value).length > 0) params.set(key, String(value));
  }
  const query = params.toString();
  return query ? '?' + query : '';
}

function resourcePath(identity: ResourceIdentity): string {
  return 'packages/' + encodeURIComponent(identity.packageId)
    + '/resources/' + encodeURIComponent(identity.type)
    + '/' + encodeURIComponent(identity.resourceId);
}

function postJson<T>(path: string, value: unknown, signal?: AbortSignal): Promise<T> {
  return request<T>(path, { method: 'POST', body: JSON.stringify(value), signal });
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
  listResources(packageId: string, query: ResourceQuery, signal?: AbortSignal): Promise<ResourcePage> {
    return request<ResourcePage>('packages/' + encodeURIComponent(packageId) + '/resources' + queryString({
      query: query.query,
      type: query.type === 'all' ? undefined : query.type,
      cursor: query.cursor,
      limit: query.limit,
    }), { signal });
  },
  getResource(identity: ResourceIdentity, signal?: AbortSignal): Promise<ResourceDetail> {
    return request<ResourceDetail>(resourcePath(identity), { signal });
  },
  getSafeSource(identity: ResourceIdentity, signal?: AbortSignal): Promise<SourceProjection> {
    return request<SourceProjection>(resourcePath(identity) + '/source', { signal });
  },
  getDeclaredConfiguration(packageId: string, signal?: AbortSignal): Promise<ConfigurationResponse> {
    return request<ConfigurationResponse>('packages/' + encodeURIComponent(packageId) + '/configuration?view=declared', { signal });
  },
  getEffectiveConfiguration(packageId: string, query: EffectiveConfigurationQuery, signal?: AbortSignal): Promise<ConfigurationResponse> {
    return request<ConfigurationResponse>('packages/' + encodeURIComponent(packageId) + '/configuration/effective' + queryString({
      environment: query.environment,
      section: query.section,
      offset: query.offset,
      limit: query.limit,
    }), { signal });
  },
  compareConfiguration(packageId: string, query: ConfigurationComparisonQuery, signal?: AbortSignal): Promise<ConfigurationResponse> {
    return request<ConfigurationResponse>('packages/' + encodeURIComponent(packageId) + '/configuration/compare' + queryString({
      left: query.left,
      right: query.right,
      offset: query.offset,
      limit: query.limit,
    }), { signal });
  },
  getDebugForm(identity: ResourceIdentity, environment?: string, signal?: AbortSignal): Promise<DebugFormResponse> {
    return request<DebugFormResponse>(resourcePath(identity) + '/debug-form' + queryString({ environment }), { signal });
  },
  previewDebug(input: DebugDraftRequest, signal?: AbortSignal): Promise<DraftResponse> {
    return postJson<DraftResponse>('drafts/debug', input, signal);
  },
  submitDebug(input: { packageId: string; draftId: string; principal?: string }, signal?: AbortSignal): Promise<AcceptedJob> {
    return postJson<AcceptedJob>('jobs/debug', input, signal);
  },
  runCase(input: { packageId: string; suite: string; caseId: string; principal?: string }, signal?: AbortSignal): Promise<AcceptedJob> {
    const body: { packageId: string; suites: string[]; caseIds: string[]; principal?: string } = {
      packageId: input.packageId,
      suites: [input.suite],
      caseIds: [input.caseId],
    };
    if (input.principal) body.principal = input.principal;
    return postJson<AcceptedJob>('jobs/run', body, signal);
  },
};
