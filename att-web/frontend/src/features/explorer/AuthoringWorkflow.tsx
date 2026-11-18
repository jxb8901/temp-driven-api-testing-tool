import './source-authoring.css';

export type AuthoringStepState = 'complete' | 'current' | 'upcoming' | 'blocked';

export interface AuthoringWorkflowStep {
  id: string;
  label: string;
  state: AuthoringStepState;
  reason?: string;
}

export function AuthoringWorkflow({ label, steps }: { label: string; steps: AuthoringWorkflowStep[] }) {
  return <ol className="att-authoring-workflow" aria-label={label}>
    {steps.map((step, index) => <li key={step.id} data-state={step.state}
      aria-current={step.state === 'current' ? 'step' : undefined}
      title={step.state === 'blocked' ? step.reason : undefined}>
      <span className="att-authoring-workflow-number" aria-hidden="true">{index + 1}</span>
      <span className="att-authoring-workflow-label">{step.label}</span>
      {step.state === 'blocked' && step.reason && <span className="att-authoring-workflow-reason">{step.reason}</span>}
    </li>)}
  </ol>;
}
