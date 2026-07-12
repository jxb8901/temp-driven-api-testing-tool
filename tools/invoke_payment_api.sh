#!/usr/bin/env sh
set -eu

request_file="${1:-}"
cat <<XML
<Response>
  <Status>SUCCESS</Status>
  <RejectCode>0000</RejectCode>
  <RequestFile>${request_file}</RequestFile>
</Response>
XML
