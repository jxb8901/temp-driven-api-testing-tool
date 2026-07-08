# Changelog

## 2026-07-08

### Added

- Implemented ATT V1.1 runtime with unified `${...}` context references and `#{...}` tool invocations.
- Added `CaseRuntimeContext` to keep case data, tool input/output, tool files, and ordered tool invocation history in one place.
- Added configurable tool execution through `config/config.yaml`, including tool arguments, parsed outputs, and context injection.
- Added built-in `renderRequestTemplate` artifact generation so rendered request XML is saved as a V1.1 tool artifact.
- Added `api.invocation.yaml` under request templates to define API tool invocation with the request template package.
- Added check template execution using the same tool invocation and context model as request templates.
- Expanded `testcase/payment_regression.xlsx` to 20 executable payment scenarios.
- Added example shell scripts for tool execution and artifact persistence.

### Changed

- Replaced V1 fixed `output/<case>/request.xml` and `output/<case>/response.xml` files with ordered tool artifacts under `output/<case>/tools/NNN_toolName/`.
- Updated post-check templates to read API response XML from `${TOOLS.invokePaymentApi[0].outputFile}`.
- Updated Excel loading to honor `testcase.columns` from `config.yaml`; unconfigured columns are ignored.
- Updated reports to point to the case artifact directory instead of a single output XML file.
- Updated V1.1 design documentation to reflect the tool-artifact request/response flow.

### Removed

- Removed V1 `ApiExecutor` and expected-template validation classes that were replaced by V1.1 tool invocation and check templates.

### Verified

- Compiled main and test sources with `javac --release 8`.
- Ran `testcase/payment_regression.xlsx`: 20 total, 20 passed, 0 failed, 0 error, 0 skipped.
- Ran `git diff --check`.
