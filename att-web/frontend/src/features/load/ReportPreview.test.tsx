// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { prepareReportHtml, ReportPreview, resolveReportHref } from './ReportPreview';
import { ApiError } from '../../types';

const report = '<!doctype html><html><head>'
  + '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'sha256-reviewed\'; style-src \'unsafe-inline\'">'
  + '<style>body{color:green}</style></head><body><h1>Safe report</h1>'
  + '<a href="../../case.log">Case log</a><a href="#summary">Summary</a>'
  + '<a href="https://evil.example/">External</a></body></html>';

describe('authenticated isolated report preview', () => {
  it('accepts only relative archive paths, never credentials-bearing external links', () => {
    expect(resolveReportHref('run/report/index.html', '../../case.log')).toBe('case.log');
    expect(resolveReportHref('run/report/index.html', '../summary.yaml#top')).toBe('run/summary.yaml');
    for (const href of ['https://evil.example/', '//evil.example/', 'javascript:alert(1)',
      '?password=abc', '/%2Fsecret', '..\\..\\secret', '\0secret']) {
      expect(resolveReportHref('run/report/index.html', href)).toBeNull();
    }
  });

  it('retains opaque-origin sandbox, inherits the Studio nonce, and prevents fallback link navigation', () => {
    const html = prepareReportHtml(report, 'run/report/index.html', 'STUDIO_NONCE');
    const document = new DOMParser().parseFromString(html, 'text/html');
    expect(document.querySelector('meta[http-equiv="Content-Security-Policy"]')?.getAttribute('content'))
      .toContain("'sha256-zhg8uc2ehLr1YOtvIswiYT86nszYQvE88Tg1hupHR80='");
    expect(document.querySelector('style')?.getAttribute('nonce')).toBe('STUDIO_NONCE');
    expect(document.querySelector('script')?.getAttribute('nonce')).toBe('STUDIO_NONCE');
    const log = document.querySelector<HTMLAnchorElement>('a[data-att-report-link]');
    expect(log?.getAttribute('data-att-report-link')).toBe('../../case.log');
    expect(log?.hasAttribute('href')).toBe(false);
    expect(document.querySelector('a[href="#summary"]')).not.toBeNull();
    expect(document.querySelector('a[href^="https://"]')).toBeNull();
    expect(() => prepareReportHtml('<html><script>alert(1)</script></html>', 'report/index.html', 'NONCE'))
      .toThrow('Content Security Policy');
  });

  let host: HTMLDivElement;
  let root: Root;
  beforeEach(() => {
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  });
  afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
  });

  it('fetches report with the authenticated API and routes links in the parent without iframe HTTP navigation', async () => {
    const readText = vi.fn(async (_job: string, path: string, _signal?: AbortSignal) =>
      path.endsWith('.html') ? report : 'Authenticated case log');
    await act(async () => root.render(<ReportPreview jobId="JOB-1" path="run/report/index.html" readText={readText} />));
    await act(async () => { await Promise.resolve(); });
    const frame = host.querySelector('iframe')!;
    expect(frame).not.toBeNull();
    expect(frame.getAttribute('sandbox')).toBe('allow-scripts');
    expect(frame.hasAttribute('src')).toBe(false);
    expect(frame.getAttribute('srcdoc')).toContain('Safe report');
    expect(readText).toHaveBeenCalledWith('JOB-1', 'run/report/index.html', expect.any(AbortSignal));
    await act(async () => {
      window.dispatchEvent(new MessageEvent('message', {
        source: frame.contentWindow, data: { type: 'att-report-navigation', href: '../../case.log' },
      }));
      await Promise.resolve();
    });
    expect(readText).toHaveBeenCalledWith('JOB-1', 'case.log', expect.any(AbortSignal));
    await act(async () => {
      await vi.waitFor(() => expect(host.textContent).toContain('Authenticated case log'));
    });
    await act(async () => { (host.querySelector('button') as HTMLButtonElement).click(); await Promise.resolve(); });
    expect(host.querySelector('iframe')?.getAttribute('srcdoc')).toContain('Safe report');
  });

  it('shows a 401 as a controlled preview error instead of navigating the browser', async () => {
    const readText = vi.fn(async () => { throw new ApiError('Artifact preview is unavailable (401).', { status: 401 }); });
    await act(async () => root.render(<ReportPreview jobId="JOB-1" path="report/index.html" readText={readText} />));
    await act(async () => { await Promise.resolve(); });
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('session has expired');
    expect(host.querySelector('[role="alert"] a')?.textContent).toBe('Sign in again');
    expect(host.querySelector('iframe')).toBeNull();
  });

  it('hands vetted report-relative navigation to the standalone route when requested', async () => {
    const readText = vi.fn(async () => report);
    const onNavigate = vi.fn();
    await act(async () => root.render(<ReportPreview jobId="JOB-1" path="run/report/index.html" readText={readText} onNavigate={onNavigate} />));
    await act(async () => { await Promise.resolve(); });
    const frame = host.querySelector('iframe')!;
    await act(async () => {
      window.dispatchEvent(new MessageEvent('message', {
        source: frame.contentWindow, data: { type: 'att-report-navigation', href: '../case.log' },
      }));
      await Promise.resolve();
    });
    expect(onNavigate).toHaveBeenCalledWith('run/case.log');
    expect(readText).toHaveBeenCalledTimes(1);
  });
});
