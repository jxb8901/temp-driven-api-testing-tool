// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ComponentProps } from 'react';
import type { JobEventHandlers, LoadJobsApi } from './types';
import { FloatingJobConsole } from './FloatingJobConsole';
import { EVENT_FLUSH_INTERVAL_MS } from './useJobEventStream';

const job = { jobId: 'J1', command: 'run', executionType: 'run', packageId: 'demo', status: 'RUNNING' as const };

function makeApi(getJob: Promise<typeof job> = Promise.resolve(job)) {
  const subscriptions: Array<{ jobId: string; handlers: JobEventHandlers; active: boolean }> = [];
  const api = {
    getJob: vi.fn(() => getJob),
    subscribeJob: vi.fn((jobId: string, handlers: JobEventHandlers) => {
      const subscription = { jobId, handlers, active: true };
      subscriptions.push(subscription);
      return vi.fn(() => { subscription.active = false; });
    }),
  } as unknown as LoadJobsApi;
  return {
    api,
    handler: (jobId = 'J1') => subscriptions.filter(item => item.jobId === jobId && item.active).at(-1)?.handlers,
    activeSubscriptions: (jobId = 'J1') => subscriptions.filter(item => item.jobId === jobId && item.active).length,
  };
}

describe('FloatingJobConsole', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  });
  afterEach(() => { act(() => root.unmount()); host.remove(); });

  function renderConsole(overrides: Partial<ComponentProps<typeof FloatingJobConsole>> = {}, apiControl = makeApi()) {
    const { api, handler, activeSubscriptions } = apiControl;
    const props: ComponentProps<typeof FloatingJobConsole> = {
      api, request: { jobId: 'J1', view: 'live', job }, minimized: false,
      onMinimize: vi.fn(), onRestore: vi.fn(), onClose: vi.fn(),
      onSwitchRequest: null, onConfirmSwitch: vi.fn(), onCancelSwitch: vi.fn(), ...overrides,
    };
    act(() => root.render(<FloatingJobConsole {...props} />));
    return { api, props, handler, activeSubscriptions };
  }

  it('places focus on the move handle and supports keyboard move and resize', () => {
    renderConsole();
    const move = host.querySelector<HTMLButtonElement>('.att-console-drag-handle')!;
    const resize = host.querySelector<HTMLDivElement>('.att-floating-console-resize')!;
    expect(document.activeElement).toBe(move);
    expect(resize.getAttribute('aria-orientation')).toBe('vertical');
    expect(resize.getAttribute('aria-valuemin')).toBe('360');
    expect(resize.getAttribute('aria-valuemax')).toBeTruthy();
    const initialWidthValue = resize.getAttribute('aria-valuenow');
    expect(initialWidthValue).toBe(host.querySelector<HTMLElement>('.att-floating-console')!.style.width.replace('px', ''));
    expect(resize.getAttribute('aria-valuetext')).toContain(`${initialWidthValue}px wide`);
    const initialLeft = host.querySelector<HTMLElement>('.att-floating-console')!.style.left;
    act(() => move.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true })));
    expect(host.querySelector<HTMLElement>('.att-floating-console')!.style.left).not.toBe(initialLeft);
    const initialWidth = host.querySelector<HTMLElement>('.att-floating-console')!.style.width;
    act(() => resize.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true })));
    expect(host.querySelector<HTMLElement>('.att-floating-console')!.style.width).not.toBe(initialWidth);
    expect(resize.getAttribute('aria-valuenow')).not.toBe(initialWidthValue);
    expect(resize.getAttribute('aria-valuetext')).toContain(`${resize.getAttribute('aria-valuenow')}px wide`);
  });

  it('focuses and requires confirmation before switching Job', () => {
    const onConfirmSwitch = vi.fn();
    const onCancelSwitch = vi.fn();
    renderConsole({
      onConfirmSwitch,
      onCancelSwitch,
      onSwitchRequest: { jobId: 'J2', view: 'events', job: { ...job, jobId: 'J2' } },
    });
    expect(document.activeElement?.textContent).toBe('Switch Job');
    expect(host.textContent).toContain('Switch this console from Job J1 to Job J2?');
    host.querySelector<HTMLButtonElement>('.att-console-switch-confirm button')?.click();
    expect(onConfirmSwitch).toHaveBeenCalledTimes(1);
    expect(onCancelSwitch).not.toHaveBeenCalled();
  });

  it('keeps floating header controls above the maximized console shell', async () => {
    vi.useFakeTimers();
    try {
      const { handler } = renderConsole();
      act(() => handler()?.onEvent({ id: 'log-1', type: 'log', data: { console: '[RUN] live line' } }));
      await act(async () => { await vi.advanceTimersByTimeAsync(EVENT_FLUSH_INTERVAL_MS); });
      const maximize = host.querySelector<HTMLButtonElement>('[aria-label="Maximize Job console"]')!;
      act(() => maximize.click());

      const shell = host.querySelector<HTMLElement>('.att-console-shell')!;
      expect(shell.dataset.maximized).toBe('true');
      expect(shell.style.position).not.toBe('fixed');
      expect(shell.style.zIndex).toBe('');
      expect(host.querySelector('[aria-label="Restore Job console"]')).not.toBeNull();
      expect(host.querySelector('[aria-label="Minimize Job console"]')).not.toBeNull();
      expect(host.querySelector('[aria-label="Close Job console"]')).not.toBeNull();
      expect(host.querySelector('[role="tablist"][aria-label="Console view"] [role="tab"]')).not.toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('projects terminal stream status while minimized and after restore, despite late nonterminal updates', async () => {
    let resolveJob!: (value: typeof job) => void;
    const lateJob = new Promise<typeof job>(resolve => { resolveJob = resolve; });
    const apiControl = makeApi(lateJob);
    const { api, handler, props, activeSubscriptions } = renderConsole({ minimized: true }, apiControl);
    const events = handler()!;
    expect(host.querySelector('.att-console-chip')?.textContent).toContain('RUNNING');
    act(() => events.onEvent({ id: 'status-pass', type: 'status', data: { status: 'PASS' } }));
    act(() => events.onEvent({ id: 'result-pass', type: 'result', data: { status: 'PASS' } }));
    expect(host.querySelector('.att-console-chip')?.textContent).toContain('PASS');

    act(() => resolveJob({ ...job, status: 'RUNNING' }));
    await act(async () => { await lateJob; });
    act(() => events.onEvent({ id: 'status-late-running', type: 'status', data: { status: 'RUNNING' } }));
    expect(host.querySelector('.att-console-chip')?.textContent).toContain('PASS');

    act(() => host.querySelector<HTMLButtonElement>('.att-console-chip')?.click());
    expect(props.onRestore).toHaveBeenCalledTimes(1);
    act(() => root.render(<FloatingJobConsole {...props} minimized={false} />));
    expect(host.querySelector('.att-console-meta')?.textContent).toContain('PASS');
    expect(api.subscribeJob).toHaveBeenCalledTimes(1);
    expect(api.subscribeJob).toHaveBeenCalledWith('J1', expect.any(Object));
    expect(activeSubscriptions()).toBe(1);
  });
});
