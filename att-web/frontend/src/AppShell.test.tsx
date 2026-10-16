import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppShell } from './AppShell';
import { api } from './api';
import { inspectionCache } from './inspectionCache';
import type { RouteState } from './router';

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

  it('discards refilled inspection state on tab and back-forward restores', async () => {
    host = document.createElement('div'); document.body.append(host); root = createRoot(host);
    const navigation: RouteState = {
      route: { kind: 'package', packageId: 'payments' }, pendingNavigation: null, pendingDescription: '', isDirty: false,
      navigate: vi.fn(), registerUnsavedChanges: vi.fn(() => () => {}), confirmNavigation: vi.fn(), cancelNavigation: vi.fn(),
    };
    act(() => root?.render(<AppShell route={navigation.route} navigation={navigation}
      serverVersion={{ status: 'loading' }} selectedPackage="payments" selectedEnvironment="SIT"
      onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()}>
      <input aria-label="Inspection projection" defaultValue="fresh mount" />
    </AppShell>));
    for (const event of ['pagehide', 'pageshow', 'visibilitychange']) {
      inspectionCache.setView('filters', { search: 'echo', pages: 3, scrollTop: 120 });
      await inspectionCache.query('page', async () => 'prior account');
      const input = host.querySelector('input[aria-label="Inspection projection"]') as HTMLInputElement;
      input.value = 'prior account';
      act(() => (event === 'visibilitychange' ? document : window).dispatchEvent(new Event(event)));
      expect((host.querySelector('input[aria-label="Inspection projection"]') as HTMLInputElement).value).toBe('fresh mount');
      expect(inspectionCache.getView('filters')).toEqual({ search: 'echo', pages: 3, scrollTop: 120 });
      expect(await inspectionCache.query('page', async () => 'current account')).toBe('current account');
      act(() => inspectionCache.clear());
    }
  });

  it('preserves an unfinished Load form when tab visibility changes', () => {
    host = document.createElement('div'); document.body.append(host); root = createRoot(host);
    const navigation: RouteState = {
      route: { kind: 'load' }, pendingNavigation: null, pendingDescription: '', isDirty: false,
      navigate: vi.fn(), registerUnsavedChanges: vi.fn(() => () => {}), confirmNavigation: vi.fn(), cancelNavigation: vi.fn(),
    };
    act(() => root?.render(<AppShell route={navigation.route} navigation={navigation}
      serverVersion={{ status: 'loading' }} selectedPackage="payments" selectedEnvironment="SIT"
      onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()}>
      <input aria-label="Unfinished Load form" defaultValue="" />
    </AppShell>));
    const input = host.querySelector('input[aria-label="Unfinished Load form"]') as HTMLInputElement;
    input.value = 'unsaved workload';
    act(() => document.dispatchEvent(new Event('visibilitychange')));
    expect(host.querySelector('input[aria-label="Unfinished Load form"]')).toBe(input);
    expect(input.value).toBe('unsaved workload');
  });

  it('renders accessible navigation, identity disclosure, context, and a feature mount', () => {
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

    act(() => root?.render(
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
    ));

    expect(host.querySelector('nav[aria-label="Primary navigation"]')).not.toBeNull();
    expect(host.querySelector('[data-nav="explorer"]')?.getAttribute('aria-current')).toBe('page');
    expect(host.querySelector('[data-nav="settings"]')).not.toBeNull();
    expect(host.textContent).toContain('Server managed');
    expect(host.textContent).toContain('not exposed by API');
    expect(host.textContent).toContain('payments');
    expect(host.querySelector('#main-content #feature-mount')?.textContent).toBe('Feature content');
  });

  it('gates Scheduled Load navigation on the advertised server capability', () => {
    localStorage.setItem('att-ui-theme', 'light');
    host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
    const navigation: RouteState = {
      route: { kind: 'schedules' }, pendingNavigation: null, pendingDescription: '', isDirty: false,
      navigate: vi.fn(), registerUnsavedChanges: vi.fn(() => () => {}), confirmNavigation: vi.fn(), cancelNavigation: vi.fn(),
    };
    act(() => root?.render(<AppShell
      route={navigation.route} navigation={navigation}
      serverVersion={{ status: 'ready', data: { apiVersion: '1', inlineLoadEnabled: false, loadSchedulingEnabled: true } }}
      showScheduledLoad selectedPackage="" selectedEnvironment="" onPackageChange={vi.fn()}
      onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()}><p>Schedules</p></AppShell>));
    expect(host.querySelector('[data-nav="scheduled-load"]')?.getAttribute('aria-current')).toBe('page');
    expect(host.querySelector('#breadcrumbs')?.textContent).toContain('Schedules');
  });

  it('opens a searchable page palette and shortcut help from the keyboard', () => {
    localStorage.setItem('att-ui-theme', 'light');
    host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
    const navigation: RouteState = {
      route: { kind: 'dashboard' }, pendingNavigation: null, pendingDescription: '', isDirty: false,
      navigate: vi.fn(), registerUnsavedChanges: vi.fn(() => () => {}), confirmNavigation: vi.fn(), cancelNavigation: vi.fn(),
    };
    act(() => root?.render(<AppShell
      route={navigation.route} navigation={navigation} serverVersion={{ status: 'ready', data: { apiVersion: '1', inlineLoadEnabled: false } }}
      showScheduledLoad selectedPackage="" selectedEnvironment="" onPackageChange={vi.fn()}
      onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()}><p>Dashboard</p></AppShell>));

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
});
