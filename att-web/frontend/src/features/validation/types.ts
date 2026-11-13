type EnvironmentInput = { environment?: string };
export type ValidationRequest =
  | ({ targetKind: 'config'; packageId?: string } & EnvironmentInput)
  | ({ targetKind: 'package'; packageId: string } & EnvironmentInput)
  | ({ targetKind: 'case' | 'suite' | 'template' | 'flow' | 'tool' | 'load'; packageId: string; target: string } & EnvironmentInput);

export interface ValidationDiagnostic {
  code: string;
  severity: 'ERROR' | 'WARN' | 'WARNING' | 'INFO' | string;
  message: string;
  source?: string | { file?: string; line?: number; column?: number; [key: string]: unknown };
  file?: string;
  template?: string;
  context?: { flowId?: string; [key: string]: unknown };
  field?: string;
  suggestion?: string;
  resourceId?: string;
  resourceType?: string;
  target?: string;
  [key: string]: unknown;
}
export interface ValidationSection {
  name: string;
  status: 'PASS' | 'FAIL' | 'WARN' | 'SKIP' | string;
  source?: string;
  diagnostics?: ValidationDiagnostic[];
  sections?: ValidationSection[];
}

export interface ValidationReportData {
  schemaVersion: 'att-validation/v1';
  scope: string;
  backend: 'LOCAL' | 'SERVER';
  sections: ValidationSection[];
  diagnostics: ValidationDiagnostic[];
  counts: { pass: number; fail: number; warn: number; skip: number; errors: number; warnings: number };
  overallStatus: 'PASS' | 'INVALID' | 'PARTIAL';
  complete: boolean;
  exitCode: number;
  context?: Record<string, unknown>;
  requestId?: string;
}
