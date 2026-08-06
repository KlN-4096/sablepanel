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
  // 请求发出时的凭据。401 回来时得先确认它说的还是这一套 —— 见下面
  const sent = token, seq = authSeq, gen = srvGen();
  const options = {...(opts || {}), headers:{...((opts && opts.headers) || {}), 'X-Token':token}};
  const r = await fetch(path + srv, options);
  if (!r.ok) {
    const e = (await r.json().catch(()=>({}))).error || r.status;
    // 重新登录、改口令、切服之后,旧请求晚到的 401 说的是上一套凭据。拿它注销会把刚登录成功的
    // 人直接踢回登录页,而且顺手清掉 token 和 JOB_WATCH —— 后台作业还在跑,用户却以为没执行
    if (r.status === 401 && sent === token && seq === authSeq && gen === srvGen()) {
      showLogin(t('loginChanged'));
    }
    throw new Error(e);
  }
  return r.json();
}

function showLogin(message){
  // 认证代次就是一份新的服务器上下文:弹层先按旧上下文收掉,随后统一归零。
  // 只停轮询/请求还不够,否则登录门再次解锁到新快照回来之间会露出上一会话的数据。
  closeServerModals();
  stopEventStream();
  authSeq++;               // 作废在途的登录尝试,别让它稍后又把界面解锁
  authenticated = false;
  token = '';
  defaultTokenWarningShown = false;
  localStorage.removeItem(TOKEN_STORAGE_KEY);
  document.body.classList.add('locked');
  busy(null);              // 旧配置写入卡住时,忙碌层不能盖住登录门
  resetServerContext();
  const input = document.getElementById('loginToken');
  input.value = '';
  document.getElementById('loginError').textContent = message || '';
  setTimeout(()=>input.focus(), 30);
}

function applyServersResponse(r){
  SERVERS = r.servers || [];
  SERVERS_ERROR = '';
  if (CURSRV && !SERVERS.some(s => s.id === CURSRV)) CURSRV = '';
  renderServerPicker(r.self);
  if (VIEW === 'dash') renderDashServer();
}

/* 登录代次:只有最后一次尝试可以写 token / 认证状态 / 界面。
   从前没有它:输对口令 → 短暂成功 → 上一次输错的旧请求晚一步失败 → showLogin 把人踢回登录页,
   而且 token 已经被清掉了。自动登录(记住的 token)和手动登录共用这个计数器,互相也不会盖。 */
let authSeq = 0;
async function authenticate(candidate, remembered){
  const value = String(candidate || '').trim();
  if (!value) { showLogin(''); return false; }
  const seq = ++authSeq;
  try {
    if (gatewayMode === 'client') await connectGateway();
    if (seq !== authSeq) return false;
    const r = await fetch('/api/servers', {headers:{'X-Token':value}});
    if (seq !== authSeq) return false;
    if (!r.ok) throw new Error('unauthorized');
    const data = await r.json();
    if (seq !== authSeq) return false;
    token = value;
    authenticated = true;
    localStorage.setItem(TOKEN_STORAGE_KEY, token);
    document.getElementById('loginError').textContent = '';
    document.body.classList.remove('locked');
    applyServersResponse(data);
    maybeWarnDefaultToken(data);
    await loadAll(true);
    if (seq !== authSeq) return false;
    startEventStream();
    scheduleStartupConsistency();
    return true;
  } catch (e) {
    // 旧尝试的失败不得清 token、不得重新锁页 —— 现在的状态属于更新的那次提交
    if (seq !== authSeq) return false;
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
  closeServerModals();      // 要在改 CURSRV 之前
  SERVERS = []; SERVERS_ERROR = ''; CURSRV = '';
  localStorage.removeItem('spServer');
  // showLogin 会走完整的 resetServerContext；这里不再重复清理和重画一次。
  renderServerPicker('');
  document.getElementById('gatewayDisconnect').style.display = 'none';
  showLogin('');
}

function loginSubmit(e){
  e.preventDefault();
  authenticate(document.getElementById('loginToken').value, false);
}
