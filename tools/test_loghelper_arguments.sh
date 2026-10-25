#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
helper="$repo_root/packages/default/tools/loghelper.sh"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/loghelper-arguments.XXXXXX")"
declare -a output_files=()

cleanup() {
    local output_file
    rm -rf "$tmp_dir"
    for output_file in "${output_files[@]}"; do
        rm -f "$output_file"
    done
}
trap cleanup EXIT

log_file="$tmp_dir/application.log"
mkdir -p "$tmp_dir/custom-output"
cat > "$log_file" <<'EOF'
[INFO] [2026/10/25 12:00:00.000] [Sample.first] [TID123456] alpha token
[INFO] [2026/10/25 12:00:01.000] [Sample.second] [TID123456] beta token
EOF

assert_one_output() {
    local label=$1
    local expected_prefix=$2
    local result=$3
    local output_file

    if ! printf '%s\n' "$result" | grep -Fxq 'success: true'; then
        printf '%s\n' "FAIL: $label did not return success." "$result" >&2
        return 1
    fi
    if ! printf '%s\n' "$result" | grep -Fxq 'count: 1'; then
        printf '%s\n' "FAIL: $label did not return exactly one result." "$result" >&2
        return 1
    fi

    output_file="$(printf '%s\n' "$result" | sed -n "s/^    path: '\(.*\)'$/\1/p" | sed -n '1p')"
    if [ -z "$output_file" ] || [ ! -f "$output_file" ]; then
        printf '%s\n' "FAIL: $label did not create its reported output file." "$result" >&2
        return 1
    fi
    output_files+=("$output_file")

    case "$output_file" in
        "$expected_prefix"-*) ;;
        *)
            printf '%s\n' "FAIL: $label wrote '$output_file', expected prefix '$expected_prefix'." >&2
            return 1
            ;;
    esac

    grep -Fq 'alpha token' "$output_file"
    grep -Fq 'beta token' "$output_file"
}

positional_result="$(LOGHELPER_HOST="P$$" "$helper" "$log_file" 'alpha,beta')"
assert_one_output "positional defaults" "/tmp/application.log" "$positional_result"
case "$(printf '%s\n' "$positional_result" | sed -n "s/^    path: '\(.*\)'$/\1/p" | sed -n '1p')" in
    /tmp/*|/private/tmp/*) ;;
    *)
        printf '%s\n' 'FAIL: positional output did not default to /tmp.' "$positional_result" >&2
        exit 1
        ;;
esac

custom_dir_result="$(LOGHELPER_HOST="D$$" "$helper" "$log_file" 'alpha,beta' --output-dir "$tmp_dir/custom-output")"
assert_one_output "positional custom output directory" "$tmp_dir/custom-output/application.log" "$custom_dir_result"

short_result="$(LOGHELPER_HOST="S$$" "$helper" -o "$tmp_dir/short" -l "$log_file" -k 'alpha,beta')"
assert_one_output "short options" "$tmp_dir/short" "$short_result"

plural_result="$(LOGHELPER_HOST="L$$" "$helper" --output-prefix "$tmp_dir/plural" --log-file "$log_file" --keywords 'alpha,beta')"
assert_one_output "plural keyword option" "$tmp_dir/plural" "$plural_result"

legacy_result="$(LOGHELPER_HOST="G$$" "$helper" --output-prefix "$tmp_dir/legacy" --log-file "$log_file" --keyword alpha beta)"
assert_one_output "legacy long options" "$tmp_dir/legacy" "$legacy_result"

if "$helper" "$log_file" 'alpha,,beta' >"$tmp_dir/invalid.out" 2>"$tmp_dir/invalid.err"; then
    printf '%s\n' 'FAIL: an empty comma-separated keyword was accepted.' >&2
    exit 1
fi

printf '%s\n' 'loghelper argument-form tests passed.'
