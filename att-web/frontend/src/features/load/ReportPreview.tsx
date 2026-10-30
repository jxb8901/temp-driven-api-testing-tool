import { useEffect, useRef, useState } from 'react';
import { CodeEditor, detectCodeLanguage } from '../code/CodeEditor';
import { ApiError } from '../../types';
import { contextRelativeReturnTo } from '../../contextPaths';

type ReadText = (jobId: string, path: string, signal?: AbortSignal) => Promise<string>;

const BRIDGE = "(()=>{const data=document.getElementById('att-report-style-data'),nonce=data?.dataset.attStyleNonce||'';if(data){try{const styles=JSON.parse(data.content.textContent||'[]');if(Array.isArray(styles))styles.forEach(css=>{if(typeof css!=='string')return;const style=document.createElement('style');if(nonce)style.nonce=nonce;style.textContent=css;document.head.appendChild(style)})}catch{}data.remove()}function navigate(event){const target=event.target;if(!(target instanceof Element))return;const link=target.closest('a[data-att-report-link]');if(!link)return;const href=link.getAttribute('data-att-report-link');if(!href)return;event.preventDefault();parent.postMessage({type:'att-report-navigation',href},'*')}document.addEventListener('click',navigate);document.addEventListener('keydown',event=>{if(event.key==='Enter')navigate(event)});})();";
const BRIDGE_HASH = "'sha256-ziKzIL4i4zPg6m2UvoYD8Jkb6Rj2S+c1hIS6rC1s4O0='";
const REPORT_ORIGIN = 'https://att-report.invalid';
const APPROVED_REPORT_SCRIPTS = [
  { producer: 'legacy-report-v1', hash: 'DmkzPdiGxyCfiKKZtr34jiaf1ukqUeAK8RSwFF19ryg=' },
  { producer: 'legacy-report-v2', hash: '2n13XsaCAniqXDGJgtRR2kgh8VlBPDQQ3kb5BQLyzq4=' },
  { producer: 'load-report-4.2.7', hash: 'JngTyYfak/+hWviebrkpXD20Pdm7CCCZsual7sBCLrQ=' },
] as const;

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
 * ZIP publication preserves the original producer HTML. Apply an independent, restrictive
 * viewer CSP only inside srcdoc; never assume the archived source is already sanitized.
 * Unreviewed scripts, external resources and event handlers must not gain the Studio nonce.
 */
export async function prepareReportHtml(source: string, currentPath: string, studioNonce: string): Promise<string> {
  const reportStyles: string[] = [];
  const htmlWithoutStyles = source.replace(/<!--[\s\S]*?(?:-->|$)|<script\b[^>]*>[\s\S]*?(?:<\/script\s*>|$)|<style\b[^>]*>([\s\S]*?)(?:<\/style\s*>|$)/gi,
    (block, css: string | undefined) => {
      if (css !== undefined) { reportStyles.push(css); return ''; }
      return block;
    });
  const document = new DOMParser().parseFromString(htmlWithoutStyles, 'text/html');
  document.querySelectorAll('iframe, object, embed, form, base, link, meta[http-equiv], foreignObject').forEach(node => node.remove());
  for (const element of document.querySelectorAll('*')) {
    for (const attribute of Array.from(element.attributes)) {
      if (/^on/i.test(attribute.name) || ['srcdoc', 'formaction', 'ping', 'download', 'target'].includes(attribute.name.toLowerCase())) {
        element.removeAttribute(attribute.name);
      }
    }
    if (element.hasAttribute('src') && !/^data:image\/(png|jpeg|gif);base64,[A-Za-z0-9+/=\s]+$/i.test(element.getAttribute('src') || '')) {
      element.removeAttribute('src');
    }
  }
  // Hash the actual producer script bytes, not names or attributes. Only scripts already
  // reviewed by ATT may inherit the parent page's script nonce. Others are removed.
  const approved = new Set<string>(APPROVED_REPORT_SCRIPTS.map(script => script.hash));
  for (const script of Array.from(document.querySelectorAll('script'))) {
    if (script.hasAttribute('src')) { script.remove(); continue; }
    if (script.getAttribute('type') === 'application/json' && script.id === 'att-load-summary-data') {
      try {
        const value: unknown = JSON.parse(script.textContent || '');
        if (value && typeof value === 'object' && !Array.isArray(value)) continue;
      } catch { /* unsafe embedded data is discarded */ }
      script.remove();
      continue;
    }
    let trusted = false;
    if (!script.getAttribute('type') && globalThis.crypto?.subtle) {
      const bytes = new TextEncoder().encode(script.textContent || '');
      if (bytes.length <= 256 * 1024) {
        const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes);
        const base64 = btoa(String.fromCharCode(...new Uint8Array(digest)));
        trusted = approved.has(base64);
      }
    }
    if (!trusted) script.remove();
    else if (studioNonce) script.setAttribute('nonce', studioNonce);
  }
  const styleData = document.createElement('template');
  styleData.id = 'att-report-style-data';
  if (studioNonce) styleData.dataset.attStyleNonce = studioNonce;
  styleData.content.appendChild(document.createTextNode(JSON.stringify(reportStyles)));
  document.head.appendChild(styleData);
  const policy = document.createElement('meta');
  policy.setAttribute('http-equiv', 'Content-Security-Policy');
  policy.setAttribute('content', "default-src 'none'; script-src " + APPROVED_REPORT_SCRIPTS.map(script => "'sha256-" + script.hash + "'").join(' ') + ' ' + BRIDGE_HASH
    + (studioNonce ? " 'nonce-" + studioNonce + "'" : '')
    + "; style-src 'unsafe-inline'" + (studioNonce ? " 'nonce-" + studioNonce + "'" : '')
    + "; style-src-attr 'unsafe-inline'"
    + "; img-src data:; form-action 'none'; base-uri 'none'; connect-src 'none'");
  document.head.prepend(policy);

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
    readText(jobId, currentPath, controller.signal).then(async content => {
      if (controller.signal.aborted) return;
      if (/\.html?$/i.test(currentPath)) {
        const nonce = document.querySelector<HTMLScriptElement>('script[nonce]')?.nonce || '';
        const safeHtml = await prepareReportHtml(content, currentPath, nonce);
        if (!controller.signal.aborted) setHtml(safeHtml);
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
