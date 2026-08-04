'use strict';
/* 数据访问层:token 鉴权、fetch 封装(自动带集群 server 参数)、登录流程 */
/* ===================== 基础设施 ===================== */
const TOKEN_STORAGE_KEY = 'spToken';
const DEFAULT_TOKEN_WARNING_KEY = 'spDefaultTokenWarningDisabled';
let token = localStorage.getItem(TOKEN_STORAGE_KEY) || '';
let authenticated = false, defaultTokenWarningShown = false;
let gatewayMode = 'server', gatewayConnected = false;
const initialUrl = new URL(location.href);
if (initialUrl.searchParams.has('token')) {
  initialUrl.searchParams.delete('token');
  history.replaceState(null, '', initialUrl);
}
async function api(path, opts) {
  const sep = path.includes('?') ? '&' : '?';
  const srv = CURSRV ? sep + 'server=' + encodeURIComponent(CURSRV) : '';
  const options = {...(opts || {}), headers:{...((opts && opts.headers) || {}), 'X-Token':token}};
  const r = await fetch(path + srv, options);
  if (!r.ok) {
    const e = (await r.json().catch(()=>({}))).error || r.status;
    if (r.status === 401) showLogin(t('loginChanged'));
    throw new Error(e);
  }
  return r.json();
}

function showLogin(message){
  authenticated = false;
  token = '';
  localStorage.removeItem(TOKEN_STORAGE_KEY);
  document.body.classList.add('locked');
  const input = document.getElementById('loginToken');
  input.value = '';
  document.getElementById('loginError').textContent = message || '';
  setTimeout(()=>input.focus(), 30);
}

function applyServersResponse(r){
  SERVERS = r.servers || [];
  if (CURSRV && !SERVERS.some(s => s.id === CURSRV)) CURSRV = '';
  renderServerPicker(r.self);
  if (VIEW === 'dash') renderDashServer();
}

async function authenticate(candidate, remembered){
  const value = String(candidate || '').trim();
  if (!value) { showLogin(''); return false; }
  try {
    if (gatewayMode === 'client') await connectGateway();
    const r = await fetch('/api/servers', {headers:{'X-Token':value}});
    if (!r.ok) throw new Error('unauthorized');
    const data = await r.json();
    token = value;
    authenticated = true;
    localStorage.setItem(TOKEN_STORAGE_KEY, token);
    document.getElementById('loginError').textContent = '';
    document.body.classList.remove('locked');
    applyServersResponse(data);
    maybeWarnDefaultToken(data);
    await loadAll(true);
    return true;
  } catch (e) {
    showLogin(remembered ? t('loginChanged') : t('loginBad'));
    return false;
  }
}

async function loadGatewayState(){
  const response = await fetch('/gateway/state');
  if (!response.ok) throw new Error('gateway unavailable');
  const state = await response.json();
  gatewayMode = state.mode || 'server';
  gatewayConnected = !!state.connected;
  const clientMode = gatewayMode === 'client';
  document.getElementById('loginAddressWrap').style.display = clientMode ? 'block' : 'none';
  document.getElementById('gatewayDisconnect').style.display = clientMode && gatewayConnected ? 'inline-flex' : 'none';
  if (clientMode && !document.getElementById('loginAddress').value)
    document.getElementById('loginAddress').value = state.address || localStorage.getItem('spAddress') || '';
  return state;
}

async function connectGateway(){
  const address = document.getElementById('loginAddress').value.trim();
  if (!address) throw new Error(t('loginBad'));
  let response = await fetch('/gateway/connect', {
    method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({address})
  });
  let result = await response.json().catch(()=>({}));
  if (response.status === 409 && result.fingerprint) {
    if (!window.confirm(t('certConfirm')(result.fingerprint))) throw new Error(t('loginBad'));
    response = await fetch('/gateway/connect', {
      method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({
        address, accept_fingerprint:result.fingerprint
      })
    });
    result = await response.json().catch(()=>({}));
  }
  if (!response.ok) throw new Error(result.error || response.status);
  gatewayConnected = true;
  localStorage.setItem('spAddress', address);
  document.getElementById('gatewayDisconnect').style.display = 'inline-flex';
}

async function disconnectGateway(){
  if (gatewayMode !== 'client') return;
  await fetch('/gateway/disconnect', {
    method:'POST', headers:{'Content-Type':'application/json'}, body:'{}'
  }).catch(()=>{});
  gatewayConnected = false;
  DATA = STATS = RECYCLE = null;
  SERVERS = []; CURSRV = '';
  localStorage.removeItem('spServer');
  document.getElementById('gatewayDisconnect').style.display = 'none';
  showLogin('');
}

function loginSubmit(e){
  e.preventDefault();
  authenticate(document.getElementById('loginToken').value, false);
}
