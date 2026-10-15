import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api } from './api';
import { AuthPasswordField } from './AuthPasswordField';
import { authUiText, type AuthUiLocale } from './authUiText';
import { ApiError } from './types';

function validPassword(password: string): boolean {
  return password.length >= 12 && password.length <= 1024 && !/[\u0000-\u001f\u007f-\u009f]/.test(password);
}

export function ChangePasswordDialog({ open, onClose, locale }: { open: boolean; onClose(): void; locale: AuthUiLocale }) {
  const t = (key: Parameters<typeof authUiText>[0]) => authUiText(key,locale);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      if (typeof dialog.showModal === 'function') dialog.showModal();
      else dialog.setAttribute('open', '');
    } else if (!open && dialog.open) {
      if (typeof dialog.close === 'function') dialog.close();
      else dialog.removeAttribute('open');
    }
  }, [open]);

  function close() {
    setCurrentPassword('');setNewPassword('');setConfirmation('');setError('');onClose();
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();setError('');
    if (newPassword !== confirmation) { setError(t('passwordMismatch'));return; }
    if (!validPassword(newPassword)) { setError(t('passwordPolicy'));return; }
    setBusy(true);
    try {
      await api.changeLocalPassword(currentPassword,newPassword);
      setCurrentPassword('');setNewPassword('');setConfirmation('');
      const login = new URL('login.html?message=password-changed',document.baseURI);
      location.assign(login.toString());
    } catch (failure) {
      if(failure instanceof ApiError&&failure.code==='ATT-AUTH-INVALID-CREDENTIALS')setError(locale==='zh-Hant'?'目前密碼不正確。':'The current password is incorrect.');
      else if(failure instanceof ApiError&&failure.code==='ATT-AUTH-CSRF-INVALID')setError(t('csrfError'));
      else if(failure instanceof ApiError&&failure.status===401)setError(t('sessionExpired'));
      else if(failure instanceof ApiError&&failure.status===403)setError(t('forbidden'));
      else if(failure instanceof ApiError&&failure.status===0)setError(t('networkError'));
      else setError(t('requestError'));
    } finally { setBusy(false); }
  }

  return <dialog ref={dialogRef} className="att-auth-dialog" aria-labelledby="change-password-title"
    onCancel={event => { event.preventDefault();close(); }}>
    <form onSubmit={submit}>
      <div className="att-auth-dialog-heading"><div><p className="eyebrow">ATT Studio</p><h2 id="change-password-title">{t('passwordChangeTitle')}</h2></div>
        <button type="button" className="icon-button" aria-label={t('cancel')} onClick={close}>×</button></div>
      <p className="att-auth-help">{t('passwordChangeHelp')}</p>
      <AuthPasswordField label={t('currentPassword')} value={currentPassword} onChange={setCurrentPassword} locale={locale} autoComplete="current-password" autoFocus />
      <AuthPasswordField label={t('newPassword')} value={newPassword} onChange={setNewPassword} locale={locale} autoComplete="new-password" />
      <AuthPasswordField label={t('confirmNewPassword')} value={confirmation} onChange={setConfirmation} locale={locale} autoComplete="new-password" />
      <p className="att-auth-help">{t('passwordHint')}</p>
      {error && <p className="att-auth-error" role="alert">{error}</p>}
      <div className="dialog-actions"><button type="button" className="button button-quiet" onClick={close} disabled={busy}>{t('cancel')}</button>
        <button type="submit" className="button button-primary" disabled={busy}>{busy ? t('saving') : t('savePassword')}</button></div>
    </form>
  </dialog>;
}
