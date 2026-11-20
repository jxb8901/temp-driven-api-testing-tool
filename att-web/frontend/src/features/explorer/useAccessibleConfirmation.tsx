import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { readUiLocale, translateUiMessage } from '../../uiLocale';
import './source-authoring.css';

export interface ConfirmationOptions {
  title: string;
  message: string;
  confirmLabel: string;
  cancelLabel?: string;
  destructive?: boolean;
}

export function useAccessibleConfirmation() {
  const [pending, setPending] = useState<ConfirmationOptions | null>(null);
  const resolveRef = useRef<((confirmed: boolean) => void) | null>(null);
  const cancelRef = useRef<HTMLButtonElement | null>(null);
  const triggerRef = useRef<HTMLElement | null>(null);
  const id = useId();
  const locale = readUiLocale();

  const confirm = useCallback((options: ConfirmationOptions) => new Promise<boolean>(resolve => {
    if (resolveRef.current) { resolve(false); return; }
    triggerRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    resolveRef.current = resolve;
    setPending(options);
  }), []);

  const finish = useCallback((confirmed: boolean) => {
    const resolve = resolveRef.current;
    resolveRef.current = null;
    setPending(null);
    resolve?.(confirmed);
  }, []);

  useEffect(() => {
    if (!pending) return;
    cancelRef.current?.focus();
    return () => triggerRef.current?.focus();
  }, [pending]);

  useEffect(() => () => { resolveRef.current?.(false); resolveRef.current = null; }, []);

  const dialog = pending ? <div className="att-confirm-backdrop">
    <section className="att-confirm-dialog" role="alertdialog" aria-modal="true"
      aria-labelledby={`${id}-title`} aria-describedby={`${id}-message`}
      onKeyDown={event => {
        if (event.key === 'Escape') { event.preventDefault(); finish(false); }
        if (event.key === 'Tab') {
          const buttons = event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not(:disabled)');
          const first = buttons.item(0), last = buttons.item(buttons.length - 1);
          if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
          else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
        }
      }}>
      <h2 id={`${id}-title`}>{translateUiMessage(pending.title, locale)}</h2>
      <p id={`${id}-message`}>{translateUiMessage(pending.message, locale)}</p>
      <div className="att-confirm-actions">
        <button ref={cancelRef} type="button" className="button button-quiet" onClick={() => finish(false)}>
          {translateUiMessage(pending.cancelLabel || 'Cancel', locale)}
        </button>
        <button type="button" className={pending.destructive ? 'button button-danger' : 'button button-primary'} onClick={() => finish(true)}>
          {translateUiMessage(pending.confirmLabel, locale)}
        </button>
      </div>
    </section>
  </div> : null;

  return { confirm, dialog };
}
