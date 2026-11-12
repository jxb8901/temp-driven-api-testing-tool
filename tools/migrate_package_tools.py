#!/usr/bin/env python3
"""Convert legacy Global Tools into Package-local att-tool/v1 files and Group folders.
Dry-run by default; preserves qualified group Tool calls and dispatcher semantics.
Requires PyYAML. No global runtime fallback is produced.
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
    has_legacy_fields = "tools" in raw or "toolGroups" in raw
    global_tools = raw.get("tools") or {}
    if not isinstance(global_tools, dict):
        raise ValueError("Global tools must be a mapping")
    group_paths = raw.get("toolGroups") or []
    if not isinstance(group_paths, list):
        raise ValueError("Global toolGroups must be a list")
    standalone = {}
    groups = {}
    for tool_id, tool in global_tools.items():
        if isinstance(tool_id, str) and tool_id.startswith("x-"):
            continue
        if not isinstance(tool_id, str) or not isinstance(tool, dict):
            raise ValueError("Invalid global Tool: " + str(tool_id))
        if tool_id in standalone:
            raise ValueError("Duplicate Tool ID: " + tool_id)
        standalone[tool_id] = dict(tool)

    for group_path in group_paths:
        if not isinstance(group_path, str):
            raise ValueError("Tool Group path must be text")
        group = yaml.safe_load(safe(runtime, group_path).read_text(encoding="utf-8"))
        if not isinstance(group, dict):
            raise ValueError("Invalid Tool Group descriptor: " + group_path)
        group_id = group["id"]
        if not isinstance(group_id, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_-]*", group_id):
            raise ValueError("Invalid Tool Group ID: " + str(group_id))
        if group_id in groups:
            raise ValueError("Duplicate Tool Group ID: " + group_id)
        ssh = group.get("ssh")
        if ssh and (not isinstance(ssh, dict) or not ssh.get("helper")
                    or "host" in ssh or "identityFile" in ssh):
            raise ValueError("Unsupported legacy Tool Group SSH binding in " + group_path
                             + "; migrate direct SSH topology to global SSHHelper first")
        source_tools = group.get("tools")
        if not isinstance(source_tools, dict):
            raise ValueError("Tool Group tools must be a mapping: " + group_path)
        members = {}
        for local_id, tool in source_tools.items():
            if isinstance(local_id, str) and local_id.startswith("x-"):
                continue
            if not isinstance(local_id, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_-]*", local_id):
                raise ValueError("Invalid grouped Tool ID: " + str(local_id))
            if not isinstance(tool, dict):
                raise ValueError("Invalid Tool in Tool Group: " + group_path)
            if group.get("script") and "call" in tool:
                raise ValueError("Group script dispatcher cannot wrap call Tool: " + group_id + "." + local_id)
            members[local_id] = dict(tool)
        if not members:
            raise ValueError("Tool Group has no enabled members: " + group_id)
        groups[group_id] = (group, members)

    if not standalone and not groups and not has_legacy_fields:
        print("No legacy Tool fields were found.")
        return
    if not standalone and not groups:
        print("Legacy tools/toolGroups are empty or disabled; obsolete Config keys must still be removed.")

    paths = []
    for package in args.package:
        root = package.resolve()
        for tool_id, definition in sorted(standalone.items()):
            if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.-]*", tool_id):
                raise ValueError("Invalid Tool ID: " + tool_id)
            if tool_id.startswith(("db.", "mq.", "http.", "ssh.")):
                raise ValueError("Tool ID conflicts with global Helper namespace: " + tool_id)
            destination = root / "tools" / (tool_id + ".yaml")
            converted = dict(schemaVersion="att-tool/v1", id=tool_id, **definition)
            paths.append((destination, yaml.safe_dump(converted, sort_keys=False, allow_unicode=True)))
        for group_id, (group, members) in sorted(groups.items()):
            folder = root / "tools" / group_id
            manifest = {
                "schemaVersion": "att-tool-group/v3.0",
                "id": group_id,
                "name": group.get("name", group_id),
                "description": group.get("description", "Migrated Tool Group " + group_id),
                "tools": [name + ".yaml" for name in sorted(members)],
            }
            if group.get("script"):
                manifest["script"] = group["script"]
            if group.get("ssh"):
                manifest["ssh"] = group["ssh"]
            paths.append((folder / "tool-group.yaml",
                          yaml.safe_dump(manifest, sort_keys=False, allow_unicode=True)))
            for name, definition in sorted(members.items()):
                converted = dict(schemaVersion="att-tool/v1", id=name, **definition)
                paths.append((folder / (name + ".yaml"),
                              yaml.safe_dump(converted, sort_keys=False, allow_unicode=True)))
        # Preflight ALL destinations before creating any file in any Package.
    target_paths = [destination for destination, _ in paths]
    if len(set(target_paths)) != len(target_paths):
        raise ValueError("Duplicate migrated Tool or Group destination")
    for destination, content in paths:
        if destination.exists():
            raise ValueError("Refusing to overwrite existing descriptor: " + str(destination))
        converted = yaml.safe_load(content)
        if "command" in converted:
            executable = argv(converted["command"])[0]
            if executable.startswith("./") and not (destination.parents[1] / executable).exists():
                print("WARNING: review Package-local script path: " + executable
                      + " in " + str(destination))
        if "script" in converted:
            executable = argv(converted["script"])[0]
            print("INFO: migrated Group dispatcher " + executable + " for " + str(destination)
                  + "; review Package-relative script path")
        if "call" in converted:
            helpers = re.findall(r"#\\{\\s*((?:db|mq|http|ssh)\\.[A-Za-z_][A-Za-z0-9_-]*)\\.",
                                 converted["call"])
            if helpers:
                print("INFO: " + str(converted["id"]) + " depends on global Helper(s): "
                      + ", ".join(sorted(set(helpers))))
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
    print("Wrote standalone Tool descriptors and Group manifests/member Tools; revalidate all Packages.")

if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, yaml.YAMLError) as error:
        sys.exit("Migration failed: " + str(error))
