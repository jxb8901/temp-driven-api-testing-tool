#!/usr/bin/env python3
from pathlib import Path
from urllib.parse import unquote
import json
import re
import subprocess
import sys

from documentation_contracts import active_schemas, current_files, current_blocks

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs"
PACKAGE_ROOT = ROOT / "packages" / "default"
SCHEMAS = ROOT / "schemas"
MANIFEST = DOCS / "reference-manifest.txt"
LANDING = DOCS / "README.md"
HISTORY_INDEX = DOCS / "history" / "README.md"

HISTORICAL_PATHS = (
    DOCS / "history" / "02_System_Design_V2.6.2.md",
    DOCS / "history" / "02_System_Design_V3.md",
    DOCS / "history" / "03_Roadmap_V4.md",
    DOCS / "history" / "08_Quick_Start_V3.md",
    DOCS / "history" / "09_Reference_Manual_V3.md",
    DOCS / "history" / "09_Reference_Manual_V3.zh.md",
    DOCS / "history" / "10_Diagnostics_V3.3_Plan.md",
    DOCS / "history" / "documentation-architecture.md",
    DOCS / "history" / "reference-migration-map.md",
    DOCS / "history" / "issue-39-db-action-retry.md",
)

errors = []


def fail(message):
    errors.append(message)


def read(path):
    return path.read_text(encoding="utf-8")


def product_version():
    match = re.search(r"<version>([^<]+)</version>", read(ROOT / "pom.xml"))
    if not match:
        raise SystemExit("Unable to read ATT version from pom.xml")
    return match.group(1).strip()


def run_gate(label, args):
    result = subprocess.run(args, cwd=str(ROOT), stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, universal_newlines=True)
    if result.returncode != 0:
        fail("%s failed (exit %s):\n%s" %
             (label, result.returncode, result.stdout.rstrip()))


def manifest_items():
    if not MANIFEST.is_file():
        fail("missing docs/reference-manifest.txt")
        return []
    return [line.strip() for line in read(MANIFEST).splitlines()
            if line.strip() and not line.lstrip().startswith("#")]


def numbered_heading_shape(path):
    shape = []
    for line in read(path).splitlines():
        match = re.match(r"^(#{1,6})\s+([0-9]+(?:\.[0-9]+)*)\b", line)
        if match:
            shape.append((len(match.group(1)), match.group(2)))
        appendix = re.match(r"^(#{1,6})\s+Appendix\s+([A-D])\b", line)
        if appendix:
            shape.append((len(appendix.group(1)), "Appendix " + appendix.group(2)))
    return shape


def markdown_slug(text):
    text = re.sub(r"`([^`]*)`", r"\1", text)
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", text)
    text = text.strip().lower()
    text = "".join(ch for ch in text if ch.isalnum() or ch in " _-")
    text = re.sub(r"\s+", "-", text)
    text = re.sub(r"-+", "-", text)
    return text.strip("-")


def markdown_anchors(path):
    anchors = set()
    counts = {}
    for line in read(path).splitlines():
        match = re.match(r"^#{1,6}\s+(.+?)\s*#*\s*$", line)
        if not match:
            continue
        base = markdown_slug(match.group(1))
        if not base:
            continue
        count = counts.get(base, 0)
        counts[base] = count + 1
        anchors.add(base if count == 0 else "%s-%d" % (base, count))
    return anchors


def is_external(target):
    return bool(re.match(r"^(?:https?|mailto|tel|data|javascript):", target, re.I))


def normalize_link_target(raw):
    raw = raw.strip()
    if raw.startswith("<") and ">" in raw:
        return raw[1:raw.index(">")]
    match = re.match(r"([^\s]+)(?:\s+['\"(].*)?$", raw)
    return match.group(1) if match else raw


def check_markdown_links(path):
    text = read(path)
    for match in re.finditer(r"(?<!!)\[[^\]]+\]\(([^)]+)\)", text):
        target = normalize_link_target(match.group(1))
        if not target or is_external(target):
            continue
        path_part, _, fragment = target.partition("#")
        if not path_part:
            resolved = path
        else:
            resolved = (path.parent / unquote(path_part)).resolve()
            try:
                resolved.relative_to(ROOT.resolve())
            except ValueError:
                fail("%s link escapes repository root: %s" %
                     (path.relative_to(ROOT), target))
                continue
            if not resolved.exists():
                fail("%s broken local link: %s -> %s" %
                     (path.relative_to(ROOT), target, resolved.relative_to(ROOT)))
                continue
        if fragment and resolved.is_file() and resolved.suffix.lower() == ".md":
            anchor = unquote(fragment).lower()
            if anchor not in markdown_anchors(resolved):
                fail("%s broken Markdown anchor: %s" %
                     (path.relative_to(ROOT), target))


def check_version_consistency(version):
    exact_prefixes = {
        ROOT / "README.md": "# ATT %s — API and integration testing" % version,
        DOCS / "quick-start.md": "# ATT v%s quick start" % version,
        DOCS / "quick-start.zh.md": "# ATT v%s 快速入門" % version,
        DOCS / "reference.md": "# ATT v%s reference manual" % version,
        DOCS / "reference.zh.md": "# ATT v%s 使用手冊與參考" % version,
    }
    for path, expected in exact_prefixes.items():
        if not path.is_file():
            fail("missing versioned current document: %s" % path.relative_to(ROOT))
            continue
        lines = read(path).splitlines()
        first = lines[0] if lines else ""
        if not first.startswith(expected):
            fail("%s version/title drift: expected prefix %r, found %r" %
                 (path.relative_to(ROOT), expected, first))

    for path in (DOCS / "reference.md", DOCS / "reference.zh.md"):
        if path.is_file() and ("Version: " + version) not in read(path)[:500]:
            fail("%s does not declare authoritative Version: %s" %
                 (path.relative_to(ROOT), version))

def check_language_parity(items):
    expected = set(items)
    roots = (DOCS / "reference", DOCS / "reference.zh")
    for root in roots:
        actual = set(str(path.relative_to(root)).replace("\\", "/")
                     for path in root.rglob("*.md") if path.name != "README.md")
        for missing in sorted(expected - actual):
            fail("%s missing manifest module: %s" % (root.relative_to(ROOT), missing))
        for extra in sorted(actual - expected):
            fail("%s contains module not listed in reference-manifest.txt: %s" %
                 (root.relative_to(ROOT), extra))

    for rel in items:
        en = DOCS / "reference" / rel
        zh = DOCS / "reference.zh" / rel
        if not en.is_file() or not zh.is_file():
            continue
        en_shape = numbered_heading_shape(en)
        zh_shape = numbered_heading_shape(zh)
        if en_shape != zh_shape:
            fail("EN/ZH numbered heading mismatch for %s: EN=%s ZH=%s" %
                 (rel, en_shape, zh_shape))

    for name in ("reference.md", "reference.zh.md"):
        if not (DOCS / name).is_file():
            fail("missing generated EN/ZH artifact: docs/%s" % name)

    for dirname in ("concepts", "guides", "interfaces", "operations"):
        base = DOCS / dirname
        if not base.is_dir():
            fail("missing bilingual documentation area: docs/%s" % dirname)
            continue
        for path in base.rglob("*.md"):
            if path.name.endswith(".zh.md"):
                english = path.with_name(path.name[:-len(".zh.md")] + ".md")
                if not english.is_file():
                    fail("missing English counterpart for %s" % path.relative_to(ROOT))
            else:
                chinese = path.with_name(path.stem + ".zh.md")
                if not chinese.is_file():
                    fail("missing Traditional Chinese counterpart for %s" % path.relative_to(ROOT))


def check_documentation_status():
    allowed = {"current", "legacy-current", "compatibility", "planned", "deprecated", "historical"}
    marker = re.compile(r"<!-- att-doc-status: ([a-z-]+)(?:\s+[^>]*)? -->")
    for path in DOCS.rglob("*.md"):
        if path in (DOCS / "reference.md", DOCS / "reference.zh.md"):
            continue
        text = read(path)
        found = marker.search(text[:1600])
        if found and found.group(1) not in allowed:
            fail("%s has unknown documentation status: %s" %
                 (path.relative_to(ROOT), found.group(1)))
        if "vnext-proposed" in path.name:
            if not found or found.group(1) != "planned":
                fail("%s must be marked Planned" % path.relative_to(ROOT))
            planned_label = "尚未發行" if path.name.endswith(".zh.md") else "Planned, not released"
            if planned_label not in text[:1200]:
                fail("%s must label the vNext contract as not released" % path.relative_to(ROOT))


def check_documented_examples():
    contract_path = DOCS / "example-contracts.json"
    if not contract_path.is_file():
        fail("missing docs/example-contracts.json")
        return
    try:
        manifest = json.loads(read(contract_path))
    except Exception as error:
        fail("docs/example-contracts.json is invalid JSON: %s" % error)
        return
    if manifest.get("schemaVersion") != "att-doc-examples/v1":
        fail("docs/example-contracts.json must declare att-doc-examples/v1")
    entries = manifest.get("examples")
    if not isinstance(entries, list):
        fail("docs/example-contracts.json must contain an examples array")
        return

    active = active_schemas(read(SCHEMAS / "catalog.yaml"))
    contracts = {}
    for entry in entries:
        if not isinstance(entry, dict):
            fail("docs/example-contracts.json contains a non-object entry")
            continue
        example_id = entry.get("id")
        source = entry.get("source")
        schema = entry.get("schema")
        fmt = entry.get("format")
        if not all(isinstance(value, str) and value for value in (example_id, source, schema, fmt)):
            fail("docs/example-contracts.json contains an incomplete entry: %r" % entry)
            continue
        if example_id in contracts:
            fail("duplicate documentation example id: %s" % example_id)
        contracts[example_id] = entry
        if entry.get("status") != "current":
            fail("only Current examples may be listed in docs/example-contracts.json: %s" % example_id)
        if fmt not in ("json", "yaml"):
            fail("unsupported documentation example format for %s: %s" % (example_id, fmt))
        if "/v" not in schema:
            fail("documentation example %s has no versioned schema: %s" % (example_id, schema))
        else:
            name, version = schema.rsplit("/v", 1)
            if version not in active.get(name, ()):
                fail("documentation example %s uses an unregistered or historical schema: %s" %
                     (example_id, schema))
        candidate = (ROOT / source).resolve()
        try:
            candidate.relative_to(ROOT.resolve())
        except ValueError:
            fail("documentation example source escapes repository root: %s" % source)
            continue
        if not candidate.is_file() or candidate.suffix.lower() != ".md":
            fail("documentation example source is missing or not Markdown: %s" % source)

    tagged = {}
    marker = re.compile(r"^\s*<!-- att-doc-example: current id=([a-z0-9-]+) schema=([^ ]+) format=(json|yaml) -->\s*$")
    fence = re.compile(r"^\s*(`{3,}|~{3,})(json|yaml|yml)\s*$")
    for path in DOCS.rglob("*.md"):
        if path in (DOCS / "reference.md", DOCS / "reference.zh.md") or "history" in path.relative_to(DOCS).parts:
            continue
        lines = read(path).splitlines()
        for index, line in enumerate(lines):
            match = marker.match(line)
            if not match:
                continue
            example_id, schema, fmt = match.groups()
            if example_id in tagged:
                fail("duplicate Current documentation example tag: %s" % example_id)
            tagged[example_id] = {"id": example_id,
                                  "source": path.relative_to(ROOT).as_posix(),
                                  "schema": schema, "format": fmt, "status": "current"}
            next_line = index + 1
            while next_line < len(lines) and not lines[next_line].strip():
                next_line += 1
            if next_line >= len(lines):
                fail("documentation example tag has no code block: %s" % example_id)
                continue
            code = fence.match(lines[next_line])
            if not code:
                fail("documentation example tag is not followed by a YAML/JSON fence: %s" % example_id)
                continue
            language = "yaml" if code.group(2) == "yml" else code.group(2)
            if language != fmt:
                fail("documentation example format/fence mismatch for %s" % example_id)
            closing = code.group(1)[0] * len(code.group(1))
            end = next_line + 1
            while end < len(lines) and lines[end].strip() != closing:
                end += 1
            if end >= len(lines):
                fail("unterminated documentation example code fence: %s" % example_id)
                continue
            body = "\n".join(lines[next_line + 1:end])
            if fmt == "json":
                try:
                    value = json.loads(body)
                    if value.get("schemaVersion") != schema:
                        fail("JSON example schemaVersion does not match tag for %s" % example_id)
                except Exception as error:
                    fail("JSON documentation example %s is invalid: %s" % (example_id, error))
            else:
                declared = re.search(r"^schemaVersion:\s*([^\s#]+)", body, re.M)
                if not declared or declared.group(1) != schema:
                    fail("YAML example schemaVersion does not match tag for %s" % example_id)

    if set(tagged) != set(contracts):
        fail("Current documentation tags and example contract manifest differ: unlisted=%s missing=%s" %
             (sorted(set(tagged) - set(contracts)), sorted(set(contracts) - set(tagged))))
    for example_id in sorted(set(tagged) & set(contracts)):
        if tagged[example_id] != contracts[example_id]:
            fail("example contract metadata differs from its Markdown tag for %s" % example_id)


def check_schema_references():
    current = [ROOT / "README.md", DOCS / "quick-start.md"]
    current += list((DOCS / "reference").rglob("*.md"))
    current += list((DOCS / "reference.zh").rglob("*.md"))
    current += list((DOCS / "system-design").rglob("*.md"))
    tokens = set()
    for path in current:
        if path.is_file():
            tokens.update(re.findall(r"\batt-[a-z0-9-]+/v[0-9]+(?:\.[0-9]+)*\b",
                                     read(path), re.I))

    schema_corpus = "\n".join(read(path) for path in SCHEMAS.rglob("*")
                                if path.is_file() and path.suffix.lower() in
                                (".json", ".xsd", ".yaml", ".yml"))
    catalog_path = SCHEMAS / "catalog.yaml"
    registered = active_schemas(read(catalog_path)) if catalog_path.is_file() else {}
    registered_tokens = {name + "/v" + version for name, versions in registered.items() for version in versions}
    documented_protocols = {
        "att-worker/v1": DOCS / "system-design/worker-protocol.md",
        "att-prewarm/v1": DOCS / "system-design/execution-prewarm.md",
    }
    for token in sorted(tokens):
        protocol_doc = documented_protocols.get(token)
        if (token not in schema_corpus and token not in registered_tokens
                and (protocol_doc is None or not protocol_doc.is_file())):
            fail("current documentation references schemaVersion with no matching schema contract: %s" % token)

    catalog = SCHEMAS / "catalog.yaml"
    if not catalog.is_file():
        fail("missing schemas/catalog.yaml")
    else:
        for filename in re.findall(r":\s*((?:history/)?[A-Za-z0-9._-]+\.(?:json|xsd))\s*$",
                                   read(catalog), re.M):
            if not (SCHEMAS / filename).is_file():
                fail("schemas/catalog.yaml references missing schema file: %s" % filename)


def check_cli_documentation():
    source = read(ROOT / "att-cli/src/main/java/att/CliOptions.java")
    supported_options = set(re.findall(r'"(--[a-z][a-z0-9-]*)"', source))
    supported_options.update(("--server", "--no-auth", "--principal", "--detach", "--download", "--scenario"))
    public_commands = set(("run", "validate", "snapshot", "docs", "report",
                           "build", "clean", "version", "debug", "load", "help", "remote"))

    for rel in ("reference/cli.md", "reference.zh/cli.md"):
        path = DOCS / rel
        if not path.is_file():
            fail("missing CLI Reference: docs/%s" % rel)
            continue
        text = read(path)
        for command in sorted(public_commands):
            if not re.search(r"(?:\./att\.sh|att\.bat)\s+%s\b" %
                             re.escape(command), text):
                fail("%s does not document public command: %s" %
                     (path.relative_to(ROOT), command))
        documented_options = set(re.findall(r"--[a-z][a-z0-9-]*", text))
        for option in sorted(supported_options - documented_options):
            fail("%s does not document supported CLI option: %s" %
                 (path.relative_to(ROOT), option))
        for option in sorted(documented_options - supported_options):
            fail("%s documents unsupported CLI option: %s" %
                 (path.relative_to(ROOT), option))
        for code in ("0", "1", "2", "3"):
            if not re.search(r"(?:exit(?:\s+code)?|退出码|退出碼)[^\n]{0,80}\b%s\b|\b%s\b[^\n]{0,80}(?:PASS|FAIL|INVALID|ERROR|runtime|validation)" %
                             (code, code), text, re.I):
                fail("%s does not clearly cover exit code %s" %
                     (path.relative_to(ROOT), code))

    quick = read(DOCS / "quick-start.md") if (DOCS / "quick-start.md").is_file() else ""
    for command in re.findall(r"\./att\.sh\s+([a-z][a-z0-9-]*)", quick):
        if command not in public_commands:
            fail("docs/quick-start.md uses unsupported ATT command: %s" % command)
    for line in quick.splitlines():
        if "./att.sh" not in line and "att.bat" not in line:
            continue
        for option in re.findall(r"--[a-z][a-z0-9-]*", line):
            if option not in supported_options:
                fail("docs/quick-start.md uses unsupported ATT option: %s" % option)


    # Secondary current docs/examples must use supported commands/options too.
    for path in current_files(ROOT):
        if path.suffix.lower() != ".md":
            continue
        if path.name in ("migrations.md", "compatibility.md") and "appendices" in path.parts:
            continue
        for line in current_blocks(read(path)):
            match = re.search(r"(?:\./att\.sh|att\.bat)\s+([a-z][a-z0-9-]*)", line)
            if not match:
                continue
            if match.group(1) not in public_commands:
                fail("%s uses unsupported ATT command: %s" %
                     (path.relative_to(ROOT), match.group(1)))
            for option in re.findall(r"--[a-z][a-z0-9-]*", line):
                if option not in supported_options:
                    fail("%s uses unsupported ATT option: %s" %
                         (path.relative_to(ROOT), option))



def check_vnext_cli_contract():
    source_path = ROOT / "att-cli/src/main/java/att/VNextOptions.java"
    if not source_path.is_file():
        fail("missing vNext CLI parser: %s" % source_path.relative_to(ROOT))
        return
    source = read(source_path)
    command_match = re.search(r"COMMANDS\s*=\s*set\(([^)]*)\)", source)
    if not command_match:
        fail("could not derive vNext commands from VNextOptions.COMMANDS")
        return
    commands = set(re.findall(r'"([^"]+)"', command_match.group(1)))
    supported_options = set(re.findall(r'"(--[a-z][a-z0-9-]*|-[a-z])"', source))
    takes_value = re.search(
        r"takesValue\(String arg\)\s*\{\s*return set\((.*?)\)\.contains\(arg\);",
        source, re.S)
    if not takes_value:
        fail("could not derive value-taking vNext options from VNextOptions.takesValue")
        value_options = set()
    else:
        value_options = set(re.findall(r'"([^"]+)"', takes_value.group(1)))

    if ("if (b.dryRun&&isResource(b.targetKind))" not in source
            or "run --dry-run is not available for Template, Flow, or Tool targets" not in source):
        fail("vNext parser no longer rejects dry-run for Template, Flow, and Tool RUN")
    if ("if (b.input!=null && !(run&&isResource(b.targetKind)))" not in source
            or "--input is supported only for Template, Flow, or Tool RUN" not in source):
        fail("vNext parser no longer limits input to Template, Flow, and Tool RUN")

    for rel in ("interfaces/cli/vnext-proposed.md",
                "interfaces/cli/vnext-proposed.zh.md"):
        path = DOCS / rel
        if not path.is_file():
            fail("missing proposed vNext CLI reference: docs/%s" % rel)
            continue
        text = read(path)
        marker = re.search(r"<!-- att-doc-status: ([^>]+) -->", text[:1500])
        if not marker or "planned" not in marker.group(1) or "issue=287" not in marker.group(1):
            fail("%s must remain marked Planned for issue #287" % path.relative_to(ROOT))
        grammar = re.search(r"<([^<>]+)>\s*<target-kind>", text)
        if not grammar:
            fail("%s does not expose a parser-shaped top-level command grammar" %
                 path.relative_to(ROOT))
        else:
            documented_commands = set(grammar.group(1).split("|"))
            if documented_commands != commands:
                fail("%s command grammar differs from VNextOptions: documented=%s parser=%s" %
                     (path.relative_to(ROOT), sorted(documented_commands), sorted(commands)))

        documented_options = set(re.findall(
            r"(?<!\w)--[a-z][a-z0-9-]*|(?<!\w)-[a-z](?!\w)", text))
        if documented_options != supported_options:
            fail("%s vNext options differ from VNextOptions: undocumented=%s unsupported=%s" %
                 (path.relative_to(ROOT), sorted(supported_options - documented_options),
                  sorted(documented_options - supported_options)))

        backtick = chr(96)
        if rel.endswith(".zh.md"):
            target_rules = (
                "Template、Flow 及 Tool RUN 會拒絕 " + backtick + "--dry-run" + backtick,
                "直接 LOAD 會拒絕 " + backtick + "--input" + backtick,
            )
        else:
            target_rules = (
                "Template, Flow, and Tool RUN reject " + backtick + "--dry-run" + backtick,
                "Direct LOAD rejects " + backtick + "--input" + backtick,
            )
        for rule in target_rules:
            if rule not in text:
                fail("%s is missing the parser-aligned target option rule: %s" %
                     (path.relative_to(ROOT), rule))

        # A legacy verb cannot become vNext merely by appearing in a migration table.
        for lineno, line in enumerate(text.splitlines(), 1):
            match = re.search(r"(?:\./att\.sh|att\.bat)\s+(.+)$", line)
            if not match:
                continue
            tokens = re.findall(r"--[a-z][a-z0-9-]*|-[a-z]|[A-Za-z0-9_./:@+-]+",
                                match.group(1))
            index = 0
            command = None
            while index < len(tokens):
                token = tokens[index]
                if token in value_options:
                    if index + 1 >= len(tokens):
                        fail("%s:%s option %s is missing its value" %
                             (path.relative_to(ROOT), lineno, token))
                        break
                    index += 2
                elif token.startswith("-"):
                    index += 1
                else:
                    command = token
                    break
            if command not in commands:
                fail("%s:%s uses a top-level command outside the vNext parser: %s" %
                     (path.relative_to(ROOT), lineno, command or "<missing>"))


def check_server_validation_api_contract():
    servlet_path = ROOT / "att-server/src/main/java/att/server/ApiServlet.java"
    runtime_path = ROOT / "att-server/src/main/java/att/server/ServerRuntime.java"
    dto_path = ROOT / "att-server-api/src/main/java/att/server/api/AttValidationReport.java"
    schema_path = SCHEMAS / "att-validation-v1.schema.json"
    test_path = ROOT / "att-server/src/test/java/att/server/AuthenticationIntegrationTest.java"
    servlet = read(servlet_path)
    runtime = read(runtime_path)
    dto = read(dto_path)
    tests = read(test_path)
    try:
        schema = json.loads(read(schema_path))
    except Exception as error:
        fail("att-validation/v1 schema is invalid JSON: %s" % error)
        return

    if '"/validation".equals(path)' not in servlet or "validationPost(req,res,requestId,authorizationPrincipal)" not in servlet:
        fail("Server POST route table no longer dispatches /validation to validationPost")
    validation_method = re.search(
        r"(?ms)^    private void validationPost\b.*?^    }\s*$", servlet)
    if not validation_method or "runtime.validateVNext(input,actor)" not in validation_method.group(0):
        fail("POST /validation no longer delegates to ServerRuntime.validateVNext")
    elif ("requireOperator(req,res,requestId)" not in validation_method.group(0)
          or "requireSystemAdmin(req,res,requestId)" not in validation_method.group(0)):
        fail("POST /validation authorization no longer distinguishes operators from system administrators")

    allowed_match = re.search(r"allowedFields\s*=\s*Set\.of\(([^)]*)\)", runtime)
    if not allowed_match:
        fail("could not derive Server validation request fields")
        allowed_fields = set()
    else:
        allowed_fields = set(re.findall(r'"([^"]+)"', allowed_match.group(1)))
    expected_request_fields = {
        "command", "targetKind", "target", "packageId", "environment", "all",
    }
    if allowed_fields != expected_request_fields:
        fail("Server validation request field set changed: %s" % sorted(allowed_fields))

    tick = chr(96)
    for rel, label in (
            ("interfaces/server-api/validation.md", "Allowed fields:"),
            ("interfaces/server-api/validation.zh.md", "允許欄位：")):
        path = DOCS / rel
        if not path.is_file():
            fail("missing planned Server validation API page: docs/%s" % rel)
            continue
        text = read(path)
        marker = re.search(r"<!-- att-doc-status: ([^>]+) -->", text[:1500])
        if (not marker or "planned" not in marker.group(1)
                or "release=unreleased" not in marker.group(1)
                or "issue=287" not in marker.group(1)):
            fail("%s must remain Planned until issue #287 is released" %
                 path.relative_to(ROOT))
        allowed_line = re.search(r"(?m)^%s\s*(.*)$" % re.escape(label), text)
        if not allowed_line:
            fail("%s does not list the exact Server validation request fields" %
                 path.relative_to(ROOT))
        else:
            documented_fields = set(re.findall(
                tick + r"([A-Za-z][A-Za-z0-9]*)" + tick, allowed_line.group(1)))
            if documented_fields != allowed_fields:
                fail("%s request fields differ from ServerRuntime.validateVNext: documented=%s server=%s" %
                     (path.relative_to(ROOT), sorted(documented_fields), sorted(allowed_fields)))
        for field in ("schemaVersion", "scope", "backend", "sections", "diagnostics",
                      "counts", "overallStatus", "complete", "exitCode", "requestId"):
            if tick + field + tick not in text:
                fail("%s does not document response field %s" %
                     (path.relative_to(ROOT), field))
        if "att-validation-v1.schema.json" not in text:
            fail("%s does not link the att-validation/v1 JSON Schema" %
                 path.relative_to(ROOT))
        for status in ("200", "400", "401", "403", "404", "413", "415", "500", "503", "504"):
            if tick + status + tick not in text:
                fail("%s does not document HTTP status %s" %
                     (path.relative_to(ROOT), status))

    required_response_fields = set(schema.get("required", []))
    dto_fields = set(re.findall(r'out\.put\("([A-Za-z][A-Za-z0-9]*)"', dto))
    if not required_response_fields.issubset(dto_fields):
        fail("att-validation/v1 DTO no longer emits schema-required fields: %s" %
             sorted(required_response_fields - dto_fields))
    if schema.get("properties", {}).get("schemaVersion", {}).get("const") != "att-validation/v1":
        fail("att-validation/v1 schema version changed without updating the API contract gate")
    if "att-validation/v1" not in dto or 'report.put("requestId",requestId)' not in servlet:
        fail("Server validation response no longer uses att-validation/v1 with requestId")

    for rel in ("interfaces/server-api/v1.md", "interfaces/server-api/v1.zh.md"):
        current = DOCS / rel
        if not current.is_file():
            fail("missing Current Server API index: docs/%s" % rel)
            continue
        text = read(current)
        header = "| Method | Path | Purpose |" if rel.endswith("/v1.md") else "| 方法 | 路徑 | 用途 |"
        if header not in text:
            fail("%s is missing its Current route table" % current.relative_to(ROOT))
            continue
        route_table = text.split(header, 1)[1].split("\n\n", 1)[0]
        if "/validation" in route_table or "/jobs/validate" not in route_table:
            fail("%s must keep /validation out of Current routes and document /jobs/validate" %
                 current.relative_to(ROOT))

    for rel, link in (
            ("interfaces/server-api/README.md", "[Validation API (planned, #287)](validation.md)"),
            ("interfaces/server-api/README.zh.md", "[規劃中的 Validation API（#287）](validation.zh.md)")):
        nav = DOCS / rel
        if not nav.is_file() or link not in read(nav):
            fail("Server API navigation is missing the Planned validation link: docs/%s" % rel)

    route_position = tests.find('"/att/api/v1/validation"')
    test_start = tests.rfind("\n", 0, route_position) + 1 if route_position >= 0 else -1
    test_end = tests.find("JsonNode form=getJson", test_start)
    test_block = tests[test_start:test_end] if test_start >= 0 and test_end > test_start else ""
    if (not test_block or '"att-validation/v1"' not in test_block
            or 'validation.path("backend")' not in test_block):
        fail("AuthenticationIntegrationTest must assert the Server validation report contract")
    if ",200);" not in test_block:
        fail("AuthenticationIntegrationTest must verify a successful POST /validation response")
    for status in ("400", "401", "403"):
        if "assertEquals(" + status + "," not in test_block:
            fail("AuthenticationIntegrationTest must cover POST /validation HTTP %s" % status)


def check_secret_placeholders():
    files = []
    for root in (ROOT / "config", ROOT / "examples", PACKAGE_ROOT / "templates", PACKAGE_ROOT / "testdata"):
        if root.is_dir():
            files += [path for path in root.rglob("*")
                      if path.is_file() and path.suffix.lower() in
                      (".yaml", ".yml", ".md")]
    files += [ROOT / "README.md", DOCS / "quick-start.md"]
    files += list((DOCS / "reference").rglob("*.md"))
    files += list((DOCS / "reference.zh").rglob("*.md"))

    pattern = re.compile(r"(?im)^\s*(password|token|secret|credential(?:s)?)\s*:\s*([^#\n]*?)\s*$")
    for path in files:
        if not path.is_file():
            continue
        for match in pattern.finditer(read(path)):
            value = match.group(2).strip().strip("'\"")
            if not value:
                continue
            safe = ("${ENV:" in value or value.startswith("<") or
                    "placeholder" in value.lower() or "example" in value.lower() or
                    "changeme" in value.lower() or set(value) <= set("*xX"))
            if not safe:
                fail("%s contains a literal value in secret-like field %s; use ENV/placeholder syntax" %
                     (path.relative_to(ROOT), match.group(1)))


def check_critical_examples():
    assets = (
        "packages/default/testcase/payment2.xlsx",
        "packages/default/templates/PAYMENT_INVOKE/debug.yaml",
        "examples/load/closed-smoke.yaml",
        "examples/load/arrival-smoke.yaml",
        "config/config.yaml",
        "config/environments/sit.yaml",
        "config/environments/uat.yaml",
        "config/dbhelpers/sit/orders.yaml",
        "config/dbhelpers/uat/orders.yaml",
        "config/mqhelpers/sit/payment.yaml",
        "config/mqhelpers/uat/payment.yaml",
        "config/tools/sample.yaml",
        "config/tools/orders-db.yaml",
    )
    for rel in assets:
        if not (ROOT / rel).is_file():
            fail("missing checked-in documentation example asset: %s" % rel)
    if not (DOCS / "example-contracts.json").is_file():
        fail("missing tagged example contract manifest: docs/example-contracts.json")

    command_group = ROOT / "config/tools/sample.yaml"
    call_group = ROOT / "config/tools/orders-db.yaml"
    if command_group.is_file() and "command:" not in read(command_group):
        fail("config/tools/sample.yaml must remain a command-backed Tool documentation example")
    if call_group.is_file() and "call:" not in read(call_group):
        fail("config/tools/orders-db.yaml must remain a call-backed Tool documentation example")

    test = ROOT / "att-cli/src/test/java/att/docs/DocumentationExamplesTest.java"
    if not test.is_file():
        fail("missing Java documentation example regression test: att-cli/src/test/java/att/docs/DocumentationExamplesTest.java")
    else:
        test_text = read(test)
        for rel in ("packages/default/testcase/payment2.xlsx", "packages/default/templates/PAYMENT_INVOKE/debug.yaml",
                    "examples/load/closed-smoke.yaml", "examples/load/arrival-smoke.yaml",
                    "config/environments/sit.yaml", "config/environments/uat.yaml",
                    "config/mqhelpers/sit/payment.yaml", "config/tools/orders-db.yaml"):
            if rel not in test_text:
                fail("DocumentationExamplesTest does not regression-protect documented asset: %s" % rel)
        for contract in ("docs/example-contracts.json", "SchemaFiles.resolveVersion",
                         "JsonSchemaVerifier.verify", "YamlSupport.parser"):
            if contract not in test_text:
                fail("DocumentationExamplesTest does not validate tagged examples through %s" % contract)


def check_ownership_links():
    readme = read(ROOT / "README.md")
    for canonical in ("docs/README.md", "docs/quick-start.md", "docs/reference.md",
                      "docs/system-design/", "docs/history/"):
        if canonical not in readme:
            fail("README missing canonical documentation entry point: %s" % canonical)
    if "docs/generated/" in readme:
        fail("README still points to removed docs/generated/ layout")
    if re.search(r"\[[^\]]*(?:Reference|System Design|Quick Start)[^\]]*\]\(docs/history/", readme, re.I):
        fail("README points a primary current-documentation label to docs/history/")

    if not LANDING.is_file():
        fail("missing canonical documentation landing page: docs/README.md")
        return
    landing = read(LANDING)
    for canonical in (
            "quick-start.md", "quick-start.zh.md", "reference.md", "reference.zh.md",
            "reference/test-authoring.md", "reference/execution-modes/debug.md",
            "reference/execution-modes/load.md", "reference/resources/dbhelper.md",
            "reference/resources/mqhelper.md", "reference/configuration.md",
            "reference/expressions.md", "reference/validation-diagnostics.md",
            "reference/ci-packaging-operations.md", "system-design/runtime-execution.md",
            "history/README.md"):
        if "(" + canonical + ")" not in landing and "(`" + canonical + "`)" not in landing:
            fail("docs/README.md missing canonical task link: %s" % canonical)
    for required in ("Start here", "Common tasks", "Current tree", "English", "繁體中文"):
        if required not in landing:
            fail("docs/README.md missing landing-page section/content: %s" % required)
    if "generated/" in landing:
        fail("docs/README.md still describes removed docs/generated/ layout")
    if re.search(r"\[[^\]]*(?:Reference|System Design|Quick Start)[^\]]*\]\(history/", landing, re.I):
        fail("docs/README.md points a primary current-documentation label to history/")

    if not HISTORY_INDEX.is_file():
        fail("missing history classification index: docs/history/README.md")
    for path in HISTORICAL_PATHS:
        if not path.is_file():
            fail("missing archived historical document: %s" % path.relative_to(ROOT))

    if (DOCS / "generated").exists():
        fail("docs/generated still exists; generated Reference outputs belong directly under docs/")

    forbidden_root_names = {
        "documentation-architecture.md",
        "reference-migration-map.md",
        "issue-39-db-action-retry.md",
    }
    for path in DOCS.iterdir():
        if not path.is_file():
            continue
        if re.match(r"^\d{2}_", path.name):
            fail("numbered/versioned document remains in current docs root: %s" %
                 path.relative_to(ROOT))
        if path.name in forbidden_root_names or re.match(r"^issue-\d+", path.name, re.I):
            fail("historical/migration-specific document remains in current docs root: %s" %
                 path.relative_to(ROOT))


def main():
    version = product_version()

    run_gate("generated Reference freshness",
             [sys.executable, "tools/build_reference_manual.py", "--check"])
    run_gate("current documentation contracts",
             [sys.executable, "tools/documentation_contracts.py"])
    run_gate("documentation contract regression tests",
             [sys.executable, "-m", "unittest", "discover", "-s", "tools",
              "-p", "test_documentation_contracts.py"])
    run_gate("documentation inventory freshness",
             [sys.executable, "tools/build_documentation_inventory.py", "--check"])
    run_gate("Reference semantic coverage",
             [sys.executable, "tools/validate_reference_content.py"])

    items = manifest_items()
    check_version_consistency(version)
    check_language_parity(items)
    check_schema_references()
    check_cli_documentation()
    check_vnext_cli_contract()
    check_server_validation_api_contract()
    check_secret_placeholders()
    check_critical_examples()
    check_documentation_status()
    check_documented_examples()
    check_ownership_links()

    markdown_files = [ROOT / "README.md", HISTORY_INDEX]
    markdown_files += [path for path in DOCS.rglob("*.md")
                       if "history" not in path.relative_to(DOCS).parts]
    markdown_files += list((ROOT / "examples").rglob("*.md"))
    for path in markdown_files:
        if path.is_file():
            check_markdown_links(path)

    if errors:
        print("Documentation release gate failed:", file=sys.stderr)
        for error in errors:
            print("  - " + error.replace("\n", "\n    "), file=sys.stderr)
        return 2

    print("Documentation release gate verified for ATT V%s" % version)
    return 0


if __name__ == "__main__":
    sys.exit(main())
