# ATT Roadmap V3

**Version:** Draft
**Status:** Proposed
**Last Updated:** 2026-07-09

---

# 1. Summary

V2 is the architectural expansion release for ATT.

It should be started only when there is a clear requirement that the current linear stage/action model cannot express cleanly.

V2 should focus on:

- orchestration improvements
- richer execution semantics
- stronger data model
- broader reporting and analysis support

---

# 2. Design Direction

## 2.1 Execution Model

- Parallel execution of cases or stages where safe
- Conditional branches based on runtime results
- Retry and fallback policies per action or stage
- Dependency-aware execution order

## 2.2 Data Model

- Structured run graph or execution trace
- Queryable result model for case/action history
- Better support for trend analysis and failure grouping

## 2.3 Extensibility

- Cleaner executor contract
- Optional plugin-style integrations only if multiple teams need them
- More formal capability discovery for tools and actions

## 2.4 Reporting

- Multiple output formats beyond the Excel workbook
- Machine-readable run export for CI or dashboards
- Aggregated history views

---

# 3. V2 Non-Goals

V2 should still avoid unnecessary complexity.

It should not become:

- a generic workflow engine
- a rule engine
- a web application platform
- a database-backed test management system unless that is explicitly needed

---

# 4. Decision Criteria

Use the following test to decide whether a feature belongs in V2:

- It requires new execution semantics
- It adds orchestration behavior beyond linear actions
- It needs new internal abstractions to stay maintainable
- It changes how test execution is scheduled or aggregated

Examples:

- parallel cases
- dependency-based execution
- conditional branches
- richer run graph analysis

