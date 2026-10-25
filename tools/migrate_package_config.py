#!/usr/bin/env python3
"""Inventory registered Packages and propose att-package/v1 manifests; dry-run by default."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import yaml
import zipfile


class MigrationError(ValueError):
    pass


def read_yaml(path):
    with path.open(encoding="utf-8") as stream:
        value = yaml.safe_load(stream)
    if not isinstance(value, dict):
        raise MigrationError("Expected a YAML mapping")
    return value


def package_path(root, name):
    if not isinstance(name, str) or "\\" in name or ":" in name:
        raise MigrationError("outside-package")
    relative = Path(name)
    if relative.is_absolute() or any(part in ("", ".", "..") for part in name.split("/")):
        raise MigrationError("outside-package")
    candidate = root / relative
    if not candidate.resolve().is_relative_to(root.resolve()):
        raise MigrationError("outside-package")
    return candidate


def owned_imports(root, imports, references, diagnostics, layer):
    result = []
    if not isinstance(imports, list):
        raise MigrationError("Legacy Testdata inventory must be a list")
    for name in imports:
        try:
            path = package_path(root, name)
            if not path.is_file() or path.is_symlink():
                diagnostics.append({"path": name, "layer": layer, "reason": "missing-or-unreadable"})
                continue
            descriptor = read_yaml(path)
            logical_id = descriptor.get("id")
            if not isinstance(logical_id, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]*", logical_id):
                raise MigrationError("invalid-schema")
            if logical_id in references:
                result.append(name)
            else:
                diagnostics.append({"path": name, "layer": layer, "reason": "unused-not-copied"})
        except (OSError, ValueError, yaml.YAMLError):
            diagnostics.append({"path": name if isinstance(name, str) and not Path(name).is_absolute() else "[unsafe-path]",
                                "layer": layer, "reason": "invalid-or-outside-package"})
    return result


def inventory(runtime, registry):
    """No filesystem changes and no credential/record values in the returned plan."""
    config = read_yaml(runtime)
    server = read_yaml(registry)
    packages = server.get("packages", {})
    entries, allowed = packages.get("entries", {}), packages.get("allowedRoots", [])
    if not isinstance(entries, dict) or not isinstance(allowed, list) or not allowed:
        raise MigrationError("An explicit trusted Package registry and allowedRoots are required")
    allowed_roots = [Path(path).resolve(strict=True) for path in allowed]
    profiles = config.get("environments", {})
    if not isinstance(profiles, dict):
        raise MigrationError("Global environment profiles must be a mapping")
    inherited = {key: config[key] for key in ("timeoutMs", "caseLog", "xml") if key in config}
    for section, keys in (("execution", ("runIdFormat", "debugIdFormat")), ("report", ("fileNamePattern", "columns", "html", "junit"))):
        value = config.get(section, {})
        if isinstance(value, dict): inherited[section] = {key: value[key] for key in keys if key in value}
    plan = {"schemaVersion": "att-package-migration/v1", "dryRun": True,
            "inheritedDefaults": inherited, "outputPolicy": "managed-runtime-output",
            "globalConfigDigest": hashlib.sha256(runtime.read_bytes()).hexdigest(),
            "globalDefaultEnvironment": config.get("environment"), "packages": []}
    for package_id, configured_root in entries.items():
        if not isinstance(package_id, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", package_id):
            raise MigrationError("Invalid registered Package ID")
        root = Path(configured_root).resolve(strict=True)
        if not root.is_dir() or not any(root.is_relative_to(allowed_root) for allowed_root in allowed_roots):
            raise MigrationError("Registered Package is outside allowedRoots")
        row = {"packageId": package_id, "diagnostics": []}
        if (root / "package.yaml").exists() or (root / "package.yaml").is_symlink():
            row["status"] = "manifest-exists"; plan["packages"].append(row); continue
        references = set()
        # Search only Package-owned business text, never central config or other Packages.
        folders = ["load"]
        for kind in ("templates", "testcase"):
            declared = config.get(kind, {})
            if not isinstance(declared, dict): raise MigrationError("Legacy resource roots must be mappings")
            folders.append(declared.get("root", kind))
        for folder in folders:
            try: directory = package_path(root, folder)
            except (OSError, ValueError):
                row["diagnostics"].append({"field": "resources", "reason": "outside-package"}); continue
            if not directory.is_dir() or directory.is_symlink():
                continue
            for path in directory.rglob("*"):
                if not path.is_file() or path.is_symlink() or path.suffix.lower() not in (".yaml", ".yml", ".xml", ".xlsx"):
                    continue
                if not path.resolve().is_relative_to(root) or path.stat().st_size > 3_000_000:
                    row["diagnostics"].append({"path": path.relative_to(root).as_posix(), "reason": "unsafe-or-size-limit"}); continue
                if path.suffix.lower() == ".xlsx":
                    # Inspect only bounded workbook text; never extract archives to disk.
                    try:
                        with zipfile.ZipFile(path) as workbook:
                            entries = workbook.infolist()
                            if len(entries) > 10000 or sum(item.file_size for item in entries) > 32_000_000:
                                raise MigrationError("workbook-size-limit")
                            text = "\n".join(workbook.read(item).decode("utf-8", errors="replace") for item in entries
                                             if item.filename == "xl/sharedStrings.xml" or item.filename.startswith("xl/worksheets/") and item.filename.endswith(".xml"))
                    except (OSError, ValueError, zipfile.BadZipFile):
                        row["diagnostics"].append({"path": path.relative_to(root).as_posix(), "reason": "unreadable-workbook"}); continue
                else:
                    text = path.read_text(encoding="utf-8", errors="replace")
                references.update(re.findall(r"@\{([A-Za-z][A-Za-z0-9_-]*)(?:[.}\[])", text))
        manifest = {"schemaVersion": "att-package/v1"}
        if config.get("environment"):
            manifest["defaultEnvironment"] = config["environment"]
        descriptors = owned_imports(root, config.get("testdata", []), references, row["diagnostics"], "global")
        testdata = {"descriptors": descriptors}
        variations = {}
        for name, profile in profiles.items():
            if name.startswith("x-") or not isinstance(profile, dict) or "testdata" not in profile:
                continue
            variations[name] = owned_imports(root, profile["testdata"], references, row["diagnostics"], "global/" + name)
        if variations:
            testdata["environments"] = variations
        manifest["testdata"] = testdata
        resources = {}
        for old, new in (("templates", "templatesRoot"), ("testcase", "testcaseRoot")):
            name = config.get(old, {}).get("root", old)
            try:
                if name != old and package_path(root, name).is_dir():
                    resources[new] = name
            except (OSError, ValueError):
                row["diagnostics"].append({"field": old + ".root", "reason": "outside-package"})
        if resources:
            manifest["resources"] = resources
        # Presentation defaults remain globally inherited unless an author intentionally changes them.
        row.update(status="proposed", manifest=manifest)
        plan["packages"].append(row)
    return plan


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True, help="Trusted central Runtime Config")
    parser.add_argument("--server-config", type=Path, required=True, help="Trusted explicit Package registry")
    args = parser.parse_args()
    try:
        print(json.dumps(inventory(args.config, args.server_config), indent=2, ensure_ascii=False))
    except (OSError, ValueError, yaml.YAMLError):
        parser.exit(2, "Migration inventory failed; check the trusted registry, allowed roots and YAML configuration. No files changed.\n")


if __name__ == "__main__":
    main()
