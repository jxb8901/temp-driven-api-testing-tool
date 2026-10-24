import { useEffect, useState } from 'react';
import { api } from '../../api';
import { contextRelativeReturnTo } from '../../contextPaths';
import { ApiError } from '../../types';
import { formatRoute } from '../../router';
import { JobsPage } from './JobsPage';
import type { JobsFeatureProps } from './types';

export function StandaloneJobConsolePage({ jobId, view }: { jobId: string; view?: JobsFeatureProps['initialTab'] }) {
  const [job, setJob] = useState<Awaited<ReturnType<typeof api.getJob>> | null>(null);
  const [error, setError] = useState<Error | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    setJob(null); setError(null);
    api.getJob(jobId, controller.signal).then(value => { if (!controller.signal.aborted) setJob(value); }).catch(reason => {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason : new Error('Job details could not be loaded.'));
    });
    return () => controller.abort();
  }, [jobId]);
  if (error) {
    const status = error instanceof ApiError ? error.status : 0;
    const loginHref = new URL('login.html?returnTo=' + encodeURIComponent(contextRelativeReturnTo(location.pathname, location.search, location.hash)), document.baseURI).toString();
    return <div className="att-standalone-console" role="alert"><h1>Job Details unavailable</h1><p>{status === 404 ? 'This Job is unavailable or was deleted.' : status === 401 || status === 403 ? 'Your session cannot access this Job Details.' : error.message}</p>{(status === 401 || status === 403) && <a className="button button-primary" href={loginHref}>Sign in again</a>}</div>;
  }
  if (!job) return <div className="att-standalone-console" role="status" aria-busy="true">Loading Job Details…</div>;
  return <div className="att-standalone-console"><JobsPage api={api} jobId={jobId} initialTab={view || 'live'} standalone onTabChange={tab => { location.hash=formatRoute({kind:'jobs',jobId,standalone:true,tab}); }} onOpenJob={() => {}} onShowList={() => {location.hash=formatRoute({kind:'jobs'});}} /></div>;
}
