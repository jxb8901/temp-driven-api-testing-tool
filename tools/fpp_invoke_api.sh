#!/usr/bin/env sh
# FPP reference skeleton: replace the marked integration block with the approved API client.
set -eu

if [ "$#" -ne 4 ]; then
  echo "usage: fpp_invoke_api.sh <request-id> <request-type> <request-file> <api-log-path>" >&2
  exit 2
fi

request_id=$1
request_type=$2
request_file=$3
api_log_path=$4

xml_escape() {
  printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g' -e "s/'/\&apos;/g"
}

result_code=NOT_IMPLEMENTED
result_message="Reference skeleton: replace the TODO block with the approved FPP API client"
if [ ! -f "$request_file" ]; then
  result_code=INPUT_FILE_NOT_FOUND
  result_message="Request file does not exist"
fi

mkdir -p "$(dirname "$api_log_path")"
printf '%s requestId=%s requestType=%s requestFile=%s resultCode=%s\n' \
  "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$request_id" "$request_type" "$request_file" "$result_code" >> "$api_log_path"

# TODO: invoke the approved API client here, then set result_code/result_message from its response.

cat <<XML
<FppApiResult>
  <RequestId>$(xml_escape "$request_id")</RequestId>
  <RequestType>$(xml_escape "$request_type")</RequestType>
  <RequestFile>$(xml_escape "$request_file")</RequestFile>
  <ApiLogPath>$(xml_escape "$api_log_path")</ApiLogPath>
  <ResultCode>$(xml_escape "$result_code")</ResultCode>
  <ResultMessage>$(xml_escape "$result_message")</ResultMessage>
</FppApiResult>
XML
