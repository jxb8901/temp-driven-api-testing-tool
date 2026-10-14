import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';

export interface ResourceRoute {
  type: string;
  resourceId: string;
}

export type ConfigurationTab = 'source' | 'declared' | 'effective' | 'compare' | 'testdata';

export type AppRoute =
  | { kind: 'dashboard' }
  | { kind: 'packages' }
  | { kind: 'package'; packageId: string; resource?: ResourceRoute }
  | { kind: 'configuration'; packageId?: string; environment?: string; filePath?: string; fileOrigin?: string; tab?: ConfigurationTab }
  | { kind: 'quick-load'; packageId: string; target: ResourceRoute; environment?: string }
  | { kind: 'jobs'; jobId?: string }
  | { kind: 'load'; packageId?: string }
  | { kind: 'schedules' }
  | { kind: 'settings' };

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
  if (parts[0] === 'packages' && parts.length === 1) return { kind: 'packages' };
  if (parts[0] === 'packages' && parts[1]) {
    if (parts.length === 5 && parts[2] === 'resources') {
      return { kind: 'package', packageId: parts[1], resource: { type: parts[3], resourceId: parts[4] } };
    }
    return { kind: 'package', packageId: parts[1] };
  }
  if (parts[0] === 'configuration') {
    const rawTab = query.get('tab');
    const tab: ConfigurationTab | undefined = rawTab === 'source' || rawTab === 'declared' || rawTab === 'effective' || rawTab === 'compare' || rawTab === 'testdata' ? rawTab : undefined;
    return {
      kind: 'configuration',
      packageId: parts[1] || undefined,
      environment: query.has('environment') ? query.get('environment') || '' : undefined,
      filePath: query.get('file') || undefined,
      ...(query.get('origin') ? { fileOrigin: query.get('origin')! } : {}),
      tab,
    };
  }
  if (parts[0] === 'quick-load' && parts[1] && parts[2] && parts[3]) {
    return {
      kind: 'quick-load', packageId: parts[1], target: { type: parts[2], resourceId: parts[3] },
      environment: query.has('environment') ? query.get('environment') || '' : undefined,
    };
  }
  if (parts[0] === 'jobs') return parts[1] ? { kind: 'jobs', jobId: parts[1] } : { kind: 'jobs' };
  if (parts[0] === 'advanced-load') return parts[1] ? { kind: 'load', packageId: parts[1] } : { kind: 'load' };
  if (parts[0] === 'load-schedules' || parts[0] === 'scheduled-load') return { kind: 'schedules' };
  if (parts[0] === 'settings' && parts.length === 1) return { kind: 'settings' };
  return { kind: 'dashboard' };
}

export function formatRoute(route: AppRoute): string {
  switch (route.kind) {
    case 'dashboard': return '#/';
    case 'packages': return '#/packages';
    case 'package': {
      const root = '#/packages/' + encodeURIComponent(route.packageId);
      return route.resource
        ? root + '/resources/' + encodeURIComponent(route.resource.type) + '/' + encodeURIComponent(route.resource.resourceId)
        : root;
    }
    case 'configuration': {
      const query = new URLSearchParams();
      if (route.environment !== undefined) query.set('environment', route.environment);
      if (route.filePath) query.set('file', route.filePath);
      if (route.fileOrigin) query.set('origin', route.fileOrigin);
      if (route.tab) query.set('tab', route.tab);
      const suffix = query.toString();
      return '#/configuration' + (route.packageId ? '/' + encodeURIComponent(route.packageId) : '') + (suffix ? '?' + suffix : '');
    }
    case 'quick-load': {
      const query = new URLSearchParams();
      if (route.environment !== undefined) query.set('environment', route.environment);
      const suffix = query.toString();
      return '#/quick-load/' + encodeURIComponent(route.packageId) + '/' + encodeURIComponent(route.target.type)
        + '/' + encodeURIComponent(route.target.resourceId) + (suffix ? '?' + suffix : '');
    }
    case 'jobs': return route.jobId ? '#/jobs/' + encodeURIComponent(route.jobId) : '#/jobs';
    case 'load': return route.packageId ? '#/advanced-load/' + encodeURIComponent(route.packageId) : '#/advanced-load';
    case 'schedules': return '#/load-schedules';
    case 'settings': return '#/settings';
  }
}

export function normalizeRouteHash(to: string | AppRoute): string {
  if (typeof to !== 'string') return formatRoute(to);
  if (to.startsWith('#/')) return to;
  if (to.startsWith('#')) return '#/' + to.slice(1).replace(/^\//, '');
  return '#/' + to.replace(/^\/+/, '');
}

export interface NavigationGuardApi {
  navigate(to: string | AppRoute): void;
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
    if (target === committedHash.current) return;
    if (dirtyEntries.current.size > 0) {
      showConfirmation(target);
      return;
    }
    if (window.location.hash === target) {
      committedHash.current = target;
      setRoute(parseRoute(target));
      return;
    }
    allowedHash.current = target;
    window.location.hash = target;
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
