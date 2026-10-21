// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ComponentProps } from 'react';
import type { JobEventHandlers, LoadJobsApi } from './types';
import { FloatingJobConsole } from './FloatingJobConsole';

const job = { jobId: 'J1', command: 'run', executionType: 'run', packageId: 'demo', status: 'RUNNING' as const };

function makeApi() {
  const api = {
    getJob: vi.fn().mockResolvedValue(job),
    subscribeJob: vi.fn((_jobId: string, _handlers: JobEventHandlers) => vi.fn()),
  } as unknown as LoadJobsApi;
  return api;
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

  function renderConsole(overrides: Partial<ComponentProps<typeof FloatingJobConsole>> = {}) {
    const api = makeApi();
    const props: ComponentProps<typeof FloatingJobConsole> = {
      api, request: { jobId: 'J1', view: 'live', job }, minimized: false,
      onMinimize: vi.fn(), onRestore: vi.fn(), onClose: vi.fn(),
      onSwitchRequest: null, onConfirmSwitch: vi.fn(), onCancelSwitch: vi.fn(), ...overrides,
    };
    act(() => root.render(<FloatingJobConsole {...props} />));
    return { api, props };
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
});
