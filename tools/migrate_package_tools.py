#!/usr/bin/env python3
"""Convert legacy global ATT Tools to one att-tool/v1 file per selected Package.
Dry-run by default; requires PyYAML. No global runtime fallback is produced.
"""
import argparse
import pathlib
import re
import shlex
import sys

try:
    import yaml
except ImportError:
    sys.exit("PyYAML required: python3 -m pip install pyyaml")

def argv(value):
    if isinstance(value, list):
        return value
    if isinstance(value, str):
        return shlex.split(value)
    raise ValueError("Expected a Tool command/script string or argv list")

def safe(root, name):
    target = (root / name).resolve()
    if not target.is_relative_to(root.resolve()):
        raise ValueError("Descriptor path escapes runtime root: " + str(name))
    return target

def remove_top_level(source, names):
    lines = source.splitlines(keepends=True)
    output = []
    skip = False
    for line in lines:
        if re.match(r"^[A-Za-z_][A-Za-z0-9_-]*:", line):
            key = line.split(":", 1)[0]
            skip = key in names
        if not skip:
            output.append(line)
    return "".join(output)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default="config/config.yaml", type=pathlib.Path)
    parser.add_argument("--package", action="append", required=True, type=pathlib.Path)
    parser.add_argument("--write", action="store_true", help="Explicitly perform the reviewed migration")
    args = parser.parse_args()
    config = args.config.resolve()
    runtime = config.parent.parent if config.parent.name == "config" else config.parent
    source = config.read_text(encoding="utf-8")
    raw = yaml.safe_load(source)
    if not isinstance(raw, dict):
        raise ValueError("Expected a global YAML mapping")
    definitions = {}
    for tool_id, tool in (raw.get("tools") or {}).items():
        if not isinstance(tool, dict):
            raise ValueError("Invalid global Tool: " + tool_id)
        definitions[tool_id] = dict(tool)
    for group_path in raw.get("toolGroups") or []:
        path = safe(runtime, group_path)
        group = yaml.safe_load(path.read_text(encoding="utf-8"))
        if group.get("ssh"):
            raise ValueError("Unsupported legacy Tool Group SSH binding in " + str(group_path))
        prefix = group["id"] + "."
        script = argv(group["script"]) if "script" in group else []
        for local_id, tool in group["tools"].items():
            identifier = prefix + local_id
            if identifier in definitions:
                raise ValueError("Duplicate migrated Tool ID: " + identifier)
            definition = dict(tool)
            if script:
                if "command" not in definition:
                    raise ValueError("Group script with non-command Tool: " + identifier)
                definition["command"] = script + argv(definition["command"])
            definitions[identifier] = definition
    if not definitions:
        print("No legacy Tool definitions were found.")
        return
    paths = []
    for package in args.package:
        root = package.resolve()
        for identifier, definition in sorted(definitions.items()):
            if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.-]*", identifier):
                raise ValueError("Invalid Tool ID: " + identifier)
            if identifier.startswith(("db.", "mq.", "http.", "ssh.")):
                raise ValueError("Tool ID conflicts with global Helper namespace: " + identifier)
            destination = root / "tools" / (identifier + ".yaml")
            if destination.exists():
                raise ValueError("Refusing to overwrite existing descriptor: " + str(destination))
            converted = dict(schemaVersion="att-tool/v1", id=identifier, **definition)
            if "command" in converted:
                executable = argv(converted["command"])[0]
                if executable.startswith("./") and not (root / executable).exists():
                    print("WARNING: missing Package script: " + str(root / executable))
            if "call" in converted:
                helpers = re.findall(r"#\{\s*((?:db|mq|http|ssh)\.[A-Za-z_][A-Za-z0-9_-]*)\.", converted["call"])
                if helpers:
                    print("INFO: " + identifier + " depends on global Helper(s): " + ", ".join(sorted(set(helpers))))
            paths.append((destination, yaml.safe_dump(converted, sort_keys=False, allow_unicode=True)))
    print(("WRITE" if args.write else "DRY RUN") + ": " + str(len(paths)) + " Package-local descriptors")
    for destination, _ in paths:
        print("  " + str(destination))
    print("Config after conversion: " + str(config) + " (remove top-level tools/toolGroups)")
    if not args.write:
        print("Review Tool names, script paths and package membership; rerun with --write.")
        return
    for destination, content in paths:
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(content, encoding="utf-8")
    config.write_text(remove_top_level(source, {"tools", "toolGroups"}), encoding="utf-8")
    print("Wrote Package Tool descriptors and converted the global Config. Revalidate all deployments.")

if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, yaml.YAMLError) as error:
        sys.exit("Migration failed: " + str(error))
