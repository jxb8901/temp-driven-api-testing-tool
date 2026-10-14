// @vitest-environment jsdom
import { act, type ReactNode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ExplorerPage } from './ExplorerPage';
import { SourceViewer, sourceViewerLimits } from './SourceViewer';
import { ConfigurationExplorer } from './ConfigurationExplorer';
import { TypedSidecarForm } from './TypedSidecarForm';
import type { ConfigurationResponse, ExplorerApi, JsonObject, ResourceIdentity, ResourceSummary } from './types';

vi.mock('../code/CodeEditor', () => ({
  CodeEditor: ({ value, label, readOnly = true, onChange }: {
    value: string; label: string; readOnly?: boolean; onChange?: (value: string) => void;
  }) => <section className="att-code-editor" aria-label={label}>
    <div className="att-code-host">{readOnly ? <pre>{value}</pre>
      : <textarea aria-label={label} value={value} onChange={event => onChange?.(event.currentTarget.value)} />}</div>
    {readOnly && <button type="button" onClick={() => void navigator.clipboard?.writeText(value)}>Copy displayed source</button>}
  </section>,
  detectCodeLanguage: () => 'yaml',
}));

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
  container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
});

afterEach(async () => {
  await act(async () => root.unmount());
  container.remove();
  vi.restoreAllMocks();
});

async function render(node: ReactNode) {
  await act(async () => root.render(node));
  await settle();
  return container;
}

async function settle() {
  await act(async () => { await new Promise(resolve => window.setTimeout(resolve, 0)); });
}

function button(name: string): HTMLButtonElement {
  const found = [...container.querySelectorAll('button')].find(item => item.textContent?.trim() === name);
  if (!found) throw new Error(`Button not found: ${name}`);
  return found;
}

function setValue(element: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement, value: string) {
  const prototype = Object.getPrototypeOf(element) as object;
  const setter = Object.getOwnPropertyDescriptor(prototype, 'value')?.set;
  act(() => {
    setter?.call(element, value);
    element.dispatchEvent(new Event(element instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
  });
}

function click(element: HTMLElement) {
  act(() => element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })));
}

function apiWith(overrides: Partial<ExplorerApi>): ExplorerApi {
  const fallback = {
    listResources: vi.fn(async () => ({ items: [], total: 0, nextCursor: null })),
    getResource: vi.fn(async () => ({ resource: { type: 'template', resourceId: 'template-1' } as ResourceSummary, definition: {} })),
    getSafeSource: vi.fn(async () => ({ available: true, text: 'safe: true\n', format: 'yaml' })),
    getDeclaredConfiguration: vi.fn(async () => ({ state: 'ready', environments: [], globals: {}, sections: [] })),
    getEffectiveConfiguration: vi.fn(async () => ({ state: 'ready', globals: {}, sections: [] })),
    compareConfiguration: vi.fn(async () => ({ state: 'ready', fields: [] })),
    getDebugForm: vi.fn(async () => ({ target: { type: 'template' as const, id: 'template-1' }, input: {} })),
    previewDebug: vi.fn(async () => ({ draftId: 'draft-1', previewYaml: 'accepted: true' })),
    submitDebug: vi.fn(async () => ({ jobId: 'job-debug-1' })),
    runCase: vi.fn(async () => ({ jobId: 'job-case-1' })),
  } satisfies ExplorerApi;
  return { ...fallback, ...overrides };
}

function resource(type: ResourceSummary['type'], resourceId: string, extra: Partial<ResourceSummary> = {}): ResourceSummary {
  return { type, resourceId, logicalId: resourceId, name: resourceId, state: 'ready', sourceAvailable: true, ...extra };
}

describe('Explorer source viewer', () => {
  it('virtualizes a multi-thousand-line safe projection, refreshes repeatedly, and copies only displayed text', async () => {
    let version = 0;
    const source = Array.from({ length: 5000 }, (_, index) => `line-${index + 1}: ${index === 0 ? '<img src=x onerror=alert(1)>' : 'value'}`).join('\n');
    const getSafeSource = vi.fn(async () => ({ available: true, text: version++ === 0 ? source : `${source}\nrefreshed`, format: 'yaml' }));
    const api = apiWith({ getSafeSource });
    const identity: ResourceIdentity = { packageId: 'payments', type: 'template', resourceId: 'checkout' };
    const clipboardWrite = vi.fn<(text: string) => Promise<void>>(async () => {});
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: clipboardWrite } });

    await render(<SourceViewer api={api} identity={identity} available active />);
    expect(container.textContent).toMatch(/5[,.]?000 lines/);
    expect(container.querySelector('.att-code-editor pre')?.textContent).toContain('line-1: <img src=x onerror=alert(1)>');
    expect(container.querySelector('img,script,svg')).toBeNull();
    expect(container.querySelector('.att-code-editor pre')?.textContent).toContain('line-5000: value');

    click(button('Refresh source'));
    await settle();
    expect(getSafeSource).toHaveBeenCalledTimes(2);
    click(button('Refresh source'));
    await settle();
    expect(getSafeSource).toHaveBeenCalledTimes(3);

    click(button('Copy displayed source'));
    await settle();
    expect(clipboardWrite).toHaveBeenCalledTimes(1);
    expect(clipboardWrite.mock.calls[0][0]).toBe(`${source}\nrefreshed`);
  });

  it('shows an explicit empty state and reports expired authentication without leaking API detail', async () => {
    const identity: ResourceIdentity = { packageId: 'payments', type: 'flow', resourceId: 'empty' };
    const api = apiWith({ getSafeSource: vi.fn(async () => ({ available: true, text: '', format: 'yaml' })) });
    await render(<SourceViewer api={api} identity={identity} available active />);
    expect(container.textContent).toContain('Safe source is empty.');
    expect(container.querySelector('.att-code-editor')).toBeNull();

    const authApi = apiWith({ getSafeSource: vi.fn(async () => { throw Object.assign(new Error('token=must-not-display'), { status: 401 }); }) });
    await render(<SourceViewer api={authApi} identity={identity} available active />);
    expect(container.textContent).toContain('Your session has expired.');
    expect(container.textContent).not.toContain('token=must-not-display');
  });

  it('caps the safe source projection before rendering', async () => {
    const text = 'x'.repeat(sourceViewerLimits.maxCharacters + 100);
    const api = apiWith({ getSafeSource: vi.fn(async () => ({ available: true, text })) });
    await render(<SourceViewer api={api} identity={{ packageId: 'p', type: 'template', resourceId: 'r' }} available active />);
    expect(container.textContent).toContain('Source exceeds the viewer limit.');
    expect(container.querySelector('.att-code-editor pre')?.textContent?.length).toBeLessThanOrEqual(sourceViewerLimits.maxCharacters);
  });
});

describe('typed sidecar form', () => {
  it('keeps redacted package defaults until a scalar override is explicitly selected', async () => {
    const keepDefault = { $attDebugKeepDefault: '/inputs/credential' };
    const onChange = vi.fn((_value: JsonObject) => {});
    const onValidityChange = vi.fn((_valid: boolean) => {});
    const initial: JsonObject = { inputs: { credential: keepDefault, retries: 3 }, vars: { dryRun: true } };
    await render(<TypedSidecarForm value={initial} groups={[{ key: 'inputs', label: 'Inputs' }, { key: 'vars', label: 'Variables' }]}
      resetKey={0} onChange={onChange} onValidityChange={onValidityChange} />);

    const checkbox = container.querySelector<HTMLInputElement>('.att-sidecar-keep-default input');
    expect(checkbox?.checked).toBe(true);
    expect(container.textContent).not.toContain('real-password');
    expect(onChange).not.toHaveBeenCalled();

    click(checkbox!);
    const type = [...container.querySelectorAll('select')].find(item => item.getAttribute('aria-label') === 'credential value type');
    expect(type).toBeDefined();
    setValue(type!, 'string');
    const override = container.querySelector<HTMLInputElement>('input[aria-label="credential value"]');
    expect(override).not.toBeNull();
    setValue(override!, 'safe-override');
    expect(onChange).toHaveBeenLastCalledWith({ inputs: { credential: 'safe-override', retries: 3 }, vars: { dryRun: true } });
  });

  it('validates advanced JSON and preserves multiple typed groups through reset', async () => {
    let current: JsonObject = { inputs: { count: 2 }, arguments: { enabled: false } };
    const onChange = vi.fn((value: JsonObject) => { current = value; });
    const onValidityChange = vi.fn();
    await render(<TypedSidecarForm value={current} groups={[{ key: 'inputs', label: 'Inputs' }, { key: 'arguments', label: 'Tool arguments' }]}
      resetKey={0} onChange={onChange} onValidityChange={onValidityChange} />);

    const advanced = container.querySelector<HTMLTextAreaElement>('textarea');
    expect(advanced).not.toBeNull();
    setValue(advanced!, '{');
    await settle();
    expect(container.textContent).toContain('Expected property name or');
    expect(container.querySelector('.att-sidecar-advanced [aria-invalid="true"]')).not.toBeNull();

    setValue(advanced!, '{"inputs":{"count":4},"arguments":{"enabled":true,"nested":{"ok":true}}}');
    await settle();
    expect(current).toEqual({ inputs: { count: 4 }, arguments: { enabled: true, nested: { ok: true } } });
    expect(onValidityChange).toHaveBeenLastCalledWith(true);
  });

  it('blocks invalid numeric values and non-object JSON', async () => {
    let valid = true;
    await render(<TypedSidecarForm value={{ inputs: { count: 2 } }} groups={[{ key: 'inputs', label: 'Inputs' }]}
      resetKey={0} onChange={() => {}} onValidityChange={next => { valid = next; }} />);
    const numberInput = container.querySelector<HTMLInputElement>('input[aria-label="count value"]');
    expect(numberInput?.type).toBe('number');
    setValue(numberInput!, '');
    await settle();
    expect(valid).toBe(false);

    const advanced = container.querySelector<HTMLTextAreaElement>('textarea');
    setValue(advanced!, '[]');
    await settle();
    expect(container.textContent).toContain('Enter a JSON object.');
  });
});

describe('Explorer page integration', () => {
  it('supports keyboard source tabs, registers dirty sidecar edits, and carries anonymous labels into accepted jobs', async () => {
    const template = resource('template', 'checkout');
    const debugInput: JsonObject = { inputs: { credential: { $attDebugKeepDefault: '/inputs/credential' } }, vars: { retries: 2 } };
    const getSafeSource = vi.fn(async () => ({ available: true, text: 'template: checkout\n', format: 'yaml' }));
    const getDebugForm = vi.fn(async () => ({ target: { type: 'template' as const, id: 'checkout' }, input: debugInput, redacted: true }));
    const submitDebug = vi.fn(async () => ({ jobId: 'job-99' }));
    const api = apiWith({
      listResources: vi.fn(async () => ({ items: [template], total: 1, nextCursor: null })),
      getResource: vi.fn(async () => ({ resource: template, definition: { template: 'checkout' } })),
      getSafeSource, getDebugForm, submitDebug,
    });
    const onResourceRouteChange = vi.fn();
    const onJobAccepted = vi.fn();
    const dirtyTransitions: boolean[] = [];
    const registerUnsavedChanges = vi.fn((dirty: boolean) => {
      dirtyTransitions.push(dirty);
      return () => {};
    });
    const props = {
      api, packageId: 'payments', resourceRoute: { type: 'template' as const, resourceId: 'checkout' }, environment: '',
      registerUnsavedChanges, onResourceRouteChange, onEnvironmentChange: vi.fn(), onJobAccepted,
    };
    await render(<ExplorerPage {...props} />);
    await settle();

    const tabs = container.querySelector<HTMLElement>('[aria-label="Resource content"]');
    expect(tabs).not.toBeNull();
    act(() => tabs!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true })));
    await settle();
    expect(container.querySelector('[data-tab="source"]')?.getAttribute('aria-selected')).toBe('true');
    expect(getSafeSource).toHaveBeenCalled();

    click(button('Debug this target'));
    await settle();
    expect(container.textContent).toContain('Hidden package-authored values are preserved');
    click(container.querySelector('.att-sidecar-keep-default input')!);
    const type = [...container.querySelectorAll('select')].find(item => item.getAttribute('aria-label') === 'credential value type');
    setValue(type!, 'string');
    setValue(container.querySelector<HTMLInputElement>('input[aria-label="credential value"]')!, 'temporary-override');
    await settle();
    expect(dirtyTransitions).toContain(true);

    click(button('Reset defaults'));
    await settle();
    expect(dirtyTransitions.at(-1)).toBe(false);
    expect(container.querySelector<HTMLInputElement>('.att-sidecar-keep-default input')?.checked).toBe(true);
    click(container.querySelector('.att-sidecar-keep-default input')!);
    const resetType = [...container.querySelectorAll('select')].find(item => item.getAttribute('aria-label') === 'credential value type');
    setValue(resetType!, 'string');
    setValue(container.querySelector<HTMLInputElement>('input[aria-label="credential value"]')!, 'temporary-override');
    await settle();

    setValue(container.querySelector<HTMLInputElement>('input[maxlength="120"]')!, 'anonymous-run-42');
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    click(button('Start Debug'));
    await settle();
    expect(submitDebug).toHaveBeenCalledWith(expect.objectContaining({ packageId: 'payments', principal: 'anonymous-run-42' }));
    expect(onJobAccepted).toHaveBeenCalledWith('job-99');
    expect(dirtyTransitions.at(-1)).toBe(false);
  });

  it('shows anonymous unauthorized resource errors in the page without exposing the response body', async () => {
    const api = apiWith({ listResources: vi.fn(async () => { throw Object.assign(new Error('authorization header rejected'), { status: 401 }); }) });
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={null} environment=""
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onEnvironmentChange={() => {}} onJobAccepted={() => {}} />);
    await settle();
    expect(container.textContent).toContain('Your session has expired.');
    expect(container.textContent).not.toContain('authorization header rejected');
  });

  it('renders hidden and missing configuration sides distinctly and omits hidden values', async () => {
    const api = apiWith({
      getDeclaredConfiguration: vi.fn(async () => ({
        state: 'ready', environments: [{ name: 'left', state: 'active', default: true }, { name: 'right', state: 'active' }],
        globals: {}, sections: [],
      })),
      compareConfiguration: vi.fn(async () => ({
        state: 'ready', leftEnvironment: 'left', rightEnvironment: 'right',
        fields: [{ path: 'database.password', left: { state: 'hidden', value: 'must-not-display' }, right: { state: 'missing' }, change: 'changed' }],
      })),
    });
    await render(<ConfigurationExplorer api={api} packageId="payments" environment=""
      onEnvironmentChange={() => {}} initialMode="declared" />);
    await settle();
    click(button('Compare'));
    await settle();
    expect(container.textContent).toContain('hidden');
    expect(container.textContent).toContain('missing');
    expect(container.textContent).not.toContain('must-not-display');
  });

  it('scopes Compare pages to the selected section and preserves that section while loading more', async () => {
    const compareConfiguration = vi.fn(async (_packageId: string, query: { section: string; offset?: number }) => ({
      state: 'ready', section: query.section, nextOffset: query.offset === 0 ? 50 : null,
      total: 60, fields: [{ path: `${query.section}.field${query.offset || 0}`, left: { state: 'visible', value: 1 }, right: { state: 'visible', value: 2 }, change: 'changed' }],
    }));
    const api = apiWith({
      getDeclaredConfiguration: vi.fn(async () => ({
        state: 'ready', globals: {},
        environments: [{ name: 'left', state: 'active', default: true }, { name: 'right', state: 'active' }],
        sections: [{ id: 'globals', title: 'Global fields' }, { id: 'dbhelpers', title: 'DB helpers' }],
      })),
      compareConfiguration,
    });
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={null} environment=""
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onEnvironmentChange={() => {}} onJobAccepted={() => {}} />);
    click(button('Compare'));
    await settle();
    expect(compareConfiguration.mock.calls[0][1]).toMatchObject({ left: 'left', right: 'right', section: 'globals', offset: 0, limit: 50 });

    const section = [...container.querySelectorAll('label')].find(label => label.textContent?.startsWith('Section'))?.querySelector('select');
    expect(section).toBeTruthy();
    setValue(section!, 'dbhelpers');
    await settle();
    expect(compareConfiguration.mock.calls[1][1]).toMatchObject({ section: 'dbhelpers', offset: 0, limit: 50 });

    click(button('Load more configuration'));
    await settle();
    expect(compareConfiguration.mock.calls[2][1]).toMatchObject({ section: 'dbhelpers', offset: 50, limit: 50 });
    expect(container.textContent).toContain('dbhelpers.field0');
    expect(container.textContent).toContain('dbhelpers.field50');
  });

  it('ignores stale Compare responses after a section change and refreshes after profile changes', async () => {
    const pending: Array<(response: ConfigurationResponse) => void> = [];
    const compareConfiguration = vi.fn<ExplorerApi['compareConfiguration']>((_packageId, _query, _signal) =>
      new Promise<ConfigurationResponse>(resolve => pending.push(resolve)));
    const api = apiWith({
      getDeclaredConfiguration: vi.fn(async () => ({
        state: 'ready', globals: {},
        environments: [{ name: 'left', state: 'active', default: true }, { name: 'right', state: 'active' }, { name: 'stage', state: 'active' }],
        sections: [{ id: 'globals', title: 'Global fields' }, { id: 'dbhelpers', title: 'DB helpers' }],
      })),
      compareConfiguration,
    });
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={null} environment=""
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onEnvironmentChange={() => {}} onJobAccepted={() => {}} />);
    click(button('Compare'));
    await settle();
    const section = [...container.querySelectorAll('label')].find(label => label.textContent?.startsWith('Section'))?.querySelector('select');
    setValue(section!, 'dbhelpers');
    await settle();
    expect(compareConfiguration).toHaveBeenCalledTimes(2);
    expect(compareConfiguration.mock.calls[1][1]).toMatchObject({ section: 'dbhelpers' });

    await act(async () => { pending[0]({ state: 'ready', fields: [{ path: 'stale-global', left: { state: 'visible' }, right: { state: 'visible' }, change: 'changed' }] }); await Promise.resolve(); });
    await settle();
    expect(container.textContent).not.toContain('stale-global');
    await act(async () => { pending[1]({ state: 'ready', fields: [{ path: 'fresh-db', left: { state: 'visible' }, right: { state: 'visible' }, change: 'changed' }] }); await Promise.resolve(); });
    await settle();
    expect(container.textContent).toContain('fresh-db');

    const leftProfile = container.querySelector<HTMLSelectElement>('select[aria-label="Left environment"]');
    setValue(leftProfile!, 'stage');
    await settle();
    expect(compareConfiguration.mock.calls[2][1]).toMatchObject({ left: 'stage', right: 'right', section: 'dbhelpers', offset: 0 });
  });
});
