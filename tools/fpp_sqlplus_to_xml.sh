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
BEGIN {
  print "<SqlPlusResult>"
  print "  <Rows>"
}
{
  line = $0
  sub(/\r$/, "", line)
  normalized = trim(line)
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
  print "  </Rows>"
  printf "  <RowCount>%d</RowCount>\n", row
  print "</SqlPlusResult>"
}
' "$input_file"
