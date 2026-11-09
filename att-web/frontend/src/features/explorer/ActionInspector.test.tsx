// @vitest-environment jsdom
import { act, type ReactNode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
vi.mock('../code/CodeEditor', () => ({
  CodeEditor: ({ value, label, language, className = '' }: { value: string; label: string; language: string; className?: string }) =>
    <pre className={`att-code-editor ${className}`} aria-label={label} data-language={language}>{value}</pre>,
}));
import { ActionInspector } from './ActionInspector';

declare global { var IS_REACT_ACT_ENVIRONMENT: boolean; }
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLDivElement;
let root: Root;

beforeEach(() => {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  vi.restoreAllMocks();
});

async function render(node: ReactNode) {
  await act(async () => root.render(node));
}

function click(button: HTMLButtonElement) {
  act(() => button.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })));
}

function actionButton(name: string): HTMLButtonElement {
  const button = [...host.querySelectorAll<HTMLButtonElement>('.att-action-list button')]
    .find(item => item.textContent?.includes(name));
  if (!button) throw new Error(`Action button not found: ${name}`);
  return button;
}

describe('ActionInspector', () => {
  it('preserves supplied authored order, marks disabled entries, and shows common and unknown properties without evaluating expressions', async () => {
    const definition = { actions: {
      '2': { type: 'log', message: 'second' },
      '10': { type: 'assert', assert: '${EXEC.VARS.count} > 0', expected: 'positive', actual: '${EXEC.VARS.count}',
        runWhen: "${EXEC.VARS.count} != '0'", onFailure: 'continue', retry: { maxAttempts: 3, intervalMs: 120, retryOn: ['ASSERTION'] },
        evidence: { response: { source: 'tool.output' } }, customField: '<img src=x onerror=alert(1)>' },
      'x-disabled': { type: 'tool', call: '#{neverRuns()}' },
    } };
    await render(<ActionInspector definition={definition} actionOrder={['10', '2', 'x-disabled']} resourceType="template"
      resourceId="template-1" onOpenFlow={vi.fn()} />);

    const labels = [...host.querySelectorAll('.att-action-list .att-action-name')].map(item => item.textContent);
    expect(labels).toEqual(['10', '2', 'x-disabled']);
    expect(actionButton('x-disabled').textContent).toContain('Disabled');
    expect(host.textContent).toContain("${EXEC.VARS.count} != '0'");
    expect(host.textContent).toContain('${EXEC.VARS.count} > 0');
    expect(host.textContent).toContain('continue');
    expect(host.textContent).toContain('maxAttempts');
    expect(host.textContent).toContain('evidence');
    expect(host.textContent).toContain('customField');
    expect(host.querySelector('img, script, svg')).toBeNull();
    expect(host.textContent).toContain('expression shown as authored');
    expect(host.textContent).not.toContain('positive\n0');

    click(actionButton('x-disabled'));
    expect(host.querySelector('.att-action-properties')?.textContent).toContain('Disabled extension entry');
    expect(host.querySelector('.att-action-properties')?.textContent).toContain('#{neverRuns()}');
  });

  it('shows contextual values for tool, log, and assign actions', async () => {
    const definition = { actions: {
      invoke: { type: 'tool', call: '#{lookup(id=${EXEC.INPUT.id})}', timeoutMs: 500 },
      report: { type: 'log', message: 'done', value: { ok: true }, format: 'json' },
      publish: { type: 'assign', name: 'result', expression: "concat(${EXEC.INPUT.id}, '-done')" },
    } };
    await render(<ActionInspector definition={definition} resourceType="flow" resourceId="flow-1" onOpenFlow={vi.fn()} />);

    expect(host.textContent).toContain('#{lookup(id=${EXEC.INPUT.id})}');
    expect(host.textContent).toContain('timeoutMs');
    click(actionButton('report'));
    expect(host.querySelector('.att-action-properties')?.textContent).toContain('done');
    expect(host.querySelector('.att-action-properties')?.textContent).toContain('ok: true');
    click(actionButton('publish'));
    expect(host.querySelector('.att-action-properties')?.textContent).toContain("concat(${EXEC.INPUT.id}, '-done')");
  });

  it('summarizes action types, authored descriptions, execution modifiers, and grouped properties', async () => {
    const onOpenFlow = vi.fn();
    const longDescription = 'A'.repeat(150);
    const definition = { actions: {
      invoke: { type: 'tool', description: longDescription, call: 'billing.lookup.v2', runWhen: '${EXEC.INPUT.enabled}',
        onFailure: 'continue', retry: { maxAttempts: 3 }, evidence: { collectors: ['response'] }, customFlag: true },
      compose: { type: 'flow', use: 'common.compose.v1' },
      verify: { type: 'assert', assert: '${EXEC.VARS.count} > 0' },
    } };
    await render(<ActionInspector definition={definition} resourceType="template" resourceId="template-1" onOpenFlow={onOpenFlow} />);

    const invoke = actionButton('invoke');
    expect(invoke.textContent).toContain('Tool call · billing.lookup.v2');
    expect(invoke.textContent).toContain('Conditional');
    expect(invoke.textContent).toContain('On failure · continue');
    expect(invoke.textContent).toContain('Retry · up to 3 attempts');
    expect(invoke.textContent).toContain('Evidence collectors');
    const clippedDescription = invoke.querySelector('.att-action-description')?.textContent || '';
    expect(clippedDescription).toHaveLength(120);
    expect(clippedDescription.endsWith('…')).toBe(true);
    expect(host.querySelector('[aria-label="Common properties"] h4')?.textContent).toBe('Common');
    expect(host.querySelector('[aria-label="Type-specific properties"] h4')?.textContent).toBe('Type-specific');
    expect(host.querySelector('[aria-label="Retry properties"] h4')?.textContent).toBe('Retry');
    expect(host.querySelector('[aria-label="Evidence collectors properties"] h4')?.textContent).toBe('Evidence collectors');
    expect(host.querySelector('[aria-label="Additional action properties"]')?.textContent).toContain('customFlag');

    expect(actionButton('compose').textContent).toContain('Flow target · common.compose.v1');
    expect(actionButton('verify').textContent).toContain('Assert · ${EXEC.VARS.count} > 0');
  });

  it('opens only a resolved Flow reference and guards unresolved and recursive targets', async () => {
    const onOpenFlow = vi.fn();
    const definition = { actions: { compose: { type: 'flow', use: 'common.compose.v1' } } };
    const references = [{ type: 'flow' as const, logicalId: 'common.compose.v1', resourceId: 'flow-2', resolution: 'resolved',
      callSite: { actionId: 'compose' } }];
    await render(<ActionInspector definition={definition} resourceType="template" resourceId="template-1" references={references} onOpenFlow={onOpenFlow} />);

    click([...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.includes('Open referenced Flow'))!);
    expect(onOpenFlow).toHaveBeenCalledWith('flow-2');

    await render(<ActionInspector definition={definition} resourceType="template" resourceId="template-1"
      references={[{ ...references[0], resolution: 'unresolved', resourceId: undefined }]} onOpenFlow={onOpenFlow} />);
    expect(host.textContent).toContain('unresolved or invalid');
    expect(host.querySelector('button')?.textContent).not.toContain('Open referenced Flow');

    await render(<ActionInspector definition={definition} resourceType="flow" resourceId="flow-2" references={references} onOpenFlow={onOpenFlow} />);
    expect(host.textContent).toContain('Recursive Flow navigation is disabled');
  });

  it('shows server-authorized file links on every referring Action and keeps blocked references inert', async () => {
    const onOpenFile = vi.fn();
    const referenceId = 'c'.repeat(64);
    const references = [
      { type: 'file' as const, targetKind: 'package-file', logicalId: 'templates/payments/CT001_Confirm.xml',
        originPath: 'templates/payments/template.yaml', referenceId, resolution: 'resolved', available: true,
        callSites: [{ kind: 'action', actionId: 'sendCT001Confirm' }, { kind: 'action', actionId: 'retryConfirm' }] },
      { type: 'file' as const, targetKind: 'package-file', reason: 'forbidden', resolution: 'blocked', available: false,
        callSites: [{ kind: 'action', actionId: 'blockedAction' }] },
    ];
    await render(<ActionInspector definition={{ actions: {
      sendCT001Confirm: { type: 'tool', call: "#{send(payload='&{./CT001_Confirm.xml}')}" },
      retryConfirm: { type: 'log', message: '&{./CT001_Confirm.xml}' },
      blockedAction: { type: 'log', message: '&{./forbidden.xml}' },
    } }} resourceType="template" resourceId="template-1" references={references} onOpenFlow={vi.fn()} onOpenFile={onOpenFile} />);

    expect(host.querySelector('[aria-label="Referenced files for action sendCT001Confirm"]')?.textContent).toContain('templates/payments/CT001_Confirm.xml');
    click([...host.querySelectorAll<HTMLButtonElement>('.att-action-file-links button')][0]);
    expect(onOpenFile).toHaveBeenLastCalledWith('templates/payments/CT001_Confirm.xml', 'templates/payments/template.yaml', {
      type: 'template', resourceId: 'template-1', referenceId, returnTab: 'definition',
    });
    click(actionButton('retryConfirm'));
    expect(host.querySelector('[aria-label="Referenced files for action retryConfirm"] button')?.textContent).toBe('View file');
    click(host.querySelector('[aria-label="Referenced files for action retryConfirm"] button')!);
    expect(onOpenFile).toHaveBeenCalledTimes(2);
    click(actionButton('blockedAction'));
    expect(host.querySelector('[aria-label="Referenced files for action blockedAction"]')?.textContent).toContain('blocked by the Server source policy');
    expect(host.querySelector('[aria-label="Referenced files for action blockedAction"] button')).toBeNull();
  });

  it('renders structured properties and advanced views as read-only YAML without escaping expression newlines', async () => {
    const definition = { actions: { inspect: {
      type: 'tool', call: "#{db.query(sql='select id: value from 中文')}",
      retry: { attempts: 3, delays: [10, 20], when: null, empty: [] },
      evidence: { collectors: ['response', '中文'], details: { 'header: quoted': 'Authorization: "Bearer sample"',
        expression: "#{mq.request(queue='Q1', payload=${EXEC.INPUT.body})}", multiline: 'line one\nline two' } },
      advanced: { values: [null, false, 7], label: '中文: 保留順序' },
    } } };
    await render(<ActionInspector definition={definition} resourceType="template" resourceId="template-1" onOpenFlow={vi.fn()} />);

    const yamlViews = [...host.querySelectorAll<HTMLElement>('.att-action-yaml[data-language="yaml"]')];
    expect(yamlViews.length).toBeGreaterThanOrEqual(4);
    expect(host.querySelector('[aria-label="evidence as read-only YAML"]')?.textContent).toContain('line one');
    expect(host.querySelector('[aria-label="evidence as read-only YAML"]')?.textContent).toContain('line two');
    expect(host.querySelector('[aria-label="evidence as read-only YAML"]')?.textContent).toContain('|-');
    expect(host.querySelector('[aria-label="retry as read-only YAML"]')?.textContent).toContain('empty: []');
    expect(host.querySelector('[aria-label="retry as read-only YAML"]')?.textContent).toContain('when: null');
    expect(host.textContent).toContain("#{mq.request(queue='Q1', payload=${EXEC.INPUT.body})}");
    expect(host.textContent).toContain('中文: 保留順序');
    expect(host.textContent).not.toContain('line one\\nline two');
    expect(host.textContent).toContain('expressions are not evaluated');
  });

  it('handles malformed, empty, long, and large action data within bounded views', async () => {
    await render(<ActionInspector definition={{ actions: [] }} resourceType="template" resourceId="template-1" onOpenFlow={vi.fn()} />);
    expect(host.textContent).toContain('does not contain an action map');

    await render(<ActionInspector definition={{ actions: {} }} resourceType="template" resourceId="template-1" onOpenFlow={vi.fn()} />);
    expect(host.textContent).toContain('No actions are present');

    await render(<ActionInspector definition={{ actions: { broken: 'not an object' } }} resourceType="template" resourceId="template-1" onOpenFlow={vi.fn()} />);
    expect(host.textContent).toContain('This action entry is not an object');
    expect(host.querySelector('.att-action-advanced')?.textContent).toContain('not an object');

    const actions: Record<string, unknown> = { long: { type: 'log', message: 'L'.repeat(110_000) } };
    for (let index = 0; index < 450; index++) actions[`step${index}`] = { type: 'log', message: `step ${index}` };
    await render(<ActionInspector definition={{ actions }} resourceType="template" resourceId="template-1" onOpenFlow={vi.fn()} />);
    expect(host.textContent).toContain('Showing 200 of 451 actions');
    expect(host.querySelector('.att-action-properties')?.textContent).toContain('Expand safe preview');
    expect(host.querySelectorAll('.att-action-value').length).toBeGreaterThan(0);
    expect([...host.querySelectorAll('.att-action-value')].every(item => item.textContent!.length <= 100_100)).toBe(true);
    const valueDetails = host.querySelector<HTMLDetailsElement>('.att-action-value-details');
    expect(valueDetails).not.toBeNull();
    click(valueDetails!.querySelector('summary') as unknown as HTMLButtonElement);
    expect(valueDetails?.open).toBe(true);

    const more = [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.startsWith('Show next'))!;
    click(more);
    expect(host.querySelectorAll('.att-action-list li')).toHaveLength(400);
    expect(host.textContent).toContain('Showing 400 of 451 actions');
  });
});
