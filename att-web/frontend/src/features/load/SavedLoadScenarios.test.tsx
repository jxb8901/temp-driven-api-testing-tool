// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { expect, it, vi } from 'vitest';
import { SavedLoadScenarios } from './SavedLoadScenarios';
const endpoints = vi.hoisted(() => ({ listSavedLoadScenarios: vi.fn(), validateSavedLoadScenario: vi.fn(), submitLoadDraft: vi.fn(), duplicateSavedLoadScenario: vi.fn(), deleteSavedLoadScenario: vi.fn() }));
vi.mock('../../api', () => ({ api: endpoints }));
vi.mock('../code/CodeEditor', () => ({ CodeEditor: ({ value }: { value: string }) => <pre>{value}</pre> }));
it('validates a saved revision before allowing a run or schedule', async () => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  const host = document.createElement('div'); document.body.append(host); const root = createRoot(host);
  const item = { id:'LS1', name:'Traffic', packageId:'p', model:'arrivalRate', revision:2, description:'', tags:[], updatedAt:'' };
  endpoints.listSavedLoadScenarios.mockResolvedValue({items:[item],nextOffset:null});
  endpoints.validateSavedLoadScenario.mockResolvedValue({draftId:'A1',packageId:'p',previewYaml:'approved',expiresAt:new Date(Date.now()+60000).toISOString()});
  const schedule=vi.fn();
  await act(async () => root.render(<SavedLoadScenarios editorApi={{} as never} packages={[]} enabled onJobAccepted={vi.fn()} onSchedule={schedule} />));
  await act(async () => [...host.querySelectorAll('button')].find(button => button.textContent?.includes('Validate / Review'))!.click());
  expect(endpoints.validateSavedLoadScenario).toHaveBeenCalledWith('LS1',2);
  expect(host.textContent).toContain('approved');
  await act(async () => [...host.querySelectorAll('button')].find(button => button.textContent === 'Schedule')!.click());
  expect(schedule).toHaveBeenCalledWith(expect.objectContaining({packageId:'p',draftId:'A1'}));
  expect(endpoints.submitLoadDraft).not.toHaveBeenCalled();
  await act(async () => root.unmount()); host.remove();
});
