'use strict';
/* 通用 UI 基础件:格式化 / toast / 模态框 / busy / 剪贴板 / 明暗主题(无业务状态) */
/* 主题:首帧由 index.html 头部内联脚本定下(合法值/日夜归属/取默认逻辑两处同步);这里管选择弹层。
   R6:日间/夜间分组各记一个默认(spThemeDay/spThemeNight,由该组内最近一次手选决定);
   spTheme=当前手动选择,清掉即"跟随系统"——系统切明暗时落到对应组的默认上,而不是写死的图纸对 */
const THEMES = [
  {id:'light',          label:'图纸 · 米纸',     mode:'day',   swatch:['#e9e2cf','#f4eee0','#8a6420']},
  {id:'aero-day',       label:'航空学 · 晴空',   mode:'day',   swatch:['#5297d2','#f2f7fd','#2c5fa8']},
  {id:'dark',           label:'图纸 · 夜炭',     mode:'night', swatch:['#131313','#1c1c1d','#d0a354']},
  {id:'aero-ink',       label:'航空学 · 墨夜',   mode:'night', swatch:['#0e0f14','#17181f','#acb9ec']},
  {id:'aero-star',      label:'航空学 · 星夜',   mode:'night', swatch:['#121627','#1b2036','#9aa4ec']},
  {id:'aero-dusk',      label:'航空学 · 暮航',   mode:'night', swatch:['#141b34','#1e2138','#8f8ce8']},
  {id:'aero-blueprint', label:'航空学 · 夜间蓝图', mode:'night', swatch:['#14294a','#1b3358','#7dd4f8']},
];
function themeById(id){ return THEMES.find(x => x.id === id); }
/* 该模式的默认主题:存过且归属正确才认,否则回落图纸对 */
function themeDefault(mode){
  const stored = themeById(localStorage.getItem(mode === 'night' ? 'spThemeNight' : 'spThemeDay'));
  return stored && stored.mode === mode ? stored.id : (mode === 'night' ? 'dark' : 'light');
}
function systemTheme(){
  return themeDefault(themeMedia && themeMedia.matches ? 'night' : 'day');
}
function applyTheme(mode){ document.documentElement.dataset.theme = mode; }
function setTheme(id){
  const theme = themeById(id);
  if (theme) {
    localStorage.setItem('spTheme', id);
    localStorage.setItem(theme.mode === 'night' ? 'spThemeNight' : 'spThemeDay', id);
    applyTheme(id);
  } else {
    // 跟随系统:清掉手动选择,立刻按系统明暗落到对应默认
    localStorage.removeItem('spTheme');
    applyTheme(systemTheme());
  }
  const pop = document.getElementById('themePop');
  if (pop) { renderThemePop(pop); pop.style.display = 'none'; }
}
function renderThemePop(pop){
  const current = document.documentElement.dataset.theme;
  const auto = !localStorage.getItem('spTheme');
  const item = theme => `
    <button class="themeItem ${!auto && theme.id === current ? 'on' : ''}" onclick="setTheme('${theme.id}')">
      <span class="sw"><i style="background:${theme.swatch[0]}"></i><i style="background:${theme.swatch[1]}"></i><i style="background:${theme.swatch[2]}"></i></span>
      ${theme.label}
      <span class="tRight">${themeDefault(theme.mode) === theme.id ? '<span class="def">默认</span>' : ''}${!auto && theme.id === current ? '<span class="chk">✓</span>' : ''}</span>
    </button>`;
  pop.innerHTML = `
    <button class="themeItem ${auto ? 'on' : ''}" onclick="setTheme('')">
      <span class="swAuto">◐</span>跟随系统<span class="tRight">${auto ? '<span class="chk">✓</span>' : ''}</span>
    </button>
    <div class="themeSec">日间</div>` + THEMES.filter(x => x.mode === 'day').map(item).join('') +
    `<div class="themeSec">夜间</div>` + THEMES.filter(x => x.mode === 'night').map(item).join('');
}
function toggleThemePop(){
  const pop = document.getElementById('themePop');
  if (!pop) return;
  const open = pop.style.display === 'block';
  if (!open) renderThemePop(pop);
  pop.style.display = open ? 'none' : 'block';
}
document.addEventListener('click', e => {
  const pop = document.getElementById('themePop');
  if (pop && pop.style.display === 'block' && !e.target.closest('#themeWrap')) pop.style.display = 'none';
});
/* 测试沙箱没有 matchMedia(被兜成 noop 返回 undefined),不设防会让整个 bundle 加载失败 */
const themeMedia = typeof matchMedia === 'function' ? matchMedia('(prefers-color-scheme: dark)') : null;
if (themeMedia && themeMedia.addEventListener) themeMedia.addEventListener('change', () => {
  if (!localStorage.getItem('spTheme')) applyTheme(systemTheme());
});
function toast(msg, cls) {
  const box = document.getElementById('toasts');
  const d = document.createElement('div');
  d.className = 'toast' + (cls ? ' ' + cls : '');
  d.textContent = msg;
  box.appendChild(d);
  setTimeout(() => { d.style.opacity = '0'; d.style.transition = 'opacity .3s'; setTimeout(()=>d.remove(), 350); }, 4200);
}
function esc(s){ return s ? String(s).replace(/[<>&"]/g, c=>({'<':'&lt;','>':'&gt;','&':'&amp;','"':'&quot;'}[c])) : ''; }
/* 勾选值收集:物理体筛选/回收站筛选/一致性弹层三处同款 */
function checkedValues(cls){ return [...document.querySelectorAll('.'+cls+':checked')].map(x=>x.value); }
/* 组 id → 确定性色相:占位立方体按组着色,同组永远同色 */
function hueOf(id){
  let hue = 0;
  for (let i = 0; i < id.length; i++) hue = (hue * 31 + id.charCodeAt(i)) % 360;
  return hue;
}
/* 全选/取消全选的就地翻转骨架:物理体(组展开成员)与回收站(按组)共用。
   items()=当前可全选的条目;keys(it)=该条目占用的选择键;sel()=选择集(切服会整个换新,必须现取) */
function makeSelectAll({items, keys, sel, after}){
  const all = () => { const list = items(); return list.length > 0 && list.every(it => keys(it).every(k => sel().has(k))); };
  return {
    all,
    label(){ return (all() ? T.deselectAll : T.selectAll)(items().length); },
    toggle(){
      const on = all();
      for (const it of items()) for (const k of keys(it)) on ? sel().delete(k) : sel().add(k);
      after();
    },
  };
}
let modalCb = null;
/* 「永不提醒」勾选态在关框瞬间抓走:调用方在 await 之后才读,不能指望期间没有第二次弹框 */
let modalNeverChecked = false;
/* 确认框也属于服务器上下文。调用方普遍是"先把 uuid/组 id 攒进闭包,再 await 确认"
   (doDeleteSelected、confirmRestore、confirmPurge、doDelete……),而 api() 是在确认之后
   才按当时的 CURSRV 拼 server= 参数 —— 中途切了服,攒着的就是旧服的 uuid,发到新服上执行。
   两服由同一份存档复制而来时(同机多服的常见做法)uuid 还会真的命中,那就是误删。 */
function askModal(title, msg, needInput, expected, opts = {}){
  const ctx = captureCtx();
  return new Promise(res => {
    document.getElementById('modalTitle').textContent = title;
    document.getElementById('modalMsg').textContent = msg;
    const inp = document.getElementById('modalInput');
    inp.style.display = needInput ? 'block' : 'none';
    inp.value = '';
    const check = document.getElementById('modalNeverInput');
    check.checked = false;
    document.getElementById('modalNever').style.display = opts.never ? 'flex' : 'none';
    document.getElementById('modalCancelBtn').textContent = opts.cancelLabel || T.cancel;
    document.getElementById('modalConfirmBtn').textContent = opts.okLabel || T.confirm;
    document.getElementById('modalBack').showModal();
    if (needInput) setTimeout(()=>inp.focus(), 50);
    modalCb = (ok) => {
      // 用过就作废:不清的话,后面任何一次 modalCancel() 都会再打到这个已经结束的框上
      modalCb = null;
      modalNeverChecked = check.checked;
      document.getElementById('modalBack').close();
      if (!ctx.fresh()) return res(false);
      if (!ok) return res(false);
      if (needInput && expected !== undefined && inp.value.trim() !== String(expected)) {
        toast(T.confirmMismatch, 'bad');
        return res(false);
      }
      res(true);
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
    const change = await askModal(T.defaultTokenT, T.defaultTokenMsg, false, undefined,
      {never: true, okLabel: T.changeNow, cancelLabel: T.later});
    // 持久化语义归本调用方;勾选态用 askModal 关框瞬间抓走的快照,不读活 DOM
    if (modalNeverChecked) localStorage.setItem(DEFAULT_TOKEN_WARNING_KEY, '1');
    if (change && ctx.authFresh()) doChangeToken();
  }, 0);
}
function modalConfirm(){ modalCb && modalCb(true); }
function modalCancel(){ modalCb && modalCb(false); }
document.getElementById('modalInput').addEventListener('keydown', e => { if (e.key === 'Enter') modalConfirm(); });
/* ESC 走原生 <dialog> 的 cancel:拦下默认关闭,统一从 modalCancel 出去(要 resolve 挂着的 Promise) */
document.getElementById('modalBack').addEventListener('cancel', e => { e.preventDefault(); modalCancel(); });
function busy(text){ document.getElementById('busyText').textContent = text || ''; document.getElementById('busy').style.display = text ? 'flex' : 'none'; }
/* 加载失败/过期提示的统一文案与载体:hasOld=true 表示还留着上一份数据("上次的结果"),
   false 表示没有可展示的旧值("加载失败")。载体统一为 class:stale + title */
function staleLabel(err, hasOld){ return err ? (hasOld ? T.staleData : T.loadFail) + err : ''; }
function staleMark(el, status){ if (el) { el.classList.toggle('stale', !!status); el.title = status || ''; } }
function copyText(s){
  (navigator.clipboard ? navigator.clipboard.writeText(s) : Promise.reject()).then(
    ()=>toast(T.copied,'ok'),
    ()=>{ const ta=document.createElement('textarea'); ta.value=s; document.body.appendChild(ta); ta.select(); document.execCommand('copy'); ta.remove(); toast(T.copied,'ok'); });
}
