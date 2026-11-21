import { describe, expect, it } from 'vitest';
import { previewNativeTestCase } from './nativeTestCasePreview';

const flows = new Set(['checkout.v1']);

describe('previewNativeTestCase', () => {
  it('resolves bounded inline records into stable qualified IDs and redacts sensitive values', () => {
    const result = previewNativeTestCase(`schemaVersion: att-testcase/v1
id: payments.refund
name: Refund
flow: checkout.v1
tags: [smoke]
caseIdExpression: "\${DATA.id}"
inputs:
  amount:
    from: DATA.amount
dataSource:
  type: inline
  records:
    - recordId: r1
      data:
        id: R-001
        amount: 12
        password: private-value
      tags: [fast]
`, flows);
    expect(result.diagnostics).toEqual([]);
    expect(result.cases).toEqual([expect.objectContaining({
      qualifiedId: 'payments.refund.R-001', caseId: 'R-001', flow: 'checkout.v1', tags: ['smoke', 'fast'],
      inputs: { amount: 12 }, provenance: { kind: 'inline', recordId: 'r1', recordIndex: 1 },
    })]);
    expect(JSON.stringify(result.cases)).not.toContain('private-value');
  });

  it('keeps inline parsing bounded and reports invalid YAML without echoing source text', () => {
    const result = previewNativeTestCase('schemaVersion: [secret-value\n');
    expect(result.cases).toEqual([]);
    expect(result.diagnostics[0]).toContain('YAML is invalid');
    expect(result.diagnostics.join(' ')).not.toContain('secret-value');
  });

  it('does not claim to resolve Excel rows before the S04 adapter is available', () => {
    const result = previewNativeTestCase(`schemaVersion: att-testcase/v1\nid: payment\nflow: checkout.v1\ndataSource:\n  type: excel\n  path: data.xlsx\n  sheet: Cases\n  caseId: ID\n`, flows);
    expect(result.sourceType).toBe('excel');
    expect(result.cases).toHaveLength(0);
    expect(result.diagnostics.join(' ')).toContain('S04 Excel adapter');
  });

  it('rejects a duplicate resolved ID and a missing Flow', () => {
    const result = previewNativeTestCase(`schemaVersion: att-testcase/v1\nid: payment\nflow: missing.v1\ncaseIdExpression: "\${DATA.id}"\ndataSource:\n  type: inline\n  records:\n    - recordId: first\n      data: {id: SAME}\n    - recordId: second\n      data: {id: SAME}\n`, flows);
    expect(result.diagnostics.some(message => message.includes('not available'))).toBe(true);
    expect(result.diagnostics.some(message => message.includes('duplicate Case ID'))).toBe(true);
  });
});
