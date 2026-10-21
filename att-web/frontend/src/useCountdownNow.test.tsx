// @vitest-environment jsdom
import { act, Profiler } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useCountdownNow } from './useCountdownNow';

function Probe({ active }: { active: boolean }) {
  return <output>{useCountdownNow(active)}</output>;
}

let root: Root;
let host: HTMLDivElement;

beforeEach(() => {
  vi.useFakeTimers();
  Object.defineProperty(document, 'hidden', { configurable: true, value: false });
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
});

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe('useCountdownNow', () => {
  it('keeps the page body Profiler stable while the countdown subtree updates', async () => {
    let pageCommits = 0;
    let countdownCommits = 0;
    await act(async () => root.render(<>
      <Profiler id="page-body" onRender={() => { pageCommits += 1; }}><div data-page-body="true">static page</div></Profiler>
      <Profiler id="countdown" onRender={() => { countdownCommits += 1; }}><Probe active /></Profiler>
    </>));
    expect(pageCommits).toBe(1);
    expect(countdownCommits).toBe(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(2500); });
    expect(pageCommits).toBe(1);
    expect(countdownCommits).toBeGreaterThan(1);
  });

  it('does not create a timer when inactive, pauses while hidden, resumes on visibility, and cleans up', async () => {
    const setIntervalSpy = vi.spyOn(window, 'setInterval');
    const clearIntervalSpy = vi.spyOn(window, 'clearInterval');
    await act(async () => root.render(<Probe active />));
    expect(setIntervalSpy).toHaveBeenCalledTimes(1);
    const initial = host.querySelector('output')?.textContent;

    Object.defineProperty(document, 'hidden', { configurable: true, value: true });
    await act(async () => document.dispatchEvent(new Event('visibilitychange')));
    expect(clearIntervalSpy).toHaveBeenCalledTimes(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
    expect(host.querySelector('output')?.textContent).toBe(initial);

    Object.defineProperty(document, 'hidden', { configurable: true, value: false });
    await act(async () => document.dispatchEvent(new Event('visibilitychange')));
    expect(setIntervalSpy).toHaveBeenCalledTimes(2);
    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    expect(host.querySelector('output')?.textContent).not.toBe(initial);

    await act(async () => root.render(<Probe active={false} />));
    expect(clearIntervalSpy).toHaveBeenCalledTimes(2);
  });
});

