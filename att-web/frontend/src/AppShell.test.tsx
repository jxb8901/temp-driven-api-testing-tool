import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppShell } from './AppShell';
import { api } from './api';
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
        serverVersion={{ status: 'ready', data: { apiVersion: '1', version: '4.2.0', inlineLoadEnabled: true } }}
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
    expect(host.querySelector('#breadcrumbs')?.textContent).toContain('Scheduled Load');
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
      setter?.call(search, 'Scheduled');
      search.dispatchEvent(new Event('input', { bubbles: true }));
    });
    const command = [...host.querySelectorAll('.command-list button')].find(button => button.textContent?.includes('Scheduled Load'));
    expect(command).not.toBeUndefined();
    act(() => command?.dispatchEvent(new MouseEvent('click', { bubbles: true })));
    expect(navigation.navigate).toHaveBeenCalledWith({ kind: 'schedules' });
    expect(host.querySelector('.keyboard-dialog[open]')).toBeNull();

    act(() => window.dispatchEvent(new KeyboardEvent('keydown', { key: '?', bubbles: true })));
    expect(host.textContent).toContain('Start a validated Load draft');
  });

  it('offers Change Password from the authenticated local user menu for a USER role', async () => {
    vi.spyOn(api,'getCurrentUser').mockResolvedValue({loginId:'jane',displayName:'Jane Tester',role:'USER'});
    localStorage.setItem('att-ui-theme','light');
    host=document.createElement('div');document.body.append(host);root=createRoot(host);
    const navigation:RouteState={route:{kind:'settings'},pendingNavigation:null,pendingDescription:'',isDirty:false,
      navigate:vi.fn(),registerUnsavedChanges:vi.fn(()=>()=>{}),confirmNavigation:vi.fn(),cancelNavigation:vi.fn()};
    await act(async()=>{root?.render(<AppShell route={navigation.route} navigation={navigation}
      serverVersion={{status:'ready',data:{apiVersion:'1',version:'4.1.4',authMode:'local',inlineLoadEnabled:false}}}
      selectedPackage="" selectedEnvironment="" onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()}>
      <p>Settings</p></AppShell>);await Promise.resolve();});
    expect(host.textContent).toContain('Jane Tester');
    const menu=host.querySelector('.att-current-user-menu') as HTMLDetailsElement;
    act(()=>{menu.open=true;});
    const change=[...host.querySelectorAll('.att-user-menu-panel button')].find(button=>button.textContent==='Change password') as HTMLButtonElement|undefined;
    act(()=>change?.click());
    expect(host.querySelector('#change-password-title')?.textContent).toBe('Change your password');
    expect(host.querySelector('dialog[open]')).not.toBeNull();
    expect(api.getCurrentUser).toHaveBeenCalledOnce();
  });
});
