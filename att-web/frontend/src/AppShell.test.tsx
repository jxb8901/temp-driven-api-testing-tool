import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppShell } from './AppShell';
import type { RouteState } from './router';

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

describe('AppShell', () => {
  let host: HTMLDivElement | undefined;
  let root: ReturnType<typeof createRoot> | undefined;

  afterEach(() => {
    if (root) act(() => root?.unmount());
    host?.remove();
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
        serverVersion={{ status: 'ready', data: { apiVersion: '1', version: '4.1.3', inlineLoadEnabled: true } }}
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
    expect(host.textContent).toContain('Server managed');
    expect(host.textContent).toContain('not exposed by API');
    expect(host.textContent).toContain('payments');
    expect(host.querySelector('#main-content #feature-mount')?.textContent).toBe('Feature content');
  });
});
