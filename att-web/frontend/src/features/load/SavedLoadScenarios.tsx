import { useEffect, useState } from 'react';
import { api, type SavedLoadScenario } from '../../api';
import type { LoadDraftResponse, LoadFeatureProps, LoadJobsApi, PackageOption } from './types';
import { LoadTemplateBuilder } from './LoadTemplateBuilder';
import { LoadDialog } from './LoadDialog';
import { CodeEditor } from '../code/CodeEditor';

/** Private saved definitions are always validated again before they can be submitted. */
export function SavedLoadScenarios({ onJobAccepted, onSchedule, enabled, editorApi, packages }: {
  onJobAccepted(jobId: string): void; onSchedule?: LoadFeatureProps['onSchedule']; enabled: boolean; editorApi: LoadJobsApi; packages: PackageOption[];
}) {
  const [items, setItems] = useState<SavedLoadScenario[]>([]);
  const [next, setNext] = useState<number | null>(null);
  const [message, setMessage] = useState('Loading saved scenarios…');
  const [query, setQuery] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState<SavedLoadScenario | null>(null);
  const [editor, setEditor] = useState<(SavedLoadScenario & { definition: Record<string, unknown> }) | null>(null);
  const [environment, setEnvironment] = useState('');
  const [environments, setEnvironments] = useState<string[]>([]);
  const [draft, setDraft] = useState<LoadDraftResponse | null>(null);
  useEffect(() => {
    const controller = new AbortController(); setMessage('Loading saved scenarios…');
    api.listSavedLoadScenarios(0, controller.signal).then(page => {
      if (!controller.signal.aborted) { setItems(page.items); setNext(page.nextOffset ?? null); setMessage(''); }
    }).catch(error => { if (!controller.signal.aborted) setMessage(error instanceof Error ? error.message : 'Saved scenarios could not be loaded.'); });
    return () => controller.abort();
  }, [refresh]);
  async function action(operation: () => Promise<unknown>) {
    if (busy) return; setBusy(true); setMessage('');
    try { await operation(); } catch (error) { setMessage(error instanceof Error ? error.message : 'The operation failed.'); }
    finally { setBusy(false); }
  }
  async function more() {
    if (next == null) return;
    await action(async () => { const page = await api.listSavedLoadScenarios(next); setItems(current => [...current, ...page.items]); setNext(page.nextOffset ?? null); });
  }
  function exportYaml() {
    if (!draft?.previewYaml || draft.redacted !== false) return;
    const url = URL.createObjectURL(new Blob([draft.previewYaml], { type: 'application/yaml' }));
    const link = document.createElement('a'); link.href = url; link.download = 'att-saved-scenario.yaml'; link.click();
    URL.revokeObjectURL(url);
  }
  if (editor) return <LoadTemplateBuilder templateId={String((editor.definition.templateComposition as Record<string, unknown>)?.templateId || '')} savedScenario={editor} api={editorApi} packages={packages} selectedPackage={editor.packageId} selectedEnvironment={environment} environments={environments} inlineLoadEnabled={enabled} schedulingEnabled={Boolean(onSchedule)} onPackageChange={() => {}} onEnvironmentChange={setEnvironment} onJobAccepted={onJobAccepted} onSchedule={onSchedule} onClose={() => { setEditor(null); setRefresh(value => value + 1); }} />;
  return <section className="page-content att-query-workspace" aria-label="Saved scenarios">
    <h2>Saved scenarios</h2><p>Your private saved definitions must be validated again before use.</p>
    {message && <p role="status">{message}</p>}
    <label>Search saved scenarios <input value={query} onChange={event => setQuery(event.target.value)} /></label>
    <div className="att-table-scroll"><table className="att-data-table"><thead><tr><th>Name / private</th><th>Package</th><th>Model / workloads</th><th>Revision / modified</th><th>Actions</th></tr></thead><tbody>
      {items.filter(item => [item.name,item.description,item.packageId,item.model,...item.tags].join(' ').toLocaleLowerCase().includes(query.toLocaleLowerCase())).map(item => <tr key={item.id}><td>{item.name}<small>{item.description}</small><small>Private · Needs validation</small></td><td>{item.packageId}</td><td>{item.model} · {item.workloadCount ?? '—'} workloads</td><td>{item.revision}<small>{item.updatedAt}</small></td><td>
        <button className="button button-quiet" type="button" disabled={busy || !enabled} onClick={() => void action(async () => { const record = await api.getSavedLoadScenario(item.id); if (!record.definition.templateComposition) throw new Error('This definition cannot be opened in the template editor.'); const current = String(record.definition.environment || ''); setEnvironment(current); const config = await api.getDeclaredConfiguration(record.packageId); setEnvironments((config.environments || []).filter(value => value.state === 'active').map(value => value.name)); setEditor(record); })}>Open / Edit</button>
        <button className="button button-quiet" type="button" disabled={busy || !enabled} onClick={() => void action(async () => { setSelected(item); setDraft(null); setDraft(await api.validateSavedLoadScenario(item.id,item.revision)); })}>Validate / Review</button>
        <button className="button button-quiet" type="button" disabled={busy} onClick={() => void action(async () => { await api.duplicateSavedLoadScenario(item.id,item.revision); setRefresh(value => value + 1); })}>Duplicate</button>
        <button className="button button-quiet" type="button" disabled={busy} onClick={() => { if (window.confirm(`Delete saved scenario ${item.name}?`)) void action(async () => { await api.deleteSavedLoadScenario(item.id,item.revision); setRefresh(value => value + 1); }); }}>Delete</button>
      </td></tr>)}
    </tbody></table></div>
    {next != null && <button className="button button-quiet" type="button" disabled={busy} onClick={() => void more()}>Load more</button>}
    {selected && draft && <LoadDialog title={`Review ${selected.name}`} onCancel={() => { setSelected(null); setDraft(null); }}>
      {message && <p role="alert">{message}</p>}
      <p>Revision {selected.revision} · {draft.packageId} · {draft.environment || 'default environment'}</p>
      <CodeEditor language="yaml" value={draft.previewYaml || ''} readOnly label="Validated scenario preview" />
      <button className="button button-quiet" type="button" disabled={!draft.previewYaml || draft.redacted !== false} onClick={exportYaml}>Export YAML</button>
      <button className="button button-primary" type="button" disabled={busy || Date.parse(draft.expiresAt) <= Date.now()} onClick={() => { if (window.confirm(`Start LOAD for ${selected.name}?`)) void action(async () => { const result = await api.submitLoadDraft({ packageId: draft.packageId, draftId: draft.draftId }); onJobAccepted(result.jobId); }); }}>Run</button>
      {onSchedule && <button className="button button-quiet" type="button" disabled={busy || Date.parse(draft.expiresAt) <= Date.now()} onClick={() => onSchedule({ packageId: draft.packageId, environment: draft.environment, draftId: draft.draftId, previewYaml: draft.previewYaml, expiresAt: draft.expiresAt })}>Schedule</button>}
    </LoadDialog>}
  </section>;
}
