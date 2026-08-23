'use strict';
/* 页面适配层:预览器官的生命周期、业务来源和旧视图函数名。模型/纹理/LOD 不在此处实现。 */
let previewRuntime = null;
let fsMode = false;
let previewRequestSeq = 0, previewAbort = null;
let autoRotate = localStorage.getItem('spRot') !== '0';

/* previewWrap 挪宿主(视图切换/副本弹层开合共用):画布节点常驻复用,
   搬完把 renderer 的 canvas 拽回容器首位 —— 三处此前各抄一份 */
function movePreviewTo(hostId){
  hideTip();
  const preview = document.getElementById('previewWrap');
  const host = document.getElementById(hostId);
  if (host && preview.parentElement !== host) host.appendChild(preview);
  const tip = document.getElementById('hoverTip');
  const layer = host && host.closest ? host.closest('dialog') : null;
  const tipOwner = layer || document.body;
  if (tip && tip.parentElement !== tipOwner && typeof tipOwner.appendChild === 'function') tipOwner.appendChild(tip);
  const canvas = previewRuntime && previewRuntime.renderer && previewRuntime.renderer.domElement;
  if (canvas && canvas.parentElement !== preview) preview.insertBefore(canvas, preview.firstChild);
}

function initGL() {
  const host = document.getElementById('previewWrap');
  if (!host || typeof THREE === 'undefined' || typeof SablePreviewRuntime === 'undefined') return;
  previewRuntime = new SablePreviewRuntime({
    host,
    onRotate: value => { autoRotate = value; updateRotateUi(); },
    onStatus: (status, detail) => updatePreviewStatus(status, detail),
    onHover: (index, pointer, reason) => showPreviewHover(index, pointer, reason),
    onPointerMove: pointer => moveHoverTip(pointer)
  }).init();
  updateRotateUi();
  const speed = document.getElementById('rotSpeed');
  if (speed) speed.value = Math.round(parseFloat(localStorage.getItem('spRotSpeed') || '0.18') / 1.5 * 100);
}

function updateRotateUi() {
  const button = document.getElementById('rotBtn'); if (button) button.style.color = autoRotate ? 'var(--acc)' : 'var(--dim)';
}

function updatePreviewStatus(status, detail) {
  const info = document.getElementById('pvInfo'); if (!info) return;
  const progress = document.getElementById('pvProgress');
  const retry = document.getElementById('pvRetry');
  const versions = document.getElementById('pvVersions');
  const preparing = status === 'fallback' || status === 'resource_progress';
  if (progress) progress.style.display = preparing ? 'block' : 'none';
  if (retry) retry.style.display = status === 'resource_failed' ? 'inline-flex' : 'none';
  // 版本不明确是唯一有明确出路的失败:去重面板能逐版本预览并选定
  if (versions) versions.style.display = status === 'failed' && detail === 'preview_version_ambiguous'
    && typeof SEL !== 'undefined' && SEL ? 'inline-flex' : 'none';
  if (status === 'fallback' && progress) {
    const bar = progress.querySelector('i');
    if (bar) { bar.style.animation = ''; bar.style.transform = ''; bar.style.width = ''; }
  }
  if (status === 'empty') info.textContent = T.pvNone;
  else if (status === 'fallback') info.textContent = MESH_DATA ? T.pvStat(MESH_DATA.shell, MESH_DATA.total) : T.pvLoad;
  else if (status === 'resource_progress') {
    const value = detail || {}, source = value.source ? T.pvProgressSource(value.source) + ' · ' : '';
    const known = Number.isFinite(value.downloaded) && value.downloaded >= 0
      && Number.isFinite(value.total) && value.total > 0;
    info.textContent = source + (known ? `${fmtBytes(value.downloaded)} / ${fmtBytes(value.total)}`
      : (value.detail ? T.pvProgressDetail(value.detail) : T.pvPrepare));
    const bar = progress && progress.querySelector('i');
    if (bar) {
      if (known) {
        bar.style.animation = 'none'; bar.style.transform = 'none';
        bar.style.width = Math.max(0, Math.min(100, value.downloaded / value.total * 100)) + '%';
      } else {
        bar.style.animation = ''; bar.style.transform = ''; bar.style.width = '';
      }
    }
  }
  else if (status === 'high') {
    const stats = detail || {};
    info.textContent = (MESH_DATA ? T.pvStat(MESH_DATA.shell, MESH_DATA.total) : '')
      + ` · ${T.pvHigh(stats.highStates || 0, stats.simplifiedStates || 0)}`;
  }
  else if (status === 'lod') info.textContent = (MESH_DATA ? T.pvStat(MESH_DATA.shell, MESH_DATA.total) : '') + ` · ${T.pvLod((detail && detail.count) || 0)}`;
  else if (status === 'resource_failed') info.textContent = (MESH_DATA ? T.pvStat(MESH_DATA.shell, MESH_DATA.total) : '') + ` · ${T.pvResourceFallback}`;
  else if (status === 'resource_unavailable') info.textContent = (MESH_DATA ? T.pvStat(MESH_DATA.shell, MESH_DATA.total) : '') + ` · ${T.pvBasicOnly}`;
  else if (status === 'unsupported') info.textContent = T.pvUnsupported(detail === '需要 WebGL2' ? T.pvNeedWebgl2 : detail);
  else if (status === 'too_large') info.textContent = T.pvTooLargeStats;
  else if (status === 'failed') info.textContent = T.pvFail + T.pvError(detail);
}

function showPreviewHover(index, pointer, reason) {
  const tip = document.getElementById('hoverTip');
  if (index === null || !MESH_DATA || !tip) { if (tip) tip.style.display = 'none'; return; }
  const offset = index * 4, stateIndex = MESH_DATA.voxels[offset + 3], palette = MESH_DATA.palette[stateIndex] || {};
  const x = MESH_DATA.voxels[offset], y = MESH_DATA.voxels[offset + 1], z = MESH_DATA.voxels[offset + 2];
  const origin = MESH_DATA.metadata || {};
  const px = (origin.plot_x || 0) + (origin.origin_x || 0) + x;
  const py = (origin.origin_y || 0) + y;
  const pz = (origin.plot_z || 0) + (origin.origin_z || 0) + z;
  const state = palette.state || palette.id || '?';
  const simplified = reason ? T.pvSimplified(reason) : '';
  tip.innerHTML = `<b>${esc((LANG === 'zh' && palette.zh) || palette.id || '?')}</b><div class="bid">${esc(state)} · (${x}, ${y}, ${z}) · plot (${px}, ${py}, ${pz})${simplified}</div>`;
  tip.style.display = 'block';
  placeTip(tip, pointer && pointer.cx || 0, pointer && pointer.cy || 0);
  if (fsMode) {
    const hover = document.getElementById('fsHover');
    if (hover) hover.innerHTML = `<b style="color:var(--fg)">${esc((LANG === 'zh' && palette.zh) || palette.id || '?')}</b> <span class="mono" style="font-size:10.5px">${esc(state)}</span> · (${x}, ${y}, ${z}) · plot (${px}, ${py}, ${pz})${simplified}`;
  }
}

function hideTip() { const tip = document.getElementById('hoverTip'); if (tip) tip.style.display = 'none'; }

/* html{zoom:1.2} 下 fixed 定位坐标系被放大,而 clientX/Y 是未缩放的视觉像素:
   直接把 clientX 写进 left,框会越往右下偏得越远("提示不跟手"的真正病根)。
   统一除以实际 zoom;不支持 zoom 的浏览器 computedStyle 给不出数,按 1 处理。 */
function placeTip(tip, cx, cy) {
  const zoom = parseFloat(getComputedStyle(document.documentElement).zoom) || 1;
  tip.style.left = Math.min(cx / zoom + 14, innerWidth / zoom - 300) + 'px';
  tip.style.top = (cy / zoom + 14) + 'px';
}

/* 提示框跟手:拾取(内容更新)有 60ms 节流,位置不能跟着等 —— 每次鼠标移动都直接挪框 */
function moveHoverTip(pointer) {
  const tip = document.getElementById('hoverTip');
  if (!tip || tip.style.display !== 'block') return;
  placeTip(tip, pointer.cx || 0, pointer.cy || 0);
}

function toggleRotate() { if (previewRuntime) previewRuntime.toggleRotate(); else { autoRotate = !autoRotate; updateRotateUi(); } }
function setRotSpeed(value) { if (previewRuntime) previewRuntime.setRotSpeed(value); }
async function retryPreviewResources() {
  const context = captureCtx();
  try {
    await api('/api/preview/resources/retry', {method:'POST', body:'{}', headers:{'Content-Type':'application/json'}});
    if (!context.fresh()) return;
    if (MESH_SOURCE && MESH_UUID) {
      if (MESH_SOURCE === 'body') loadMesh(MESH_UUID);
      else if (MESH_SOURCE.startsWith('recycle:') && RSELG) loadRecycleMesh(RSELG.id, MESH_UUID);
      else if (MESH_SOURCE.startsWith('copy:') && COPY_VERSION) loadCopyVersionMesh(MESH_UUID, COPY_VERSION);
    }
  } catch (error) {
    if (context.fresh()) updatePreviewStatus('resource_failed', error.message || String(error));
  }
}
function resizeGL() { if (previewRuntime) previewRuntime.resize(); }

function disposeMesh() { if (previewRuntime) previewRuntime.disposeObjects(); }

async function loadMesh(uuid) {
  return loadMeshAt(`/api/body/${uuid}/mesh`, uuid, 'body', () => SEL && SEL.uuid === uuid);
}
async function loadRecycleMesh(groupId, uuid) {
  return loadMeshAt(`/api/recycle/${groupId}/body/${uuid}/mesh`, uuid, `recycle:${groupId}`,
    () => RSEL && RSELG && RSEL.uuid === uuid && RSELG.id === groupId);
}
async function loadCopyVersionMesh(uuid, versionId) {
  return loadMeshAt(`/api/body/${uuid}/copy/${versionId}/mesh`, uuid, `copy:${versionId}`,
    () => COPY_UUID === uuid && COPY_VERSION === versionId && document.getElementById('copyBack').open);
}

async function loadMeshAt(endpoint, uuid, source, isCurrent) {
  const seq = ++previewRequestSeq;
  if (previewAbort) previewAbort.abort();
  const controller = previewAbort = new AbortController();
  const context = captureCtx();
  const info = document.getElementById('pvInfo'); if (info) info.textContent = T.pvLoad;
  disposeMesh(); MESH_DATA = null; MESH_UUID = MESH_SOURCE = null; if (typeof renderComposition === 'function') renderComposition();
  try {
    let result;
    for (let attempt = 0; attempt < 120; attempt++) {
      if (!context.fresh() || seq !== previewRequestSeq || !isCurrent()) return;
      result = await SablePreviewTransport.request(endpoint, {signal:controller.signal});
      if (result.status === 'ready') break;
      if (result.status === 'too_large') { if (context.fresh() && seq === previewRequestSeq) updatePreviewStatus('too_large'); return; }
      await delay(Math.max(250, Math.min(5000, (result.retryAfter || 1) * 1000)), controller.signal);
    }
    if (!result || result.status !== 'ready') throw new Error('preview_timeout');
    if (!context.fresh() || seq !== previewRequestSeq || !isCurrent()) return;
    const parsed = result.mesh;
    MESH_DATA = legacyMeshData(parsed); MESH_UUID = uuid; MESH_SOURCE = source;
    const resourceMeta = parsed.metadata && parsed.metadata.resources;
    const resourceRequest = resourceMeta && resourceMeta.status !== 'unavailable' && resourceMeta.status !== 'failed'
      ? {manifestUrl: resourceMeta.manifest, token: typeof token !== 'undefined' ? token : '',
          server: typeof CURSRV !== 'undefined' ? CURSRV : ''} : null;
    if (previewRuntime) previewRuntime.load(parsed, resourceRequest);
    if (resourceMeta && resourceMeta.status === 'failed') updatePreviewStatus('resource_failed', '资源准备失败');
    if (typeof renderComposition === 'function') renderComposition();
  } catch (error) {
    if (error && error.name === 'AbortError') return;
    if (context.fresh() && seq === previewRequestSeq && isCurrent()) updatePreviewStatus('failed', error.message || String(error));
  }
}

function legacyMeshData(parsed) {
  let shell = 0;
  for (let i = 0; i < parsed.voxelCount; i++) if (parsed.isShell(i)) shell++;
  return {
    palette: parsed.metadata.states || [],
    voxels: parsed.records,
    shell,
    total: Number(parsed.metadata.voxel_count || parsed.voxelCount),
    truncated: false,
    metadata: parsed.metadata,
    spm2: parsed
  };
}

function delay(milliseconds, signal) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, milliseconds);
    if (signal) signal.addEventListener('abort', () => { clearTimeout(timer); reject(new DOMException('aborted', 'AbortError')); }, {once:true});
  });
}

function openPreviewFs() {
  const selected = VIEW === 'recycle' ? RSEL : SEL;
  if (!previewRuntime || !selected) return;
  fsMode = true;
  previewRuntime.openFullscreen(document.getElementById('fsOverlay'), document.getElementById('fsCanvasBox'),
    selected.name || selected.uuid.slice(0, 8), `${selected.uuid} · ${fmt(selected.blocks)} ${T.blocksUnit} · ${selected.dim}`);
  const hover = document.getElementById('fsHover'); if (hover) hover.textContent = '';
  // 成员抽屉整个搬进全屏层左侧(同一节点同一套事件,回来再搬回去);回收站全屏没有这份列表
  const members = document.getElementById('dMembers'), fsBox = document.getElementById('fsMembers');
  if (members && fsBox && VIEW === 'bodies') fsBox.appendChild(members);
  if (typeof renderComposition === 'function') renderComposition();
}

function closePreviewFs() {
  fsMode = false;
  if (previewRuntime) previewRuntime.closeFullscreen();
  const overlay = document.getElementById('fsOverlay'); if (overlay) overlay.style.display = 'none';
  // 抽屉搬回详情页网格原位(视口之前)
  const members = document.getElementById('dMembers');
  if (members && members.parentElement && members.parentElement.id === 'fsMembers') {
    const main = document.getElementById('dMain');
    if (main) main.insertBefore(members, document.getElementById('dViewport'));
  }
  hideTip();
}

onServerReset(() => {
  previewRequestSeq++;
  if (previewAbort) { previewAbort.abort(); previewAbort = null; }
  if (fsMode) closePreviewFs();
  disposeMesh(); MESH_DATA = MESH_UUID = MESH_SOURCE = null; hideTip();
  const info = document.getElementById('pvInfo'); if (info) info.textContent = '';
  const retry = document.getElementById('pvRetry'); if (retry) retry.style.display = 'none';
  const versions = document.getElementById('pvVersions'); if (versions) versions.style.display = 'none';
});

const fsOpen = document.getElementById('fsOpen'); if (fsOpen) fsOpen.addEventListener('click', openPreviewFs);
const fsClose = document.getElementById('fsClose'); if (fsClose) fsClose.addEventListener('click', closePreviewFs);
window.addEventListener('resize', resizeGL);
