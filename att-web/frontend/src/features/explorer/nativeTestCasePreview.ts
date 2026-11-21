import { parseDocument } from 'yaml';

export const MAX_NATIVE_PREVIEW_BYTES = 100 * 1024;
export const MAX_NATIVE_PREVIEW_CASES = 1000;

export interface NativeCasePreviewItem {
  qualifiedId: string;
  caseId: string;
  flow: string;
  tags: string[];
  inputs: Record<string, unknown>;
  provenance: { kind: 'inline' | 'definition'; recordId?: string; recordIndex?: number };
}

export interface NativeCasePreviewResult {
  cases: NativeCasePreviewItem[];
  diagnostics: string[];
  sourceType: 'direct' | 'inline' | 'excel' | 'invalid';
}

const DEFINITION_ID = /^[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*$/;
const FLOW_ID = /^[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*\.v[1-9][0-9]*$/;
const CASE_ID = /^[A-Za-z0-9_-]{1,128}$/;
const INPUT_NAME = /^[A-Za-z_][A-Za-z0-9_-]{0,127}$/;
const DATA_REFERENCE = /^DATA\.([A-Za-z_][A-Za-z0-9_-]*)$/;
const CASE_ID_EXPRESSION = /^\$\{DATA\.([A-Za-z_][A-Za-z0-9_-]*)\}$/;
const RESERVED_NAME = /^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])$/i;
const SECRET_KEY = /(?:password|passwd|secret|token|authorization|api[-_]?key|private[-_]?key|credential|client[-_]?secret)/i;
const TOP_LEVEL_FIELDS = new Set(['schemaVersion', 'id', 'name', 'description', 'tags', 'flow', 'inputs', 'dataSource', 'caseIdExpression', 'report']);
const INLINE_SOURCE_FIELDS = new Set(['type', 'records']);
const EXCEL_SOURCE_FIELDS = new Set(['type', 'path', 'sheet', 'headerRows', 'caseId', 'tags', 'dataColumns']);
const INLINE_RECORD_FIELDS = new Set(['recordId', 'tags', 'data']);

function object(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function validateUnknownProperties(value: Record<string, unknown>, allowed: ReadonlySet<string>, label: string, diagnostics: string[]): void {
  for (const key of Object.keys(value)) if (!allowed.has(key) && !key.startsWith('x-')) diagnostics.push(`${label} contains an unsupported field.`);
}

function validateTags(value: unknown, label: string, diagnostics: string[]): string[] {
  if (!Array.isArray(value) || value.some(item => typeof item !== 'string' || item.length === 0)) {
    diagnostics.push(`${label} must be a list of non-empty strings.`);
    return [];
  }
  if (new Set(value).size !== value.length) diagnostics.push(`${label} contains duplicate tags.`);
  return value as string[];
}

function validateReport(value: unknown, excel: boolean, diagnostics: string[]): void {
  if (value === undefined) return;
  const report = object(value);
  if (!report) { diagnostics.push('Report must be an object.'); return; }
  validateUnknownProperties(report, new Set(['format', 'template']), 'Report', diagnostics);
  if (report.format !== 'xlsx') diagnostics.push('Report format must be xlsx.');
  if (report.template !== undefined && report.template !== 'sourceWorkbook') diagnostics.push('Report template is invalid.');
  if (report.template === 'sourceWorkbook' && !excel)
    diagnostics.push('A sourceWorkbook report requires an Excel Data Source.');
}

function redact(value: unknown, depth = 0): unknown {
  if (depth > 12) return '[omitted: nesting limit]';
  if (Array.isArray(value)) return value.slice(0, 100).map(item => redact(item, depth + 1));
  const record = object(value);
  if (!record) return value;
  return Object.fromEntries(Object.entries(record).slice(0, 100).map(([key, item]) => [key,
    SECRET_KEY.test(key) ? '[redacted]' : redact(item, depth + 1)]));
}

function error(message: string): NativeCasePreviewResult {
  return { cases: [], diagnostics: [message], sourceType: 'invalid' };
}

/**
 * Bounded browser-side preview of the active inline subset. This deliberately does
 * not claim Server validation or freeze a Job selection; RUN re-resolves the source.
 */
export function previewNativeTestCase(source: string, availableFlows?: ReadonlySet<string>): NativeCasePreviewResult {
  if (new TextEncoder().encode(source).byteLength > MAX_NATIVE_PREVIEW_BYTES)
    return error(`Definition exceeds the ${MAX_NATIVE_PREVIEW_BYTES.toLocaleString()} byte preview limit.`);

  const document = parseDocument(source, { uniqueKeys: true });
  if (document.errors.length) return error('Definition YAML is invalid. Correct its syntax in the advanced source view.');
  let parsed: unknown;
  try { parsed = document.toJS(); }
  catch { return error('Definition YAML could not be read as a safe object.'); }
  const definition = object(parsed);
  if (!definition) return error('Definition must be a YAML object.');

  const diagnostics: string[] = [];
  validateUnknownProperties(definition, TOP_LEVEL_FIELDS, 'Definition', diagnostics);
  const id = typeof definition.id === 'string' ? definition.id : '';
  const flow = typeof definition.flow === 'string' ? definition.flow : '';
  if (!DEFINITION_ID.test(id) || id.length > 126 || id.split('.').some(part => RESERVED_NAME.test(part)))
    diagnostics.push('Definition ID is invalid.');
  if (!FLOW_ID.test(flow)) diagnostics.push('Select a canonical Flow ID.');
  else if (availableFlows && !availableFlows.has(flow)) diagnostics.push('The selected Flow is not available in this Package.');
  if (typeof definition.schemaVersion !== 'string' || definition.schemaVersion !== 'att-testcase/v1')
    diagnostics.push('schemaVersion must be att-testcase/v1.');
  if (definition.name !== undefined && (typeof definition.name !== 'string' || !definition.name.trim()))
    diagnostics.push('Definition name must be a non-empty string when provided.');
  if (definition.description !== undefined && typeof definition.description !== 'string')
    diagnostics.push('Definition description must be a string.');
  const definitionTags = definition.tags === undefined ? [] : validateTags(definition.tags, 'Definition tags', diagnostics);
  const hasInputField = Object.hasOwn(definition, 'inputs');
  const parsedInputs = hasInputField ? object(definition.inputs) : null;
  if (hasInputField && !parsedInputs) diagnostics.push('Inputs must be a YAML object.');

  const hasDataSource = Object.hasOwn(definition, 'dataSource');
  const dataSource = object(definition.dataSource);
  if (hasDataSource && !dataSource) {
    diagnostics.push('Data Source must be an object.');
    return { cases: [], diagnostics, sourceType: 'invalid' };
  }
  const excelSource = dataSource?.type === 'excel';
  validateReport(definition.report, excelSource, diagnostics);
  if (dataSource?.type === 'excel') {
    validateUnknownProperties(dataSource, EXCEL_SOURCE_FIELDS, 'Excel Data Source', diagnostics);
    const workbookPath = typeof dataSource.path === 'string' ? dataSource.path : '';
    if (!/^(?!\/)(?![A-Za-z]:)(?!.*\\)(?!.*(?:^|\/)\.{1,2}(?:\/|$)).+\.[Xx][Ll][Ss][Xx]$/.test(workbookPath))
      diagnostics.push('Excel workbook path must be a safe Package-relative .xlsx path.');
    if (typeof dataSource.sheet !== 'string' || !dataSource.sheet.trim()) diagnostics.push('Excel sheet is required.');
    if (dataSource.headerRows !== undefined && (!Number.isInteger(dataSource.headerRows) || Number(dataSource.headerRows) < 1))
      diagnostics.push('Excel headerRows must be an integer of at least 1.');
    if (typeof dataSource.caseId !== 'string' || !dataSource.caseId.trim()) diagnostics.push('Excel Case ID column is required.');
    if (dataSource.tags !== undefined && (typeof dataSource.tags !== 'string' || !dataSource.tags.trim()))
      diagnostics.push('Excel tags column must be a non-empty string when provided.');
    if (dataSource.dataColumns !== undefined && typeof dataSource.dataColumns !== 'string')
      diagnostics.push('Excel dataColumns must be a string.');
    if (definition.caseIdExpression !== undefined && !CASE_ID_EXPRESSION.test(String(definition.caseIdExpression)))
      diagnostics.push('Case ID expression must be a direct ${DATA.key} lookup.');
    return { cases: [], sourceType: 'excel', diagnostics: [...diagnostics,
      'Excel row preview requires the S04 Excel adapter. No workbook has been opened or changed.'] };
  }
  if (dataSource && dataSource.type !== 'inline')
    return { cases: [], sourceType: 'invalid', diagnostics: [...diagnostics, 'This Data Source type is not available in the current preview.'] };
  if (dataSource) validateUnknownProperties(dataSource, INLINE_SOURCE_FIELDS, 'Inline Data Source', diagnostics);
  if (!dataSource && !hasInputField) diagnostics.push('A single Case Definition must provide an inputs object.');
  if (!dataSource && definition.caseIdExpression !== undefined)
    diagnostics.push('Case ID expression requires a multi-record Data Source.');

  const records: Array<{ recordId: string; data: Record<string, unknown>; tags: string[]; recordIndex: number }> = [];
  if (dataSource) {
    if (!Array.isArray(dataSource.records)) return error('Inline Data Source records must be a list.');
    if (dataSource.records.length === 0) return error('Inline Data Source must contain at least one record.');
    if (dataSource.records.length > MAX_NATIVE_PREVIEW_CASES)
      return error(`Inline preview is limited to ${MAX_NATIVE_PREVIEW_CASES.toLocaleString()} cases.`);
    dataSource.records.forEach((raw, index) => {
      const record = object(raw);
      const recordId = typeof record?.recordId === 'string' ? record.recordId : '';
      const data = object(record?.data);
      if (!record || !recordId || !data) {
        diagnostics.push(`Record ${index + 1} must contain a recordId and a data object.`);
        return;
      }
      validateUnknownProperties(record, INLINE_RECORD_FIELDS, `Record ${index + 1}`, diagnostics);
      if (!CASE_ID.test(recordId) || RESERVED_NAME.test(recordId)) diagnostics.push(`Record ${index + 1} has an invalid recordId.`);
      for (const key of Object.keys(data)) if (!INPUT_NAME.test(key)) diagnostics.push(`Record ${index + 1} has an invalid data field.`);
      const recordTags = record.tags === undefined ? [] : validateTags(record.tags, `Record ${index + 1} tags`, diagnostics);
      records.push({ recordId, data, tags: recordTags, recordIndex: index });
    });
  } else {
    const inputs = object(definition.inputs);
    records.push({ recordId: 'default', data: inputs || {}, tags: [], recordIndex: 0 });
  }

  const projection = dataSource ? parsedInputs : null;
  if (dataSource && hasInputField && !projection) diagnostics.push('Input projection must be a YAML object.');
  if (projection) for (const name of Object.keys(projection)) if (!INPUT_NAME.test(name)) diagnostics.push('Input projection contains an invalid input name.');
  const expression = typeof definition.caseIdExpression === 'string' ? definition.caseIdExpression : '';
  const expressionMatch = expression ? CASE_ID_EXPRESSION.exec(expression) : null;
  if (expression && !expressionMatch) diagnostics.push('Case ID expression must be a direct ${DATA.key} lookup.');
  const foldedIds = new Set<string>();
  const foldedRecords = new Set<string>();
  const cases: NativeCasePreviewItem[] = [];
  for (const record of records) {
    const foldedRecord = record.recordId.replace(/[A-Z]/g, char => char.toLowerCase());
    if (dataSource && foldedRecords.has(foldedRecord)) diagnostics.push(`Record ${record.recordIndex + 1} has a duplicate recordId.`);
    foldedRecords.add(foldedRecord);
    let caseId = record.recordId;
    if (expressionMatch) {
      const key = expressionMatch[1];
      const value = record.data[key];
      if (typeof value !== 'string') {
        diagnostics.push(`Record ${record.recordIndex + 1} has a missing or non-string Case ID source key.`);
        continue;
      }
      caseId = value;
    }
    if (!CASE_ID.test(caseId) || RESERVED_NAME.test(caseId)) {
      diagnostics.push(`Record ${record.recordIndex + 1} resolves to an invalid Case ID.`);
      continue;
    }
    const folded = caseId.replace(/[A-Z]/g, char => char.toLowerCase());
    if (foldedIds.has(folded)) diagnostics.push(`Record ${record.recordIndex + 1} resolves to a duplicate Case ID.`);
    foldedIds.add(folded);
    const qualifiedId = `${id}.${caseId}`;
    if (qualifiedId.length > 255) {
      diagnostics.push(`Record ${record.recordIndex + 1} resolves to an overlong qualified Case ID.`);
      continue;
    }
    let inputValues: Record<string, unknown> = record.data;
    if (projection) {
      inputValues = {};
      for (const [name, rawBinding] of Object.entries(projection)) {
        if (!INPUT_NAME.test(name)) { diagnostics.push(`Input name ${name} is invalid.`); continue; }
        const binding = object(rawBinding);
        if (binding && typeof binding.from === 'string') {
          const match = DATA_REFERENCE.exec(binding.from);
          if (!match) { diagnostics.push(`Input ${name} must use a direct DATA.key binding.`); continue; }
          if (!Object.hasOwn(record.data, match[1])) { diagnostics.push(`Record ${record.recordIndex + 1} is missing a mapped input value.`); continue; }
          inputValues[name] = record.data[match[1]];
        } else if (binding && Object.hasOwn(binding, 'value')) inputValues[name] = binding.value;
        else diagnostics.push(`Input ${name} must contain a value or DATA.key binding.`);
      }
    }
    const itemTags = [...new Set([...definitionTags, ...record.tags])];
    cases.push({ qualifiedId, caseId, flow, tags: itemTags, inputs: redact(inputValues) as Record<string, unknown>,
      provenance: dataSource ? { kind: 'inline', recordId: record.recordId, recordIndex: record.recordIndex + 1 }
        : { kind: 'definition' } });
  }
  return { cases, diagnostics, sourceType: dataSource ? 'inline' : 'direct' };
}
