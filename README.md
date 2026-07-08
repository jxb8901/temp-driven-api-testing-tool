# ATT - Automated Testing Tool

V1.1 configuration and sample assets for the framework described in `docs/02_System_Design_v1.1.md`.

## Run

Requires JDK 8 and Maven.

```sh
mvn test
mvn exec:java -Dexec.args="--config config/config.yaml --suite testcase/payment_regression.xlsx"
```

The repository includes a sample suite at `testcase/payment_regression.xlsx`.

## V1.1 Scope

- Excel-based test suite loading with columns defined in `config/config.yaml`.
- Request XML generation from `templates/request/<name>/template.xml`.
- API invocation definition from `templates/request/<name>/api.invocation.yaml`.
- External tool invocation through `tools` entries in `config/config.yaml`.
- Request-template tool calls using `#{toolName}`.
- Check templates under `templates/check/<name>/template.yaml`.
- PreCheck and PostCheck execution using the same check-template format.
- Sequential execution only.
- Excel report generation.
