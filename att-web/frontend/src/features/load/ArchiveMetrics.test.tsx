// @vitest-environment jsdom
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ArchiveMetrics } from './ArchiveMetrics';

describe('ArchiveMetrics presentation', () => {
  it.each(['READY', 'FAILED'])('uses Studio card/table/badge semantics for %s', outcome => {
    const host = document.createElement('div');
    host.innerHTML = renderToStaticMarkup(<ArchiveMetrics value={{ version: 1, outcome, attempts: 1, retries: 0,
      queueWaitMs: 0, writerAllocatedBytes: 1099511627776, zipBytes: null, compressionRatio: 1.23456,
      failurePhase: outcome === 'FAILED' ? 'VERIFY' : null, failureReason: outcome === 'FAILED' ? 'verification_failed' : null }} />);
    expect(host.querySelector('.att-metric-section .att-table-scroll table.att-data-table')).not.toBeNull();
    expect(host.querySelector('caption')?.textContent).toBe('Artifact archive publication metrics');
    expect(host.querySelector('.att-status-badge')?.textContent).toBe(outcome);
    expect(host.querySelector(`.att-status-${outcome === 'READY' ? 'pass' : 'error'}`)).not.toBeNull();
    const rows = [...host.querySelectorAll('tr')];
    expect(rows.find(row => row.textContent?.includes('Queue wait'))?.querySelector('td')?.textContent).toBe('0');
    expect(rows.find(row => row.textContent?.includes('ZIP bytes') && row.textContent?.includes('Unavailable'))).toBeDefined();
    expect(host.querySelector('td[title="1099511627776 bytes"]')?.textContent).toBe('1 TB');
    expect(host.textContent).toContain('not retained or peak heap');
    expect(host.querySelectorAll('th[scope="row"]').length).toBe(17);
    expect(host.textContent).toContain('CPU / memory allocation');
  });
  it('keeps unavailable measurements separate from zero', () => {
    expect(renderToStaticMarkup(<ArchiveMetrics />)).toContain('Archive metrics unavailable.');
  });
});
