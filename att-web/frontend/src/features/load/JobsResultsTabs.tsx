import type { KeyboardEvent } from 'react';
import { useAppShellContext } from '../../AppShell';

export type JobsResultsTab = 'jobs' | 'validations';

export function JobsResultsTabs({ selected, onSelect }: { selected: JobsResultsTab; onSelect(tab: JobsResultsTab): void }) {
  const locale = useAppShellContext().uiLocale;
  const tabs: Array<{ id: JobsResultsTab; label: string }> = locale === 'zh-Hant'
    ? [{ id: 'jobs', label: '工作' }, { id: 'validations', label: '驗證' }]
    : [{ id: 'jobs', label: 'Jobs' }, { id: 'validations', label: 'Validations' }];
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const current = tabs.findIndex(tab => tab.id === selected);
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
      : event.key === 'ArrowRight' ? (current + 1) % tabs.length
        : event.key === 'ArrowLeft' ? (current + tabs.length - 1) % tabs.length : -1;
    if (next < 0) return;
    event.preventDefault();
    onSelect(tabs[next].id);
    event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]')[next]?.focus();
  };
  return <div className="att-jobs-view-tabs" role="tablist" aria-label={locale === 'zh-Hant' ? '工作與結果檢視' : 'Jobs and results views'} onKeyDown={onKeyDown}>
    {tabs.map(tab => <button key={tab.id} type="button" role="tab" id={`jobs-results-tab-${tab.id}`}
      aria-controls="jobs-results-tabpanel" aria-selected={selected === tab.id} tabIndex={selected === tab.id ? 0 : -1}
      onClick={() => onSelect(tab.id)}>{tab.label}</button>)}
  </div>;
}
