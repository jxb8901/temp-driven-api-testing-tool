// @vitest-environment jsdom
import { act, type ReactNode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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
    expect(host.querySelector('.att-action-properties')?.textContent).toContain('"ok": true');
    click(actionButton('publish'));
    expect(host.querySelector('.att-action-properties')?.textContent).toContain("concat(${EXEC.INPUT.id}, '-done')");
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
