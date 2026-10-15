import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppShellContext, type AppShellContextValue } from './AppShell';
import { api } from './api';
import { UserManagementPanel } from './UserManagement';
import { ApiError, type LocalAuthUser } from './types';

vi.mock('./api',()=>({api:{
  listLocalUsers:vi.fn(),createLocalUser:vi.fn(),setLocalUserEnabled:vi.fn(),resetLocalUserPassword:vi.fn(),changeLocalPassword:vi.fn(),
}}));

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT=true;

const initialUsers:LocalAuthUser[]=[
  {loginId:'admin',displayName:'ATT Admin',role:'ADMIN',active:true},
  {loginId:'jane',displayName:'Jane Tester',role:'USER',active:true},
  {loginId:'zoe',displayName:'Zoe Disabled',role:'USER',active:false},
];

describe('UserManagementPanel',()=>{
  let host:HTMLDivElement|undefined;
  let root:ReturnType<typeof createRoot>|undefined;
  let users:LocalAuthUser[];

  beforeEach(()=>{
    users=initialUsers.map(user=>({...user}));
    vi.mocked(api.listLocalUsers).mockImplementation(async()=>({items:users.map(user=>({...user}))}));
    vi.mocked(api.createLocalUser).mockImplementation(async input=>{
      if(users.some(user=>user.loginId===input.loginId))throw new ApiError('duplicate',{status:409,code:'ATT-AUTH-LOGIN-ID-TAKEN'});
      const created={loginId:input.loginId.toLowerCase(),displayName:input.displayName,role:input.role,active:true} as LocalAuthUser;
      users=[...users,created];return {user:created};
    });
    vi.mocked(api.setLocalUserEnabled).mockImplementation(async(loginId,enabled)=>{
      users=users.map(user=>user.loginId===loginId?{...user,active:enabled}:user);
      return enabled?{enabled:true}:{disabled:true};
    });
    vi.mocked(api.resetLocalUserPassword).mockResolvedValue({updated:true});
    vi.mocked(api.changeLocalPassword).mockResolvedValue({updated:true});
  });

  afterEach(()=>{
    if(root)act(()=>root?.unmount());host?.remove();root=undefined;host=undefined;vi.clearAllMocks();
  });

  function render(authMode:'local'|'container'='local',role:'ADMIN'|'USER'='ADMIN') {
    host=document.createElement('div');document.body.append(host);root=createRoot(host);
    const context:AppShellContextValue={
      selectedPackage:'',selectedEnvironment:'',themePreference:'system',uiLocale:'en',uiLocalePreference:'browser',authMode,canRevealConfigurationFields:false,
      currentUser:{loginId:role==='ADMIN'?'admin':'jane',displayName:role==='ADMIN'?'ATT Admin':'Jane Tester',role},
      onPackageChange:vi.fn(),onEnvironmentChange:vi.fn(),onThemePreferenceChange:vi.fn(),onUiLocaleChange:vi.fn(),onJobAccepted:vi.fn(),
    };
    act(()=>root?.render(<AppShellContext.Provider value={context}><UserManagementPanel /></AppShellContext.Provider>));
  }

  async function flush() {await act(async()=>{await Promise.resolve();});}

  function setValue(element:HTMLInputElement|HTMLSelectElement,value:string) {
    const prototype=element instanceof HTMLSelectElement?HTMLSelectElement.prototype:HTMLInputElement.prototype;
    act(()=>{Object.getOwnPropertyDescriptor(prototype,'value')?.set?.call(element,value);element.dispatchEvent(new Event(element instanceof HTMLSelectElement?'change':'input',{bubbles:true}));});
  }

  it('shows safe searchable and sortable user fields only to local ADMINs',async()=>{
    render();await flush();
    expect(api.listLocalUsers).toHaveBeenCalledOnce();
    expect(host?.textContent).toContain('Login ID');expect(host?.textContent).toContain('Jane Tester');
    expect(host?.textContent).not.toContain('passwordHash');
    const displaySort=[...host!.querySelectorAll('thead button')].find(button=>button.textContent?.includes('Display Name')) as HTMLButtonElement;
    act(()=>displaySort.click());expect(displaySort.closest('th')?.getAttribute('aria-sort')).toBe('ascending');
    const search=host!.querySelector('#att-user-search') as HTMLInputElement;setValue(search,'zoe');
    expect(host?.querySelectorAll('tbody tr')).toHaveLength(1);expect(host?.textContent).toContain('Zoe Disabled');
    setValue(search,'no-such-user');expect(host?.textContent).toContain('No users match this search.');
  });

  it('does not expose user operations or fetch account data for a regular local user',async()=>{
    render('local','USER');await flush();
    expect(api.listLocalUsers).not.toHaveBeenCalled();expect(host?.textContent).not.toContain('User Management');
  });

  it('explains external account ownership in container mode without loading ATT users',async()=>{
    render('container','USER');await flush();
    expect(host?.textContent).toContain('credentials are managed by Tomcat or your SSO provider');
    expect(host?.textContent).not.toContain('Create User');expect(api.listLocalUsers).not.toHaveBeenCalled();
  });

  it('validates password confirmation and reports duplicate IDs without retaining values in browser storage',async()=>{
    render();await flush();
    const create=[...host!.querySelectorAll('button')].find(button=>button.textContent==='Create User')!;
    act(()=>create.click());
    const dialog=host!.querySelector('dialog')!;
    const login=dialog.querySelector('input[autocomplete="off"]') as HTMLInputElement;
    const display=dialog.querySelector('input[autocomplete="name"]') as HTMLInputElement;
    const passwords=[...dialog.querySelectorAll<HTMLInputElement>('input[autocomplete="new-password"]')];
    setValue(login,'new-user');setValue(display,'New User');setValue(passwords[0],'long new password');setValue(passwords[1],'different new password');
    const form=dialog.querySelector('form')!;
    await act(async()=>{form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));});
    expect(host?.textContent).toContain('The passwords do not match.');expect(api.createLocalUser).not.toHaveBeenCalled();
    setValue(passwords[1],'long new password');setValue(login,'admin');
    await act(async()=>{form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));});
    expect(host?.textContent).toContain('That Login ID is already in use.');
    expect(localStorage.getItem('new-user')).toBeNull();expect(sessionStorage.getItem('long new password')).toBeNull();
  });

  it('requires confirmation for disabling and resetting accounts and refreshes after changes',async()=>{
    render();await flush();
    const disable=[...host!.querySelectorAll('button')].find(button=>button.getAttribute('aria-label')==='Disable: jane')!;
    act(()=>disable.click());expect(host?.textContent).toContain('revokes all active sessions');
    act(()=>(([...host!.querySelectorAll('dialog button')].find(button=>button.textContent==='Cancel') as HTMLButtonElement|undefined)?.click()));
    expect(api.setLocalUserEnabled).not.toHaveBeenCalled();
    const reset=[...host!.querySelectorAll('button')].find(button=>button.getAttribute('aria-label')==='Reset password: jane')!;
    act(()=>reset.click());expect(host?.textContent).toContain('Existing passwords are never shown.');
    const dialog=host!.querySelector('dialog')!;
    const passwords=[...dialog.querySelectorAll<HTMLInputElement>('input[autocomplete="new-password"]')];
    setValue(passwords[0],'reset passphrase 123');setValue(passwords[1],'reset passphrase 123');
    const form=dialog.querySelector('form')!;
    await act(async()=>{form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));});
    expect(api.resetLocalUserPassword).toHaveBeenCalledWith('jane','reset passphrase 123');
    expect(host?.textContent).toContain('The user must sign in again.');
  });

  it('shows CSRF and last-admin errors as actionable states',async()=>{
    vi.mocked(api.listLocalUsers).mockRejectedValueOnce(new ApiError('forbidden',{status:403,code:'ATT-AUTH-CSRF-INVALID'}));
    render();await flush();expect(host?.textContent).toContain('Request verification failed. Refresh the page and try again.');
    vi.mocked(api.listLocalUsers).mockResolvedValue({items:initialUsers});
    act(()=>(([...host!.querySelectorAll('button')].find(button=>button.textContent==='Retry') as HTMLButtonElement|undefined)?.click()));await flush();
    const disable=[...host!.querySelectorAll('button')].find(button=>button.getAttribute('aria-label')==='Disable: jane')!;act(()=>disable.click());
    vi.mocked(api.setLocalUserEnabled).mockRejectedValueOnce(new ApiError('last admin',{status:409,code:'ATT-AUTH-LAST-ADMIN'}));
    await act(async()=>{([...host!.querySelectorAll('dialog button')].find(button=>button.textContent==='Confirm') as HTMLButtonElement|undefined)?.click();await Promise.resolve();});
    expect(host?.textContent).toContain('The last active ADMIN cannot be disabled.');
  });
});
