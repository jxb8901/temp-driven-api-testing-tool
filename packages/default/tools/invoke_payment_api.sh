#!/usr/bin/env sh
set -eu

request_text="${1:-}"
request_chars=$(printf '%s' "$request_text" | wc -c | tr -d ' ')
environment="${2:-}"
xml_escape() {
  printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g' -e "s/'/\&apos;/g"
}
cat <<XML
<Response>
  <Status>SUCCESS</Status>
  <RejectCode>0000</RejectCode>
  <Environment>$(xml_escape "$environment")</Environment>
  <RequestChars>$(xml_escape "$request_chars")</RequestChars>
</Response>
XML
