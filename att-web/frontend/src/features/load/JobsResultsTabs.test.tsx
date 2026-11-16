// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AppShellContext, type AppShellContextValue } from '../../AppShell';
import { JobsResultsTabs } from './JobsResultsTabs';

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLDivElement;
let root: Root;
const context = { uiLocale: 'en' } as unknown as AppShellContextValue;

function TabHarness({ locale = 'en' }: { locale?: 'en' | 'zh-Hant' }) {
  const [selected, setSelected] = useState<'jobs' | 'validations'>('jobs');
  return <AppShellContext.Provider value={{ ...context, uiLocale: locale } as AppShellContextValue}>
    <JobsResultsTabs selected={selected} onSelect={setSelected} />
    <div role="tabpanel" aria-label={selected}>{selected}</div>
  </AppShellContext.Provider>;
}

beforeEach(() => {
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});

afterEach(() => {
  act(() => root.unmount()); host.remove();
});

describe('JobsResultsTabs', () => {
  it('supports click and arrow-key selection while keeping one tab in the tab order', async () => {
    await act(async () => root.render(<TabHarness />));
    const jobs = host.querySelector<HTMLButtonElement>('#jobs-results-tab-jobs')!;
    const validations = host.querySelector<HTMLButtonElement>('#jobs-results-tab-validations')!;
    expect(jobs.getAttribute('aria-selected')).toBe('true');
    expect(jobs.tabIndex).toBe(0);
    expect(validations.tabIndex).toBe(-1);

    await act(async () => jobs.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true })));
    expect(validations.getAttribute('aria-selected')).toBe('true');
    expect(validations.tabIndex).toBe(0);
    expect(document.activeElement).toBe(validations);
    expect(host.querySelector('[role="tabpanel"]')?.textContent).toBe('validations');

    await act(async () => validations.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true })));
    expect(jobs.getAttribute('aria-selected')).toBe('true');
    expect(host.querySelector('[role="tabpanel"]')?.textContent).toBe('jobs');
  });

  it('selects Validations by click and exposes the translated tab labels', async () => {
    await act(async () => root.render(<TabHarness locale="zh-Hant" />));
    await act(async () => host.querySelector<HTMLButtonElement>('#jobs-results-tab-validations')!.click());
    expect(host.querySelector('[role="tabpanel"]')?.textContent).toBe('validations');
    expect(host.textContent).toContain('工作');
    expect(host.textContent).toContain('驗證');
  });
});
