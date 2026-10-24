import { useEffect, useRef, useState } from 'react';
import { CodeEditor, detectCodeLanguage } from '../code/CodeEditor';
import { ApiError } from '../../types';
import { contextRelativeReturnTo } from '../../contextPaths';

type ReadText = (jobId: string, path: string, signal?: AbortSignal) => Promise<string>;

const BRIDGE = "(()=>{function navigate(event){const target=event.target;if(!(target instanceof Element))return;const link=target.closest('a[data-att-report-link]');if(!link)return;const href=link.getAttribute('data-att-report-link');if(!href)return;event.preventDefault();parent.postMessage({type:'att-report-navigation',href},'*')}document.addEventListener('click',navigate);document.addEventListener('keydown',event=>{if(event.key==='Enter')navigate(event)});})();";
const BRIDGE_HASH = "'sha256-zhg8uc2ehLr1YOtvIswiYT86nszYQvE88Tg1hupHR80='";
const REPORT_ORIGIN = 'https://att-report.invalid';

/** Resolve only package-archive-relative URLs. Never pass untrusted links to fetch. */
export function resolveReportHref(currentPath: string, href: string): string | null {
  try {
    if (!href || href.length > 2048 || href.startsWith('/') || href.includes('\\')
      || /[\x00-\x1f\x7f]/.test(href) || /%2f|%5c/i.test(href)) return null;
    const resolved = new URL(href, REPORT_ORIGIN + '/' + currentPath);
    if (resolved.origin !== REPORT_ORIGIN || resolved.search) return null;
    const path = decodeURIComponent(resolved.pathname.slice(1));
    if (!path || path.length > 2048 || path.includes('\\') || /[\x00-\x1f\x7f]/.test(path)
      || path.split('/').some(part => !part || part === '.' || part === '..')) return null;
    return path;
  } catch { return null; }
}

/**
 * The Server has already sanitized the archive HTML. Its CSP permits only
 * reviewed report scripts. Add a tightly scoped click bridge; preserve both
 * that CSP and the opaque-origin iframe boundary.
 */
export function prepareReportHtml(source: string, currentPath: string, studioNonce: string): string {
  const document = new DOMParser().parseFromString(source, 'text/html');
  const policy = document.querySelector<HTMLMetaElement>('meta[http-equiv="Content-Security-Policy"]');
  const configured = policy?.getAttribute('content') || '';
  if (!policy || !configured.includes("default-src 'none'") || !/\bscript-src\s+[^;]+/.test(configured))
    throw new Error('The report does not carry the required sanitized archive Content Security Policy.');
  policy.setAttribute('content', configured.replace(/\bscript-src\s+([^;]+)/,
    (_match, allowed: string) => 'script-src ' + allowed.trim() + ' ' + BRIDGE_HASH));

  // The Studio document CSP is inherited by srcdoc; authorize only the same
  // per-response nonce already used by the parent page, not unsafe-inline.
  if (studioNonce) document.querySelectorAll('style, script').forEach(element => element.setAttribute('nonce', studioNonce));

  for (const link of document.querySelectorAll<HTMLAnchorElement>('a[href]')) {
    const href = link.getAttribute('href') || '';
    if (href.startsWith('#')) continue;
    const destination = resolveReportHref(currentPath, href);
    if (!destination) { link.removeAttribute('href'); continue; }
    if (destination === currentPath && href.includes('#')) {
      link.setAttribute('href', '#' + href.split('#').slice(1).join('#'));
      continue;
    }
    link.setAttribute('data-att-report-link', href);
    link.removeAttribute('href'); // fail closed if the bridge cannot run
    link.setAttribute('role', 'link');
    link.setAttribute('tabindex', '0');
    link.setAttribute('style', 'cursor:pointer;text-decoration:underline');
  }
  const bridge = document.createElement('script');
  if (studioNonce) bridge.setAttribute('nonce', studioNonce);
  bridge.textContent = BRIDGE;
  document.body.appendChild(bridge);
  return '<!doctype html>\n' + document.documentElement.outerHTML;
}

export function ReportPreview({ jobId, path, readText, onNavigate }: { jobId: string; path: string; readText: ReadText; onNavigate?: (path: string) => void }) {
  const [history, setHistory] = useState([path]);
  const [position, setPosition] = useState(0);
  const currentPath = history[position] || path;
  const frame = useRef<HTMLIFrameElement>(null);
  const [html, setHtml] = useState<string | null>(null);
  const [plain, setPlain] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [errorStatus, setErrorStatus] = useState<number | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setHtml(null); setPlain(null); setError(''); setErrorStatus(null);
    readText(jobId, currentPath, controller.signal).then(content => {
      if (controller.signal.aborted) return;
      if (/\.html?$/i.test(currentPath)) {
        const nonce = document.querySelector<HTMLScriptElement>('script[nonce]')?.nonce || '';
        setHtml(prepareReportHtml(content, currentPath, nonce));
      } else setPlain(content);
    }).catch(reason => {
      if (!controller.signal.aborted) {
        setErrorStatus(reason instanceof ApiError ? reason.status : null);
        setError(reason instanceof ApiError && reason.status === 401 ? 'Your session has expired. Sign in again to open this artifact.'
          : reason instanceof ApiError && reason.status === 403 ? 'Your account is not authorized to open this Job artifact.'
            : reason instanceof ApiError && reason.status === 404 ? 'This artifact is unavailable or was not approved for preview.'
              : reason instanceof ApiError && reason.status === 409 ? 'The artifact archive is unavailable. Refresh to check its status.'
                : reason instanceof Error ? reason.message : 'Report preview could not be loaded.');
      }
    });
    return () => controller.abort();
  }, [jobId, currentPath, readText]);

  useEffect(() => {
    const navigate = (event: MessageEvent) => {
      if (event.source !== frame.current?.contentWindow) return;
      const message: unknown = event.data;
      if (!message || typeof message !== 'object') return;
      const data = message as Record<string, unknown>;
      if (data.type !== 'att-report-navigation' || typeof data.href !== 'string') return;
      const target = resolveReportHref(currentPath, data.href);
      if (!target) return;
      if (target === currentPath) return;
      if (onNavigate) { onNavigate(target); return; }
      setHistory(previous => [...previous.slice(0, position + 1), target]);
      setPosition(position + 1);
    };
    window.addEventListener('message', navigate);
    return () => window.removeEventListener('message', navigate);
  }, [currentPath, onNavigate, position]);

  const signInHref = new URL('login.html?returnTo=' + encodeURIComponent(contextRelativeReturnTo(location.pathname, location.search, location.hash)), document.baseURI).toString();

  return <section aria-label="Isolated authenticated report preview">
    <p>Report content is retrieved with your existing ATT session and displayed in an isolated viewer.</p>
    <div>{!onNavigate && <>
      <button type="button" disabled={position === 0} onClick={() => setPosition(position - 1)}>Report back</button>
      <button type="button" disabled={position >= history.length - 1} onClick={() => setPosition(position + 1)}>Report forward</button>
    </>}<span> {currentPath}</span></div>
    {error && <p role="alert">{error}{errorStatus === 401 && <> <a href={signInHref}>Sign in again</a></>}</p>}
    {!error && html === null && plain === null && <p role="status">Loading authenticated report...</p>}
    {html !== null && <iframe key={currentPath} ref={frame} title={'Report: ' + currentPath}
      sandbox="allow-scripts" referrerPolicy="no-referrer" srcDoc={html}
      style={{ width: '100%', minHeight: '65vh', border: 0 }} />}
    {plain !== null && <CodeEditor value={plain.slice(0, 200_000)} readOnly
      language={detectCodeLanguage(currentPath)} sourcePath={currentPath}
      label={currentPath + '; line numbers shown'} maxCharacters={200_000} />}
  </section>;
}
