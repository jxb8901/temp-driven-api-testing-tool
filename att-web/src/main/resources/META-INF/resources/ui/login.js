(() => {
  'use strict';
  const api = new URL('../api/v1/', document.baseURI).pathname;
  const error = document.getElementById('error');
  const login = document.getElementById('login-form');
  const bootstrap = document.getElementById('bootstrap-form');
  const params = new URLSearchParams(location.search);
  const notice = document.getElementById('notice');
  let preferredLanguage = '';
  try { preferredLanguage = localStorage.getItem('att-ui-language') || ''; } catch { /* Storage may be blocked. */ }
  const isTraditionalChinese = preferredLanguage === 'zh-Hant'
    || (preferredLanguage !== 'en' && [...(navigator.languages || []), navigator.language].some(language => /^zh-(?:Hant|TW|HK|MO)(?:-|$)/i.test(language)));
  const messages = {
    'password-changed': isTraditionalChinese ? '密碼已變更，請使用新密碼重新登入。' : 'Password changed. Sign in again with your new password.',
    'password-reset': isTraditionalChinese ? '密碼已重設，請使用新密碼重新登入。' : 'Password reset. Sign in again with your new password.',
    'account-disabled': isTraditionalChinese ? '此帳戶已停用，無法再登入。' : 'This account has been disabled and can no longer sign in.',
  };
  notice.textContent = messages[params.get('message')] || '';
  if (isTraditionalChinese) {
    document.documentElement.lang = 'zh-Hant';
    document.title = '登入 · ATT Server';
    document.getElementById('intro').textContent = '登入以繼續。';
    const labels = [...document.querySelectorAll('label')];
    const chineseLabels = ['登入 ID', '密碼', '一次性設定權杖', '管理員登入 ID', '顯示名稱', '密碼（至少 12 個字元）'];
    labels.forEach((label, index) => { if (label.firstChild?.nodeType === Node.TEXT_NODE) label.firstChild.textContent = chineseLabels[index] || label.textContent; });
    document.querySelector('#login-form button').textContent = '登入';
    document.querySelector('#bootstrap-form button').textContent = '建立管理員';
  }
  const appRoot = new URL('../', document.baseURI);
  const uiRoot = new URL('ui/', appRoot).pathname;
  const fallback = new URL('ui/', appRoot).toString();
  function safeReturnTo(candidate) {
    if (typeof candidate !== 'string' || !candidate.startsWith('/') || candidate.startsWith('//')
        || candidate.length > 2048 || candidate.includes('\\') || /[\u0000-\u001f\u007f]/.test(candidate)) return fallback;
    const path = candidate.split(/[?#]/, 1)[0];
    const encodedPathSeparatorOrDot = /%(?:25)*(?:2e|2f|5c|00)/i;
    if (encodedPathSeparatorOrDot.test(path)) return fallback;
    let decoded = path;
    let fullyDecoded = false;
    for (let pass = 0; pass < 8; pass++) {
      let next;
      try { next = decodeURIComponent(decoded); } catch { return fallback; }
      if (next === decoded) { fullyDecoded = true; break; }
      decoded = next;
    }
    if (!fullyDecoded || encodedPathSeparatorOrDot.test(decoded)
        || decoded.includes('\\') || /[\u0000-\u001f\u007f]/.test(decoded)
        || !decoded.startsWith('/ui/') || decoded.split('/').some(segment => segment === '.' || segment === '..')) return fallback;
    try {
      const target = new URL(candidate.slice(1), appRoot);
      if (target.origin !== location.origin || !target.pathname.startsWith(uiRoot)) return fallback;
      return target.toString();
    } catch { return fallback; }
  }
  const returnTo = safeReturnTo(params.get('returnTo') || '/ui/');
  async function post(path, payload) {
    const response = await fetch(api + path, {method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
    const data = await response.json();
    if (!response.ok) throw new Error(data.error?.summary || 'Authentication failed.');
    return data;
  }
  fetch(api + 'auth/bootstrap/status', {credentials:'same-origin'}).then(r=>r.json()).then(data=>{
    if (data.required) { login.hidden=true; bootstrap.hidden=false; document.getElementById('intro').textContent=isTraditionalChinese?'使用 Server 資料目錄中的一次性權杖建立第一位管理員。':'Create the first administrator using the one-time token in the Server data directory.'; }
  }).catch(()=>{error.textContent=isTraditionalChinese?'無法連線至 ATT Server。':'Unable to contact ATT Server.';});
  login.addEventListener('submit', async event=>{event.preventDefault();error.textContent='';const data=Object.fromEntries(new FormData(login));try{await post('auth/login',data);location.assign(returnTo);}catch(e){error.textContent=e.message;}});
  bootstrap.addEventListener('submit', async event=>{event.preventDefault();error.textContent='';const data=Object.fromEntries(new FormData(bootstrap));try{await post('auth/bootstrap',data);document.getElementById('intro').textContent=isTraditionalChinese?'管理員已建立。請使用新帳戶登入。':'Administrator created. Sign in with the new account.';bootstrap.hidden=true;login.hidden=false;login.elements.loginId.value=data.loginId;}catch(e){error.textContent=e.message;}});
})();
