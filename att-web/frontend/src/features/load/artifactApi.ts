import { api, apiBaseUrl, request } from '../../api';
import { readArtifactText } from './artifactTransport';
import type { JobArtifactArchive } from './types';

export const artifactApi = {
  getJobArtifactArchive(jobId: string, offset = 0, signal?: AbortSignal): Promise<JobArtifactArchive> {
    return request<JobArtifactArchive>('jobs/' + encodeURIComponent(jobId) + '/artifacts?offset=' + offset + '&limit=100', { signal });
  },
  async getJobArtifactText(jobId: string, path: string, signal?: AbortSignal): Promise<string> {
    return readArtifactText(api.artifactHref(jobId, path), signal);
  },
  retryJobArtifactArchive(jobId: string): Promise<unknown> {
    return request('jobs/' + encodeURIComponent(jobId) + '/artifacts/retry', { method: 'POST', body: '{}' });
  },
  artifactArchiveHref(jobId: string): string {
    return new URL('jobs/' + encodeURIComponent(jobId) + '/artifacts/archive.zip', apiBaseUrl()).toString();
  },
};
