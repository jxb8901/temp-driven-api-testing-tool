#!/usr/bin/env sh
set -eu

input=""
output=""
request_file=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --input)
      input="$2"
      shift 2
      ;;
    --output)
      output="$2"
      shift 2
      ;;
    --request-file)
      request_file="$2"
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done

if [ -z "$output" ]; then
  echo "missing --output" >&2
  exit 2
fi

mkdir -p "$(dirname "$output")"
if [ -z "$request_file" ] && [ -n "$input" ]; then
  request_file="$(sed -n 's/.*requestFile: \([^,}]*\).*/\1/p' "$input" 2>/dev/null | head -1)"
fi
request_file="${request_file:-$input}"
cat > "$output" <<XML
<Response>
  <Status>SUCCESS</Status>
  <RejectCode>0000</RejectCode>
  <InputFile>${input}</InputFile>
  <RequestFile>${request_file}</RequestFile>
</Response>
XML
