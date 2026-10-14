import { useId, useState } from 'react';
import { authUiText, type AuthUiLocale } from './authUiText';

export function AuthPasswordField({ label, value, onChange, locale, autoComplete, required = true, autoFocus = false }: {
  label: string;
  value: string;
  onChange(value: string): void;
  locale: AuthUiLocale;
  autoComplete: string;
  required?: boolean;
  autoFocus?: boolean;
}) {
  const id = useId();
  const [visible, setVisible] = useState(false);
  const visibilityLabel = authUiText(visible ? 'hidePassword' : 'revealPassword',locale);
  return <div className="att-auth-password-field">
    <label htmlFor={id}>{label}</label>
    <div className="att-auth-password-control">
      <input id={id} type={visible ? 'text' : 'password'} value={value} onChange={event => onChange(event.currentTarget.value)}
        autoComplete={autoComplete} minLength={autoComplete === 'current-password' ? undefined : 12} maxLength={1024}
        required={required} autoFocus={autoFocus} />
      <button type="button" className="button button-quiet" aria-label={visibilityLabel} aria-pressed={visible}
        onClick={() => setVisible(current => !current)}>{visibilityLabel}</button>
    </div>
  </div>;
}
