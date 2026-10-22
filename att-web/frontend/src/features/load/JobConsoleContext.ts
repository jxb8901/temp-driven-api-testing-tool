import { createContext, useContext } from 'react';
import type { JobView } from './types';

export type JobConsoleViewMode = 'live' | 'events';

export interface ConsoleRequest {
  jobId: string;
  view: JobConsoleViewMode;
  job?: JobView | null;
}

export interface JobConsoleContextValue {
  openConsole(request: ConsoleRequest): boolean;
}

export const JobConsoleContext = createContext<JobConsoleContextValue>({ openConsole: () => false });

export function useJobConsole(): JobConsoleContextValue {
  return useContext(JobConsoleContext);
}
