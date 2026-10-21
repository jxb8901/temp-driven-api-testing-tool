import { lazy, Suspense, createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';
import { api } from '../../api';
import type { JobView, LoadJobsApi } from './types';
import type { JobConsoleViewMode } from './JobConsoleView';

const FloatingJobConsole = lazy(() => import('./FloatingJobConsole').then(module => ({ default: module.FloatingJobConsole })));

interface ConsoleRequest {
  jobId: string;
  view: JobConsoleViewMode;
  job?: JobView | null;
}

interface JobConsoleContextValue {
  openConsole(request: ConsoleRequest): boolean;
}

const JobConsoleContext = createContext<JobConsoleContextValue>({ openConsole: () => false });

export function useJobConsole(): JobConsoleContextValue {
  return useContext(JobConsoleContext);
}

export function JobConsoleProvider({ children, jobsApi = api }: { children: ReactNode; jobsApi?: LoadJobsApi }) {
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
