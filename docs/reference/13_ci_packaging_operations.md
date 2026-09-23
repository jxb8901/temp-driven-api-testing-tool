## 13 CI, Packaging, and Operations

ATT supports source-tree development and offline release packages.

### Development/release gates

```sh
mvn clean verify
python3 tools/build_reference_manual.py --check
python3 tools/validate_reference_content.py
./att.sh validate --package
```

`build.sh` runs the release gate, regenerates the modular Reference Manual, builds the application jar and release/source archives, and verifies the packaged launcher. It requires Java/Maven plus Python 3 and Pandoc for Reference generation.

### Runtime dependencies

Java 8+ is the runtime baseline. ATT does not bundle JDBC drivers; place required driver/dependency jars in `lib/`. IBM MQ is optional: the default build remains usable without MQ client classes, while MQ deployments package the supported IBM client jar/profile.

### Documentation operations

`./att.sh docs` generates package documentation at `build/docs/index.html` from the validated ATT package model. The normative product Reference is independently generated from `docs/reference*` by `tools/build_reference_manual.py`. `./att.sh clean` removes documented generated runtime/build outputs but preserves source inputs.

### CI and environment promotion

CI should validate the package before executing external integration tests, keep Run/Debug/Load evidence as job artifacts as appropriate, and select environments through explicit `--config`/`--env` policy. Stable logical DB/MQ IDs let the same Templates move across SIT/UAT/PREPROD without Action edits.

Parallel jobs should use unique Run IDs and, when independent retention/latest-run state is required, separate output roots. Destructive operations such as clean/report/archive over one shared output root must be serialized.

Maintainer implementation sequencing, scheduler internals and resource-owner details live in `docs/system-design/`, not in this end-user Reference.
