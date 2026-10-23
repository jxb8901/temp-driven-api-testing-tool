// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { LoadTestingPage } from './LoadTestingPage';
import type { LoadTemplateItem } from './templateTypes';
vi.mock('../code/CodeEditor', () => ({ CodeEditor: ({ value }: { value: string }) => <pre>{value}</pre> }));
let host: HTMLDivElement, root: Root;
beforeEach(() => { vi.useFakeTimers(); host = document.createElement('div'); document.body.append(host); root = createRoot(host); (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true; });
afterEach(async () => { await act(async () => root.unmount()); host.remove(); vi.useRealTimers(); });
const modes = ['single-vu','single-tps','single-workload-mix','multi-vu','multi-tps','multi-workload-mix'];
const items = modes.map(mode => ({ id: `builtin:${mode}`, name: mode, mode, builtin: true, schemaVersion: 'att-load-template/v1', validation: 'valid', definition: {} })) as LoadTemplateItem[];
it('shows all six built-ins and routes creation and history without traffic', async () => {
 const api = { listLoadTemplates: vi.fn(async () => ({ items, total: 6, revisionDigest: 'revision' })) };
 const onCreate = vi.fn(), onJobs = vi.fn();
 await act(async () => root.render(<LoadTestingPage api={api as never} packages={[{ packageId: 'p' }]} tab="templates" enabled onTab={vi.fn()} onCreate={onCreate} onBlank={vi.fn()} onJobs={onJobs}>Legacy resources</LoadTestingPage>));
 await act(async () => vi.advanceTimersByTimeAsync(200));
 expect(host.querySelectorAll('.att-template-presets article')).toHaveLength(6); expect(host.textContent).not.toContain('Legacy resources');
 await act(async () => [...host.querySelectorAll('button')].find(button => button.textContent?.includes('Create Load'))!.click());
 expect(onCreate).toHaveBeenCalledWith('p', items[0]);
 await act(async () => [...host.querySelectorAll('button')].find(button => button.textContent?.includes('View LOAD Jobs'))!.click()); expect(onJobs).toHaveBeenCalledOnce();
});
it('does not fetch templates on the resource-only Scenarios tab', async () => {
 const api = { listLoadTemplates: vi.fn() };
 await act(async () => root.render(<LoadTestingPage api={api} packages={[{ packageId: 'p' }]} tab="scenarios" enabled onTab={vi.fn()} onCreate={vi.fn()} onBlank={vi.fn()} onJobs={vi.fn()}>Legacy resources</LoadTestingPage>));
 await act(async () => vi.advanceTimersByTimeAsync(200)); expect(api.listLoadTemplates).not.toHaveBeenCalled(); expect(host.textContent).toContain('Legacy resources');
});
