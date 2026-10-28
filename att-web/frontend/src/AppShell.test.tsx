import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppShell, useAppShellContext } from './AppShell';
import { api } from './api';
import { inspectionCache } from './inspectionCache';
import type { RouteState } from './router';
import { ApiError } from './types';

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

describe('AppShell', () => {
  let host: HTMLDivElement | undefined;
  let root: ReturnType<typeof createRoot> | undefined;

  afterEach(() => {
    if (root) act(() => root?.unmount());
    host?.remove();
    vi.restoreAllMocks();
    root = undefined;
    host = undefined;
  });

  beforeEach(() => {
    vi.spyOn(api, 'getAuthCapabilities').mockResolvedValue({
      configurationAdmin: true,
      capabilities: { operate: true, accountManage: true, systemSettingsWrite: true, configurationAdmin: true },
    });
    vi.spyOn(api, 'getUserPreferences').mockResolvedValue({ available: false, reason: 'unsupported' });
    vi.spyOn(api, 'putUserPreferences').mockResolvedValue({ available: true });
  });

  function PreferenceProbe() {
    const context = useAppShellContext();
    return <div data-preference-status={context.preferenceStatus}>
      <div data-theme-preference={context.themePreference}>
        <button type="button" onClick={() => context.onThemePreferenceChange('dark')}>Choose dark</button>
      </div>
      <div data-language-preference={context.uiLocalePreference}>
        <button type="button" onClick={() => context.onUiLocaleChange('zh-Hant')}>Choose Traditional Chinese</button>
      </div>
    </div>;
  }

  it('renders standalone consoles without global navigation or a floating console host', async () => {
    host = document.createElement('div'); document.body.append(host); root = createRoot(host);
    const navigation: RouteState = { route: { kind: 'job-console', jobId: 'J1', view: 'live' }, pendingNavigation: null, pendingDescription: '', isDirty: false,
      navigate: vi.fn(), registerUnsavedChanges: vi.fn(() => () => {}), confirmNavigation: vi.fn(), cancelNavigation: vi.fn() };
    await act(async () => { root?.render(<AppShell route={navigation.route} navigation={navigation}
      serverVersion={{ status: 'ready', data: { apiVersion: '1', inlineLoadEnabled: true } }} selectedPackage="" selectedEnvironment="" onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()}>
      <p>Fixed Job console</p></AppShell>); await Promise.resolve(); });
    expect(host.querySelector('.att-standalone-console-shell')).not.toBeNull();
    expect(host.querySelector('aside, nav, header, footer, .att-breadcrumbs, .att-floating-job-console')).toBeNull();
    expect(host.textContent).toContain('Fixed Job console');
  });

  it('invalidates refilled inspection state without remounting on tab and back-forward restores', async () => {
    host = document.createElement('div'); document.body.append(host); root = createRoot(host);
    const navigation: RouteState = {
      route: { kind: 'package', packageId: 'payments' }, pendingNavigation: null, pendingDescription: '', isDirty: false,
      navigate: vi.fn(), registerUnsavedChanges: vi.fn(() => () => {}), confirmNavigation: vi.fn(), cancelNavigation: vi.fn(),
    };
    await act(async () => { root?.render(<AppShell route={navigation.route} navigation={navigation}
      serverVersion={{ status: 'ready', data: { apiVersion: '1', inlineLoadEnabled: true } }} selectedPackage="payments" selectedEnvironment="SIT"
      onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()}>
      <input aria-label="Inspection projection" defaultValue="fresh mount" />
    </AppShell>); await Promise.resolve(); });
    for (const event of ['pagehide', 'pageshow', 'visibilitychange']) {
      inspectionCache.setView('filters', { search: 'echo', pages: 3, scrollTop: 120 });
      await inspectionCache.query('page', async () => 'prior account');
      const input = host.querySelector('input[aria-label="Inspection projection"]') as HTMLInputElement;
      input.value = 'prior account';
      act(() => (event === 'visibilitychange' ? document : window).dispatchEvent(new Event(event)));
      expect(host.querySelector('input[aria-label="Inspection projection"]')).toBe(input);
      expect(input.value).toBe('prior account');
      expect(inspectionCache.getView('filters')).toEqual({ search: 'echo', pages: 3, scrollTop: 120 });
      expect(await inspectionCache.query('page', async () => 'current account')).toBe('current account');
      act(() => inspectionCache.clear());
    }
  });

  it('preserves an unfinished Load form when tab visibility changes', async () => {
    host = document.createElement('div'); document.body.append(host); root = createRoot(host);
    const navigation: RouteState = {
      route: { kind: 'load' }, pendingNavigation: null, pendingDescription: '', isDirty: false,
      navigate: vi.fn(), registerUnsavedChanges: vi.fn(() => () => {}), confirmNavigation: vi.fn(), cancelNavigation: vi.fn(),
    };
    await act(async () => { root?.render(<AppShell route={navigation.route} navigation={navigation}
      serverVersion={{ status: 'ready', data: { apiVersion: '1', inlineLoadEnabled: true } }} selectedPackage="payments" selectedEnvironment="SIT"
      onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()}>
      <input aria-label="Unfinished Load form" defaultValue="" />
    </AppShell>); await Promise.resolve(); });
    const input = host.querySelector('input[aria-label="Unfinished Load form"]') as HTMLInputElement;
    input.value = 'unsaved workload';
    act(() => document.dispatchEvent(new Event('visibilitychange')));
    expect(host.querySelector('input[aria-label="Unfinished Load form"]')).toBe(input);
    expect(input.value).toBe('unsaved workload');
  });

  it('renders accessible navigation, identity disclosure, context, and a feature mount', async () => {
    localStorage.setItem('att-ui-theme', 'light');
    host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
    const navigation: RouteState = {
      route: { kind: 'package', packageId: 'payments' },
      pendingNavigation: null,
      pendingDescription: '',
      isDirty: false,
      navigate: vi.fn(),
      registerUnsavedChanges: vi.fn(() => () => {}),
      confirmNavigation: vi.fn(),
      cancelNavigation: vi.fn(),
    };

    await act(async () => { root?.render(
      <AppShell
        route={navigation.route}
        navigation={navigation}
        serverVersion={{ status: 'ready', data: { apiVersion: '1', version: '4.2.1', inlineLoadEnabled: true } }}
        selectedPackage="payments"
        selectedEnvironment=""
        onPackageChange={vi.fn()}
        onEnvironmentChange={vi.fn()}
        onJobAccepted={vi.fn()}
      >
        <p id="feature-mount">Feature content</p>
      </AppShell>,
    ); await Promise.resolve(); });

    expect(host.querySelector('nav[aria-label="Primary navigation"]')).not.toBeNull();
    expect(host.querySelector('[data-nav="explorer"]')?.getAttribute('aria-current')).toBe('page');
    expect(host.querySelector('[data-nav="settings"]')).not.toBeNull();
    expect(host.textContent).toContain('Server managed');
    expect(host.textContent).toContain('not exposed by API');
    expect(host.textContent).toContain('payments');
    expect(host.querySelector('#main-content #feature-mount')?.textContent).toBe('Feature content');
  });

  it('gates Scheduled Load navigation on the advertised server capability', async () => {
    localStorage.setItem('att-ui-theme', 'light');
    host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
    const navigation: RouteState = {
      route: { kind: 'schedules' }, pendingNavigation: null, pendingDescription: '', isDirty: false,
      navigate: vi.fn(), registerUnsavedChanges: vi.fn(() => () => {}), confirmNavigation: vi.fn(), cancelNavigation: vi.fn(),
    };
    await act(async () => { root?.render(<AppShell
      route={navigation.route} navigation={navigation}
      serverVersion={{ status: 'ready', data: { apiVersion: '1', inlineLoadEnabled: false, loadSchedulingEnabled: true } }}
      showScheduledLoad selectedPackage="" selectedEnvironment="" onPackageChange={vi.fn()}
      onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()}><p>Schedules</p></AppShell>); await Promise.resolve(); });
    expect(host.querySelector('[data-nav="scheduled-load"]')?.getAttribute('aria-current')).toBe('page');
    expect(host.querySelector('#breadcrumbs')?.textContent).toContain('Schedules');
  });

  it('opens a searchable page palette and shortcut help from the keyboard', async () => {
    localStorage.setItem('att-ui-theme', 'light');
    host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
    const navigation: RouteState = {
      route: { kind: 'dashboard' }, pendingNavigation: null, pendingDescription: '', isDirty: false,
      navigate: vi.fn(), registerUnsavedChanges: vi.fn(() => () => {}), confirmNavigation: vi.fn(), cancelNavigation: vi.fn(),
    };
    await act(async () => { root?.render(<AppShell
      route={navigation.route} navigation={navigation} serverVersion={{ status: 'ready', data: { apiVersion: '1', inlineLoadEnabled: false } }}
      showScheduledLoad selectedPackage="" selectedEnvironment="" onPackageChange={vi.fn()}
      onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()}><p>Dashboard</p></AppShell>); await Promise.resolve(); });

    act(() => window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true })));
    expect(host.querySelector('.keyboard-dialog[open]')).not.toBeNull();
    const search = host.querySelector('#command-search') as HTMLInputElement;
    act(() => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setter?.call(search, 'Schedules');
      search.dispatchEvent(new Event('input', { bubbles: true }));
    });
    const command = [...host.querySelectorAll('.command-list button')].find(button => button.textContent?.includes('Schedules'));
    expect(command).not.toBeUndefined();
    act(() => command?.dispatchEvent(new MouseEvent('click', { bubbles: true })));
    expect(navigation.navigate).toHaveBeenCalledWith({ kind: 'schedules' });
    expect(host.querySelector('.keyboard-dialog[open]')).toBeNull();

    act(() => window.dispatchEvent(new KeyboardEvent('keydown', { key: '?', bubbles: true })));
    expect(host.textContent).toContain('Start a validated Load draft');
  });

  it('offers Change Password from the authenticated local user menu for a USER role', async () => {
    vi.spyOn(api,'getCurrentUser').mockResolvedValue({loginId:'jane',displayName:'Jane Tester',role:'USER'});
    vi.spyOn(api,'getAuthCapabilities').mockResolvedValue({configurationAdmin:false});
    localStorage.setItem('att-ui-theme','light');
    host=document.createElement('div');document.body.append(host);root=createRoot(host);
    const navigation:RouteState={route:{kind:'settings'},pendingNavigation:null,pendingDescription:'',isDirty:false,
      navigate:vi.fn(),registerUnsavedChanges:vi.fn(()=>()=>{}),confirmNavigation:vi.fn(),cancelNavigation:vi.fn()};
    await act(async()=>{root?.render(<AppShell route={navigation.route} navigation={navigation}
      serverVersion={{status:'ready',data:{apiVersion:'1',version:'4.1.4',authMode:'local',inlineLoadEnabled:false}}}
      selectedPackage="" selectedEnvironment="" onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()}>
      <p>Settings</p></AppShell>);await Promise.resolve();});
    expect(host.textContent).toContain('Jane Tester');
    expect(host.querySelector('.context-identity')?.textContent).toContain('Jane Tester');
    expect(host.querySelector('.context-identity')?.textContent).toContain('jane · USER');
    const menu=host.querySelector('.att-current-user-menu') as HTMLDetailsElement;
    act(()=>{menu.open=true;});
    const change=[...host.querySelectorAll('.att-user-menu-panel button')].find(button=>button.textContent==='Change password') as HTMLButtonElement|undefined;
    act(()=>change?.click());
    expect(host.querySelector('#change-password-title')?.textContent).toBe('Change your password');
    expect(host.querySelector('dialog[open]')).not.toBeNull();
    expect(api.getCurrentUser).toHaveBeenCalledOnce();
  });

  it('uses account preferences ahead of legacy browser values', async () => {
    vi.spyOn(api,'getCurrentUser').mockResolvedValue({loginId:'jane',displayName:'Jane Tester',role:'USER'});
    vi.spyOn(api,'getUserPreferences').mockResolvedValue({available:true,language:'zh-Hant',theme:'dark',version:2});
    localStorage.setItem('att-ui-theme','light');
    localStorage.setItem('att-ui-language','en');
    host=document.createElement('div');document.body.append(host);root=createRoot(host);
    const navigation:RouteState={route:{kind:'dashboard'},pendingNavigation:null,pendingDescription:'',isDirty:false,
      navigate:vi.fn(),registerUnsavedChanges:vi.fn(()=>()=>{}),confirmNavigation:vi.fn(),cancelNavigation:vi.fn()};
    await act(async()=>{root?.render(<AppShell route={navigation.route} navigation={navigation}
      serverVersion={{status:'ready',data:{apiVersion:'1',authMode:'local',inlineLoadEnabled:false}}}
      selectedPackage="" selectedEnvironment="" onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()}>
      <PreferenceProbe /></AppShell>);await new Promise(resolve=>setTimeout(resolve,0));});
    expect(host.querySelector('[data-theme-preference="dark"]')).not.toBeNull();
    expect(host.querySelector('[data-language-preference="zh-Hant"]')).not.toBeNull();
    expect(localStorage.getItem('att-ui-theme')).toBeNull();
    expect(localStorage.getItem('att-ui-language')).toBeNull();
    expect(api.putUserPreferences).not.toHaveBeenCalled();
  });

  it('migrates legacy browser values only through the absent-value migration path', async () => {
    vi.spyOn(api,'getCurrentUser').mockResolvedValue({loginId:'jane',displayName:'Jane Tester',role:'USER'});
    vi.spyOn(api,'getUserPreferences').mockResolvedValue({available:true,language:null,theme:null,version:0});
    localStorage.setItem('att-ui-theme','dark');
    localStorage.setItem('att-ui-language','zh-Hant');
    host=document.createElement('div');document.body.append(host);root=createRoot(host);
    const navigation:RouteState={route:{kind:'dashboard'},pendingNavigation:null,pendingDescription:'',isDirty:false,
      navigate:vi.fn(),registerUnsavedChanges:vi.fn(()=>()=>{}),confirmNavigation:vi.fn(),cancelNavigation:vi.fn()};
    await act(async()=>{root?.render(<AppShell route={navigation.route} navigation={navigation}
      serverVersion={{status:'ready',data:{apiVersion:'1',authMode:'local',inlineLoadEnabled:false}}}
      selectedPackage="" selectedEnvironment="" onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()}>
      <PreferenceProbe /></AppShell>);await new Promise(resolve=>setTimeout(resolve,0));});
    expect(api.putUserPreferences).toHaveBeenCalledWith({language:'zh-Hant',theme:'dark',migrateIfAbsent:true});
    expect(host.querySelector('[data-theme-preference="dark"]')).not.toBeNull();
    expect(host.querySelector('[data-language-preference="zh-Hant"]')).not.toBeNull();
    expect(localStorage.getItem('att-ui-theme')).toBeNull();
    expect(localStorage.getItem('att-ui-language')).toBeNull();
  });

  it('saves a change to the loaded account and exposes completion status', async () => {
    vi.spyOn(api,'getCurrentUser').mockResolvedValue({loginId:'jane',displayName:'Jane Tester',role:'USER'});
    vi.spyOn(api,'getUserPreferences').mockResolvedValue({available:true,language:'en',theme:'light',version:1});
    host=document.createElement('div');document.body.append(host);root=createRoot(host);
    const navigation:RouteState={route:{kind:'dashboard'},pendingNavigation:null,pendingDescription:'',isDirty:false,
      navigate:vi.fn(),registerUnsavedChanges:vi.fn(()=>()=>{}),confirmNavigation:vi.fn(),cancelNavigation:vi.fn()};
    await act(async()=>{root?.render(<AppShell route={navigation.route} navigation={navigation}
      serverVersion={{status:'ready',data:{apiVersion:'1',authMode:'local',inlineLoadEnabled:false}}}
      selectedPackage="" selectedEnvironment="" onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()}>
      <PreferenceProbe /></AppShell>);await new Promise(resolve=>setTimeout(resolve,0));});
    await act(async()=>{(host?.querySelector('[data-theme-preference] button') as HTMLButtonElement).click();await Promise.resolve();});
    expect(api.putUserPreferences).toHaveBeenCalledWith({theme:'dark'});
    expect(host.querySelector('[data-theme-preference="dark"]')).not.toBeNull();
    expect(host.querySelector('[data-preference-status="saved"]')).not.toBeNull();
  });

  it('keeps save status pending until the latest queued preference edit completes', async () => {
    vi.spyOn(api,'getCurrentUser').mockResolvedValue({loginId:'jane',displayName:'Jane Tester',role:'USER'});
    vi.spyOn(api,'getUserPreferences').mockResolvedValue({available:true,language:'en',theme:'light',version:1});
    let resolveFirst: (value:{available:boolean})=>void = () => undefined;
    let rejectSecond: (reason?:unknown)=>void = () => undefined;
    const firstPut=new Promise<{available:boolean}>(resolve=>{resolveFirst=resolve;});
    const secondPut=new Promise<{available:boolean}>((_,reject)=>{rejectSecond=reject;});
    const put=vi.spyOn(api,'putUserPreferences').mockReturnValueOnce(firstPut).mockReturnValueOnce(secondPut);
    host=document.createElement('div');document.body.append(host);root=createRoot(host);
    const navigation:RouteState={route:{kind:'dashboard'},pendingNavigation:null,pendingDescription:'',isDirty:false,
      navigate:vi.fn(),registerUnsavedChanges:vi.fn(()=>()=>{}),confirmNavigation:vi.fn(),cancelNavigation:vi.fn()};
    await act(async()=>{root?.render(<AppShell route={navigation.route} navigation={navigation}
      serverVersion={{status:'ready',data:{apiVersion:'1',authMode:'local',inlineLoadEnabled:false}}}
      selectedPackage="" selectedEnvironment="" onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()}>
      <PreferenceProbe /></AppShell>);await new Promise(resolve=>setTimeout(resolve,0));});

    await act(async()=>{
      (host?.querySelector('[data-language-preference] button') as HTMLButtonElement).click();
      await new Promise(resolve=>setTimeout(resolve,0));
    });
    expect(put).toHaveBeenCalledTimes(1);
    await act(async()=>{(host?.querySelector('[data-theme-preference] button') as HTMLButtonElement).click();});
    expect(host.querySelector('[data-preference-status="saving"]')).not.toBeNull();

    await act(async()=>{resolveFirst({available:true});await new Promise(resolve=>setTimeout(resolve,0));});
    expect(put).toHaveBeenCalledTimes(2);
    expect(host.querySelector('[data-preference-status="saving"]')).not.toBeNull();

    await act(async()=>{rejectSecond(new Error('latest preference write failed'));await new Promise(resolve=>setTimeout(resolve,0));});
    expect(host.querySelector('[data-preference-status="failed"]')).not.toBeNull();
  });

  it('keeps an earlier failed preference edit failed and retries it after reload', async () => {
    localStorage.clear();
    vi.spyOn(api,'getCurrentUser').mockResolvedValue({loginId:'jane',displayName:'Jane Tester',role:'USER'});
    const getPreferences=vi.spyOn(api,'getUserPreferences').mockResolvedValue({available:true,language:'en',theme:'light',version:1});
    let rejectLanguage: (reason?:unknown)=>void = () => undefined;
    let resolveTheme: (value:{available:boolean})=>void = () => undefined;
    const languagePut=new Promise<{available:boolean}>((_,reject)=>{rejectLanguage=reject;});
    const themePut=new Promise<{available:boolean}>(resolve=>{resolveTheme=resolve;});
    const put=vi.spyOn(api,'putUserPreferences').mockReturnValueOnce(languagePut).mockReturnValueOnce(themePut)
      .mockResolvedValue({available:true});
    host=document.createElement('div');document.body.append(host);
    const navigation:RouteState={route:{kind:'dashboard'},pendingNavigation:null,pendingDescription:'',isDirty:false,
      navigate:vi.fn(),registerUnsavedChanges:vi.fn(()=>()=>{}),confirmNavigation:vi.fn(),cancelNavigation:vi.fn()};
    const renderShell=async()=>{
      root=createRoot(host!);
      await act(async()=>{root?.render(<AppShell route={navigation.route} navigation={navigation}
        serverVersion={{status:'ready',data:{apiVersion:'1',authMode:'local',inlineLoadEnabled:false}}}
        selectedPackage="" selectedEnvironment="" onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()}>
        <PreferenceProbe /></AppShell>);
        await new Promise(resolve=>setTimeout(resolve,0));await new Promise(resolve=>setTimeout(resolve,0));});
    };
    await renderShell();

    await act(async()=>{
      (host?.querySelector('[data-language-preference] button') as HTMLButtonElement).click();
      await new Promise(resolve=>setTimeout(resolve,0));
    });
    expect(put).toHaveBeenCalledTimes(1);
    await act(async()=>{(host?.querySelector('[data-theme-preference] button') as HTMLButtonElement).click();});
    rejectLanguage(new Error('language write failed'));
    await act(async()=>{await new Promise(resolve=>setTimeout(resolve,0));});
    expect(put).toHaveBeenCalledTimes(2);
    expect(host.querySelector('[data-preference-status="saving"]')).not.toBeNull();
    await act(async()=>{resolveTheme({available:true});await new Promise(resolve=>setTimeout(resolve,0));});
    expect(host.querySelector('[data-language-preference="zh-Hant"]')).not.toBeNull();
    expect(host.querySelector('[data-preference-status="failed"]')).not.toBeNull();
    expect(JSON.parse(localStorage.getItem('att-ui-language-pending')||'{}')['local:jane']).toBe('zh-Hant');

    await act(async()=>{root?.unmount();});
    root=undefined;
    getPreferences.mockResolvedValue({available:true,language:'en',theme:'dark',version:2});
    await renderShell();

    expect(put).toHaveBeenCalledTimes(3);
    expect(put).toHaveBeenLastCalledWith({language:'zh-Hant'});
    expect(host.querySelector('[data-language-preference="zh-Hant"]')).not.toBeNull();
    expect(host.querySelector('[data-theme-preference="dark"]')).not.toBeNull();
    expect(host.querySelector('[data-preference-status="saved"]')).not.toBeNull();
    expect(localStorage.getItem('att-ui-language-pending')).toBeNull();
  });

  it('preserves the untouched legacy preference after a transient read failure and a one-field save', async () => {
    vi.spyOn(api,'getCurrentUser').mockResolvedValue({loginId:'jane',displayName:'Jane Tester',role:'USER'});
    vi.spyOn(api,'getUserPreferences').mockRejectedValue(new Error('temporary preference read failure'));
    localStorage.setItem('att-ui-language','zh-Hant');
    localStorage.setItem('att-ui-theme','light');
    host=document.createElement('div');document.body.append(host);root=createRoot(host);
    const navigation:RouteState={route:{kind:'dashboard'},pendingNavigation:null,pendingDescription:'',isDirty:false,
      navigate:vi.fn(),registerUnsavedChanges:vi.fn(()=>()=>{}),confirmNavigation:vi.fn(),cancelNavigation:vi.fn()};
    await act(async()=>{root?.render(<AppShell route={navigation.route} navigation={navigation}
      serverVersion={{status:'ready',data:{apiVersion:'1',authMode:'local',inlineLoadEnabled:false}}}
      selectedPackage="" selectedEnvironment="" onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()}>
      <PreferenceProbe /></AppShell>);await new Promise(resolve=>setTimeout(resolve,0));});
    expect(host.querySelector('[data-preference-status="failed"]')).not.toBeNull();
    await act(async()=>{(host?.querySelector('[data-theme-preference] button') as HTMLButtonElement).click();await new Promise(resolve=>setTimeout(resolve,0));});
    expect(api.putUserPreferences).toHaveBeenCalledWith({theme:'dark'});
    expect(host.querySelector('[data-theme-preference="dark"]')).not.toBeNull();
    expect(host.querySelector('[data-preference-status="saved"]')).not.toBeNull();
    expect(localStorage.getItem('att-ui-theme')).toBeNull();
    expect(localStorage.getItem('att-ui-language')).toBe('zh-Hant');
  });

  it('keeps preferences in browser storage when no authenticated account exists', async () => {
    host=document.createElement('div');document.body.append(host);root=createRoot(host);
    const navigation:RouteState={route:{kind:'dashboard'},pendingNavigation:null,pendingDescription:'',isDirty:false,
      navigate:vi.fn(),registerUnsavedChanges:vi.fn(()=>()=>{}),confirmNavigation:vi.fn(),cancelNavigation:vi.fn()};
    await act(async()=>{root?.render(<AppShell route={navigation.route} navigation={navigation}
      serverVersion={{status:'ready',data:{apiVersion:'1',authenticationRequired:false,inlineLoadEnabled:false}}}
      selectedPackage="" selectedEnvironment="" onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()}>
      <PreferenceProbe /></AppShell>);await Promise.resolve();});
    await act(async()=>{(host?.querySelector('[data-theme-preference] button') as HTMLButtonElement).click();await Promise.resolve();});
    expect(host.querySelector('[data-theme-preference="dark"]')).not.toBeNull();
    expect(host.querySelector('[data-preference-status="browser-only"]')).not.toBeNull();
    expect(localStorage.getItem('att-ui-theme')).toBe('dark');
    expect(api.getUserPreferences).not.toHaveBeenCalled();
    expect(api.putUserPreferences).not.toHaveBeenCalled();
  });

  it('falls back to browser preferences when an older Server has no preference endpoint', async () => {
    vi.spyOn(api,'getCurrentUser').mockResolvedValue({loginId:'jane',displayName:'Jane Tester',role:'USER'});
    vi.spyOn(api,'getUserPreferences').mockRejectedValue(new ApiError('Not found',{status:404}));
    host=document.createElement('div');document.body.append(host);root=createRoot(host);
    const navigation:RouteState={route:{kind:'dashboard'},pendingNavigation:null,pendingDescription:'',isDirty:false,
      navigate:vi.fn(),registerUnsavedChanges:vi.fn(()=>()=>{}),confirmNavigation:vi.fn(),cancelNavigation:vi.fn()};
    await act(async()=>{root?.render(<AppShell route={navigation.route} navigation={navigation}
      serverVersion={{status:'ready',data:{apiVersion:'1',authMode:'local',inlineLoadEnabled:false}}}
      selectedPackage="" selectedEnvironment="" onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()}>
      <PreferenceProbe /></AppShell>);await new Promise(resolve=>setTimeout(resolve,0));});
    await act(async()=>{(host?.querySelector('[data-theme-preference] button') as HTMLButtonElement).click();await Promise.resolve();});
    expect(host.querySelector('[data-theme-preference="dark"]')).not.toBeNull();
    expect(host.querySelector('[data-preference-status="browser-only"]')).not.toBeNull();
    expect(localStorage.getItem('att-ui-theme')).toBe('dark');
    expect(api.putUserPreferences).not.toHaveBeenCalled();
  });
});
