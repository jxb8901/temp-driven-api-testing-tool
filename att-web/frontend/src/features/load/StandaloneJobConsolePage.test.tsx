// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../../api';
import { ApiError } from '../../types';
import { StandaloneJobConsolePage } from './StandaloneJobConsolePage';

describe('StandaloneJobConsolePage', () => {
  let host: HTMLDivElement; let root: Root;
  beforeEach(() => { host = document.createElement('div'); document.body.append(host); root = createRoot(host); (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true; });
  afterEach(() => { act(() => root.unmount()); host.remove(); vi.restoreAllMocks(); });

  it('shows a safe missing-Job message for a 404', async () => {
    vi.spyOn(api, 'getJob').mockRejectedValue(new ApiError('not found', { status: 404 }));
    await act(async () => root.render(<StandaloneJobConsolePage jobId="missing" />));
    await act(async () => await Promise.resolve());
    expect(host.textContent).toContain('unavailable or was deleted');
    expect(host.textContent).not.toContain('not found');
  });

  it('offers the existing sign-in path for 401/403', async () => {
    vi.spyOn(api, 'getJob').mockRejectedValue(new ApiError('expired', { status: 401 }));
    await act(async () => root.render(<StandaloneJobConsolePage jobId="private" />));
    await act(async () => await Promise.resolve());
    expect(host.textContent).toContain('session cannot access');
    expect(host.querySelector('a[href*="login.html"]')).not.toBeNull();
  });
});
