# Reference Manual migration map for issue #42

Status: completed content migration baseline
Source: pre-#42 `09_Reference_Manual_V3` EN/ZH manuals
Target architecture: `docs/documentation-architecture.md`

The same semantic disposition applies to the matching Chinese sections. This map records where normative behavior moved; release-history wording and duplicate tutorials are intentionally not preserved verbatim.

| Legacy major area | Disposition | Current owner |
|---|---|---|
| 01 Introduction | MERGE / REWRITE | Reference 1 Overview; README keeps only orientation |
| 02 Quick Start | DELETE / MERGE | `docs/quick-start.md`; concise contract examples remain in owning Reference chapters |
| 03 User Guide | SPLIT / MOVE | Workbook/Template -> 2; Tool -> 5.1; Run -> 4.1/10; Reports -> 11 |
| 04 Cookbook | DELETE / MERGE | Quick Start and `examples/`; only normative examples remain in Reference |
| 05 CLI Reference | KEEP / REORGANIZE | Reference 10; Debug/Load conceptual material moves to 4.2/4.3 |
| 06 Configuration Reference | KEEP / SPLIT | Field lookup -> 9; environment concept -> 6; resource concept -> 5 |
| 07 Expression Reference | KEEP / REWRITE | Reference 7; Context ownership moved centrally to 3 |
| 08 Report Reference | KEEP / MERGE | Reference 11; common Action evidence links to 3/5.4 |
| 09 Troubleshooting | MOVE / REWRITE | Reference 12 Validation and Diagnostics |
| 10 Architecture for Maintainers | MOVE | `docs/system-design/runtime-execution.md`; public observable contracts stay in Reference |

## Major subsection dispositions

| Legacy subsection/topic | Disposition | Current owner |
|---|---|---|
| package layout / core concepts | MOVE / MERGE | 1 + 2 |
| historical "What V3.x guarantees" | REWRITE | current chapter contracts; chronology -> CHANGELOG/history |
| 3.1 Workbook | MOVE | 2 |
| 3.2 Template / Flow authoring | MOVE / REWRITE | 2; Flow scope -> 3 |
| 3.3 Tool | SPLIT / REWRITE | 5.1; common result/evidence -> 5.4; execution control -> 8 |
| DBHelper embedded in Tool/config chronology | MOVE / REWRITE | 5.2; field lookup -> 9 |
| MQHelper embedded in configuration | MOVE / REWRITE | 5.3; field lookup -> 9 |
| 3.4 Running Tests | SPLIT | 4.1 + 10 + 12 |
| 3.5 Reports | MOVE / MERGE | 11 |
| standalone Debug material | MOVE / REWRITE | 4.2; CLI syntax remains in 10 |
| Load scenario/scheduler/context/report material | MOVE / MERGE / REWRITE | 4.3; Context -> 3; outputs -> 11; CLI -> 10 |
| Environment profiles / secrets / topology | MOVE / REWRITE | 6; field lookup -> 9 |
| EXEC/META/output scattered material | MOVE / MERGE / REWRITE | 3 |
| timeout/retry/evidence collectors | MOVE / MERGE | 8; resource restrictions cross-link from 5 |
| debug/load outputs | MOVE / MERGE | 4.2/4.3 + 11 |
| diagnostics/validation spread across chapters | MERGE | 12 |
| scheduler/thread/resource-owner internals | MOVE | System Design |
| compatibility aliases/deprecations | MERGE | 3 + Appendix 14.2 |
| schema/version compatibility | MERGE | Appendix 14.1 + owning configuration/authoring chapters |
