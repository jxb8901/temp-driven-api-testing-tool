#!/usr/bin/env sh
# FPP reference skeleton: replace the marked integration block with the approved API client.
set -eu

if [ "$#" -ne 5 ]; then
  echo "usage: fpp_invoke_api.sh <request-id> <request-type> <request-text> <request-file> <api-log-path>" >&2
  exit 2
fi

request_id=$1
request_type=$2
request_text=$3
request_file=$4
api_log_path=$5

xml_escape() {
  printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g' -e "s/'/\&apos;/g"
}

result_code=NOT_IMPLEMENTED
result_message="Reference skeleton: replace the TODO block with the approved FPP API client"
if [ -z "$request_text" ] && [ ! -f "$request_file" ]; then
  result_code=INPUT_FILE_NOT_FOUND
  result_message="Provide requestText or an existing requestFile"
fi

mkdir -p "$(dirname "$api_log_path")"
request_chars=$(printf '%s' "$request_text" | wc -c | tr -d ' ')
printf '%s requestId=%s requestType=%s requestChars=%s requestFile=%s resultCode=%s\n' \
  "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$request_id" "$request_type" "$request_chars" "$request_file" "$result_code" >> "$api_log_path"

# TODO: invoke the approved API client here, then set result_code/result_message from its response.

cat <<XML
<FppApiResult>
  <RequestId>$(xml_escape "$request_id")</RequestId>
  <RequestType>$(xml_escape "$request_type")</RequestType>
  <RequestFile>$(xml_escape "$request_file")</RequestFile>
  <RequestChars>$(xml_escape "$request_chars")</RequestChars>
  <ApiLogPath>$(xml_escape "$api_log_path")</ApiLogPath>
  <ResultCode>$(xml_escape "$result_code")</ResultCode>
  <ResultMessage>$(xml_escape "$result_message")</ResultMessage>
</FppApiResult>
XML
