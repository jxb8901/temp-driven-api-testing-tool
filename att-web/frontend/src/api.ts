import { ApiError, type ApiEnvelope, type ApiErrorBody, type JobSummary, type LocalAuthUser, type PackageSummary, type ServerVersion } from './types';
import type {
  AcceptedJob, ConfigurationComparisonQuery, ConfigurationFilePage, ConfigurationResponse, DebugDraftRequest, DraftResponse,
  DebugFormResponse, EffectiveConfigurationQuery, ResourceDetail, ResourceIdentity, SourceProjection, TestdataRecordsResponse,
  ResourcePage, ResourceQuery,
} from './features/explorer/types';
import type {
  JobAccepted as LoadJobAccepted, JobArtifact, JobEventHandlers, JobResultResponse,
  JobSummary as LoadJobSummary, JobView, LoadDraftInput, LoadDraftResponse, LoadModel,
  LoadPolicyResponse, LoadTargetType, QuickLoadFormResponse, SubmitLoadDraftInput, TargetResource,
} from './features/load/types';
import type {
  DeleteScheduleResponse, LoadSchedule, LoadScheduleInput, ScheduleOccurrence, SchedulePreview,
  SchedulePreviewInput,
} from './features/schedules/types';

let csrfToken = '';
let localAuthMode = false;

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
  const method = (init.method || 'GET').toUpperCase();
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && csrfToken) headers.set('X-ATT-CSRF', csrfToken);
  const response = await fetch(url, { ...init, headers, credentials: 'same-origin' });
  const nextCsrf = response.headers.get('X-ATT-CSRF');
  if (nextCsrf) csrfToken = nextCsrf;
  const body = parseBody(await response.text());
  if (!response.ok) {
    const payload = body && typeof body === 'object' ? body as { error?: ApiErrorBody; requestId?: string; diagnostics?: unknown } : {};
    const detail = payload.error;
    if (localAuthMode && detail?.code === 'ATT-SERVER-AUTHENTICATION-REQUIRED' && path !== 'auth/me') {
      const returnTo = location.pathname + location.search + location.hash;
      location.assign(new URL('login.html?returnTo=' + encodeURIComponent(returnTo), document.baseURI).toString());
    }
    throw new ApiError(detail?.summary || ('ATT API request failed (' + response.status + ').'), {
      status: response.status,
      code: detail?.code,
      requestId: payload.requestId || response.headers.get('X-Request-Id') || undefined,
      details: detail?.detail,
      diagnostics: payload.diagnostics,
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

function queryString(values: Record<string, string | number | boolean | undefined>): string {
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

function parseEventData(raw: string): unknown {
  try { return JSON.parse(raw) as unknown; }
  catch { return raw; }
}

function artifactUrl(jobId: string, path: string): string {
  const segments = path.replaceAll('\\', '/').split('/');
  if (!segments.length || segments.some(segment => !segment || segment === '.' || segment === '..')) return '#invalid-artifact';
  const base = apiBaseUrl();
  const encodedPath = segments.map(encodeURIComponent).join('/');
  const url = new URL('jobs/' + encodeURIComponent(jobId) + '/artifacts/' + encodedPath, base);
  if (url.origin !== base.origin || !url.pathname.startsWith(base.pathname)) return '#invalid-artifact';
  return url.toString();
}

function subscribeJob(jobId: string, handlers: JobEventHandlers): () => void {
  const url = new URL('jobs/' + encodeURIComponent(jobId) + '/events', apiBaseUrl());
  const source = new EventSource(url.toString(), { withCredentials: true });
  source.onopen = () => handlers.onOpen();
  source.onerror = () => handlers.onError();
  for (const type of ['status', 'progress', 'log', 'diagnostic', 'result']) {
    source.addEventListener(type, event => {
      const message = event as MessageEvent<string>;
      handlers.onEvent({ id: message.lastEventId || undefined, type, data: parseEventData(message.data) });
      if (type === 'result') source.close();
    });
  }
  source.onmessage = event => handlers.onEvent({
    id: event.lastEventId || undefined,
    type: 'message',
    data: parseEventData(event.data),
  });
  return () => source.close();
}

export const api = {
  async getVersion(signal?: AbortSignal): Promise<ServerVersion> {
    const version = await request<ServerVersion>('version', { signal });
    localAuthMode = version.authMode === 'local';
    return version;
  },
  getCurrentUser(signal?: AbortSignal): Promise<{ loginId: string; displayName: string; role: 'ADMIN' | 'USER'; csrfToken?: string }> {
    return request('auth/me', { signal });
  },
  logout(): Promise<{ loggedOut: boolean }> {
    return request('auth/logout', { method: 'POST', body: '{}' });
  },
  listLocalUsers(signal?: AbortSignal): Promise<{ items: LocalAuthUser[] }> {
    return request('auth/users', { signal });
  },
  createLocalUser(input: { loginId: string; displayName: string; role: 'ADMIN' | 'USER'; password: string }): Promise<{ user: LocalAuthUser }> {
    return postJson('auth/users', input);
  },
  setLocalUserEnabled(loginId: string, enabled: boolean): Promise<{ enabled: boolean } | { disabled: boolean }> {
    const path = 'auth/users/' + encodeURIComponent(loginId);
    return enabled ? postJson(path + '/enable', {}) : request(path, { method: 'DELETE' });
  },
  resetLocalUserPassword(loginId: string, password: string): Promise<{ updated: boolean }> {
    return postJson('auth/users/' + encodeURIComponent(loginId) + '/password', { password });
  },
  changeLocalPassword(currentPassword: string, newPassword: string): Promise<{ updated: boolean }> {
    return postJson('auth/password', { currentPassword, newPassword });
  },
  getPackages(signal?: AbortSignal): Promise<ApiEnvelope<PackageSummary>> {
    return request<ApiEnvelope<PackageSummary>>('packages', { signal });
  },
  getJobs(signal?: AbortSignal): Promise<ApiEnvelope<JobSummary>> {
    return request<ApiEnvelope<JobSummary>>('jobs', { signal });
  },
  getLoadPolicy(packageId: string, model: LoadModel, environment?: string, signal?: AbortSignal, templateId?: string): Promise<LoadPolicyResponse> {
    return request<LoadPolicyResponse>('packages/' + encodeURIComponent(packageId) + '/load-policy' + queryString({ model, environment, templateId }), { signal });
  },
  getTargetLoadForm(
    packageId: string,
    type: LoadTargetType,
    resourceId: string,
    model: LoadModel,
    environment?: string,
    signal?: AbortSignal,
  ): Promise<QuickLoadFormResponse> {
    const path = 'packages/' + encodeURIComponent(packageId) + '/resources/' + encodeURIComponent(type) + '/' + encodeURIComponent(resourceId) + '/quick-load-form';
    return request<QuickLoadFormResponse>(path + queryString({ model, environment }), { signal });
  },
  async listTargets(packageId: string, type: LoadTargetType, signal?: AbortSignal): Promise<TargetResource[]> {
    const page = await request<ResourcePage>(
      'packages/' + encodeURIComponent(packageId) + '/resources' + queryString({ type, limit: 100 }),
      { signal },
    );
    return (page.items || []).map(item => ({ type, logicalId: item.logicalId || '', state: item.state || '' }));
  },
  createLoadDraft(input: LoadDraftInput, signal?: AbortSignal): Promise<LoadDraftResponse> {
    return postJson<LoadDraftResponse>('drafts/load', input, signal);
  },
  submitLoadDraft(input: SubmitLoadDraftInput): Promise<LoadJobAccepted> {
    return postJson<LoadJobAccepted>('jobs/load', input);
  },
  async listJobs(input: { principal?: string; signal?: AbortSignal } = {}): Promise<LoadJobSummary[]> {
    const envelope = await request<ApiEnvelope<LoadJobSummary>>('jobs' + queryString({ principal: input.principal }), { signal: input.signal });
    return Array.isArray(envelope.items) ? envelope.items.slice(0, 100) : [];
  },
  getJob(jobId: string, signal?: AbortSignal): Promise<JobView> {
    return request<JobView>('jobs/' + encodeURIComponent(jobId), { signal });
  },
  getJobResult(jobId: string, signal?: AbortSignal): Promise<JobResultResponse> {
    return request<JobResultResponse>('jobs/' + encodeURIComponent(jobId) + '/result', { signal });
  },
  async getJobArtifacts(jobId: string, signal?: AbortSignal): Promise<JobArtifact[]> {
    const envelope = await request<ApiEnvelope<JobArtifact>>('jobs/' + encodeURIComponent(jobId) + '/artifacts', { signal });
    return Array.isArray(envelope.items) ? envelope.items : [];
  },
  cancelJob(jobId: string): Promise<{ jobId: string; status: LoadJobSummary['status'] }> {
    return request<{ jobId: string; status: LoadJobSummary['status'] }>('jobs/' + encodeURIComponent(jobId), { method: 'DELETE' });
  },
  subscribeJob,
  artifactHref: artifactUrl,
  listResources(packageId: string, query: ResourceQuery, signal?: AbortSignal): Promise<ResourcePage> {
    return request<ResourcePage>('packages/' + encodeURIComponent(packageId) + '/resources' + queryString({
      query: query.query,
      type: query.type === 'all' ? undefined : query.type,
      cursor: query.cursor,
      limit: query.limit,
      sort: query.sort,
      direction: query.direction,
    }), { signal });
  },
  getResource(identity: ResourceIdentity, signal?: AbortSignal): Promise<ResourceDetail> {
    return request<ResourceDetail>(resourcePath(identity), { signal });
  },
  getSafeSource(identity: ResourceIdentity, signal?: AbortSignal): Promise<SourceProjection> {
    return request<SourceProjection>(resourcePath(identity) + '/source', { signal });
  },
  getResourceSourceFile(identity: ResourceIdentity, referenceId: string, signal?: AbortSignal, origin?: string): Promise<SourceProjection> {
    return request<SourceProjection>(resourcePath(identity) + '/source-file' + queryString({ referenceId, origin }), { signal });
  },
  getDeclaredConfiguration(packageId: string, signal?: AbortSignal): Promise<ConfigurationResponse> {
    return request<ConfigurationResponse>('packages/' + encodeURIComponent(packageId) + '/configuration?view=declared', { signal });
  },
  getEffectiveConfiguration(packageId: string, query: EffectiveConfigurationQuery, signal?: AbortSignal): Promise<ConfigurationResponse> {
    return request<ConfigurationResponse>('packages/' + encodeURIComponent(packageId) + '/configuration/effective' + queryString({
      environment: query.environment,
      section: query.section,
      query: query.query,
      offset: query.offset,
      limit: query.limit,
    }), { signal });
  },
  compareConfiguration(packageId: string, query: ConfigurationComparisonQuery, signal?: AbortSignal): Promise<ConfigurationResponse> {
    return request<ConfigurationResponse>('packages/' + encodeURIComponent(packageId) + '/configuration/compare' + queryString({
      left: query.left,
      right: query.right,
      section: query.section,
      includeInherited: query.includeInherited,
      offset: query.offset,
      limit: query.limit,
    }), { signal });
  },
  listConfigurationFiles(packageId: string, path?: string, search?: string, offset = 0, signal?: AbortSignal): Promise<ConfigurationFilePage> {
    return request<ConfigurationFilePage>('packages/' + encodeURIComponent(packageId) + '/configuration/files' + queryString({ path, query: search, offset, limit: 100 }), { signal });
  },
  getConfigurationFileSource(packageId: string, path: string, origin?: string, signal?: AbortSignal, referenceId?: string): Promise<SourceProjection> {
    return request<SourceProjection>('packages/' + encodeURIComponent(packageId) + '/configuration/source' + queryString({ path, origin, referenceId }), { signal });
  },
  getTestdataRecords(packageId: string, descriptorId: string, query: { environment?: string; offset?: number; limit?: number; previewGenerated?: boolean }, signal?: AbortSignal): Promise<TestdataRecordsResponse> {
    return request<TestdataRecordsResponse>('packages/' + encodeURIComponent(packageId) + '/configuration/testdata/' + encodeURIComponent(descriptorId) + '/records' + queryString(query), { signal });
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
  listLoadSchedules(signal?: AbortSignal): Promise<ApiEnvelope<LoadSchedule>> {
    return request<ApiEnvelope<LoadSchedule>>('load-schedules', { signal });
  },
  previewLoadSchedule(input: SchedulePreviewInput, signal?: AbortSignal): Promise<SchedulePreview> {
    return postJson<SchedulePreview>('load-schedules/preview', input, signal);
  },
  createLoadSchedule(input: LoadScheduleInput, signal?: AbortSignal): Promise<LoadSchedule> {
    return postJson<LoadSchedule>('load-schedules', input, signal);
  },
  updateLoadSchedule(id: string, input: LoadScheduleInput, signal?: AbortSignal): Promise<LoadSchedule> {
    return request<LoadSchedule>('load-schedules/' + encodeURIComponent(id), {
      method: 'PATCH', body: JSON.stringify(input), signal,
    });
  },
  setLoadScheduleEnabled(id: string, enabled: boolean, signal?: AbortSignal): Promise<LoadSchedule> {
    return postJson<LoadSchedule>(
      'load-schedules/' + encodeURIComponent(id) + (enabled ? '/resume' : '/pause'), {}, signal,
    );
  },
  triggerLoadSchedule(id: string, signal?: AbortSignal): Promise<ScheduleOccurrence> {
    return postJson<ScheduleOccurrence>('load-schedules/' + encodeURIComponent(id) + '/trigger', {}, signal);
  },
  deleteLoadSchedule(id: string, signal?: AbortSignal): Promise<DeleteScheduleResponse> {
    return request<DeleteScheduleResponse>('load-schedules/' + encodeURIComponent(id), { method: 'DELETE', signal });
  },
  getLoadScheduleExecutions(id: string, limit = 100, signal?: AbortSignal): Promise<ApiEnvelope<ScheduleOccurrence>> {
    return request<ApiEnvelope<ScheduleOccurrence>>(
      'load-schedules/' + encodeURIComponent(id) + '/executions' + queryString({ limit }),
      { signal },
    );
  },
};
