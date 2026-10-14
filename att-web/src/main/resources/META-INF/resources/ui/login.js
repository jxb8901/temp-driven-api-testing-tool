(() => {
  'use strict';
  const api = new URL('../api/v1/', document.baseURI).pathname;
  const error = document.getElementById('error');
  const login = document.getElementById('login-form');
  const bootstrap = document.getElementById('bootstrap-form');
  const params = new URLSearchParams(location.search);
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
