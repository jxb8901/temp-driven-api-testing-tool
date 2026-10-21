import { useEffect, useRef, useState, type PointerEvent, type KeyboardEvent } from 'react';
import type { JobView, LoadJobsApi } from './types';
import { JobConsoleView, type JobConsoleViewMode } from './JobConsoleView';

interface ConsoleRequest { jobId: string; view: JobConsoleViewMode; job?: JobView | null }
interface FloatingJobConsoleProps {
  api: LoadJobsApi;
  request: ConsoleRequest;
  minimized: boolean;
  onMinimize(): void;
  onRestore(): void;
  onClose(): void;
  onSwitchRequest: ConsoleRequest | null;
  onConfirmSwitch(): void;
  onCancelSwitch(): void;
}

const MIN_WIDTH = 360;
const MIN_HEIGHT = 280;

function clamp(value: number, min: number, max: number): number { return Math.max(min, Math.min(max, value)); }

export function FloatingJobConsole({ api, request, minimized, onMinimize, onRestore, onClose, onSwitchRequest, onConfirmSwitch, onCancelSwitch }: FloatingJobConsoleProps) {
  const [job, setJob] = useState<JobView | null>(request.job || null);
  const [consoleView, setConsoleView] = useState<JobConsoleViewMode>(request.view);
  const [position, setPosition] = useState({ left: 0, top: 0 });
  const [size, setSize] = useState({ width: 560, height: 460 });
  const [maximized, setMaximized] = useState(false);
  const [dragging, setDragging] = useState(false);
  const panelRef = useRef<HTMLElement>(null);
  const switchDialogRef = useRef<HTMLDivElement>(null);
  const dragOrigin = useRef<{ x: number; y: number; left: number; top: number } | null>(null);
  const resizeOrigin = useRef<{ x: number; y: number; width: number; height: number } | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    if (request.job) setJob(request.job);
    api.getJob(request.jobId, controller.signal).then(value => { if (!controller.signal.aborted) setJob(value); }).catch(() => {
      if (!controller.signal.aborted) setJob(request.job || null);
    });
    return () => controller.abort();
  }, [api, request.jobId, request.job]);
  useEffect(() => setConsoleView(request.view), [request.jobId, request.view]);
  useEffect(() => {
    if (onSwitchRequest) {
      switchDialogRef.current?.querySelector<HTMLButtonElement>('button')?.focus();
    } else if (!minimized) {
      panelRef.current?.querySelector<HTMLButtonElement>('.att-console-drag-handle')?.focus();
    }
  }, [minimized, onSwitchRequest, request.jobId]);

  useEffect(() => {
    const positionPanel = () => setPosition(current => ({
      left: clamp(current.left || window.innerWidth - size.width - 24, 8, Math.max(8, window.innerWidth - size.width - 8)),
      top: clamp(current.top || window.innerHeight - size.height - 24, 8, Math.max(8, window.innerHeight - size.height - 8)),
    }));
    positionPanel();
    window.addEventListener('resize', positionPanel);
    return () => window.removeEventListener('resize', positionPanel);
  }, [size.height, size.width]);

  function startDrag(event: PointerEvent<HTMLButtonElement>) {
    if (event.button !== 0 || maximized) return;
    dragOrigin.current = { x: event.clientX, y: event.clientY, left: position.left, top: position.top };
    setDragging(true);
    try { event.currentTarget.setPointerCapture(event.pointerId); } catch { /* Pointer capture is optional. */ }
  }
  function moveDrag(event: PointerEvent<HTMLButtonElement>) {
    const origin = dragOrigin.current;
    if (!origin) return;
    setPosition({
      left: clamp(origin.left + event.clientX - origin.x, 8, Math.max(8, window.innerWidth - size.width - 8)),
      top: clamp(origin.top + event.clientY - origin.y, 8, Math.max(8, window.innerHeight - size.height - 8)),
    });
  }
  function stopDrag() { dragOrigin.current = null; setDragging(false); }
  function moveByKeyboard(event: KeyboardEvent<HTMLButtonElement>) {
    if (maximized) return;
    const step = event.shiftKey ? 48 : 16;
    const delta = event.key === 'ArrowLeft' ? { left: -step, top: 0 } : event.key === 'ArrowRight' ? { left: step, top: 0 } : event.key === 'ArrowUp' ? { left: 0, top: -step } : event.key === 'ArrowDown' ? { left: 0, top: step } : null;
    if (!delta) return;
    event.preventDefault();
    setPosition(current => ({ left: clamp(current.left + delta.left, 8, Math.max(8, window.innerWidth - size.width - 8)), top: clamp(current.top + delta.top, 8, Math.max(8, window.innerHeight - size.height - 8)) }));
  }
  function resizeByKeyboard(event: KeyboardEvent<HTMLDivElement>) {
    const step = event.shiftKey ? 64 : 24;
    const delta = event.key === 'ArrowRight' ? step : event.key === 'ArrowLeft' ? -step : event.key === 'ArrowDown' ? step : event.key === 'ArrowUp' ? -step : 0;
    if (!delta) return;
    event.preventDefault();
    setSize(current => ({ width: clamp(current.width + (event.key === 'ArrowRight' || event.key === 'ArrowLeft' ? delta : 0), MIN_WIDTH, Math.max(MIN_WIDTH, window.innerWidth - 16)), height: clamp(current.height + (event.key === 'ArrowDown' || event.key === 'ArrowUp' ? delta : 0), MIN_HEIGHT, Math.max(MIN_HEIGHT, window.innerHeight - 16)) }));
  }
  function startResize(event: PointerEvent<HTMLDivElement>) {
    if (event.button !== 0) return;
    resizeOrigin.current = { x: event.clientX, y: event.clientY, width: size.width, height: size.height };
    try { event.currentTarget.setPointerCapture(event.pointerId); } catch { /* Pointer capture is optional. */ }
  }
  function moveResize(event: PointerEvent<HTMLDivElement>) {
    const origin = resizeOrigin.current;
    if (!origin) return;
    setSize({
      width: clamp(origin.width + event.clientX - origin.x, MIN_WIDTH, Math.max(MIN_WIDTH, window.innerWidth - position.left - 8)),
      height: clamp(origin.height + event.clientY - origin.y, MIN_HEIGHT, Math.max(MIN_HEIGHT, window.innerHeight - position.top - 8)),
    });
  }
  function stopResize() { resizeOrigin.current = null; }

  const panelStyle = maximized ? undefined : { left: position.left, top: position.top, width: size.width, height: size.height };
  return <section ref={panelRef} className={minimized ? 'att-console-minimized-host' : 'att-floating-console' + (dragging ? ' is-dragging' : '') + (maximized ? ' is-maximized' : '')} style={minimized ? undefined : panelStyle} aria-label={`Floating console for Job ${request.jobId}`}>
    {!minimized && <button className="att-console-drag-handle" type="button" aria-label="Move Job console; use arrow keys to reposition" title="Drag to move; arrow keys also move the console" onPointerDown={startDrag} onPointerMove={moveDrag} onPointerUp={stopDrag} onPointerCancel={stopDrag} onKeyDown={moveByKeyboard}>Move console</button>}
    <JobConsoleView api={api} jobId={request.jobId} job={job} view={consoleView} onViewChange={setConsoleView} minimized={minimized} onRestore={onRestore} onMinimize={onMinimize} onMaximize={() => setMaximized(value => !value)} maximized={maximized} onClose={onClose} />
    {!minimized && onSwitchRequest && <div ref={switchDialogRef} className="att-console-switch-confirm" role="alertdialog" aria-label="Confirm switching Job console"><p>Switch this console from Job {request.jobId} to Job {onSwitchRequest.jobId}?</p><button type="button" onClick={onConfirmSwitch}>Switch Job</button><button type="button" onClick={onCancelSwitch}>Keep current Job</button></div>}
    {!minimized && !maximized && <div className="att-floating-console-resize" role="separator" aria-label="Resize Job console; use arrow keys" tabIndex={0} onPointerDown={startResize} onPointerMove={moveResize} onPointerUp={stopResize} onPointerCancel={stopResize} onKeyDown={resizeByKeyboard} />}
  </section>;
}
