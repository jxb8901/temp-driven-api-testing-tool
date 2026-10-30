<!-- att-doc-status: current release=4.2.7 -->

# Schedules in Studio

![ATT Studio scheduled Load view](../../system-design/ui-shell-baseline/scheduled-load-1440-light.png)

*Static UI-shell baseline capture with sample schedule data; the version badge belongs to the screenshot baseline.*

## Schedule routes and standalone console (4.2.4)

Schedules lists never display Schedule IDs. LOAD summaries show saved configured VUs or arrival rate, planned total duration and available warmup/ramp/steady phases per workload, with bounded target bindings. Missing historical settings show Not available. The list reads persisted sanitized summaries without per-row YAML requests.
History → Back to list → Edit resolves both the route view and schedule ID. Refreshing the authorized list preserves unsaved edits in the same form; missing or forbidden definitions show an unavailable state. Legacy RUN definitions without an editable validated snapshot explain how to review a replacement RUN.

From a Job, **Schedule / Reschedule…** opens a new Schedule draft; **View / Edit originating schedule** is a separate action. **Run now** remains independent of both. Schedule `strict`/`pause`/`ignore` policy applies to scheduled submissions only; manual Job Rerun has its own current-versus-strict source choice.

**Open full Job Details in a new tab** opens `#/jobs/{encodedJobId}?standalone=1&tab=live` (or `events`) with Overview, Live Output, Events, Results, Artifacts and Metrics. Tab switching updates the URL while keeping the Job fixed. Old `#/jobs/{jobId}/console?view=live|events` bookmarks adapt to the full Details workspace. Same-origin `target="_blank" rel="noopener noreferrer"` links support `/att/ui/`, refresh and browser navigation. One Job stream is shared within each tab; floating/docked/minimized consoles keep their existing behavior. Inaccessible Jobs show safe unavailable or sign-in messages.
