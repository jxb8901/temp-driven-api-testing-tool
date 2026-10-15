import type { AsyncState, JobSummary, PackageSummary, ServerVersion } from './types';

export interface AppState {
  serverVersion: AsyncState<ServerVersion>;
  packages: AsyncState<PackageSummary[]>;
  jobs: AsyncState<JobSummary[]>;
}

export type AppAction =
  | { type: 'version/loading' }
  | { type: 'version/ready'; data: ServerVersion }
  | { type: 'version/error'; error: Error }
  | { type: 'packages/loading' }
  | { type: 'packages/ready'; data: PackageSummary[] }
  | { type: 'packages/error'; error: Error }
  | { type: 'jobs/loading' }
  | { type: 'jobs/ready'; data: JobSummary[] }
  | { type: 'jobs/error'; error: Error };

export const initialAppState: AppState = {
  serverVersion: { status: 'loading' },
  packages: { status: 'loading' },
  jobs: { status: 'loading' },
};

/** The Server returns the newest job rows first; Dashboard summaries use the same 100-row window. */
export function latestServerJobWindow(rows: JobSummary[]): JobSummary[] {
  return rows.slice(0, 100);
}

export function appReducer(state: AppState, action: AppAction): AppState {
  switch (action.type) {
    case 'version/loading': return { ...state, serverVersion: { status: 'loading' } };
    case 'version/ready': return { ...state, serverVersion: { status: 'ready', data: action.data } };
    case 'version/error': return { ...state, serverVersion: { status: 'error', error: action.error } };
    case 'packages/loading': return { ...state, packages: { status: 'loading' } };
    case 'packages/ready': return { ...state, packages: { status: 'ready', data: action.data } };
    case 'packages/error': return { ...state, packages: { status: 'error', error: action.error } };
    case 'jobs/loading': return state.jobs.status === 'ready' ? state : { ...state, jobs: { status: 'loading' } };
    case 'jobs/ready': return { ...state, jobs: { status: 'ready', data: action.data } };
    case 'jobs/error': return { ...state, jobs: { status: 'error', error: action.error } };
  }
}

export function serverCapabilities(state: AppState) {
  const version = state.serverVersion.status === 'ready' ? state.serverVersion.data : null;
  return {
    apiVersion: version?.apiVersion ?? null,
    inlineLoadEnabled: version?.inlineLoadEnabled === true,
    loadSchedulingEnabled: version?.loadSchedulingEnabled === true,
  };
}
