import { inspectionCache } from './inspectionCache';
import { createContext, useContext, useEffect, useLayoutEffect, useSyncExternalStore, useRef, useState, type MouseEvent, type ReactNode } from 'react';
import { ApiError } from './types';
import type { AsyncState, ServerVersion } from './types';
import { formatRoute, type AppRoute, type RouteState } from './router';
import { NavigationGuardContext } from './router';
import { api } from './api';
import { authUiText } from './authUiText';
import { contextRelativeReturnTo } from './contextPaths';
import { applyUiLocale, clearStoredUiLocalePreference, detectUiLocale, readUiLocale, readStoredUiLocalePreference, readUiLocalePreference, saveUiLocalePreference, type UiLocale, type UiLocalePreference } from './uiLocale';
import { ChangePasswordDialog } from './ChangePasswordDialog';
import type { LocalAuthUser } from './types';
import { JobConsoleProvider } from './features/load/JobConsoleProvider';

export type ThemePreference = 'system' | 'light' | 'dark';
export type PreferenceStatus = 'loading'|'idle'|'saving'|'saved'|'failed'|'browser-only';
export interface AccessCapabilities { operate: boolean; accountManage: boolean; systemSettingsWrite: boolean; configurationAdmin: boolean }

type PreferenceField = 'language'|'theme';
type PreferenceWriteState = 'idle'|'pending'|'failed';
const pendingPreferenceKeys:Record<PreferenceField,string>={language:'att-ui-language-pending',theme:'att-ui-theme-pending'};

function readPendingPreference<T extends string>(field:PreferenceField,identity:string,allowed:readonly T[]):T|undefined {
  try {
    const stored=JSON.parse(localStorage.getItem(pendingPreferenceKeys[field])||'null') as unknown;
    if(!stored||typeof stored!=='object'||Array.isArray(stored))return undefined;
    const value=(stored as Record<string,unknown>)[identity];
    return typeof value==='string'&&allowed.includes(value as T)?value as T:undefined;
  } catch { return undefined; }
}

function writePendingPreference(field:PreferenceField,identity:string,value:string):void {
  try {
    const parsed=JSON.parse(localStorage.getItem(pendingPreferenceKeys[field])||'{}') as unknown;
    const stored=parsed&&typeof parsed==='object'&&!Array.isArray(parsed)?{...parsed as Record<string,unknown>}:{};
    stored[identity]=value;
    localStorage.setItem(pendingPreferenceKeys[field],JSON.stringify(stored));
  } catch { /* Keep the in-memory selection when browser storage is unavailable. */ }
}

function clearPendingPreference(field:PreferenceField,identity:string):void {
  try {
    const parsed=JSON.parse(localStorage.getItem(pendingPreferenceKeys[field])||'null') as unknown;
    if(!parsed||typeof parsed!=='object'||Array.isArray(parsed))return;
    const stored={...parsed as Record<string,unknown>};
    delete stored[identity];
    if(Object.keys(stored).length)localStorage.setItem(pendingPreferenceKeys[field],JSON.stringify(stored));
    else localStorage.removeItem(pendingPreferenceKeys[field]);
  } catch { /* Browser storage can be unavailable. */ }
}

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
  accessCapabilities: AccessCapabilities;
  preferenceStatus: PreferenceStatus;
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
  if (route.kind === 'run') return 'Run';
  if (route.kind === 'packages') return 'Explorer';
  if (route.kind === 'package') return route.resource ? route.resource.resourceId : route.packageId;
  if (route.kind === 'configuration') return route.filePath?.split('/').filter(Boolean).pop() || 'Configuration';
  if (route.kind === 'quick-load') return 'Quick Load';
  if (route.kind === 'jobs') return route.jobId ? 'Job ' + route.jobId : 'Jobs and results';
  if (route.kind === 'job-console') return 'Job ' + route.jobId + ' console';
  if (route.kind === 'job-artifact') return route.path.split('/').filter(Boolean).pop() || 'Job artifact';
  if (route.kind === 'load') return route.packageId ? 'Advanced Load' : 'Load testing';
  if (route.kind === 'load-resource') return route.resourceId.split('/').pop() || 'Load resource';
  if (route.kind === 'schedules') return 'Schedules';
  if (route.kind === 'settings') return 'Settings';
  return 'Dashboard';
}

function activeNavigation(route: AppRoute): string {
  if (route.kind === 'run' || route.kind === 'packages' || route.kind === 'package') return 'explorer';
  if (route.kind === 'configuration') return 'configuration';
  if (route.kind === 'quick-load') return 'load-testing';
  if (route.kind === 'jobs' || route.kind === 'job-console' || route.kind === 'job-artifact') return 'jobs';
  if (route.kind === 'load' || route.kind === 'load-resource') return 'load-testing';
  if (route.kind === 'schedules') return 'scheduled-load';
  if (route.kind === 'settings') return 'settings';
  return 'dashboard';
}

function KeyboardDialog({ open, mode, showScheduledLoad, selectedPackage, accessCapabilities, onClose, onNavigate }: {
  open: boolean;
  mode: 'palette' | 'shortcuts';
  showScheduledLoad: boolean;
  selectedPackage: string;
  accessCapabilities: AccessCapabilities;
  onClose(): void;
  onNavigate(route: AppRoute): void;
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const [query, setQuery] = useState('');
  const commands: Array<{ label: string; description: string; route: AppRoute }> = accessCapabilities.operate ? [
    { label: 'Dashboard', description: 'Dashboard overview', route: { kind: 'dashboard' } },
    { label: 'Explorer', description: 'Choose a package, browse resources, and run targets', route: selectedPackage ? { kind: 'package', packageId: selectedPackage } : { kind: 'packages' } },
    { label: 'Configuration', description: 'Inspect shared runtime configuration and Package-local Testdata', route: selectedPackage ? { kind: 'configuration', packageId: selectedPackage } : { kind: 'configuration' } },
    { label: 'Load testing', description: 'Create a validated Load job', route: { kind: 'load' } },
    { label: 'Jobs and results', description: 'Inspect recent jobs', route: { kind: 'jobs' } },
    ...(showScheduledLoad ? [{ label: 'Schedules', description: 'Manage RUN and LOAD schedules', route: { kind: 'schedules' } as AppRoute }] : []),
    { label: 'Settings', description: 'Appearance and keyboard help', route: { kind: 'settings' } },
  ] : accessCapabilities.accountManage ? [
    { label: 'Settings', description: 'Manage accounts and administration settings', route: { kind: 'settings' } },
  ] : [];
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
  const [preferenceStatus,setPreferenceStatus]=useState<PreferenceStatus>('loading');
  const preferencesAvailableRef=useRef<boolean|null>(null);
  const preferenceEdits=useRef({language:0,theme:0});
  const preferenceWriteStates=useRef<Record<PreferenceField,PreferenceWriteState>>({language:'idle',theme:'idle'});
  const pendingPreferenceWrite=useRef<{language?:UiLocalePreference;theme?:ThemePreference}>({});
  const preferenceWriteChain=useRef<Promise<void>>(Promise.resolve());
  const preferenceStatusGeneration=useRef(0);
  const lastPreferenceIdentity=useRef<string|null>(null);
  const [authContextKey,setAuthContextKey]=useState('');
  const [canRevealConfigurationFields, setCanRevealConfigurationFields] = useState(false);
  const [accessCapabilities, setAccessCapabilities] = useState<AccessCapabilities>({ operate: false, accountManage: false, systemSettingsWrite: false, configurationAdmin: false });
  const [capabilitiesResolved, setCapabilitiesResolved] = useState(false);
  const inspectionSessionEpoch = useSyncExternalStore(inspectionCache.subscribeSession, () => inspectionCache.sessionEpoch);
  useLayoutEffect(() => { inspectionCache.select(selectedPackage, selectedEnvironment); }, [selectedPackage, selectedEnvironment]);
  useEffect(() => {
    // A hidden tab may have refilled while another tab changed the account.
    // Invalidate cached projections on suspension and restore without remounting
    // the page: remounting also discards unsaved Debug form state.
    const reset = () => { inspectionCache.clear(true); };
    window.addEventListener('pagehide', reset);
    window.addEventListener('pageshow', reset);
    document.addEventListener('visibilitychange', reset);
    return () => {
      window.removeEventListener('pagehide', reset);
      window.removeEventListener('pageshow', reset);
      document.removeEventListener('visibilitychange', reset);
    };
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
  const applyThemePreference = (preference: ThemePreference) => {
    setThemePreference(preference);
    setTheme(preference==='system'?(window.matchMedia?.('(prefers-color-scheme: dark)').matches?'dark':'light'):preference);
  };
  const applyLocalePreference = (preference: UiLocalePreference) => {
    setUiLocalePreference(preference);setUiLocale(preference==='browser'?detectUiLocale():preference);
  };
  const clearLegacyLanguagePreference = () => { clearStoredUiLocalePreference(); };
  const clearLegacyThemePreference = () => { try{localStorage.removeItem('att-ui-theme');}catch{/* Storage can be unavailable. */} };
  const clearLegacyPreferences = () => {
    clearLegacyLanguagePreference();clearLegacyThemePreference();
  };
  const saveLocalThemePreference = (preference:ThemePreference) => {
    try{if(preference==='system')localStorage.removeItem('att-ui-theme');else localStorage.setItem('att-ui-theme',preference);return true;}catch{return false;}
  };
  const updatePreferenceWriteStatus = (whenClean:'idle'|'saved'='saved') => {
    const states=Object.values(preferenceWriteStates.current);
    if(states.includes('pending'))setPreferenceStatus('saving');
    else if(states.includes('failed'))setPreferenceStatus('failed');
    else setPreferenceStatus(whenClean);
  };
  const queuePreferenceWrite = (input:{language?:UiLocalePreference;theme?:ThemePreference},migrateIfAbsent=false) => {
    ++preferenceStatusGeneration.current;
    const languageEdit=preferenceEdits.current.language,themeEdit=preferenceEdits.current.theme;
    const identity=lastPreferenceIdentity.current;
    if(input.language!==undefined) {
      preferenceWriteStates.current.language='pending';
      if(identity)writePendingPreference('language',identity,input.language);
    }
    if(input.theme!==undefined) {
      preferenceWriteStates.current.theme='pending';
      if(identity)writePendingPreference('theme',identity,input.theme);
    }
    setPreferenceStatus('saving');
    const write=preferenceWriteChain.current.catch(()=>undefined).then(()=>{
      return identity===lastPreferenceIdentity.current?api.putUserPreferences({...input,...(migrateIfAbsent?{migrateIfAbsent:true}:{})}):null;
    });
    preferenceWriteChain.current=write.then(result=>{
      if(!result||!identity||identity!==lastPreferenceIdentity.current)return;
      if(!result.available) {
        preferencesAvailableRef.current=false;
        if(input.language!==undefined&&languageEdit===preferenceEdits.current.language)preferenceWriteStates.current.language='idle';
        if(input.theme!==undefined&&themeEdit===preferenceEdits.current.theme)preferenceWriteStates.current.theme='idle';
        setPreferenceStatus('browser-only');
        return;
      }
      if(input.language!==undefined&&languageEdit===preferenceEdits.current.language) {
        preferenceWriteStates.current.language='idle';
        clearLegacyLanguagePreference();clearPendingPreference('language',identity);
      }
      if(input.theme!==undefined&&themeEdit===preferenceEdits.current.theme) {
        preferenceWriteStates.current.theme='idle';
        clearLegacyThemePreference();clearPendingPreference('theme',identity);
      }
      updatePreferenceWriteStatus();
    }).catch(()=>{
      if(!identity||identity!==lastPreferenceIdentity.current)return;
      if(input.language!==undefined&&languageEdit===preferenceEdits.current.language)preferenceWriteStates.current.language='failed';
      if(input.theme!==undefined&&themeEdit===preferenceEdits.current.theme)preferenceWriteStates.current.theme='failed';
      updatePreferenceWriteStatus();
    });
    return preferenceWriteChain.current;
  };
  const recordPreferenceEdit = (kind:'language'|'theme',value:UiLocalePreference|ThemePreference,localSaved:boolean) => {
    preferenceEdits.current[kind]++;
    if(kind==='language')pendingPreferenceWrite.current.language=value as UiLocalePreference;
    else pendingPreferenceWrite.current.theme=value as ThemePreference;
    const hasIdentity=!!currentUser||(versionData?.authMode==='container'&&versionData.authenticationRequired!==false);
    if(!hasIdentity||preferencesAvailableRef.current===false) {
      delete pendingPreferenceWrite.current[kind];preferenceWriteStates.current[kind]='idle';setPreferenceStatus(localSaved?'browser-only':'failed');
    } else {
      preferenceWriteStates.current[kind]='pending';
      const identity=lastPreferenceIdentity.current;
      if(identity)writePendingPreference(kind,identity,value);
      setPreferenceStatus('saving');
      if(preferencesAvailableRef.current===true) {
        const input=kind==='language'?{language:value as UiLocalePreference}:{theme:value as ThemePreference};
        delete pendingPreferenceWrite.current[kind];void queuePreferenceWrite(input);
      }
    }
  };
  useEffect(() => {
    if (!versionData) { setCurrentUser(null);setAuthContextKey('');setCanRevealConfigurationFields(false); setAccessCapabilities({ operate: false, accountManage: false, systemSettingsWrite: false, configurationAdmin: false }); setCapabilitiesResolved(false); return; }
    const controller = new AbortController();
    setCapabilitiesResolved(false);
    if (versionData.authMode === 'local') api.getCurrentUser(controller.signal).then(user => { if (!controller.signal.aborted) setCurrentUser(user); }).catch(() => {
        if (controller.signal.aborted) return;
        const returnTo = contextRelativeReturnTo(location.pathname, location.search, location.hash);
        location.assign(new URL('login.html?returnTo=' + encodeURIComponent(returnTo), document.baseURI).toString());
      });
    else setCurrentUser(null);
    api.getAuthCapabilities(controller.signal).then(result => {
      if (!controller.signal.aborted) {
        const caps=result.capabilities || {};
        const next={operate:caps.operate===true,accountManage:caps.accountManage===true,systemSettingsWrite:caps.systemSettingsWrite===true,configurationAdmin:result.configurationAdmin===true||caps.configurationAdmin===true};
        setCanRevealConfigurationFields(next.configurationAdmin);setAccessCapabilities(next);setAuthContextKey(result.inspectionCacheContext||`auth:${versionData.authMode||'container'}`);setCapabilitiesResolved(true);
      }
    }).catch(() => { if (!controller.signal.aborted) {setCanRevealConfigurationFields(false);setAccessCapabilities({ operate: false, accountManage: false, systemSettingsWrite: false, configurationAdmin: false });setAuthContextKey(`auth:${versionData.authMode||'container'}`);setCapabilitiesResolved(true);} });
    return () => controller.abort();
  }, [versionData?.authMode, versionData?.apiVersion, inspectionSessionEpoch]);
  useEffect(() => {
    if(!versionData){preferenceStatusGeneration.current++;preferencesAvailableRef.current=null;return;}
    if(versionData.authenticationRequired===false){preferenceStatusGeneration.current++;preferencesAvailableRef.current=false;setPreferenceStatus('browser-only');return;}
    if(versionData.authMode==='local'&&!currentUser)return;
    if(!authContextKey)return;
    const controller=new AbortController();
    const identity=versionData.authMode==='local'?`local:${currentUser?.loginId||''}`:`container:${authContextKey}`;
    if(lastPreferenceIdentity.current&&lastPreferenceIdentity.current!==identity){preferenceStatusGeneration.current++;clearLegacyPreferences();applyThemePreference('system');applyLocalePreference('browser');pendingPreferenceWrite.current={};preferenceWriteStates.current={language:'idle',theme:'idle'};preferenceEdits.current={language:preferenceEdits.current.language+1,theme:preferenceEdits.current.theme+1};}
    lastPreferenceIdentity.current=identity;
    preferencesAvailableRef.current=null;
    const statusGeneration=++preferenceStatusGeneration.current;
    const languageEdit=preferenceEdits.current.language,themeEdit=preferenceEdits.current.theme;
    setPreferenceStatus('loading');
    void api.getUserPreferences(controller.signal).then(async remote=>{
      if(controller.signal.aborted)return;
      if(!remote.available){preferencesAvailableRef.current=false;pendingPreferenceWrite.current={};if(statusGeneration===preferenceStatusGeneration.current)setPreferenceStatus('browser-only');return;}
      preferencesAvailableRef.current=true;
      const legacyLanguage=readStoredUiLocalePreference();let legacyTheme:ThemePreference|undefined;
      try{const value=localStorage.getItem('att-ui-theme');if(value==='light'||value==='dark')legacyTheme=value;}catch{/* Migration is unavailable when browser storage is blocked. */}
      const recoverable:{language?:UiLocalePreference;theme?:ThemePreference}={};
      const recoveredLanguage=readPendingPreference('language',identity,['browser','en','zh-Hant'] as const);
      const recoveredTheme=readPendingPreference('theme',identity,['system','light','dark'] as const);
      if(recoveredLanguage!==undefined)recoverable.language=recoveredLanguage;
      if(recoveredTheme!==undefined)recoverable.theme=recoveredTheme;
      const pending={...recoverable,...pendingPreferenceWrite.current};
      pendingPreferenceWrite.current={};
      const canApplyLanguage=languageEdit===preferenceEdits.current.language,canApplyTheme=themeEdit===preferenceEdits.current.theme;
      const migration:{language?:UiLocalePreference;theme?:ThemePreference}={};
      if(canApplyLanguage){if(pending.language!==undefined)applyLocalePreference(pending.language);else if(remote.language)applyLocalePreference(remote.language);else if(legacyLanguage){migration.language=legacyLanguage;applyLocalePreference(legacyLanguage);}}
      if(canApplyTheme){if(pending.theme!==undefined)applyThemePreference(pending.theme);else if(remote.theme)applyThemePreference(remote.theme);else if(legacyTheme){migration.theme=legacyTheme;applyThemePreference(legacyTheme);}}
      if(Object.keys(migration).length)void queuePreferenceWrite(migration,true);
      if(Object.keys(pending).length)void queuePreferenceWrite(pending);
      if(!Object.keys(migration).length&&!Object.keys(pending).length){clearLegacyPreferences();if(statusGeneration===preferenceStatusGeneration.current)updatePreferenceWriteStatus(remote.language||remote.theme?'saved':'idle');}
    }).catch((reason:unknown)=>{
      if(controller.signal.aborted)return;
      if(reason instanceof ApiError&&reason.status===404){preferencesAvailableRef.current=false;pendingPreferenceWrite.current={};if(statusGeneration===preferenceStatusGeneration.current)setPreferenceStatus('browser-only');return;}
      preferencesAvailableRef.current=true;if(statusGeneration===preferenceStatusGeneration.current)setPreferenceStatus('failed');
    });
    return()=>controller.abort();
  },[versionData?.authMode,versionData?.authenticationRequired,versionData?.apiVersion,currentUser?.loginId,authContextKey]);
  useEffect(() => {
    if(!capabilitiesResolved)return;
    if(accessCapabilities.accountManage&&!accessCapabilities.operate&&route.kind!=='settings')navigation.navigate({kind:'settings'});
  },[capabilitiesResolved,accessCapabilities.accountManage,accessCapabilities.operate,route.kind,navigation.navigate]);
  async function signOut() {
    try { await api.logout(); } finally {
      clearLegacyPreferences();
      const login = new URL('login.html', document.baseURI);
      location.assign(login.toString());
    }
  }
  const action = route.kind === 'load' && route.packageId
    ? { label: 'Open package', target: '#/packages/' + encodeURIComponent(route.packageId), hidden: false }
    : route.kind === 'quick-load'
      ? route.target
        ? { label: 'Return to Explorer', target: formatRoute({ kind: 'package', packageId: route.packageId, resource: route.target }), hidden: false }
        : { label: 'Back to LOAD Catalog', target: '#/advanced-load', hidden: false }
      : route.kind === 'configuration' && route.packageId
        ? { label: 'Open Explorer', target: '#/packages/' + encodeURIComponent(route.packageId), hidden: false }
        : route.kind === 'load' || route.kind === 'jobs' || route.kind === 'package'
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
      if (route.kind === 'job-console' || route.kind === 'job-artifact' || (route.kind === 'jobs' && route.standalone)) return;
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
  }, [keyboardMode, mobileNavigationOpen, route.kind]);

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
  const loadHref = '#/advanced-load';
  const configurationHref = formatRoute({ kind: 'configuration', environment: selectedEnvironment });
  const changeThemePreference = (preference: ThemePreference) => {
    applyThemePreference(preference);recordPreferenceEdit('theme',preference,saveLocalThemePreference(preference));
  };
  const changeTheme = () => changeThemePreference(theme === 'dark' ? 'light' : 'dark');
  const changeUiLocale = (preference: UiLocalePreference) => {
    applyLocalePreference(preference);recordPreferenceEdit('language',preference,saveUiLocalePreference(preference));
  };

  return (
    <NavigationGuardContext.Provider value={navigation}>
      <AppShellContext.Provider value={{ selectedPackage, selectedEnvironment, themePreference, uiLocale, uiLocalePreference, authMode: versionData?.authMode, currentUser, accessCapabilities, preferenceStatus, canRevealConfigurationFields, onPackageChange, onEnvironmentChange, onThemePreferenceChange: changeThemePreference, onUiLocaleChange: changeUiLocale, onJobAccepted }}>
      {(route.kind === 'job-console' || route.kind === 'job-artifact' || (route.kind === 'jobs' && route.standalone)) ? <main key={inspectionSessionEpoch} id="main-content" className="att-standalone-console-shell" tabIndex={-1}>
        {capabilitiesResolved ? children : <div role="status" aria-busy="true">Checking ATT access…</div>}
      </main> : <JobConsoleProvider jobsApi={api}>
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
            {accessCapabilities.operate && <><p className="nav-group-label">Operations</p>
            <a className="nav-link" href="#/" data-nav="dashboard" aria-current={activeNav === 'dashboard' ? 'page' : undefined} onClick={link('#/')}>
              <span className="nav-icon" aria-hidden="true">⌂</span><span className="nav-label">Dashboard</span>
            </a>
            <a className="nav-link" href={packageHref} data-nav="explorer" aria-current={activeNav === 'explorer' ? 'page' : undefined} onClick={link(packageHref)}>
              <span className="nav-icon" aria-hidden="true">⌕</span><span className="nav-label">Explorer</span>
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
            {showScheduledLoad && <a className="nav-link" href="#/schedules" data-nav="scheduled-load" aria-current={activeNav === 'scheduled-load' ? 'page' : undefined} onClick={link('#/schedules')}>
              <span className="nav-icon" aria-hidden="true">◷</span><span className="nav-label">Schedules</span>
            </a>}
            </>}
            {(accessCapabilities.operate||accessCapabilities.accountManage) && <a className="nav-link" href="#/settings" data-nav="settings" aria-current={activeNav === 'settings' ? 'page' : undefined} onClick={link('#/settings')}>
              <span className="nav-icon" aria-hidden="true">⚙</span><span className="nav-label">Settings</span>
            </a>}
          </nav>
          <div className="sidebar-footer"><span>ATT Server</span><span className="sidebar-version">{versionData?.version || '4.2.6'}</span></div>
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
                {route.kind === 'schedules' && <Breadcrumb label="Schedules" navigate={navigate} />}
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
              <a className="button button-primary context-action" href={action.target} hidden={action.hidden || !accessCapabilities.operate} onClick={link(action.target)}>{action.label}</a>
            </div>
          </div>

          <main key={inspectionSessionEpoch} id="main-content" tabIndex={-1}>
            {capabilitiesResolved ? children : <div className="page-content" role="status" aria-busy="true">Checking ATT access…</div>}
          </main>
          <footer className="page-footer"><span>ATT Server</span><span>API v{versionData?.apiVersion || '1'}</span></footer>
        </div>
        <DiscardChangesDialog navigation={navigation} />
        <ChangePasswordDialog open={changePasswordOpen} locale={uiLocale} onClose={()=>setChangePasswordOpen(false)} />
        <KeyboardDialog open={keyboardMode !== null} mode={keyboardMode || 'palette'} showScheduledLoad={showScheduledLoad&&accessCapabilities.operate} selectedPackage={selectedPackage} accessCapabilities={accessCapabilities}
          onClose={() => setKeyboardMode(null)} onNavigate={target => { navigate(target); setKeyboardMode(null); setMobileNavigationOpen(false); }} />
      </div>
      </JobConsoleProvider>}
      </AppShellContext.Provider>
    </NavigationGuardContext.Provider>
  );
}
