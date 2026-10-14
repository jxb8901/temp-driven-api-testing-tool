import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { api } from './api';
import { useAppShellContext } from './AppShell';
import { AuthPasswordField } from './AuthPasswordField';
import { authUiLocale, authUiText, type AuthUiLocale } from './authUiText';
import { ApiError, type LocalAuthUser } from './types';

type UserDialog =
  | { kind: 'create' }
  | { kind: 'status'; user: LocalAuthUser; enable: boolean }
  | { kind: 'reset'; user: LocalAuthUser };
type SortKey = 'loginId' | 'displayName' | 'role' | 'active';

function text(locale: AuthUiLocale, key: Parameters<typeof authUiText>[0]): string {
  return authUiText(key, locale);
}

function errorCopy(error: unknown, locale: AuthUiLocale): string {
  if (error instanceof ApiError) {
    if (error.code === 'ATT-AUTH-LOGIN-ID-TAKEN') return text(locale, 'duplicateLogin');
    if (error.code === 'ATT-AUTH-LAST-ADMIN') return text(locale, 'lastAdmin');
    if (error.code === 'ATT-AUTH-CSRF-INVALID') return text(locale, 'csrfError');
    if (error.code === 'ATT-SERVER-AUTHENTICATION-REQUIRED' || error.status === 401) return text(locale, 'sessionExpired');
    if (error.status === 403) return text(locale, 'forbidden');
    if (error.status === 0) return text(locale, 'networkError');
    if (error.status === 400) return text(locale, 'passwordPolicy');
  }
  if (error instanceof TypeError) return text(locale, 'networkError');
  return text(locale, 'requestError');
}

function validPassword(password: string): boolean {
  return password.length >= 12 && password.length <= 1024 && !/[\u0000-\u001f\u007f-\u009f]/.test(password);
}

function redirectToLogin(message: string) {
  const login = new URL('login.html?message=' + encodeURIComponent(message), document.baseURI);
  location.assign(login.toString());
}

export function UserManagementPanel() {
  const { authMode, currentUser } = useAppShellContext();
  const locale: AuthUiLocale = useMemo(authUiLocale,[]);
  const t = (key: Parameters<typeof authUiText>[0]) => text(locale,key);
  const isAdmin = authMode === 'local' && currentUser?.role === 'ADMIN';
  const [users,setUsers] = useState<LocalAuthUser[]>([]);
  const [loadState,setLoadState] = useState<'loading'|'ready'|'error'>('loading');
  const [loadError,setLoadError] = useState('');
  const [search,setSearch] = useState('');
  const [sortKey,setSortKey] = useState<SortKey>('loginId');
  const [descending,setDescending] = useState(false);
  const [dialog,setDialog] = useState<UserDialog|null>(null);
  const [feedback,setFeedback] = useState('');
  const [actionError,setActionError] = useState('');
  const [busy,setBusy] = useState(false);
  const [account,setAccount] = useState({loginId:'',displayName:'',role:'USER' as 'ADMIN'|'USER',password:'',confirmation:''});
  const [newPassword,setNewPassword] = useState('');
  const [confirmation,setConfirmation] = useState('');
  const dialogRef = useRef<HTMLDialogElement>(null);

  const reload = useCallback(async () => {
    setLoadState('loading');setLoadError('');
    try { const result=await api.listLocalUsers();setUsers(Array.isArray(result.items)?result.items:[]);setLoadState('ready'); }
    catch (failure) {setLoadError(errorCopy(failure,locale));setLoadState('error');}
  },[locale]);
  useEffect(() => { if(isAdmin) void reload(); },[isAdmin,reload]);
  useEffect(() => {
    const element=dialogRef.current;if(!element)return;
    if(dialog&&!element.open) {if(typeof element.showModal==='function')element.showModal();else element.setAttribute('open','');}
    else if(!dialog&&element.open) {if(typeof element.close==='function')element.close();else element.removeAttribute('open');}
  },[dialog]);

  const visibleUsers=useMemo(()=>{
    const query=search.trim().toLocaleLowerCase();
    const filtered=users.filter(user=>[user.loginId,user.displayName,user.role,user.active?t('active'):t('disabled')].some(value=>value.toLocaleLowerCase().includes(query)));
    return filtered.sort((a,b)=>{
      const left=sortKey==='active'?Number(a.active):String(a[sortKey]);
      const right=sortKey==='active'?Number(b.active):String(b[sortKey]);
      const comparison=typeof left==='number'&&typeof right==='number'?left-right:new Intl.Collator(locale,{numeric:true,sensitivity:'base'}).compare(String(left),String(right));
      return descending?-comparison:comparison;
    });
  },[users,search,sortKey,descending,locale]);

  function closeDialog() {
    setDialog(null);setAccount({loginId:'',displayName:'',role:'USER',password:'',confirmation:''});setNewPassword('');setConfirmation('');setActionError('');
  }
  function beginDialog(next: UserDialog) {setFeedback('');setActionError('');setDialog(next);}
  function toggleSort(key: SortKey) {if(sortKey===key)setDescending(value=>!value);else{setSortKey(key);setDescending(false);}}
  function sortAria(key: SortKey): 'ascending'|'descending'|'none' {return sortKey!==key?'none':descending?'descending':'ascending';}

  async function submitCreate(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();setActionError('');
    if(account.password!==account.confirmation){setActionError(t('passwordMismatch'));return;}
    if(!validPassword(account.password)){setActionError(t('passwordPolicy'));return;}
    setBusy(true);
    try {await api.createLocalUser({loginId:account.loginId,displayName:account.displayName,role:account.role,password:account.password});closeDialog();setFeedback(t('userCreated'));await reload();}
    catch(failure){setActionError(errorCopy(failure,locale));}
    finally{setBusy(false);}
  }
  async function submitStatus() {
    if(dialog?.kind!=='status')return;setBusy(true);setActionError('');
    try {
      await api.setLocalUserEnabled(dialog.user.loginId,dialog.enable);
      const target=dialog.user;const enabled=dialog.enable;closeDialog();
      if(!enabled&&target.loginId===currentUser?.loginId){redirectToLogin('account-disabled');return;}
      setFeedback(t('userUpdated'));await reload();
    } catch(failure){setActionError(errorCopy(failure,locale));}
    finally{setBusy(false);}
  }
  async function submitReset(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();if(dialog?.kind!=='reset')return;setActionError('');
    if(newPassword!==confirmation){setActionError(t('passwordMismatch'));return;}
    if(!validPassword(newPassword)){setActionError(t('passwordPolicy'));return;}
    setBusy(true);
    try {
      const target=dialog.user;await api.resetLocalUserPassword(target.loginId,newPassword);closeDialog();
      if(target.loginId===currentUser?.loginId){redirectToLogin('password-reset');return;}
      setFeedback(t('passwordResetDone'));await reload();
    } catch(failure){setActionError(errorCopy(failure,locale));}
    finally{setBusy(false);}
  }

  if(authMode==='container')return <section className="panel att-settings-section" aria-labelledby="user-management-title">
    <div className="att-settings-section-heading"><div><h2 id="user-management-title">{t('userManagementTitle')}</h2>
      <p className="att-settings-help">{t('externalAccountsNote')}</p></div></div>
  </section>;
  if(!isAdmin)return null;

  return <section className="panel att-settings-section att-user-management" aria-labelledby="user-management-title">
    <div className="att-settings-section-heading"><div><h2 id="user-management-title">{t('userManagementTitle')}</h2>
      <p className="att-settings-help">{t('userManagementDescription')}</p></div>
      <button className="button button-primary" type="button" onClick={()=>beginDialog({kind:'create'})}>{t('createUser')}</button>
    </div>
    <div className="att-user-search"><label htmlFor="att-user-search">{t('searchUsers')}</label>
      <input id="att-user-search" type="search" value={search} placeholder={t('searchPlaceholder')} onChange={event=>setSearch(event.currentTarget.value)} />
    </div>
    {feedback&&<p className="att-auth-feedback" role="status" aria-live="polite">{feedback}</p>}
    {loadState==='loading'&&<p role="status" aria-live="polite">{t('loadingUsers')}</p>}
    {loadState==='error'&&<div className="att-auth-error" role="alert"><p>{t('usersLoadFailed')} {loadError}</p><button type="button" className="button button-quiet" onClick={()=>void reload()}>{t('retry')}</button></div>}
    {loadState==='ready'&&users.length===0&&<p className="att-auth-empty">{t('noUsers')}</p>}
    {loadState==='ready'&&users.length>0&&visibleUsers.length===0&&<p className="att-auth-empty">{t('noMatches')}</p>}
    {loadState==='ready'&&visibleUsers.length>0&&<div className="att-user-table-wrap"><table className="att-user-table">
      <thead><tr>
        {(['loginId','displayName','role','active'] as SortKey[]).map(key=><th key={key} scope="col" aria-sort={sortAria(key)}>
          <button type="button" onClick={()=>toggleSort(key)}>{t(key==='active'?'status':key==='role'?'role':key)}<span aria-hidden="true">{sortKey===key?(descending?' ↓':' ↑'):''}</span></button>
        </th>)}<th scope="col">{t('actions')}</th>
      </tr></thead>
      <tbody>{visibleUsers.map(user=><tr key={user.loginId}>
        <th scope="row">{user.loginId}</th><td>{user.displayName}</td><td>{user.role==='ADMIN'?t('admin'):t('user')}</td>
        <td><span className={'att-user-status'+(user.active?' is-active':' is-disabled')}>{user.active?t('active'):t('disabled')}</span></td>
        <td><div className="att-user-actions">
          <button type="button" className="button button-quiet" onClick={()=>beginDialog({kind:'reset',user})} aria-label={t('resetPassword')+': '+user.loginId}>{t('resetPassword')}</button>
          <button type="button" className="button button-quiet" onClick={()=>beginDialog({kind:'status',user,enable:!user.active})} aria-label={(user.active?t('disableUser'):t('enableUser'))+': '+user.loginId}>{user.active?t('disableUser'):t('enableUser')}</button>
        </div></td>
      </tr>)}</tbody>
    </table></div>}

    <dialog ref={dialogRef} className="att-auth-dialog" aria-labelledby="att-user-dialog-title" onCancel={event=>{event.preventDefault();closeDialog();}}>
      {dialog?.kind==='create'&&<form onSubmit={submitCreate}>
        <div className="att-auth-dialog-heading"><div><p className="eyebrow">ATT Studio</p><h2 id="att-user-dialog-title">{t('newAccount')}</h2></div>
          <button type="button" className="icon-button" aria-label={t('cancel')} onClick={closeDialog}>×</button></div>
        <label>{t('loginId')}<input autoFocus autoComplete="off" required minLength={3} maxLength={120} pattern="[A-Za-z0-9][A-Za-z0-9._-]{2,119}" value={account.loginId} onChange={event=>{const loginId=event.currentTarget.value;setAccount(value=>({...value,loginId}));}} /></label>
        <label>{t('displayName')}<input autoComplete="name" required maxLength={120} value={account.displayName} onChange={event=>{const displayName=event.currentTarget.value;setAccount(value=>({...value,displayName}));}} /></label>
        <label>{t('role')}<select value={account.role} onChange={event=>{const role=event.currentTarget.value as 'ADMIN'|'USER';setAccount(value=>({...value,role}));}}>
          <option value="USER">{t('user')}</option><option value="ADMIN">{t('admin')}</option></select></label>
        <AuthPasswordField label={t('newPassword')} value={account.password} onChange={value=>setAccount(current=>({...current,password:value}))} locale={locale} autoComplete="new-password" />
        <AuthPasswordField label={t('confirmNewPassword')} value={account.confirmation} onChange={value=>setAccount(current=>({...current,confirmation:value}))} locale={locale} autoComplete="new-password" />
        <p className="att-auth-help">{t('passwordHint')}</p>
        {actionError&&<p className="att-auth-error" role="alert">{actionError}</p>}
        <div className="dialog-actions"><button type="button" className="button button-quiet" onClick={closeDialog} disabled={busy}>{t('cancel')}</button>
          <button type="submit" className="button button-primary" disabled={busy}>{busy?t('saving'):t('createAccount')}</button></div>
      </form>}
      {dialog?.kind==='status'&&<div>
        <div className="att-auth-dialog-heading"><div><p className="eyebrow">ATT Studio</p><h2 id="att-user-dialog-title">{dialog.enable?t('enableConfirmTitle'):t('disableConfirmTitle')}</h2></div>
          <button type="button" className="icon-button" aria-label={t('cancel')} onClick={closeDialog}>×</button></div>
        <p className="att-auth-help">{dialog.enable?t('enableConfirmDescription'):t('disableConfirmDescription')}</p>
        <p><strong>{dialog.user.displayName}</strong> · {dialog.user.loginId}</p>
        {actionError&&<p className="att-auth-error" role="alert">{actionError}</p>}
        <div className="dialog-actions"><button type="button" className="button button-quiet" onClick={closeDialog} disabled={busy}>{t('cancel')}</button>
          <button type="button" className={dialog.enable?'button button-primary':'button button-danger'} onClick={()=>void submitStatus()} disabled={busy}>{busy?t('saving'):t('confirm')}</button></div>
      </div>}
      {dialog?.kind==='reset'&&<form onSubmit={submitReset}>
        <div className="att-auth-dialog-heading"><div><p className="eyebrow">ATT Studio</p><h2 id="att-user-dialog-title">{t('resetConfirmTitle')}</h2></div>
          <button type="button" className="icon-button" aria-label={t('cancel')} onClick={closeDialog}>×</button></div>
        <p className="att-auth-help">{t('resetConfirmDescription')}</p><p><strong>{dialog.user.displayName}</strong> · {dialog.user.loginId}</p>
        <p className="att-auth-help">{t('noExistingPassword')} {t('passwordHint')}</p>
        <AuthPasswordField label={t('newPassword')} value={newPassword} onChange={setNewPassword} locale={locale} autoComplete="new-password" autoFocus />
        <AuthPasswordField label={t('confirmNewPassword')} value={confirmation} onChange={setConfirmation} locale={locale} autoComplete="new-password" />
        {actionError&&<p className="att-auth-error" role="alert">{actionError}</p>}
        <div className="dialog-actions"><button type="button" className="button button-quiet" onClick={closeDialog} disabled={busy}>{t('cancel')}</button>
          <button type="submit" className="button button-danger" disabled={busy}>{busy?t('saving'):t('confirm')}</button></div>
      </form>}
    </dialog>
  </section>;
}
