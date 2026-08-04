'use strict';
/* 通用 UI 基础件:格式化 / toast / 模态框 / busy / 剪贴板(无业务状态) */
/** 磁盘损坏跳过等后端非致命警告 → toast 尾注(最多展示 2 条) */
function warnText(result){
  const w=result&&result.warnings;
  if(!w||!w.length) return '';
  const shown=w.slice(0,2).join('; ');
  return ` · ⚠ ${shown}${w.length>2?` ${t('opWarnMore')(w.length-2)}`:''}`;
}
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
function askModal(title, msg, needInput, expected){
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
      document.getElementById('modalBack').style.display = 'none';
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
  setTimeout(async()=>{ if (await askDefaultTokenWarning()) doChangeToken(); }, 0);
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
