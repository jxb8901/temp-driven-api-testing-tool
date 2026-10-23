import { useEffect, useRef, type ReactNode } from 'react';

export function LoadDialog({ title, children, onCancel, onSave }: { title: string; children: ReactNode; onCancel(): void; onSave?: () => void }) {
  const ref = useRef<HTMLDialogElement>(null);
  const cancelRef = useRef(onCancel); cancelRef.current = onCancel;
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const dialog = ref.current;
    if (dialog?.showModal) dialog.showModal(); else dialog?.setAttribute('open', '');
    return () => { dialog?.close?.(); previous?.focus(); };
  }, []);
  return <dialog ref={ref} className="att-load-dialog" aria-label={title} onCancel={event => { event.preventDefault(); cancelRef.current(); }}>
    <h2>{title}</h2><div className="att-load-dialog-content">{children}</div>
    <footer><button className="button button-quiet" type="button" onClick={onCancel}>{onSave ? 'Cancel' : 'Close'}</button>{onSave && <button className="button button-primary" type="button" onClick={onSave}>Save</button>}</footer>
  </dialog>;
}
