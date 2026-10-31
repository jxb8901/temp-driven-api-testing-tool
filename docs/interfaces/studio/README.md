<!-- att-doc-status: current release=4.2.8 -->

# ATT Studio interfaces

ATT Studio is the browser interface to the registered Packages and Server lifecycle.

![ATT Studio dashboard showing Package summaries, recent Jobs, and Server status](../../system-design/ui-shell-baseline/dashboard-1440-light.png)

*Illustrative UI-shell baseline with sample Package and Job data; the version badge belongs to that screenshot baseline.*

- [Navigation and package selection](navigation.md)
- [Explorer, resource inspection and functional execution](explorer-run.md)
- [Load Testing](load-testing.md)
- [Jobs and Results](jobs-and-results.md)
- [Schedules](schedules.md)
- [Administration and local accounts](administration.md)

Studio uses the public same-origin REST and SSE contracts. The Server remains authoritative for permissions, schema validation, drafts, revisions and execution.
