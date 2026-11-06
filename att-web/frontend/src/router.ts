import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';

export interface ResourceRoute {
  type: string;
  resourceId: string;
  logicalId?: string;
  tab?: 'definition' | 'source' | 'relations';
}
export interface LoadCatalogRouteState {
  query?: string;
  packageFilter?: string;
  kindFilter?: string;
  modelFilter?: string;
  validationFilter?: string;
  environment?: string;
  scrollY?: number;
}

export type ConfigurationTab = 'source' | 'declared' | 'effective' | 'compare';
export interface ResourceSourceReferenceRoute { type: 'case' | 'template' | 'flow' | 'tool'; resourceId: string; referenceId: string }

export type AppRoute =
  | { kind: 'dashboard' }
  | { kind: 'monitoring' }
  | { kind: 'packages' }
  | { kind: 'package'; packageId: string; resource?: ResourceRoute; view?: 'testdata' | 'source-file'; environment?: string; testdataDescriptorId?: string; filePath?: string; fileOrigin?: string; sourceReference?: ResourceSourceReferenceRoute; returnTo?: string }
  | { kind: 'configuration'; packageId?: string; environment?: string; filePath?: string; fileOrigin?: string; sourceReference?: ResourceSourceReferenceRoute; configurationReferenceId?: string; tab?: ConfigurationTab; view?: 'package-manifest' }
  | { kind: 'quick-load'; packageId: string; target?: ResourceRoute; environment?: string; templateId?: string; model?: 'virtualUsers' | 'arrivalRate' }
  | { kind: 'run'; packageId?: string; target?: ResourceRoute; environment?: string; batch?: boolean; replayDraftId?: string; replaySourceJobId?: string }
  | { kind: 'jobs'; jobId?: string; executionType?: 'load'; standalone?: boolean; tab?: 'overview' | 'live' | 'events' | 'results' | 'artifacts' | 'metrics' }
  | { kind: 'job-console'; jobId: string; view?: 'live' | 'events' }
  | { kind: 'job-artifact'; jobId: string; path: string }
  | { kind: 'load'; packageId?: string; catalog?: LoadCatalogRouteState; tab?: 'templates' | 'scenarios'; templateId?: string; environment?: string; replayDraftId?: string; replaySourceJobId?: string }
  | { kind: 'load-resource'; packageId: string; resourceId: string; environment?: string; returnTo?: string }
  | { kind: 'schedules'; view?: 'new' | 'edit' | 'history'; scheduleId?: string; occurrenceId?: string; intent?: 'validated'; returnTo?: string }
  | { kind: 'settings' };

function safeRouteValue(value: string | null, maxLength: number): string | undefined {
  return value && value.length <= maxLength && !/[\u0000-\u001f\u007f]/.test(value) ? value : undefined;
}

function safePackageLogicalPath(value: string | null): string | undefined {
  const path = safeRouteValue(value, 2048);
  if (!path || path.startsWith('/') || path.includes('\\') || /^[a-z][a-z\d+.-]*:/i.test(path)) return undefined;
  const segments = path.split('/');
  if (segments.some(segment => !segment || segment === '.' || segment === '..')) return undefined;
  return path;
}

function safePackageReturnTo(value: string | null, packageId: string): string | undefined {
  if (!value || value.length > 4096 || /[\u0000-\u001f\u007f]/.test(value) || value.indexOf('#', 1) >= 0) return undefined;
  const root = '#/packages/' + encodeURIComponent(packageId);
  if (!value.startsWith(root)) return undefined;
  const suffix = value.slice(root.length);
  return !suffix || suffix.startsWith('/resources/') || suffix === '/source-file' || suffix.startsWith('/source-file?')
    ? value : undefined;
}

function isLegacyTestdataRoute(hash: string): boolean {
  const queryIndex = hash.indexOf('?');
  if (queryIndex < 0 || !/^#?\/?configuration(?:\/|$)/.test(hash.slice(0, queryIndex))) return false;
  return new URLSearchParams(hash.slice(queryIndex + 1)).get('tab') === 'testdata';
}

export function parseRoute(hash: string): AppRoute {
  let parts: string[];
  let query: URLSearchParams;
  try {
    const withoutHash = hash.replace(/^#/, '');
    const queryIndex = withoutHash.indexOf('?');
    const pathname = queryIndex < 0 ? withoutHash : withoutHash.slice(0, queryIndex);
    query = new URLSearchParams(queryIndex < 0 ? '' : withoutHash.slice(queryIndex + 1));
    parts = pathname.replace(/^\/?/, '').split('/').filter(Boolean).map(decodeURIComponent);
  } catch {
    return { kind: 'dashboard' };
  }
  if (parts.length === 0) return { kind: 'dashboard' };
  if (parts[0] === 'monitoring' && parts.length === 1) return { kind: 'monitoring' };
  if (parts[0] === 'packages' && parts.length === 1) return { kind: 'packages' };
  if (parts[0] === 'packages' && parts[1]) {
    if (parts.length === 3 && parts[2] === 'testdata') return {
      kind: 'package', packageId: parts[1], view: 'testdata',
      ...(query.has('environment') ? { environment: query.get('environment') || '' } : {}),
      ...(safeRouteValue(query.get('descriptor'), 512) ? { testdataDescriptorId: safeRouteValue(query.get('descriptor'), 512)! } : {}),
    };
    if (parts.length === 3 && parts[2] === 'source-file') {
      const sourceType = query.get('sourceType');
      const sourceResourceId = safeRouteValue(query.get('sourceResourceId'), 512);
      const referenceId = query.get('sourceReferenceId');
      const sourceReference = sourceType && ['template', 'flow'].includes(sourceType) && sourceResourceId
        && referenceId && /^[0-9a-f]{64}$/.test(referenceId)
        ? { type: sourceType as ResourceSourceReferenceRoute['type'], resourceId: sourceResourceId, referenceId } : undefined;
      const filePath = safePackageLogicalPath(query.get('file'));
      const fileOrigin = safePackageLogicalPath(query.get('origin'));
      const returnTo = safePackageReturnTo(query.get('returnTo'), parts[1]);
      return {
        kind: 'package', packageId: parts[1], view: 'source-file',
        ...(query.has('environment') ? { environment: query.get('environment') || '' } : {}),
        ...(filePath ? { filePath } : {}), ...(fileOrigin ? { fileOrigin } : {}),
        ...(sourceReference ? { sourceReference } : {}), ...(returnTo ? { returnTo } : {}),
      };
    }
    if (parts.length === 5 && parts[2] === 'resources') {
      const rawDetailTab = query.get('detail');
      const detailTab = rawDetailTab === 'definition' || rawDetailTab === 'source' || rawDetailTab === 'relations'
        ? rawDetailTab : undefined;
      return { kind: 'package', packageId: parts[1], ...(query.has('environment') ? { environment: query.get('environment') || '' } : {}), resource: {
        type: parts[3], resourceId: parts[4], ...(query.get('id') ? { logicalId: query.get('id')! } : {}),
        ...(detailTab && detailTab !== 'definition' ? { tab: detailTab } : {}),
      } };
    }
    return { kind: 'package', packageId: parts[1] };
  }
  if (parts[0] === 'configuration') {
    const rawTab = query.get('tab');
    if (rawTab === 'testdata') {
      const packageId = parts[1];
      if (!packageId) return { kind: 'packages' };
      const descriptorId = safeRouteValue(query.get('descriptorId') || query.get('descriptor') || query.get('id'), 512);
      return { kind: 'package', packageId, view: 'testdata',
        ...(query.has('environment') ? { environment: query.get('environment') || '' } : {}),
        ...(descriptorId ? { testdataDescriptorId: descriptorId } : {}),
      };
    }
    const tab: ConfigurationTab | undefined = rawTab === 'source' || rawTab === 'declared' || rawTab === 'effective' || rawTab === 'compare' ? rawTab : undefined;
    const sourceType = query.get('sourceType');
    const sourceResourceId = query.get('sourceResourceId');
    const sourceReferenceId = query.get('sourceReferenceId');
    const sourceReference = sourceType && ['case', 'template', 'flow', 'tool'].includes(sourceType)
      && sourceResourceId && sourceResourceId.length <= 512
      && sourceReferenceId && /^[0-9a-f]{64}$/.test(sourceReferenceId)
      ? { type: sourceType as ResourceSourceReferenceRoute['type'], resourceId: sourceResourceId, referenceId: sourceReferenceId } : undefined;
    const configurationReferenceId=query.get('configurationReferenceId');
    return {
      kind: 'configuration',
      packageId: parts[1] || undefined,
      environment: query.has('environment') ? query.get('environment') || '' : undefined,
      filePath: query.get('file') || undefined,
      ...(query.get('origin') ? { fileOrigin: query.get('origin')! } : {}),
      ...(sourceReference ? { sourceReference } : {}),
      ...(configurationReferenceId&&/^[A-Za-z0-9_-]{1,4096}$/.test(configurationReferenceId)?{configurationReferenceId}:{}),
      ...(query.get('view') === 'package-manifest' ? { view: 'package-manifest' as const } : {}),
      tab,
    };
  }
  if (parts[0] === 'quick-load' && parts[1]) {
    const model = query.get('model');
    return {
      kind: 'quick-load', packageId: parts[1],
      ...(parts[2] && parts[3] ? { target: { type: parts[2], resourceId: parts[3], ...(query.get('id') ? { logicalId: query.get('id')! } : {}) } } : {}),
      environment: query.has('environment') ? query.get('environment') || '' : undefined,
      ...(query.get('templateId') ? { templateId: query.get('templateId')! } : {}),
      ...(model === 'virtualUsers' || model === 'arrivalRate' ? { model } : {}),
    };
  }
  if (parts[0] === 'run') return { kind: 'run', packageId: parts[1] || undefined,
    ...(parts[2]&&parts[3]?{target:{type:parts[2],resourceId:parts[3],...(query.get('id')?{logicalId:query.get('id')!}:{})}}:{}),
    environment:query.has('environment')?query.get('environment')||'':undefined,
    ...(query.get('mode') === 'batch' ? { batch: true } : {}),
    ...(validReplayDraftQuery(query) ? { replayDraftId: query.get('replayDraft')!, replaySourceJobId: query.get('replaySourceJob')! } : {}) };
  if (parts[0] === 'jobs') {
    if (parts[1] && parts[2] === 'console' && parts.length === 3) {
      const view = query.get('view');
      return { kind: 'job-console', jobId: parts[1], ...(view === 'live' || view === 'events' ? { view } : {}) };
    }
    if (parts[1] && parts[2] === 'artifact' && parts.length === 3) {
      return { kind: 'job-artifact', jobId: parts[1], path: query.get('path') || '' };
    }
    return { kind: 'jobs', ...(parts[1] ? { jobId: parts[1] } : {}), ...(query.get('standalone') === '1' ? { standalone: true } : {}), ...(['overview','live','events','results','artifacts','metrics'].includes(query.get('tab') || '') ? {tab: query.get('tab') as 'live'} : {}), ...(query.get('type') === 'load' ? { executionType: 'load' } : {}) };
  }
  if (parts[0] === 'load' && parts[1] === 'resources' && parts[2] && parts[3]) return {
    kind: 'load-resource', packageId: parts[2], resourceId: parts[3],
    ...(query.has('environment') ? { environment: query.get('environment') || '' } : {}),
    ...(query.get('returnTo') ? { returnTo: query.get('returnTo')! } : {}),
  };
  if (parts[0] === 'advanced-load') {
    if (parts[1]) return { kind: 'load', packageId: parts[1], ...(query.has('environment')?{environment:query.get('environment')||''}:{}), ...(query.get('templateId') ? { templateId: query.get('templateId')! } : {}),
      ...(validReplayDraftQuery(query) ? { replayDraftId: query.get('replayDraft')!, replaySourceJobId: query.get('replaySourceJob')! } : {}) };
    const catalog: LoadCatalogRouteState = {};
    const queryText = query.get('q'); if (queryText) catalog.query = queryText;
    const packageFilter = query.get('package'); if (packageFilter) catalog.packageFilter = packageFilter;
    const kindFilter = query.get('kind'); if (kindFilter) catalog.kindFilter = kindFilter;
    const modelFilter = query.get('model'); if (modelFilter) catalog.modelFilter = modelFilter;
    const validationFilter = query.get('validation'); if (validationFilter) catalog.validationFilter = validationFilter;
    if (query.has('environment')) catalog.environment = query.get('environment') || '';
    const scrollY = Number(query.get('scrollY')); if (Number.isFinite(scrollY) && scrollY > 0) catalog.scrollY = Math.min(1000000, Math.floor(scrollY));
    return { kind: 'load', ...(Object.keys(catalog).length ? { catalog } : {}), ...(query.get('tab') === 'scenarios' ? { tab: 'scenarios' } : {}),
      ...(validReplayDraftQuery(query) ? { replayDraftId: query.get('replayDraft')!, replaySourceJobId: query.get('replaySourceJob')! } : {}) };
  }
  if (parts[0] === 'schedules' || parts[0] === 'load-schedules' || parts[0] === 'scheduled-load') {
    if (parts[1] === 'new' && parts.length === 2) {
      const returnTo = query.get('returnTo');
      return { kind: 'schedules', view: 'new', ...(query.get('intent') === 'validated' ? { intent: 'validated' } : {}),
        ...(returnTo?.startsWith('#/') && returnTo.length <= 4096 ? { returnTo } : {}) };
    }
    if (parts[1] && parts[2] === 'edit' && parts.length === 3) return {
      kind: 'schedules', view: 'edit', scheduleId: parts[1],
    };
    if (parts[1] && parts[2] === 'history' && (parts.length === 3 || parts.length === 4)) return {
      kind: 'schedules', view: parts[2], scheduleId: parts[1],
      ...(parts[2] === 'history' && parts[3] ? { occurrenceId: parts[3] } : {}),
    };
    return { kind: 'schedules' };
  }
  if (parts[0] === 'settings' && parts.length === 1) return { kind: 'settings' };
  return { kind: 'dashboard' };
}

export function formatRoute(route: AppRoute): string {
  switch (route.kind) {
    case 'dashboard': return '#/';
    case 'monitoring': return '#/monitoring';
    case 'run': {
      const root=route.packageId?'#/run/'+encodeURIComponent(route.packageId):'#/run';
      const target=route.target?'/'+encodeURIComponent(route.target.type)+'/'+encodeURIComponent(route.target.resourceId):'';
      const query=new URLSearchParams();if(route.environment!==undefined)query.set('environment',route.environment);if(route.target?.logicalId)query.set('id',route.target.logicalId);if(route.batch)query.set('mode','batch');
      if(route.replayDraftId&&route.replaySourceJobId){query.set('replayDraft',route.replayDraftId);query.set('replaySourceJob',route.replaySourceJobId);}
      return root+target+(query.size?'?'+query.toString():'');
    }
    case 'packages': return '#/packages';
    case 'package': {
      const root = '#/packages/' + encodeURIComponent(route.packageId);
      if (route.view === 'testdata') {
        const query = new URLSearchParams();
        if (route.environment !== undefined) query.set('environment', route.environment);
        if (route.testdataDescriptorId) query.set('descriptor', route.testdataDescriptorId);
        return root + '/testdata' + (query.size ? '?' + query.toString() : '');
      }
      if (route.view === 'source-file') {
        const query = new URLSearchParams();
        if (route.environment !== undefined) query.set('environment', route.environment);
        if (route.filePath) query.set('file', route.filePath);
        if (route.fileOrigin) query.set('origin', route.fileOrigin);
        if (route.sourceReference) {
          query.set('sourceType', route.sourceReference.type);
          query.set('sourceResourceId', route.sourceReference.resourceId);
          query.set('sourceReferenceId', route.sourceReference.referenceId);
        }
        if (route.returnTo) query.set('returnTo', route.returnTo);
        return root + '/source-file' + (query.size ? '?' + query.toString() : '');
      }
      if (!route.resource) return root;
      const resource = root + '/resources/' + encodeURIComponent(route.resource.type) + '/' + encodeURIComponent(route.resource.resourceId);
      if ((!route.resource.tab || route.resource.tab === 'definition') && !route.resource.logicalId && route.environment === undefined) return resource;
      const query = new URLSearchParams();
      if (route.resource.tab && route.resource.tab !== 'definition') query.set('detail', route.resource.tab);
      if (route.resource.logicalId) query.set('id', route.resource.logicalId);
      if (route.environment !== undefined) query.set('environment', route.environment);
      return resource + '?' + query.toString();
    }
    case 'configuration': {
      const query = new URLSearchParams();
      if (route.environment !== undefined) query.set('environment', route.environment);
      if (route.filePath) query.set('file', route.filePath);
      if (route.fileOrigin) query.set('origin', route.fileOrigin);
      if (route.sourceReference) {
        query.set('sourceType', route.sourceReference.type);
        query.set('sourceResourceId', route.sourceReference.resourceId);
        query.set('sourceReferenceId', route.sourceReference.referenceId);
      }
      if(route.configurationReferenceId)query.set('configurationReferenceId',route.configurationReferenceId);
      if (route.tab) query.set('tab', route.tab);
      if (route.view === 'package-manifest') query.set('view', route.view);
      const suffix = query.toString();
      return '#/configuration' + (route.packageId ? '/' + encodeURIComponent(route.packageId) : '') + (suffix ? '?' + suffix : '');
    }
    case 'quick-load': {
      const query = new URLSearchParams();
      if (route.environment !== undefined) query.set('environment', route.environment);
      if (route.target?.logicalId) query.set('id', route.target.logicalId);
      if (route.templateId) query.set('templateId', route.templateId);
      if (route.model) query.set('model', route.model);
      const suffix = query.toString();
      return '#/quick-load/' + encodeURIComponent(route.packageId)
        + (route.target ? '/' + encodeURIComponent(route.target.type) + '/' + encodeURIComponent(route.target.resourceId) : '')
        + (suffix ? '?' + suffix : '');
    }
    case 'jobs': {
      const query = new URLSearchParams();if (route.executionType) query.set('type',route.executionType);if (route.standalone) query.set('standalone','1');if (route.tab) query.set('tab',route.tab);
      return (route.jobId ? '#/jobs/' + encodeURIComponent(route.jobId) : '#/jobs') + (query.size ? '?' + query.toString() : '');
    }
    case 'job-console': {
      const view = route.view ? '?view=' + encodeURIComponent(route.view) : '';
      return '#/jobs/' + encodeURIComponent(route.jobId) + '/console' + view;
    }
    case 'job-artifact': {
      const query = new URLSearchParams();
      query.set('path', route.path);
      return '#/jobs/' + encodeURIComponent(route.jobId) + '/artifact?' + query.toString();
    }
    case 'load': {
      if (route.packageId) {
        const query=new URLSearchParams();if(route.environment!==undefined)query.set('environment',route.environment);if(route.templateId)query.set('templateId',route.templateId);
        if(route.replayDraftId&&route.replaySourceJobId){query.set('replayDraft',route.replayDraftId);query.set('replaySourceJob',route.replaySourceJobId);}
        return '#/advanced-load/' + encodeURIComponent(route.packageId) + (query.size?'?'+query.toString():'');
      }
      const query = new URLSearchParams();
      if (route.tab === 'scenarios') query.set('tab', 'scenarios');
      if (route.catalog?.query) query.set('q', route.catalog.query);
      if (route.catalog?.packageFilter && route.catalog.packageFilter !== 'all') query.set('package', route.catalog.packageFilter);
      if (route.catalog?.kindFilter && route.catalog.kindFilter !== 'all') query.set('kind', route.catalog.kindFilter);
      if (route.catalog?.modelFilter && route.catalog.modelFilter !== 'all') query.set('model', route.catalog.modelFilter);
      if (route.catalog?.validationFilter && route.catalog.validationFilter !== 'all') query.set('validation', route.catalog.validationFilter);
      if (route.catalog?.environment !== undefined) query.set('environment', route.catalog.environment);
      if (route.catalog?.scrollY) query.set('scrollY', String(Math.floor(route.catalog.scrollY)));
      if(route.replayDraftId&&route.replaySourceJobId){query.set('replayDraft',route.replayDraftId);query.set('replaySourceJob',route.replaySourceJobId);}
      return '#/advanced-load' + (query.size ? '?' + query.toString() : '');
    }
    case 'load-resource': {
      const query = new URLSearchParams();
      if (route.environment !== undefined) query.set('environment', route.environment);
      if (route.returnTo) query.set('returnTo', route.returnTo);
      return '#/load/resources/' + encodeURIComponent(route.packageId) + '/' + encodeURIComponent(route.resourceId)
        + (query.size ? '?' + query.toString() : '');
    }
    case 'schedules': {
      const path = '#/schedules' + (route.view === 'new' ? '/new'
        : route.scheduleId && route.view ? '/' + encodeURIComponent(route.scheduleId) + '/' + route.view
          + (route.view === 'history' && route.occurrenceId ? '/' + encodeURIComponent(route.occurrenceId) : '') : '');
      if (route.view !== 'new' || (!route.intent && !route.returnTo)) return path;
      const query = new URLSearchParams();
      if (route.intent) query.set('intent', route.intent);
      if (route.returnTo) query.set('returnTo', route.returnTo);
      return path + '?' + query.toString();
    }
    case 'settings': return '#/settings';
  }
}

function validReplayDraftQuery(query: URLSearchParams): boolean {
  return /^[RAC][0-9A-F]{32}$/.test(query.get('replayDraft') || '')
    && /^J[0-9A-F]{16}$/.test(query.get('replaySourceJob') || '');
}

export function normalizeRouteHash(to: string | AppRoute): string {
  if (typeof to !== 'string') return formatRoute(to);
  if (to.startsWith('#/')) return to;
  if (to.startsWith('#')) return '#/' + to.slice(1).replace(/^\//, '');
  return '#/' + to.replace(/^\/+/, '');
}

export interface NavigationGuardApi {
  navigate(to: string | AppRoute): boolean;
  registerUnsavedChanges(isDirty: boolean, description?: string): () => void;
}

export interface RouteState extends NavigationGuardApi {
  route: AppRoute;
  pendingNavigation: string | null;
  pendingDescription: string;
  isDirty: boolean;
  confirmNavigation(): void;
  cancelNavigation(): void;
}

export const NavigationGuardContext = createContext<NavigationGuardApi | null>(null);

export function useNavigationGuard(): NavigationGuardApi {
  const navigation = useContext(NavigationGuardContext);
  if (!navigation) throw new Error('useNavigationGuard must be used inside AppShell.');
  return navigation;
}

/** Hash router that mediates app links and browser back/forward changes through one guard. */
export function useRouteState(): RouteState {
  const initialHash = window.location.hash || '#/';
  const [route, setRoute] = useState<AppRoute>(() => parseRoute(initialHash));
  const [pendingNavigation, setPendingNavigation] = useState<string | null>(null);
  const [pendingDescription, setPendingDescription] = useState('');
  const [, setDirtyRevision] = useState(0);
  const committedHash = useRef(initialHash);
  const pendingHash = useRef<string | null>(null);
  const allowedHash = useRef<string | null>(null);
  const dirtyEntries = useRef(new Map<symbol, string>());
  const isDirty = dirtyEntries.current.size > 0;

  useEffect(() => {
    const current = window.location.hash || '#/';
    if (!isLegacyTestdataRoute(current)) return;
    const canonical = formatRoute(parseRoute(current));
    if (canonical === current) return;
    window.history.replaceState(null, '', window.location.pathname + window.location.search + canonical);
    committedHash.current = canonical;
    setRoute(parseRoute(canonical));
  }, [route]);

  const registerUnsavedChanges = useCallback((dirty: boolean, description = 'unsaved changes') => {
    const token = Symbol(description);
    if (dirty) dirtyEntries.current.set(token, description);
    setDirtyRevision(value => value + 1);
    return () => {
      dirtyEntries.current.delete(token);
      setDirtyRevision(value => value + 1);
    };
  }, []);

  const showConfirmation = useCallback((target: string) => {
    pendingHash.current = target;
    setPendingNavigation(target);
    setPendingDescription([...dirtyEntries.current.values()].join(', '));
  }, []);

  const navigate = useCallback((to: string | AppRoute) => {
    const target = normalizeRouteHash(to);
    if (target === committedHash.current) return true;
    if (dirtyEntries.current.size > 0) {
      showConfirmation(target);
      return false;
    }
    if (window.location.hash === target) {
      committedHash.current = target;
      setRoute(parseRoute(target));
      return true;
    }
    allowedHash.current = target;
    window.location.hash = target;
    return true;
  }, [showConfirmation]);

  const confirmNavigation = useCallback(() => {
    const target = pendingHash.current;
    if (!target) return;
    pendingHash.current = null;
    setPendingNavigation(null);
    setPendingDescription('');
    dirtyEntries.current.clear();
    setDirtyRevision(value => value + 1);
    if (window.location.hash === target) {
      committedHash.current = target;
      setRoute(parseRoute(target));
    } else {
      allowedHash.current = target;
      window.location.hash = target;
    }
  }, []);

  const cancelNavigation = useCallback(() => {
    pendingHash.current = null;
    setPendingNavigation(null);
    setPendingDescription('');
  }, []);

  useEffect(() => {
    const handleHashChange = () => {
      const actual = window.location.hash || '#/';
      if (actual === allowedHash.current) {
        allowedHash.current = null;
        committedHash.current = actual;
        setRoute(parseRoute(actual));
        return;
      }
      const previous = committedHash.current;
      if (actual === previous) return;
      if (dirtyEntries.current.size > 0) {
        showConfirmation(actual);
        window.history.replaceState(null, '', window.location.pathname + window.location.search + previous);
        return;
      }
      committedHash.current = actual;
      setRoute(parseRoute(actual));
    };
    window.addEventListener('hashchange', handleHashChange);
    return () => window.removeEventListener('hashchange', handleHashChange);
  }, [showConfirmation]);

  return { route, navigate, registerUnsavedChanges, pendingNavigation, pendingDescription, isDirty, confirmNavigation, cancelNavigation };
}
