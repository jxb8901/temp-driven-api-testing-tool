#!/usr/bin/env sh
# Converts SQLPlus output produced with SET COLSEP '|' into a deterministic XML row set.
set -eu

if [ "$#" -ne 1 ]; then
  echo "usage: fpp_sqlplus_to_xml.sh <sqlplus-output-file>" >&2
  exit 2
fi

input_file=$1
if [ ! -f "$input_file" ]; then
  echo "SQLPlus output file does not exist: $input_file" >&2
  exit 2
fi

awk -F '|' '
function trim(value) {
  sub(/^[[:space:]]+/, "", value)
  sub(/[[:space:]]+$/, "", value)
  return value
}
function xml(value) {
  gsub(/&/, "\\&amp;", value)
  gsub(/</, "\\&lt;", value)
  gsub(/>/, "\\&gt;", value)
  gsub(/\"/, "\\&quot;", value)
  gsub(/\047/, "\\&apos;", value)
  return value
}
function separator(value, stripped) {
  stripped = value
  gsub(/[[:space:]|+_-]/, "", stripped)
  return stripped == ""
}
function element_name(value, lower) {
  lower = tolower(value)
  return value ~ /^[A-Za-z_][A-Za-z0-9_.-]*$/ && lower !~ /^xml/
}
function error_marker(value) {
  return value ~ /^ERROR([[:space:]]+at line[[:space:]]+[0-9]+)?:[[:space:]]*$/
}
function oracle_error(value) {
  return value ~ /^(ORA|SP2|TNS|PLS|LRM|NLS|RMAN|EXP|IMP|UDE|UDI|KUP)-[0-9][0-9]*:/
}
function sqlplus_error(value, lower) {
  lower = tolower(value)
  return lower ~ /^error [0-9]+ initializing sql\*plus/ ||
         lower ~ /^sql\*plus internal error/ ||
         lower ~ /^error accessing package/
}
function add_error(value, code, colon) {
  code = "SQLPLUS"
  colon = index(value, ":")
  if (oracle_error(value) && colon > 1) code = substr(value, 1, colon - 1)
  error_count++
  error_codes[error_count] = code
  error_messages[error_count] = value
}
BEGIN {
  print "<SqlPlusResult>"
  print "  <Rows>"
}
{
  line = $0
  sub(/\r$/, "", line)
  normalized = trim(line)
  if (error_marker(normalized)) {
    pending_error = normalized
    next
  }
  if (oracle_error(normalized) || sqlplus_error(normalized)) {
    add_error(normalized)
    pending_error = ""
    next
  }
  if (pending_error != "" && normalized != "") {
    add_error(pending_error " " normalized)
    pending_error = ""
    next
  }
  if (normalized == "" || normalized ~ /^[0-9]+ rows? selected\.$/ || separator(normalized)) next
  if (!header) {
    header = 1
    columns = NF
    for (i = 1; i <= columns; i++) names[i] = trim($i)
    next
  }
  row++
  printf "    <Row index=\"%d\">\n", row
  for (i = 1; i <= columns; i++) {
    if (element_name(names[i])) {
      printf "      <%s>%s</%s>\n", names[i], xml(trim($i)), names[i]
    } else {
      printf "      <Column name=\"%s\">%s</Column>\n", xml(names[i]), xml(trim($i))
    }
  }
  print "    </Row>"
}
END {
  if (pending_error != "") add_error(pending_error)
  print "  </Rows>"
  printf "  <RowCount>%d</RowCount>\n", row
  if (error_count > 0) {
    print "  <Errors>"
    for (i = 1; i <= error_count; i++) {
      printf "    <Error index=\"%d\">\n", i
      printf "      <Code>%s</Code>\n", xml(error_codes[i])
      printf "      <Message>%s</Message>\n", xml(error_messages[i])
      print "    </Error>"
    }
    print "  </Errors>"
    printf "  <ErrorCount>%d</ErrorCount>\n", error_count
  }
  print "</SqlPlusResult>"
}
' "$input_file"
