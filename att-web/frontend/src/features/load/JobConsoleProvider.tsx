import { lazy, Suspense, useCallback, useRef, useState, type ReactNode } from 'react';
import type { LoadJobsApi } from './types';
import { JobConsoleContext, type ConsoleRequest } from './JobConsoleContext';

const FloatingJobConsole = lazy(() => import('./FloatingJobConsole'));

export function JobConsoleProvider({ children, jobsApi }: { children: ReactNode; jobsApi: LoadJobsApi }) {
  const [request, setRequest] = useState<ConsoleRequest | null>(null);
  const [minimized, setMinimized] = useState(false);
  const [switchRequest, setSwitchRequest] = useState<ConsoleRequest | null>(null);
  const requestRef = useRef(request);
  requestRef.current = request;
  const openConsole = useCallback((next: ConsoleRequest) => {
    const current = requestRef.current;
    if (current && current.jobId !== next.jobId) {
      setSwitchRequest(next);
      return false;
    }
    setRequest(current ? { ...current, view: next.view, job: next.job || current.job } : next);
    setMinimized(false);
    return true;
  }, []);
  const confirmSwitch = () => {
    if (!switchRequest) return;
    setRequest(switchRequest);
    setSwitchRequest(null);
    setMinimized(false);
  };
  return <JobConsoleContext.Provider value={{ openConsole }}>
    {children}
    <Suspense fallback={null}>
      {request && <FloatingJobConsole api={jobsApi} request={request} minimized={minimized} onMinimize={() => setMinimized(true)} onRestore={() => setMinimized(false)} onClose={() => setRequest(null)} onSwitchRequest={switchRequest} onConfirmSwitch={confirmSwitch} onCancelSwitch={() => setSwitchRequest(null)} />}
    </Suspense>
  </JobConsoleContext.Provider>;
}
