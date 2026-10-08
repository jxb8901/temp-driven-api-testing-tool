# Agent Instructions

These instructions apply to the entire ATT repository.

## Pull-request reviews — publish feedback on GitHub

**Required:** When a user asks an agent to review or re-review a GitHub pull request, leave the review findings **on that pull request** using the available GitHub integration. A response in chat alone is not a completed PR review, unless the user explicitly asks for an offline/draft-only review.

1. **Inspect the current PR state first.** Fetch the current head SHA, changed code/diff, relevant tests and CI checks, and existing PR review discussion. Do not review only an old commit or assume a previous finding is still present.
2. **Re-review changes, not stale findings.** Check whether earlier issues are fixed and avoid reposting duplicates. Identify remaining/new issues with severity, affected paths or lines, impact, and an actionable proposed fix when possible.
3. **Publish the outcome to the PR.** Post an actual GitHub PR review or PR conversation comment. If there are blockers, state `REQUEST CHANGES` as the review recommendation; if none are found, post a concise `APPROVE / READY TO MERGE` recommendation. Do not imply a formal GitHub approval or request-changes review was submitted when only a conversation comment was posted.
4. **Ground the review.** Include the reviewed head SHA, meaningful CI/test results (distinguishing inspected checks from tests personally run), the disposition, and the status of earlier findings on a re-review. Keep comments focused and actionable.
5. **Handle merged/closed PRs carefully.** Do not describe a merged PR as pending merge or create duplicate historical findings. When explicitly asked to comment after merge, clearly mark the comment as post-merge and report only the current, verified outcome.
6. **Report the result in chat with the PR comment link.** Never claim that comments were posted unless the GitHub action confirmed success. If permissions or tooling prevent posting, explain the limitation and provide a ready-to-paste review instead.

Keep this rule limited to **PR review requests**. Ordinary architecture discussions, code questions, or issue reviews do not require unsolicited PR comments.
