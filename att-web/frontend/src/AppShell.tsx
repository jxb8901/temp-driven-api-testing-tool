import { inspectionCache } from './inspectionCache';
import { createContext, useContext, useEffect, useLayoutEffect, useSyncExternalStore, useRef, useState, type MouseEvent, type ReactNode } from 'react';
import type { AsyncState, ServerVersion } from './types';
import { formatRoute, type AppRoute, type RouteState } from './router';
import { NavigationGuardContext } from './router';
import { api } from './api';
import { authUiText } from './authUiText';
import { contextRelativeReturnTo } from './contextPaths';
import { applyUiLocale, detectUiLocale, readUiLocale, readUiLocalePreference, saveUiLocalePreference, type UiLocale, type UiLocalePreference } from './uiLocale';
import { ChangePasswordDialog } from './ChangePasswordDialog';
import type { LocalAuthUser } from './types';

export type ThemePreference = 'system' | 'light' | 'dark';

export interface AppShellProps {
  route: AppRoute;
  navigation: RouteState;
  serverVersion: AsyncState<ServerVersion>;
  showScheduledLoad?: boolean;
  selectedPackage: string;
  selectedEnvironment: string;
  onPackageChange(packageId: string): void;
  onEnvironmentChange(environment: string): void;
  onJobAccepted(job: { jobId: string; packageId?: string }): void;
  children: ReactNode;
}

export interface AppShellContextValue {
  selectedPackage: string;
  selectedEnvironment: string;
  themePreference: ThemePreference;
  uiLocale: UiLocale;
  uiLocalePreference: UiLocalePreference;
  authMode?: 'container' | 'local';
  currentUser: Pick<LocalAuthUser,'loginId'|'displayName'|'role'> | null;
  canRevealConfigurationFields: boolean;
  onPackageChange(packageId: string): void;
  onEnvironmentChange(environment: string): void;
  onThemePreferenceChange(preference: ThemePreference): void;
  onUiLocaleChange(locale: UiLocalePreference): void;
  onJobAccepted(job: { jobId: string; packageId?: string }): void;
}

export const AppShellContext = createContext<AppShellContextValue | null>(null);

export function useAppShellContext(): AppShellContextValue {
  const value = useContext(AppShellContext);
  if (!value) throw new Error('useAppShellContext must be used inside AppShell.');
  return value;
}

function routeLink(event: MouseEvent<HTMLAnchorElement>, action: () => void) {
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
  event.preventDefault();
  action();
}

function routeTitle(route: AppRoute): string {
  if (route.kind === 'dashboard') return 'Dashboard';
  if (route.kind === 'packages') return 'Packages';
  if (route.kind === 'package') return route.resource ? route.resource.resourceId : route.packageId;
  if (route.kind === 'configuration') return route.filePath?.split('/').filter(Boolean).pop() || 'Configuration';
  if (route.kind === 'quick-load') return 'Quick Load';
  if (route.kind === 'jobs') return route.jobId ? 'Job ' + route.jobId : 'Jobs and results';
  if (route.kind === 'load') return route.packageId ? 'Advanced Load' : 'Load testing';
  if (route.kind === 'schedules') return 'Scheduled Load';
  if (route.kind === 'settings') return 'Settings';
  return 'Dashboard';
}

function activeNavigation(route: AppRoute): string {
  if (route.kind === 'packages') return 'packages';
  if (route.kind === 'package') return 'explorer';
  if (route.kind === 'configuration') return 'configuration';
  if (route.kind === 'quick-load') return 'load-testing';
  if (route.kind === 'jobs') return 'jobs';
  if (route.kind === 'load') return 'load-testing';
  if (route.kind === 'schedules') return 'scheduled-load';
  if (route.kind === 'settings') return 'settings';
  return 'dashboard';
}

function KeyboardDialog({ open, mode, showScheduledLoad, selectedPackage, onClose, onNavigate }: {
  open: boolean;
  mode: 'palette' | 'shortcuts';
  showScheduledLoad: boolean;
  selectedPackage: string;
  onClose(): void;
  onNavigate(route: AppRoute): void;
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const [query, setQuery] = useState('');
  const commands: Array<{ label: string; description: string; route: AppRoute }> = [
    { label: 'Dashboard', description: 'Workspace overview', route: { kind: 'dashboard' } },
    { label: 'Packages', description: 'Configured packages', route: { kind: 'packages' } },
    { label: 'Explorer and Run', description: 'Browse package resources', route: selectedPackage ? { kind: 'package', packageId: selectedPackage } : { kind: 'packages' } },
    { label: 'Configuration', description: 'Inspect package configuration and Testdata', route: selectedPackage ? { kind: 'configuration', packageId: selectedPackage } : { kind: 'configuration' } },
    { label: 'Load testing', description: 'Create a validated Load job', route: { kind: 'load' } },
    { label: 'Jobs and results', description: 'Inspect recent jobs', route: { kind: 'jobs' } },
    ...(showScheduledLoad ? [{ label: 'Scheduled Load', description: 'Manage recurring Load schedules', route: { kind: 'schedules' } as AppRoute }] : []),
    { label: 'Settings', description: 'Appearance and keyboard help', route: { kind: 'settings' } },
  ];
  const matches = commands.filter(command => `${command.label} ${command.description}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      if (typeof dialog.showModal === 'function') dialog.showModal();
      else dialog.setAttribute('open', '');
      window.setTimeout(() => (mode === 'palette' ? searchRef.current : closeRef.current)?.focus(), 0);
    } else if (!open && dialog.open) {
      if (typeof dialog.close === 'function') dialog.close();
      else dialog.removeAttribute('open');
    }
    if (open) setQuery('');
  }, [mode, open]);

  return <dialog ref={dialogRef} className="keyboard-dialog" aria-labelledby="keyboard-dialog-title"
    onCancel={event => { event.preventDefault(); onClose(); }}>
    <div className="keyboard-dialog-heading">
      <div><p className="eyebrow">ATT Studio</p><h2 id="keyboard-dialog-title">{mode === 'palette' ? 'Go to' : 'Keyboard shortcuts'}</h2></div>
      <button ref={closeRef} className="icon-button" type="button" aria-label="Close dialog" onClick={onClose}>×</button>
    </div>
    {mode === 'palette' ? <>
      <label className="sr-only" htmlFor="command-search">Search pages</label>
      <input ref={searchRef} id="command-search" type="search" value={query} onChange={event => setQuery(event.currentTarget.value)} placeholder="Search pages…" />
      <div className="command-list" role="list" aria-label="Pages">
        {matches.map(command => <div key={command.label} role="listitem"><button type="button" onClick={() => onNavigate(command.route)}>
          <span>{command.label}</span><small>{command.description}</small>
        </button></div>)}
        {matches.length === 0 && <p role="status">No matching pages.</p>}
      </div>
    </> : <>
      <dl className="shortcut-list">
        <div><dt>Open page search</dt><dd><kbd>⌘ K</kbd> / <kbd>Ctrl K</kbd> or <kbd>/</kbd></dd></div>
        <div><dt>Open this help</dt><dd><kbd>?</kbd></dd></div>
        <div><dt>Close a dialog or menu</dt><dd><kbd>Esc</kbd></dd></div>
        <div><dt>Start a validated Load draft</dt><dd><kbd>⌘ Enter</kbd> / <kbd>Ctrl Enter</kbd> · asks for confirmation</dd></div>
      </dl>
      <p className="keyboard-dialog-note">The slash and help shortcuts do not intercept text fields. The page-search key combination works from any field; the Load submit shortcut is available only on Advanced Load and always asks for confirmation.</p>
    </>}
  </dialog>;
}

function Breadcrumb({ label, href, navigate }: { label: string; href?: string; navigate: (to: string) => void }) {
  return <li>{href
    ? <a href={href} onClick={event => routeLink(event, () => navigate(href))}>{label}</a>
    : <span aria-current="page">{label}</span>}
  </li>;
}

function DiscardChangesDialog({ navigation }: { navigation: RouteState }) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (navigation.pendingNavigation && !dialog.open) dialog.showModal();
    if (!navigation.pendingNavigation && dialog.open) dialog.close();
  }, [navigation.pendingNavigation]);

  useEffect(() => {
    if (navigation.pendingNavigation) cancelRef.current?.focus();
  }, [navigation.pendingNavigation]);

  return (
    <dialog
      ref={dialogRef}
      className="discard-dialog"
      aria-labelledby="discard-title"
      aria-describedby="discard-description"
      onCancel={event => { event.preventDefault(); navigation.cancelNavigation(); }}
    >
      <p className="eyebrow">Unsaved work</p>
      <h2 id="discard-title">Discard your changes?</h2>
      <p id="discard-description">
        {navigation.pendingDescription
          ? 'You have unsaved ' + navigation.pendingDescription + '. If you continue, those changes will be lost.'
          : 'You have unsaved changes. If you continue, those changes will be lost.'}
      </p>
      <div className="dialog-actions">
        <button ref={cancelRef} className="button button-quiet" type="button" onClick={navigation.cancelNavigation}>Stay on this page</button>
        <button className="button button-danger" type="button" onClick={navigation.confirmNavigation}>Discard changes and continue</button>
      </div>
    </dialog>
  );
}

export function AppShell({
  route, navigation, serverVersion, showScheduledLoad = false, selectedPackage, selectedEnvironment,
  onPackageChange, onEnvironmentChange, onJobAccepted, children,
}: AppShellProps) {
  const [theme, setTheme] = useState<'light' | 'dark'>(() => {
    try {
      const saved = localStorage.getItem('att-ui-theme');
      if (saved === 'dark' || saved === 'light') return saved;
      return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    } catch {
      return 'light';
    }
  });
  const [themePreference, setThemePreference] = useState<ThemePreference>(() => {
    try {
      const saved = localStorage.getItem('att-ui-theme');
      return saved === 'dark' || saved === 'light' ? saved : 'system';
    } catch {
      return 'system';
    }
  });
  const [uiLocale, setUiLocale] = useState<UiLocale>(() => readUiLocale());
  const [uiLocalePreference, setUiLocalePreference] = useState<UiLocalePreference>(() => readUiLocalePreference());
  const [collapsed, setCollapsed] = useState(false);
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false);
  const [mobileViewport, setMobileViewport] = useState(() => window.matchMedia?.('(max-width: 820px)').matches || false);
  const [keyboardMode, setKeyboardMode] = useState<'palette' | 'shortcuts' | null>(null);
  const [currentUser, setCurrentUser] = useState<Pick<LocalAuthUser,'loginId'|'displayName'|'role'>|null>(null);
  const [canRevealConfigurationFields, setCanRevealConfigurationFields] = useState(false);
  const inspectionSessionEpoch = useSyncExternalStore(inspectionCache.subscribeSession, () => inspectionCache.sessionEpoch);
  useLayoutEffect(() => { inspectionCache.select(selectedPackage, selectedEnvironment); }, [selectedPackage, selectedEnvironment]);
  useEffect(() => {
    const clear = () => inspectionCache.clear();
    const visibility = () => { if (document.hidden) clear(); };
    window.addEventListener('pagehide', clear);
    document.addEventListener('visibilitychange', visibility);
    return () => { window.removeEventListener('pagehide', clear); document.removeEventListener('visibilitychange', visibility); };
  }, []);
  const [changePasswordOpen,setChangePasswordOpen]=useState(false);
  const mobileMenuButtonRef = useRef<HTMLButtonElement>(null);
  const sidebarRef = useRef<HTMLElement>(null);
  const wasMobileNavigationOpen = useRef(false);
  const currentPage = routeTitle(route);
  const activeNav = activeNavigation(route);
  const versionStatus = serverVersion.status === 'ready' ? 'Connected'
    : serverVersion.status === 'error' ? 'Unavailable' : 'Connecting…';
  const versionData = serverVersion.status === 'ready' ? serverVersion.data : null;
  useEffect(() => {
    if (!versionData) { setCurrentUser(null); setCanRevealConfigurationFields(false); return; }
    const controller = new AbortController();
    if (versionData.authMode === 'local') api.getCurrentUser(controller.signal).then(user => { if (!controller.signal.aborted) setCurrentUser(user); }).catch(() => {
        if (controller.signal.aborted) return;
        const returnTo = contextRelativeReturnTo(location.pathname, location.search, location.hash);
        location.assign(new URL('login.html?returnTo=' + encodeURIComponent(returnTo), document.baseURI).toString());
      });
    else setCurrentUser(null);
    api.getAuthCapabilities(controller.signal).then(result => {
      if (!controller.signal.aborted) setCanRevealConfigurationFields(result.configurationAdmin === true);
    }).catch(() => { if (!controller.signal.aborted) setCanRevealConfigurationFields(false); });
    return () => controller.abort();
  }, [versionData?.authMode, versionData?.apiVersion, inspectionSessionEpoch]);
  async function signOut() {
    try { await api.logout(); } finally {
      const login = new URL('login.html', document.baseURI);
      location.assign(login.toString());
    }
  }
  const action = route.kind === 'load' && route.packageId
    ? { label: 'Open package', target: '#/packages/' + encodeURIComponent(route.packageId), hidden: false }
    : route.kind === 'quick-load'
      ? { label: 'Return to Explorer', target: formatRoute({ kind: 'package', packageId: route.packageId, resource: route.target }), hidden: false }
      : route.kind === 'configuration' && route.packageId
        ? { label: 'Open Explorer', target: '#/packages/' + encodeURIComponent(route.packageId), hidden: false }
        : route.kind === 'package' && versionData?.inlineLoadEnabled === true
          ? { label: 'Start Load', target: '#/advanced-load/' + encodeURIComponent(route.packageId), hidden: false }
          : route.kind === 'load' || route.kind === 'jobs'
            ? { label: 'Browse packages', target: '#/packages', hidden: false }
            : { label: 'Browse packages', target: '#/packages', hidden: false };

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  useEffect(() => {
    applyUiLocale(uiLocale);
  }, [uiLocale]);

  useEffect(() => {
    if (uiLocalePreference !== 'browser') return;
    const update = () => setUiLocale(detectUiLocale());
    window.addEventListener('languagechange', update);
    return () => window.removeEventListener('languagechange', update);
  }, [uiLocalePreference]);

  useEffect(() => {
    const media = window.matchMedia?.('(prefers-color-scheme: dark)');
    if (!media || themePreference !== 'system') return;
    const update = () => setTheme(media.matches ? 'dark' : 'light');
    update();
    media.addEventListener?.('change', update);
    if (!media.addEventListener) media.addListener?.(update);
    return () => {
      media.removeEventListener?.('change', update);
      if (!media.removeEventListener) media.removeListener?.(update);
    };
  }, [themePreference]);

  useEffect(() => {
    const media = window.matchMedia?.('(max-width: 820px)');
    if (!media) return;
    const update = () => {
      setMobileViewport(media.matches);
      if (!media.matches) setMobileNavigationOpen(false);
    };
    update();
    media.addEventListener?.('change', update);
    if (!media.addEventListener) media.addListener?.(update);
    return () => {
      media.removeEventListener?.('change', update);
      if (!media.removeEventListener) media.removeListener?.(update);
    };
  }, []);

  useEffect(() => {
    if (mobileNavigationOpen) sidebarRef.current?.querySelector<HTMLElement>('.nav-link')?.focus();
    else if (wasMobileNavigationOpen.current && mobileViewport) mobileMenuButtonRef.current?.focus();
    wasMobileNavigationOpen.current = mobileNavigationOpen;
  }, [mobileNavigationOpen, mobileViewport]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      const editable = Boolean(target && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)));
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setKeyboardMode('palette');
      } else if (!editable && !event.metaKey && !event.ctrlKey && !event.altKey && event.key === '/') {
        event.preventDefault();
        setKeyboardMode('palette');
      } else if (!editable && !event.metaKey && !event.ctrlKey && !event.altKey && event.key === '?') {
        event.preventDefault();
        setKeyboardMode('shortcuts');
      } else if (event.key === 'Escape' && !keyboardMode && mobileNavigationOpen) {
        setMobileNavigationOpen(false);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [keyboardMode, mobileNavigationOpen]);

  useEffect(() => {
    if (!mobileNavigationOpen) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMobileNavigationOpen(false);
    };
    window.addEventListener('keydown', closeOnEscape);
    return () => window.removeEventListener('keydown', closeOnEscape);
  }, [mobileNavigationOpen]);

  const navigate = navigation.navigate;
  const link = (to: string) => (event: MouseEvent<HTMLAnchorElement>) => routeLink(event, () => {
    navigate(to);
    setMobileNavigationOpen(false);
  });
  const packageHref = selectedPackage ? '#/packages/' + encodeURIComponent(selectedPackage) : '#/packages';
  const loadHref = selectedPackage ? '#/advanced-load/' + encodeURIComponent(selectedPackage) : '#/advanced-load';
  const configurationHref = formatRoute(selectedPackage
    ? { kind: 'configuration', packageId: selectedPackage, environment: selectedEnvironment }
    : { kind: 'configuration' });
  const changeThemePreference = (preference: ThemePreference) => {
    setThemePreference(preference);
    if (preference === 'system') {
      try { localStorage.removeItem('att-ui-theme'); } catch { /* Storage can be unavailable in private browsing. */ }
      setTheme(window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
      return;
    }
    setTheme(preference);
    try { localStorage.setItem('att-ui-theme', preference); } catch { /* Storage can be unavailable in private browsing. */ }
  };
  const changeTheme = () => changeThemePreference(theme === 'dark' ? 'light' : 'dark');
  const changeUiLocale = (preference: UiLocalePreference) => {
    saveUiLocalePreference(preference);
    setUiLocalePreference(preference);
    setUiLocale(preference === 'browser' ? detectUiLocale() : preference);
  };

  return (
    <NavigationGuardContext.Provider value={navigation}>
      <AppShellContext.Provider value={{ selectedPackage, selectedEnvironment, themePreference, uiLocale, uiLocalePreference, authMode: versionData?.authMode, currentUser, canRevealConfigurationFields, onPackageChange, onEnvironmentChange, onThemePreferenceChange: changeThemePreference, onUiLocaleChange: changeUiLocale, onJobAccepted }}>
      <div className={'app-layout' + (collapsed ? ' is-collapsed' : '') + (mobileNavigationOpen ? ' mobile-nav-open' : '')} data-page={currentPage}>
        <a className="skip-link" href="#main-content">Skip to main content</a>
        <button className="sidebar-backdrop" type="button" aria-label="Close navigation" aria-hidden={!mobileNavigationOpen} onClick={() => setMobileNavigationOpen(false)} tabIndex={-1} />
        <aside ref={sidebarRef} className="app-sidebar" aria-label="Application sidebar"
          aria-hidden={mobileViewport && !mobileNavigationOpen ? true : undefined}
          inert={mobileViewport && !mobileNavigationOpen}>
          <div className="sidebar-brand-row">
            <a href="#/" className="sidebar-brand" aria-label="ATT Studio home" onClick={link('#/')}>
              <span className="brand-mark" aria-hidden="true">A</span><span className="brand-name">ATT Studio</span>
            </a>
            <button className="icon-button sidebar-collapse" type="button" aria-label={collapsed ? 'Expand navigation' : 'Collapse navigation'} aria-expanded={!collapsed} onClick={() => setCollapsed(value => !value)}>
              <span aria-hidden="true">{collapsed ? '›' : '‹'}</span>
            </button>
          </div>
          <nav id="primary-navigation" className="primary-navigation" aria-label="Primary navigation">
            <p className="nav-group-label">Workspace</p>
            <a className="nav-link" href="#/" data-nav="dashboard" aria-current={activeNav === 'dashboard' ? 'page' : undefined} onClick={link('#/')}>
              <span className="nav-icon" aria-hidden="true">⌂</span><span className="nav-label">Dashboard</span>
            </a>
            <a className="nav-link" href="#/packages" data-nav="packages" aria-current={activeNav === 'packages' ? 'page' : undefined} onClick={link('#/packages')}>
              <span className="nav-icon" aria-hidden="true">▦</span><span className="nav-label">Packages</span>
            </a>
            <a className="nav-link" href={packageHref} data-nav="explorer" aria-current={activeNav === 'explorer' ? 'page' : undefined} onClick={link(packageHref)}>
              <span className="nav-icon" aria-hidden="true">⌕</span><span className="nav-label">Explorer and Run</span>
            </a>
            <a className="nav-link" href={configurationHref} data-nav="configuration" aria-current={activeNav === 'configuration' ? 'page' : undefined} onClick={link(configurationHref)}>
              <span className="nav-icon" aria-hidden="true">⚙</span><span className="nav-label">Configuration</span>
            </a>
            <a className="nav-link" href={loadHref} data-nav="load-testing" aria-current={activeNav === 'load-testing' ? 'page' : undefined} onClick={link(loadHref)}>
              <span className="nav-icon" aria-hidden="true">⌁</span><span className="nav-label">Load testing</span>
            </a>
            <a className="nav-link" href="#/jobs" data-nav="jobs" aria-current={activeNav === 'jobs' ? 'page' : undefined} onClick={link('#/jobs')}>
              <span className="nav-icon" aria-hidden="true">▤</span><span className="nav-label">Jobs and results</span>
            </a>
            {showScheduledLoad && <a className="nav-link" href="#/load-schedules" data-nav="scheduled-load" aria-current={activeNav === 'scheduled-load' ? 'page' : undefined} onClick={link('#/load-schedules')}>
              <span className="nav-icon" aria-hidden="true">◷</span><span className="nav-label">Scheduled Load</span>
            </a>}
            <a className="nav-link" href="#/settings" data-nav="settings" aria-current={activeNav === 'settings' ? 'page' : undefined} onClick={link('#/settings')}>
              <span className="nav-icon" aria-hidden="true">⚙</span><span className="nav-label">Settings</span>
            </a>
          </nav>
          <div className="sidebar-footer"><span>ATT Server</span><span className="sidebar-version">{versionData?.version || '4.2.0'}</span></div>
        </aside>

        <div className="app-frame">
          <header className="topbar">
            <button ref={mobileMenuButtonRef} className="icon-button mobile-menu-button" type="button" aria-label="Open navigation" aria-controls="primary-navigation" aria-expanded={mobileNavigationOpen} onClick={() => setMobileNavigationOpen(true)}>
              <span aria-hidden="true">☰</span>
            </button>
            <a href="#/" className="topbar-brand" onClick={link('#/')}><span className="brand-mark" aria-hidden="true">A</span><span>ATT Studio</span></a>
            <div className="topbar-spacer" />
            <button className="topbar-command" type="button" onClick={() => setKeyboardMode('palette')} aria-label="Search pages, shortcut Control or Command K or slash">
              <span className="topbar-command-label">Search pages</span><kbd>Ctrl / ⌘ K</kbd>
            </button>
            <button className="icon-button shortcut-help" type="button" aria-label="Keyboard shortcuts" title="Keyboard shortcuts (?)" onClick={() => setKeyboardMode('shortcuts')}>?</button>
            <div className="topbar-status"><span className="topbar-status-label">Server</span><span className="connection-status" role="status" aria-live="polite" data-state={versionStatus.toLowerCase()}>{versionStatus}</span></div>
            {currentUser && <details className="att-current-user-menu" onKeyDown={event=>{if(event.key==='Escape')event.currentTarget.open=false;}}>
              <summary aria-label={authUiText('accountMenu')}><span>{currentUser.displayName}</span><span className="att-user-menu-caret" aria-hidden="true">⌄</span></summary>
              <div className="att-user-menu-panel"><span className="att-user-menu-identity">{currentUser.loginId} · {currentUser.role}</span>
                <button type="button" onClick={event=>{event.currentTarget.closest('details')?.removeAttribute('open');setChangePasswordOpen(true);}}>{authUiText('changePassword')}</button>
                <button type="button" onClick={signOut}>{authUiText('signOut')}</button>
              </div>
            </details>}
            <button className="theme-toggle" type="button" aria-pressed={theme === 'dark'} aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'} onClick={changeTheme}>
              <span className="theme-icon" aria-hidden="true">{theme === 'dark' ? '☼' : '◐'}</span><span className="theme-label">{theme === 'dark' ? 'Light' : 'Dark'}</span>
            </button>
          </header>

          <div className="page-chrome">
            <nav id="breadcrumbs" className="breadcrumbs" aria-label="Breadcrumb">
              <ol>
                <Breadcrumb label="Dashboard" href={route.kind === 'dashboard' ? undefined : '#/'} navigate={navigate} />
                {route.kind === 'packages' && <Breadcrumb label="Packages" navigate={navigate} />}
                {route.kind === 'package' && <>
                  <Breadcrumb label="Packages" href="#/packages" navigate={navigate} />
                  <Breadcrumb label={route.packageId} href={route.resource ? packageHref : undefined} navigate={navigate} />
                  {route.resource && <Breadcrumb label={route.resource.resourceId} navigate={navigate} />}
                </>}
                {route.kind === 'configuration' && <>
                  <Breadcrumb label="Configuration" href={route.packageId ? '#/configuration' : undefined} navigate={navigate} />
                  {route.packageId && <Breadcrumb label={route.packageId} navigate={navigate} />}
                  {route.filePath && <Breadcrumb label={route.filePath.split('/').filter(Boolean).pop() || route.filePath} navigate={navigate} />}
                </>}
                {route.kind === 'quick-load' && <>
                  <Breadcrumb label="Load testing" href="#/advanced-load" navigate={navigate} />
                  <Breadcrumb label="Quick Load" navigate={navigate} />
                </>}
                {route.kind === 'load' && <>
                  <Breadcrumb label="Load testing" href={route.packageId ? '#/advanced-load' : undefined} navigate={navigate} />
                  {route.packageId && <Breadcrumb label={route.packageId} href={packageHref} navigate={navigate} />}
                  {route.packageId && <Breadcrumb label="Advanced Load" navigate={navigate} />}
                </>}
                {route.kind === 'jobs' && <>
                  <Breadcrumb label="Jobs and results" href={route.jobId ? '#/jobs' : undefined} navigate={navigate} />
                  {route.jobId && <Breadcrumb label={'Job ' + route.jobId} navigate={navigate} />}
                </>}
                {route.kind === 'schedules' && <Breadcrumb label="Scheduled Load" navigate={navigate} />}
                {route.kind === 'settings' && <Breadcrumb label="Settings" navigate={navigate} />}
              </ol>
            </nav>
            <div className="context-toolbar" role="group" aria-label="Workspace context and actions">
              <div className="context-chip"><span>Package</span><strong>{selectedPackage || 'All packages'}</strong></div>
              <div className="context-chip"><span>Environment</span><strong>{selectedEnvironment || 'Per run'}</strong></div>
              <div className="context-chip context-identity"><span>Identity</span>
                {versionData?.authMode === 'local'
                  ? <><strong>{currentUser?.displayName || currentUser?.loginId || 'Loading identity…'}</strong><small>{currentUser ? `${currentUser.loginId} · ${currentUser.role}` : 'Loading authenticated identity'}</small></>
                  : <><strong>Server managed</strong><small>not exposed by API</small></>}
              </div>
              <a className="button button-primary context-action" href={action.target} hidden={action.hidden} onClick={link(action.target)}>{action.label}</a>
            </div>
          </div>

          <main key={inspectionSessionEpoch} id="main-content" tabIndex={-1}>{children}</main>
          <footer className="page-footer"><span>ATT Server</span><span>API v{versionData?.apiVersion || '1'}</span></footer>
        </div>
        <DiscardChangesDialog navigation={navigation} />
        <ChangePasswordDialog open={changePasswordOpen} locale={uiLocale} onClose={()=>setChangePasswordOpen(false)} />
        <KeyboardDialog open={keyboardMode !== null} mode={keyboardMode || 'palette'} showScheduledLoad={showScheduledLoad} selectedPackage={selectedPackage}
          onClose={() => setKeyboardMode(null)} onNavigate={target => { navigate(target); setKeyboardMode(null); setMobileNavigationOpen(false); }} />
      </div>
      </AppShellContext.Provider>
    </NavigationGuardContext.Provider>
  );
}
