(() => {
  'use strict';
  const api = new URL('../api/v1/', document.baseURI).pathname;
  const error = document.getElementById('error');
  const login = document.getElementById('login-form');
  const bootstrap = document.getElementById('bootstrap-form');
  const params = new URLSearchParams(location.search);
  const notice = document.getElementById('notice');
  const isTraditionalChinese = [...(navigator.languages || []), navigator.language].some(language => /^zh-(?:Hant|TW|HK|MO)(?:-|$)/i.test(language));
  const messages = {
    'password-changed': isTraditionalChinese ? '密碼已變更，請使用新密碼重新登入。' : 'Password changed. Sign in again with your new password.',
    'password-reset': isTraditionalChinese ? '密碼已重設，請使用新密碼重新登入。' : 'Password reset. Sign in again with your new password.',
    'account-disabled': isTraditionalChinese ? '此帳戶已停用，無法再登入。' : 'This account has been disabled and can no longer sign in.',
  };
  notice.textContent = messages[params.get('message')] || '';
  if (isTraditionalChinese) document.documentElement.lang = 'zh-Hant';
  const candidate = params.get('returnTo') || '../ui/';
  const returnTo = candidate.startsWith('/') && !candidate.startsWith('//') && !candidate.includes('\\') ? candidate : '../ui/';
  async function post(path, payload) {
    const response = await fetch(api + path, {method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
    const data = await response.json();
    if (!response.ok) throw new Error(data.error?.summary || 'Authentication failed.');
    return data;
  }
  fetch(api + 'auth/bootstrap/status', {credentials:'same-origin'}).then(r=>r.json()).then(data=>{
    if (data.required) { login.hidden=true; bootstrap.hidden=false; document.getElementById('intro').textContent='Create the first administrator using the one-time token in the Server data directory.'; }
  }).catch(()=>{error.textContent='Unable to contact ATT Server.';});
  login.addEventListener('submit', async event=>{event.preventDefault();error.textContent='';const data=Object.fromEntries(new FormData(login));try{await post('auth/login',data);location.assign(returnTo);}catch(e){error.textContent=e.message;}});
  bootstrap.addEventListener('submit', async event=>{event.preventDefault();error.textContent='';const data=Object.fromEntries(new FormData(bootstrap));try{await post('auth/bootstrap',data);document.getElementById('intro').textContent='Administrator created. Sign in with the new account.';bootstrap.hidden=true;login.hidden=false;login.elements.loginId.value=data.loginId;}catch(e){error.textContent=e.message;}});
})();
