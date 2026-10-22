import type { JobReplay } from "./features/load/types";
import { ApiError, type ApiEnvelope, type ApiErrorBody, type JobSummary, type LocalAuthUser, type PackageSummary, type ServerVersion } from './types';
import type {
  AcceptedJob, ConfigurationComparisonQuery, ConfigurationFilePage, ConfigurationResponse, DebugDraftRequest, DraftResponse,
  DebugFormResponse, EffectiveConfigurationQuery, ResourceDetail, ResourceIdentity, SourceProjection, TestdataRecordsResponse,
  ResourcePage, ResourceQuery,
} from './features/explorer/types';
import type {
  JobAccepted as LoadJobAccepted, JobArtifact, JobEventHandlers, JobResultResponse,
  JobSummary as LoadJobSummary, JobView, LoadDraftInput, LoadDraftResponse, LoadModel,
  LoadPolicyResponse, LoadTargetType, QuickLoadFormResponse, SubmitLoadDraftInput, TargetResource, LoadCatalogPage, LoadCatalogDetail,
} from './features/load/types';
import type { RunDefinition } from './features/run/RunPage';
import { contextRelativeReturnTo } from './contextPaths';
import { inspectionCache } from './inspectionCache';
import type {
  DeleteScheduleResponse, ScheduleDefinition, ScheduleInput, ScheduleOccurrence, SchedulePreview,
  SchedulePreviewInput,
} from './features/schedules/types';
import { normalizeSchedule, scheduleTransport } from './features/schedules/types';

export interface ServerJobStorageMetrics {
  enabled?: boolean;
  maxTotalBytes?: number | null;
  cleanupTargetBytes?: number | null;
  minRetentionHours?: number | null;
  cleanupIntervalMinutes?: number | null;
  state?: string;
  totalBytes?: number | null;
  measurementComplete?: boolean | null;
  eligibleJobs?: number | null;
  protectedJobs?: number | null;
  lastSweepAt?: string | null;
  deletedJobs?: number | null;
  reclaimedBytes?: number | null;
  quotaUnmet?: boolean;
}

let csrfToken = '';
let localAuthMode = false;
let unifiedRunSupported = false;
const schedulesPath = () => unifiedRunSupported ? 'schedules' : 'load-schedules';

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

function isJobActionOutcome(value: unknown): value is { jobId: string; outcome: string } {
  return Boolean(value && typeof value === 'object'
    && typeof (value as { jobId?: unknown }).jobId === 'string'
    && typeof (value as { outcome?: unknown }).outcome === 'string');
}

/** Same-origin JSON transport shared by the Server UI feature modules. */
function isInspectionQuery(path: string, init: RequestInit): boolean {
  if ((init.method || 'GET').toUpperCase() !== 'GET') return false;
  const [route, query] = path.split('?');
  if (new URLSearchParams(query).has('includeAllFields')) return false;
  return /^packages\/[^/]+\/resources(?:\/[^/]+\/[^/]+(?:\/(?:source|source-file))?)?$/.test(route)
    || /^packages\/[^/]+\/load-catalog(?:\/(?:source|detail))?$/.test(route)
    || /^packages\/[^/]+\/configuration(?:\/(?:effective|compare|files|source))?$/.test(route);
}
let capabilitiesFlight: { epoch: number; promise: Promise<void> } | undefined;
async function verifyInspectionContext() {
  const epoch = inspectionCache.epoch;
  if (!capabilitiesFlight || capabilitiesFlight.epoch !== epoch) {
    const flight = { epoch, promise: Promise.resolve() };
    flight.promise = transport<{ inspectionCacheContext?: string }>('auth/capabilities', { cache: 'no-store' }).then(result => {
      if (inspectionCache.epoch !== epoch) throw new DOMException('Session changed', 'AbortError');
      if (result.inspectionCacheContext) inspectionCache.setContext(result.inspectionCacheContext);
      return result.inspectionCacheContext;
    }).then(context => { if (!context) throw new Error('Inspection cache context unavailable'); }).finally(() => {
      if (capabilitiesFlight === flight) capabilitiesFlight = undefined;
    });
    capabilitiesFlight = flight;
  }
  return capabilitiesFlight.promise;
}
export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  if (!isInspectionQuery(path, init)) {
    if (!['GET', 'HEAD', 'OPTIONS'].includes((init.method || 'GET').toUpperCase())) {
      if (path === 'auth/logout') inspectionCache.resetSession(); else inspectionCache.clear();
    }
    return transport<T>(path, init);
  }
  if (init.signal?.aborted) throw new DOMException('Inspection request cancelled', 'AbortError');
  // Auth responses are never cached; coalesce only the currently running permission check.
  try { await verifyInspectionContext(); }
  catch (error) {
    if (error instanceof ApiError && [401, 403].includes(error.status)) { inspectionCache.resetSession(); throw error; }
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    // Older Servers without a context token remain safely uncached.
    return transport<T>(path, init);
  }
  if (init.signal?.aborted) throw new DOMException('Inspection request cancelled', 'AbortError');
  return inspectionCache.query(path, signal => transport<T>(path, { ...init, signal, cache: 'no-store' }), init.signal || undefined);
}
async function transport<T>(path: string, init: RequestInit = {}): Promise<T> {
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
  const response = await fetch(url, { ...init, cache: 'no-store', headers, credentials: 'same-origin' });
  const nextCsrf = response.headers.get('X-ATT-CSRF');
  if (nextCsrf) csrfToken = nextCsrf;
  const body = parseBody(await response.text());
  if (response.status === 409 && isJobActionOutcome(body)) return body as T;
  if (!response.ok) {
    const payload = body && typeof body === 'object' ? body as { error?: ApiErrorBody; code?: string; message?: string; detail?: string; requestId?: string; diagnostics?: unknown } : {};
    const detail = payload.error;
    const message = detail?.summary || detail?.message || detail?.detail || payload.message || payload.detail;
    if (isInspectionQuery(path, init) && [401, 403, 404, 409].includes(response.status)) {
      const packageMatch = /^packages\/([^/]+)\//.exec(path);
      if (packageMatch) inspectionCache.invalidatePackage(decodeURIComponent(packageMatch[1]));
    }
    if (localAuthMode && detail?.code === 'ATT-SERVER-AUTHENTICATION-REQUIRED' && path !== 'auth/me') {
      const returnTo = contextRelativeReturnTo(location.pathname, location.search, location.hash);
      location.assign(new URL('login.html?returnTo=' + encodeURIComponent(returnTo), document.baseURI).toString());
    }
    throw new ApiError(message || ('ATT API request failed (' + response.status + ').'), {
      status: response.status,
      code: detail?.code || payload.code,
      requestId: payload.requestId || response.headers.get('X-Request-Id') || undefined,
      details: detail?.detail || payload.detail,
      diagnostics: payload.diagnostics,
    });
  }
  if (body === undefined && response.status !== 204) {
    throw new ApiError('The ATT API returned an empty or invalid JSON response.', {
      status: response.status,
      requestId: response.headers.get('X-Request-Id') || undefined,
    });
  }
  const revision = response.headers.get('X-ATT-Inspection-Revision');
  const packageMatch = /^packages\/([^/]+)\//.exec(path);
  if (revision && packageMatch && !init.signal?.aborted) inspectionCache.revision(decodeURIComponent(packageMatch[1]), revision, path);
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

export function resourceSourcePath(identity: ResourceIdentity): string {
  return resourcePath(identity) + '/source';
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
  const url = new URL('jobs/' + encodeURIComponent(jobId) + '/artifacts/view/' + encodedPath, base);
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
    unifiedRunSupported = version.unifiedRunVersion === 'att-execution/v1';
    return version;
  },
  getCurrentUser(signal?: AbortSignal): Promise<{ loginId: string; displayName: string; role: 'ADMIN' | 'USER'; csrfToken?: string }> {
    return request('auth/me', { signal });
  },
  getAuthCapabilities(signal?: AbortSignal): Promise<{
    configurationAdmin: boolean;
    capabilities?: { operate?: boolean; accountManage?: boolean; systemSettingsWrite?: boolean; configurationAdmin?: boolean };
  }> {
    return request('auth/capabilities', { signal });
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
  listLoadJobs(input: { offset?: number; limit?: number; signal?: AbortSignal } = {}) {
    return request<{ items: JobSummary[]; nextOffset?: number | null }>('jobs' + queryString({ executionType: 'load', offset: input.offset, limit: input.limit }), { signal: input.signal });
  },
  async listLoadCatalog(packageId: string, input: { query?: string; cursor?: string; limit?: number } = {}, signal?: AbortSignal): Promise<LoadCatalogPage> {
    const response = await request<Omit<LoadCatalogPage, 'items'> & { items: Array<Omit<LoadCatalogPage['items'][number], 'packageId'>> }>(
      'packages/' + encodeURIComponent(packageId) + '/load-catalog' + queryString(input), { signal });
    return { ...response, items: (response.items || []).map(item => ({ ...item, packageId })) };
  },
  getLoadCatalogDetail(packageId: string, resourceId: string, signal?: AbortSignal): Promise<LoadCatalogDetail> {
    return request<LoadCatalogDetail>('packages/' + encodeURIComponent(packageId) + '/load-catalog/detail' + queryString({ resourceId }), { signal })
      .then(detail => ({ ...detail, packageId }));
  },
  validateLoadCatalogScenario(packageId: string, resourceId: string, environment?: string, signal?: AbortSignal) {
    return request<{ validation: string; model?: 'virtualUsers' | 'arrivalRate'; previewYaml?: string;
      scheduleAvailable?: boolean; scheduleReason?: string; draftId?: string; expiresAt?: string }>(
      'packages/' + encodeURIComponent(packageId) + '/load-catalog/validate' + queryString({ resourceId, environment }), { signal });
  },
  getLoadSource(packageId: string, resourceId: string, signal?: AbortSignal) {
    return request<{ available: boolean; logicalPath?: string; text?: string; redacted?: boolean; reason?: string }>(
      'packages/' + encodeURIComponent(packageId) + '/load-catalog/source' + queryString({ resourceId }), { signal });
  },
  submitLoadScenario(input: { packageId: string; scenario: string; environment?: string }): Promise<LoadJobAccepted> {
    return postJson<LoadJobAccepted>('jobs/load', input);
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
    return (page.items || []).map(item => ({ type, resourceId: item.resourceId, logicalId: item.logicalId || '', state: item.state || '' }));
  },
  createLoadDraft(input: LoadDraftInput, signal?: AbortSignal): Promise<LoadDraftResponse> {
    return postJson<LoadDraftResponse>('drafts/load', input, signal);
  },
  submitLoadDraft(input: SubmitLoadDraftInput): Promise<LoadJobAccepted> {
    return postJson<LoadJobAccepted>('jobs/load', input);
  },
  async listJobs(input: { principal?: string; pinnedOnly?: boolean; signal?: AbortSignal } = {}): Promise<LoadJobSummary[]> {
    const envelope = await request<ApiEnvelope<LoadJobSummary>>('jobs' + queryString({ principal: input.principal, pinnedOnly: input.pinnedOnly }), { signal: input.signal });
    return Array.isArray(envelope.items) ? envelope.items.slice(0, 100) : [];
  },
  getJobReplay(jobId: string): Promise<JobReplay> {
    return request<JobReplay>('jobs/' + encodeURIComponent(jobId) + '/replay');
  },
  createJobReplayDraft(jobId: string): Promise<JobReplay> {
    return postJson<JobReplay>('jobs/' + encodeURIComponent(jobId) + '/replay-draft', {});
  },
  rerunJob(jobId: string, intentId: string): Promise<{ jobId: string }> {
    return postJson<{ jobId: string }>('jobs/' + encodeURIComponent(jobId) + '/rerun', { intentId, confirm: true });
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
  pinJob(jobId: string, pinned: boolean, pinReason?: string): Promise<JobView> {
    const body = pinReason === undefined ? { pinned } : { pinned, pinReason };
    return request<JobView>('jobs/' + encodeURIComponent(jobId) + '/pin', { method: 'POST', body: JSON.stringify(body) });
  },
  async pinJobs(jobIds: string[], pinned: boolean, pinReason?: string): Promise<Array<{ jobId: string; outcome: string }>> {
    const body = pinReason === undefined ? { pinned, jobIds } : { pinned, pinReason, jobIds };
    const response = await request<{ items: Array<{ jobId: string; outcome: string }> }>('jobs/pin', { method: 'POST', body: JSON.stringify(body) });
    return response.items;
  },
  deleteJob(jobId: string): Promise<{ jobId: string; outcome: string }> {
    return request<{ jobId: string; outcome: string }>('jobs/' + encodeURIComponent(jobId) + '/delete', { method: 'POST', body: JSON.stringify({ confirm: true }) });
  },
  async deleteJobs(jobIds: string[]): Promise<Array<{ jobId: string; outcome: string }>> {
    const response = await request<{ items: Array<{ jobId: string; outcome: string }> }>('jobs/delete', { method: 'POST', body: JSON.stringify({ jobIds, confirm: true }) });
    return response.items;
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
    return request<SourceProjection>(resourceSourcePath(identity), { signal });
  },
  getResourceSourceFile(identity: ResourceIdentity, referenceId: string, signal?: AbortSignal, origin?: string): Promise<SourceProjection> {
    return request<SourceProjection>(resourcePath(identity) + '/source-file' + queryString({ referenceId, origin }), { signal });
  },
  getDeclaredConfiguration(packageId: string, signal?: AbortSignal): Promise<ConfigurationResponse> {
    return request<ConfigurationResponse>((packageId ? 'packages/' + encodeURIComponent(packageId) + '/configuration' : 'configuration') + queryString({ view: 'declared' }), { signal });
  },
  getEffectiveConfiguration(packageId: string, query: EffectiveConfigurationQuery, signal?: AbortSignal): Promise<ConfigurationResponse> {
    return request<ConfigurationResponse>((packageId ? 'packages/' + encodeURIComponent(packageId) + '/configuration/effective' : 'configuration/effective') + queryString({
      environment: query.environment,
      section: query.section,
      query: query.query,
      offset: query.offset,
      limit: query.limit,
      includeAllFields: query.includeAllFields || undefined,
    }), { signal });
  },
  compareConfiguration(packageId: string, query: ConfigurationComparisonQuery, signal?: AbortSignal): Promise<ConfigurationResponse> {
    return request<ConfigurationResponse>((packageId ? 'packages/' + encodeURIComponent(packageId) + '/configuration/compare' : 'configuration/compare') + queryString({
      left: query.left,
      right: query.right,
      section: query.section,
      includeInherited: query.includeInherited,
      offset: query.offset,
      limit: query.limit,
      includeAllFields: query.includeAllFields || undefined,
    }), { signal });
  },
  listConfigurationFiles(packageId: string, path?: string, search?: string, offset = 0, signal?: AbortSignal): Promise<ConfigurationFilePage> {
    return request<ConfigurationFilePage>((packageId ? 'packages/' + encodeURIComponent(packageId) + '/configuration/files' : 'configuration/files') + queryString({ path, query: search, offset, limit: 100 }), { signal });
  },
  getConfigurationFileSource(packageId: string, path: string, origin?: string, signal?: AbortSignal, referenceId?: string): Promise<SourceProjection> {
    return request<SourceProjection>((packageId ? 'packages/' + encodeURIComponent(packageId) + '/configuration/source' : 'configuration/source') + queryString({ path, origin, referenceId }), { signal });
  },
  getTestdataRecords(packageId: string, descriptorId: string, query: { environment?: string; offset?: number; limit?: number; previewGenerated?: boolean }, signal?: AbortSignal): Promise<TestdataRecordsResponse> {
    return request<TestdataRecordsResponse>('packages/' + encodeURIComponent(packageId) + '/configuration/testdata/' + encodeURIComponent(descriptorId) + '/records' + queryString(query), { signal });
  },
  getDebugForm(identity: ResourceIdentity, environment?: string, signal?: AbortSignal): Promise<DebugFormResponse> {
    return request<DebugFormResponse>(resourcePath(identity) + '/debug-form' + queryString({ environment }), { signal });
  },
  previewRun(input: RunDefinition, signal?: AbortSignal): Promise<DraftResponse> {
    if (!unifiedRunSupported) {
      if (input.selection || input.target?.type === 'case') {
        throw new Error('Case and batch Run validation require ATT Server 4.2.1 or newer. On older servers, use the legacy Case action in Explorer.');
      }
      if (input.target) {
        return postJson<DraftResponse>('drafts/debug', { packageId: input.packageId, environment: input.environment, target: input.target, input: input.input }, signal);
      }
    }
    return postJson<DraftResponse>('drafts/run', input, signal);
  },
  submitRun(input: { packageId: string; draftId: string; principal?: string }, signal?: AbortSignal): Promise<AcceptedJob> {
    return postJson<AcceptedJob>(input.draftId.startsWith('D') ? 'jobs/debug' : 'jobs/run', input, signal);
  },
  previewDebug(input: DebugDraftRequest, signal?: AbortSignal): Promise<DraftResponse> {
    return postJson<DraftResponse>('drafts/debug', input, signal);
  },
  submitDebug(input: { packageId: string; draftId: string; principal?: string }, signal?: AbortSignal): Promise<AcceptedJob> {
    return postJson<AcceptedJob>('jobs/debug', input, signal);
  },
  async runCase(input: { packageId: string; suite: string; caseId: string; environment?: string; principal?: string }, signal?: AbortSignal): Promise<AcceptedJob> {
    if (unifiedRunSupported) {
      const draft = await postJson<DraftResponse>('drafts/run', { schemaVersion: 'att-execution/v1', executionType: 'run', packageId: input.packageId, environment: input.environment, selection: { suites: [input.suite], caseIds: [input.caseId] }, options: { debug: false } }, signal);
      return postJson<AcceptedJob>('jobs/run', { packageId: input.packageId, draftId: draft.draftId, principal: input.principal }, signal);
    }
    const body: { packageId: string; suites: string[]; caseIds: string[]; environment?: string; principal?: string } = {
      packageId: input.packageId,
      suites: [input.suite],
      caseIds: [input.caseId],
    };
    if (input.environment) body.environment = input.environment;
    if (input.principal) body.principal = input.principal;
    return postJson<AcceptedJob>('jobs/run', body, signal);
  },
  async listSchedules(signal?: AbortSignal): Promise<ApiEnvelope<ScheduleDefinition>> {
    const response = await request<ApiEnvelope<ScheduleDefinition>>(schedulesPath(), { signal });
    return { ...response, items: response.items.map(normalizeSchedule) };
  },
  previewSchedule(input: SchedulePreviewInput, signal?: AbortSignal): Promise<SchedulePreview> {
    return postJson<SchedulePreview>(schedulesPath() + '/preview', input, signal);
  },
  createSchedule(input: ScheduleInput, signal?: AbortSignal): Promise<ScheduleDefinition> {
    return postJson<ScheduleDefinition>(schedulesPath(), scheduleTransport(input, unifiedRunSupported), signal).then(normalizeSchedule);
  },
  updateSchedule(id: string, input: ScheduleInput, signal?: AbortSignal): Promise<ScheduleDefinition> {
    return request<ScheduleDefinition>(schedulesPath() + '/' + encodeURIComponent(id), {
      method: 'PATCH', body: JSON.stringify(scheduleTransport(input, unifiedRunSupported)), signal,
    }).then(normalizeSchedule);
  },
  setScheduleEnabled(id: string, enabled: boolean, signal?: AbortSignal): Promise<ScheduleDefinition> {
    return postJson<ScheduleDefinition>(
      schedulesPath() + '/' + encodeURIComponent(id) + (enabled ? '/resume' : '/pause'), {}, signal,
    ).then(normalizeSchedule);
  },
  triggerSchedule(id: string, signal?: AbortSignal): Promise<ScheduleOccurrence> {
    return postJson<ScheduleOccurrence>(schedulesPath() + '/' + encodeURIComponent(id) + '/trigger', {}, signal);
  },
  deleteSchedule(id: string, signal?: AbortSignal): Promise<DeleteScheduleResponse> {
    return request<DeleteScheduleResponse>(schedulesPath() + '/' + encodeURIComponent(id), { method: 'DELETE', signal });
  },
  getServerMetrics(signal?: AbortSignal): Promise<{ jobStorage?: ServerJobStorageMetrics }> {
    return request<{ jobStorage?: ServerJobStorageMetrics }>('metrics', { signal });
  },
  getScheduleExecutions(id: string, limit = 100, signal?: AbortSignal): Promise<ApiEnvelope<ScheduleOccurrence>> {
    return request<ApiEnvelope<ScheduleOccurrence>>(
      schedulesPath() + '/' + encodeURIComponent(id) + '/executions' + queryString({ limit }),
      { signal },
    );
  },
};
