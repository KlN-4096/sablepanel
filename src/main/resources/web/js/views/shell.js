'use strict';
/* 视图外壳:页签切换与渲染分发。
   从 main.js 摘出来是因为那个文件一加载就跑启动序列(发请求、起定时器),
   前端测试沙箱只能整份排除它 —— 于是 renderAll() 在测试里一直是个空操作,
   任何"界面上应该出现 X"的断言都恒真。分发逻辑正好是最需要被测的那部分。 */
/* ===================== 视图 ===================== */
function setView(v, opts){
  if (!['dash','bodies','recycle','jobs'].includes(v)) v = 'dash';
  VIEW = v;
  localStorage.setItem('spView', v);
  document.querySelectorAll('#nav button').forEach(b => b.classList.toggle('on', b.dataset.view === v));
  document.querySelectorAll('.view').forEach(s => s.classList.remove('on'));
  document.getElementById(v === 'dash' ? 'viewDash' : v === 'bodies' ? 'viewBodies'
    : v === 'jobs' ? 'viewJobs' : 'viewRecycle').classList.add('on');
  if (v === 'jobs') loadJobs();
  const previewHost = document.getElementById(v === 'recycle' ? 'recyclePreviewHost' : 'bodyPreviewHost');
  const preview = document.getElementById('previewWrap');
  if (!fsMode && preview.parentElement !== previewHost) previewHost.appendChild(preview);
  const selectedPreview = v==='recycle' ? RSEL : v==='bodies' ? SEL : null;
  const expectedSource = v==='recycle'&&RSELG ? `recycle:${RSELG.id}` : v==='bodies' ? 'body' : null;
  if ((v==='recycle'||v==='bodies') && (!selectedPreview||MESH_UUID!==selectedPreview.uuid||MESH_SOURCE!==expectedSource)) {
    disposeMesh(); MESH_DATA=null; MESH_UUID=MESH_SOURCE=null; document.getElementById('pvInfo').textContent='';
    if (selectedPreview) setTimeout(()=>v==='recycle'
      ?loadRecycleMesh(RSELG.id,selectedPreview.uuid):loadMesh(selectedPreview.uuid),30);
  }
  if (opts && opts.tab) { TAB = opts.tab; renderLimit = 400; }
  if (opts && opts.sortCost) { sortCfg = [{k:'cost',d:-1}]; saveSort(); renderSortRows(); }
  if (opts && opts.reset) resetFilters(true);
  renderAll();
  if (v === 'bodies' || v === 'recycle') setTimeout(resizeGL, 30);
  if (authenticated && (v === 'dash' || v === 'recycle') && RECYCLE === null) loadRecycle();
}
/* 分发必须是全函数:每个视图都要能从 (数据|null, 错误|'') 画出东西。
   从前这里挡着一句 if (!DATA) return —— 首屏加载失败时默认的总览页就是一片空白,
   用户只看到一闪而过的 toast,唯一的反应是继续刷新,把同样的压力再造一遍 */
function renderAll(){
  if (VIEW === 'recycle') { renderRecycle(); return; }
  if (VIEW === 'jobs') { renderJobs(); return; }
  if (VIEW === 'dash') { renderDash(); return; }
  renderTabs(); render();
}
