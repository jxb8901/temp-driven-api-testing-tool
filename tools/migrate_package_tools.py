#!/usr/bin/env python3
"""Convert legacy Global Tools into Package-local att-tool/v1 files and Group folders.
Dry-run by default; preserves qualified group Tool calls and dispatcher semantics.
Requires PyYAML. No global runtime fallback is produced.
"""
import argparse
import os
import pathlib
import re
import shlex
import stat
import sys
import tempfile

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

def validate_output_path(root, destination):
    """Reject every symlink component and prove the planned output stays in its Package."""
    root = pathlib.Path(root).resolve(strict=True)
    destination = pathlib.Path(os.path.abspath(destination))
    try:
        relative = destination.relative_to(root)
    except ValueError:
        raise ValueError("Package Tool destination escapes selected Package: " + str(destination))
    current = root
    for part in relative.parts:
        if part in ("", ".", ".."):
            raise ValueError("Invalid Package Tool destination component: " + part)
        current = current / part
        if current.is_symlink():
            raise ValueError("Refusing symlink in Package Tool destination path: " + str(current))
        if current != destination and current.exists() and not current.is_dir():
            raise ValueError("Package Tool destination parent is not a directory: " + str(current))
    resolved = destination.resolve(strict=False)
    if not resolved.is_relative_to(root):
        raise ValueError("Package Tool destination escapes selected Package: " + str(destination))

def ensure_output_parents(root, destination, created_directories):
    """Create missing output directories one component at a time, rejecting symlinks."""
    root = pathlib.Path(root).resolve(strict=True)
    relative_parent = destination.parent.relative_to(root)
    current = root
    for part in relative_parent.parts:
        current = current / part
        if current.is_symlink():
            raise ValueError("Refusing symlink in Package Tool destination path: " + str(current))
        if not current.exists():
            current.mkdir()
            created_directories.append(current)
        elif not current.is_dir():
            raise ValueError("Package Tool destination parent is not a directory: " + str(current))
        validate_output_path(root, destination)

def stage_text(destination, content, mode=None):
    """Write complete content to a private temporary sibling before publishing it."""
    descriptor, name = tempfile.mkstemp(prefix=".att-tool-migration-", suffix=".tmp",
                                        dir=str(destination.parent))
    staged = pathlib.Path(name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        if mode is not None:
            os.chmod(staged, mode)
        return staged
    except BaseException:
        try:
            staged.unlink()
        except FileNotFoundError:
            pass
        raise

def commit_staged_file(root, staged, destination, committed):
    """Create a new destination exclusively, then copy a fully staged file into it."""
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(str(destination), flags, 0o666)
    identity = os.fstat(descriptor)
    committed.append((root, destination, identity.st_dev, identity.st_ino))
    with os.fdopen(descriptor, "wb") as output, staged.open("rb") as source:
        while True:
            chunk = source.read(65536)
            if not chunk:
                break
            output.write(chunk)
        output.flush()
        os.fsync(output.fileno())

def write_migration(paths, config, converted_config, destination_roots):
    """Stage all outputs, publish Package descriptors, then atomically replace Config.

    On any pre-Config failure, remove only destination files created by this invocation;
    the legacy Config remains untouched so the migration can be safely retried.
    """
    staged_outputs = []
    committed = []
    created_directories = []
    staged_config = None
    try:
        for destination, content in paths:
            root = destination_roots[destination]
            validate_output_path(root, destination)
            ensure_output_parents(root, destination, created_directories)
            validate_output_path(root, destination)
            staged = stage_text(destination, content)
            staged_outputs.append((root, destination, staged))

        config_mode = stat.S_IMODE(config.stat().st_mode)
        staged_config = stage_text(config, converted_config, config_mode)

        for root, destination, staged in staged_outputs:
            validate_output_path(root, destination)
            commit_staged_file(root, staged, destination, committed)

        os.replace(str(staged_config), str(config))
        staged_config = None
    except BaseException as error:
        rollback_errors = []
        for root, destination, device, inode in reversed(committed):
            try:
                validate_output_path(root, destination)
                current = destination.lstat()
                if current.st_dev != device or current.st_ino != inode or stat.S_ISLNK(current.st_mode):
                    rollback_errors.append("created destination changed before rollback: " + str(destination))
                else:
                    validate_output_path(root, destination)
                    destination.unlink()
            except FileNotFoundError:
                pass
            except OSError as rollback_error:
                rollback_errors.append(str(rollback_error))
        if rollback_errors:
            raise OSError("Migration failed and rollback was incomplete: "
                          + "; ".join(rollback_errors)) from error
        raise
    finally:
        for _, _, staged in staged_outputs:
            try:
                staged.unlink()
            except FileNotFoundError:
                pass
        if staged_config is not None:
            try:
                staged_config.unlink()
            except FileNotFoundError:
                pass
        for directory in reversed(created_directories):
            try:
                directory.rmdir()
            except OSError:
                pass

def remove_top_level(source, names):
    """Remove recognized top-level YAML mappings while preserving unrelated source.

    Use parsed YAML node marks, not a regex that misses quoted keys. Flow-style
    top-level documents/ambiguous boundaries fail *before* Package files are written.
    """
    document = yaml.compose(source)
    if not isinstance(document, yaml.MappingNode):
        raise ValueError("Expected a top-level YAML mapping for migration")
    lines = source.splitlines(keepends=True)
    marked = []
    key_lines = set()
    for key, value in document.value:
        if not isinstance(key, yaml.ScalarNode):
            raise ValueError("Unsupported complex YAML key in global configuration")
        if key.value not in names:
            key_lines.add(key.start_mark.line)
            continue
        if key.start_mark.column != 0:
            raise ValueError("Unsupported inline/flow-style top-level legacy Tool key: "
                             + key.value + "; use block mapping YAML for migration")
        start = key.start_mark.line
        end = max(start + 1, value.end_mark.line
                  + (1 if value.end_mark.column > 0 else 0))
        if end > len(lines) or any(first <= start < last for first, last in marked):
            raise ValueError("Ambiguous legacy Tool mapping boundaries: " + key.value)
        marked.append((start, end))
    for start, end in marked:
        if any(start <= line < end for line in key_lines):
            raise ValueError("Legacy Tool mapping overlaps another top-level key")
    kept = "".join(line for index, line in enumerate(lines)
                   if not any(start <= index < end for start, end in marked))
    expected = yaml.safe_load(source)
    for name in names:
        expected.pop(name, None)
    actual = yaml.safe_load(kept) or {}
    if actual != expected:
        raise ValueError("Legacy Tool removal would change unrelated YAML content; "
                         "no Package descriptors were written")
    return kept

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
    # Package-only Tools cannot silently inherit Environment-specific legacy entries.
    # This migration path must reject these before creating any files or deleting Config.
    profiles = raw.get("environments") or {}
    if not isinstance(profiles, dict):
        raise ValueError("Global environments must be a mapping")
    for profile_name, profile in profiles.items():
        if str(profile_name).startswith("x-"):
            continue
        if isinstance(profile, dict) and ("tools" in profile or "toolGroups" in profile):
            fields = ", ".join(k for k in ("tools", "toolGroups") if k in profile)
            raise ValueError("Environment-scoped legacy Tool declarations at environments."
                             + str(profile_name) + ": " + fields
                             + "; migrate each Environment's Tools into explicit Packages "
                             + "and remove these profile bindings manually before --write")
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
    destination_roots = {}
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
            destination_roots[destination] = root
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
            destination_roots[folder / "tool-group.yaml"] = root
            for name, definition in sorted(members.items()):
                converted = dict(schemaVersion="att-tool/v1", id=name, **definition)
                paths.append((folder / (name + ".yaml"),
                              yaml.safe_dump(converted, sort_keys=False, allow_unicode=True)))
                destination_roots[folder / (name + ".yaml")] = root
        # Preflight ALL destinations before creating any file in any Package.
    # Verify the legacy Config can be safely rewritten before writing any
    # Package descriptor, so unsupported YAML cannot cause a partial cutover.
    converted_config = remove_top_level(source, {"tools", "toolGroups"})
    target_paths = [destination for destination, _ in paths]
    if len(set(target_paths)) != len(target_paths):
        raise ValueError("Duplicate migrated Tool or Group destination")
    for destination, content in paths:
        root = destination_roots[destination]
        validate_output_path(root, destination)
        if destination.exists():
            raise ValueError("Refusing to overwrite existing descriptor: " + str(destination))
        converted = yaml.safe_load(content)
        if "command" in converted:
            executable = argv(converted["command"])[0]
            if executable.startswith("./") and not (destination_roots[destination] / executable).exists():
                print("WARNING: review Package-local script path: " + executable
                      + " in " + str(destination))
        if "script" in converted:
            executable = argv(converted["script"])[0]
            if executable.startswith("./") and not (destination_roots[destination] / executable).exists():
                print("WARNING: review Package-local Group dispatcher path: " + executable
                      + " in " + str(destination))
            else:
                print("INFO: migrated Group dispatcher " + executable + " for " + str(destination)
                      + "; review Package-relative script path")
        if "call" in converted:
            helpers = re.findall(r"#\{\s*((?:db|mq|http|ssh)\.[A-Za-z_][A-Za-z0-9_-]*)\.",
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
    write_migration(paths, config, converted_config, destination_roots)
    print("Wrote standalone Tool descriptors and Group manifests/member Tools; revalidate all Packages.")

if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, yaml.YAMLError) as error:
        sys.exit("Migration failed: " + str(error))
