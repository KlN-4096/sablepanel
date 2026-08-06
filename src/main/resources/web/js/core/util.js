'use strict';
/* 通用 UI 基础件:格式化 / toast / 模态框 / busy / 剪贴板(无业务状态) */
function toast(msg, cls) {
  const box = document.getElementById('toasts');
  const d = document.createElement('div');
  d.className = 'toast' + (cls ? ' ' + cls : '');
  d.textContent = msg;
  box.appendChild(d);
  setTimeout(() => { d.style.opacity = '0'; d.style.transition = 'opacity .3s'; setTimeout(()=>d.remove(), 350); }, 4200);
}
function esc(s){ return s ? String(s).replace(/[<>&"]/g, c=>({'<':'&lt;','>':'&gt;','&':'&amp;','"':'&quot;'}[c])) : ''; }
let modalCb = null;
/* 确认框也属于服务器上下文。调用方普遍是"先把 uuid/组 id 攒进闭包,再 await 确认"
   (doDeleteSelected、confirmRestore、confirmPurge、doDelete……),而 api() 是在确认之后
   才按当时的 CURSRV 拼 server= 参数 —— 中途切了服,攒着的就是旧服的 uuid,发到新服上执行。
   两服由同一份存档复制而来时(同机多服的常见做法)uuid 还会真的命中,那就是误删。 */
function askModal(title, msg, needInput, expected){
  const ctx = captureCtx();
  return new Promise(res => {
    document.getElementById('modalTitle').textContent = title;
    document.getElementById('modalMsg').textContent = msg;
    const inp = document.getElementById('modalInput');
    inp.style.display = needInput ? 'block' : 'none';
    inp.value = '';
    document.getElementById('modalNever').style.display = 'none';
    document.getElementById('modalCancelBtn').textContent = t('cancel');
    document.getElementById('modalConfirmBtn').textContent = t('confirm');
    document.getElementById('modalBack').style.display = 'flex';
    if (needInput) setTimeout(()=>inp.focus(), 50);
    modalCb = (ok) => {
      // 用过就作废:不清的话,后面任何一次 modalCancel() 都会再打到这个已经结束的框上
      modalCb = null;
      document.getElementById('modalBack').style.display = 'none';
      if (!ctx.fresh()) return res(false);
      if (!ok) return res(false);
      if (needInput && expected !== undefined && inp.value.trim() !== String(expected)) {
        toast(t('confirmMismatch'), 'bad');
        return res(false);
      }
      res(true);
    };
  });
}
function askDefaultTokenWarning(){
  return new Promise(res => {
    document.getElementById('modalTitle').textContent = t('defaultTokenT');
    document.getElementById('modalMsg').textContent = t('defaultTokenMsg');
    document.getElementById('modalInput').style.display = 'none';
    const never = document.getElementById('modalNever');
    const check = document.getElementById('modalNeverInput');
    check.checked = false;
    never.style.display = 'flex';
    document.getElementById('modalCancelBtn').textContent = t('later');
    document.getElementById('modalConfirmBtn').textContent = t('changeNow');
    document.getElementById('modalBack').style.display = 'flex';
    modalCb = (ok) => {
      modalCb = null;
      document.getElementById('modalBack').style.display = 'none';
      if (check.checked) localStorage.setItem(DEFAULT_TOKEN_WARNING_KEY, '1');
      res(ok);
    };
  });
}
function maybeWarnDefaultToken(serverData){
  if (!serverData.using_default_token || defaultTokenWarningShown
      || localStorage.getItem(DEFAULT_TOKEN_WARNING_KEY) === '1') return;
  defaultTokenWarningShown = true;
  const ctx = captureCtx();
  setTimeout(async()=>{
    if (!ctx.authFresh()) return;
    const change = await askDefaultTokenWarning();
    if (change && ctx.authFresh()) doChangeToken();
  }, 0);
}
function modalConfirm(){ modalCb && modalCb(true); }
function modalCancel(){ modalCb && modalCb(false); }
document.getElementById('modalInput').addEventListener('keydown', e => { if (e.key === 'Enter') modalConfirm(); });
function busy(text){ document.getElementById('busyText').textContent = text || ''; document.getElementById('busy').style.display = text ? 'flex' : 'none'; }
function copyText(s){
  (navigator.clipboard ? navigator.clipboard.writeText(s) : Promise.reject()).then(
    ()=>toast(t('copied'),'ok'),
    ()=>{ const ta=document.createElement('textarea'); ta.value=s; document.body.appendChild(ta); ta.select(); document.execCommand('copy'); ta.remove(); toast(t('copied'),'ok'); });
}
