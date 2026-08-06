'use strict';
/* three.js 体素预览:场景/交互/网格加载/全屏模式 */
/* ===================== three.js 预览 ===================== */
let scene, camera, renderer, mesh, edgeLines, gridHelper;
let dragging=false, px=0, py=0, rotX=0.5, rotY=0.7, dist=50, center=[0,0,0];
let autoRotate = localStorage.getItem('spRot') !== '0';
let rotSpeed = parseFloat(localStorage.getItem('spRotSpeed') || '0.18');
let raycaster, pointer = {x:0,y:0}, hoverEnabled = true, needPick = false, lastPick = 0;
let fsMode = false, clock;

function initGL() {
  const box = document.getElementById('previewWrap');
  renderer = new THREE.WebGLRenderer({antialias:true, powerPreference:'low-power'});
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.setSize(box.clientWidth || 470, box.clientHeight || 304);
  box.insertBefore(renderer.domElement, box.firstChild);
  scene = new THREE.Scene();
  camera = new THREE.PerspectiveCamera(50, (box.clientWidth||470)/(box.clientHeight||304), 0.1, 6000);
  scene.add(new THREE.AmbientLight(0xffffff, .34));
  scene.add(new THREE.HemisphereLight(0xbdd4ff, 0x39301f, .55));
  const sun = new THREE.DirectionalLight(0xffffff, 1.35); sun.position.set(1.2, 2.2, 1.4); scene.add(sun);
  const back = new THREE.DirectionalLight(0x88aaff, .26); back.position.set(-1.5, .6, -1.2); scene.add(back);
  raycaster = new THREE.Raycaster();
  clock = new THREE.Clock();
  bindCanvasEvents(renderer.domElement);
  document.getElementById('rotBtn').style.color = autoRotate ? 'var(--acc)' : 'var(--dim)';
  document.getElementById('rotSpeed').value = Math.round(rotSpeed / 1.5 * 100);
  new ResizeObserver(resizeGL).observe(box);
  loop();
}
function bindCanvasEvents(cv){
  cv.addEventListener('mousedown', e => { dragging=true; px=e.clientX; py=e.clientY; });
  window.addEventListener('mouseup', () => dragging=false);
  window.addEventListener('mousemove', e => {
    if (dragging) {
      rotY += (e.clientX-px)*.008; rotX += (e.clientY-py)*.008;
      rotX = Math.max(-1.5, Math.min(1.5, rotX)); px=e.clientX; py=e.clientY;
      hideTip();
    } else if (e.target === renderer.domElement) {
      const r = renderer.domElement.getBoundingClientRect();
      pointer.x = ((e.clientX-r.left)/r.width)*2-1;
      pointer.y = -((e.clientY-r.top)/r.height)*2+1;
      pointer.cx = e.clientX; pointer.cy = e.clientY;
      needPick = true;
    }
  });
  cv.addEventListener('mouseleave', hideTip);
  cv.addEventListener('wheel', e => { e.preventDefault(); dist *= (1 + Math.sign(e.deltaY)*.12); dist=Math.max(3,Math.min(2600,dist)); }, {passive:false});
}
function resizeGL(){
  const box = fsMode ? document.getElementById('fsCanvasBox') : document.getElementById('previewWrap');
  if (!renderer || !box || !box.clientWidth) return;
  renderer.setSize(box.clientWidth, box.clientHeight);
  camera.aspect = box.clientWidth / box.clientHeight;
  camera.updateProjectionMatrix();
}
function loop() {
  requestAnimationFrame(loop);
  if (!renderer) return;
  const dt = clock.getDelta();
  if (VIEW !== 'bodies' && VIEW !== 'recycle' && !fsMode) return;
  if (!dragging && autoRotate) rotY += rotSpeed * dt;
  camera.position.set(
    center[0]+dist*Math.cos(rotX)*Math.sin(rotY),
    center[1]+dist*Math.sin(rotX),
    center[2]+dist*Math.cos(rotX)*Math.cos(rotY));
  camera.lookAt(center[0], center[1], center[2]);
  if (needPick && !dragging && mesh && hoverEnabled && performance.now()-lastPick > 60) {
    needPick = false; lastPick = performance.now(); pick();
  }
  renderer.render(scene, camera);
}
function pick(){
  raycaster.setFromCamera(pointer, camera);
  const hits = raycaster.intersectObject(mesh, false);
  if (hits.length && hits[0].instanceId !== undefined && MESH_DATA) {
    const vi = hits[0].instanceId;
    const p = MESH_DATA.palette[MESH_DATA.voxels[vi*4+3]];
    const lx = MESH_DATA.voxels[vi*4], ly = MESH_DATA.voxels[vi*4+1], lz = MESH_DATA.voxels[vi*4+2];
    const tip = document.getElementById('hoverTip');
    tip.innerHTML = `<b>${esc(p.zh)}</b><div class="bid">${esc(p.id)} · (${lx}, ${ly}, ${lz})</div>`;
    tip.style.display = 'block';
    tip.style.left = Math.min(pointer.cx + 14, innerWidth - 280) + 'px';
    tip.style.top = (pointer.cy + 14) + 'px';
    if (fsMode) document.getElementById('fsHover').innerHTML =
      `<b style="color:var(--fg)">${esc(p.zh)}</b> <span class="mono" style="font-size:10.5px">${esc(p.id)}</span> · (${lx}, ${ly}, ${lz})`;
  } else hideTip();
}
function hideTip(){ document.getElementById('hoverTip').style.display = 'none'; }
function toggleRotate(){
  autoRotate = !autoRotate;
  localStorage.setItem('spRot', autoRotate ? '1' : '0');
  document.getElementById('rotBtn').style.color = autoRotate ? 'var(--acc)' : 'var(--dim)';
}
function setRotSpeed(v){
  rotSpeed = v / 100 * 1.5;
  localStorage.setItem('spRotSpeed', String(rotSpeed));
  if (v > 0 && !autoRotate) toggleRotate();
}
function disposeMesh(){
  if (mesh) { scene.remove(mesh); mesh.geometry.dispose(); mesh.material.dispose(); mesh = null; }
  if (edgeLines) { scene.remove(edgeLines); edgeLines.geometry.dispose(); edgeLines.material.dispose(); edgeLines = null; }
  if (gridHelper) { scene.remove(gridHelper); gridHelper.geometry.dispose(); gridHelper.material.dispose(); gridHelper = null; }
}
async function loadMesh(uuid) {
  return loadMeshAt(`/api/body/${uuid}/mesh`,uuid,'body',()=>SEL&&SEL.uuid===uuid);
}
async function loadRecycleMesh(groupId,uuid) {
  return loadMeshAt(`/api/recycle/${groupId}/body/${uuid}/mesh`,uuid,`recycle:${groupId}`,
    ()=>RSEL&&RSELG&&RSEL.uuid===uuid&&RSELG.id===groupId);
}
async function loadCopyVersionMesh(uuid,versionId) {
  return loadMeshAt(`/api/body/${uuid}/copy/${versionId}/mesh`,uuid,`copy:${versionId}`,
    ()=>COPY_UUID===uuid&&COPY_VERSION===versionId&&document.getElementById('copyBack').style.display==='flex');
}
/* 走统一的 load():请求序号 + 服务器代次 + 会话代次一次拿齐。
   isCurrent() 只看"选中的还是不是这个 uuid",那不足以认出是哪一次请求 —— 同一个体
   连点两次(X→Y→X)时,X 的第一次晚回来照样满足它,于是往场景里加第二套网格并覆盖全局
   mesh,先加的那套再也释放不掉;两个服由同一份存档复制而来时,跨服也会撞上同一个 uuid。
   isCurrent 仍然要留:切服本身不发新的 mesh 请求,序号不会动,得靠它认出"没人选了"。 */
async function loadMeshAt(endpoint, uuid, source, isCurrent) {
  const info = document.getElementById('pvInfo');
  info.textContent = t('pvLoad');
  disposeMesh();
  MESH_DATA = null; MESH_UUID = MESH_SOURCE = null;
  renderComposition();
  return load('mesh', () => api(endpoint), d => {
    if (!isCurrent()) return;
    MESH_DATA = d; MESH_UUID = uuid; MESH_SOURCE = source;
    const n = d.shell;
    if (!n) { info.textContent = t('pvNone'); return; }
    mesh = new THREE.InstancedMesh(new THREE.BoxGeometry(1,1,1), new THREE.MeshLambertMaterial(), n);
    const m4 = new THREE.Matrix4(), col = new THREE.Color();
    let maxX=0,maxY=0,maxZ=0;
    for (let i=0;i<n;i++) {
      const x=d.voxels[i*4], y=d.voxels[i*4+1], z=d.voxels[i*4+2], p=d.palette[d.voxels[i*4+3]];
      m4.setPosition(x,y,z); mesh.setMatrixAt(i,m4);
      col.setHex(p.color);
      const hsh = ((x*73856093 ^ y*19349663 ^ z*83492791) >>> 0) % 1000 / 1000;
      col.multiplyScalar(0.93 + hsh*0.14);
      mesh.setColorAt(i,col);
      if(x>maxX)maxX=x; if(y>maxY)maxY=y; if(z>maxZ)maxZ=z;
    }
    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
    scene.add(mesh);
    if (n <= 9000) {
      const pos = new Float32Array(n*24*3);
      const E = [[0,0,0,1,0,0],[0,1,0,1,1,0],[0,0,1,1,0,1],[0,1,1,1,1,1],
                 [0,0,0,0,1,0],[1,0,0,1,1,0],[0,0,1,0,1,1],[1,0,1,1,1,1],
                 [0,0,0,0,0,1],[1,0,0,1,0,1],[0,1,0,0,1,1],[1,1,0,1,1,1]];
      let o = 0;
      for (let i=0;i<n;i++){
        const x=d.voxels[i*4]-0.5, y=d.voxels[i*4+1]-0.5, z=d.voxels[i*4+2]-0.5;
        for (const e of E){
          pos[o++]=x+e[0]; pos[o++]=y+e[1]; pos[o++]=z+e[2];
          pos[o++]=x+e[3]; pos[o++]=y+e[4]; pos[o++]=z+e[5];
        }
      }
      const eg = new THREE.BufferGeometry();
      eg.setAttribute('position', new THREE.BufferAttribute(pos, 3));
      edgeLines = new THREE.LineSegments(eg, new THREE.LineBasicMaterial({color:0x000000, transparent:true, opacity:0.22}));
      scene.add(edgeLines);
    }
    const span = Math.max(maxX,maxY,maxZ);
    gridHelper = new THREE.GridHelper(Math.max(16, span*2.2), 20, 0x22304a, 0x161d2b);
    gridHelper.position.set(maxX/2, -1.5, maxZ/2);
    scene.add(gridHelper);
    center = [maxX/2, maxY/2, maxZ/2];
    dist = span*1.8 + 8;
    hoverEnabled = n <= 80000;
    info.textContent = t('pvStat')(d.shell, d.total) + (d.truncated?t('pvTrunc'):'') + (hoverEnabled?'':' · '+t('pvHoverOff'));
    renderComposition();
  }, message => {
    // 成功、空数据、失败三条出口都要过同一道 current 检查:少了这句,从 A 快切到 B 之后
    // A 的延迟失败会把已经渲染好的 B 的提示文本改成 A 的报错
    if (!isCurrent()) return;
    info.textContent = t('pvFail') + message;
  });
}
/* 注意:函数名不能叫 enterFullscreen/exitFullscreen —— inline on* 处理器的作用域链是
   元素 → document → window,document.exitFullscreen 是原生 API,会把调用吃掉。
   这里统一改名 + 用 JS 绑定事件,双保险。 */
function openPreviewFs(){
  const selected = VIEW==='recycle' ? RSEL : SEL;
  if (!renderer || !selected) return;
  fsMode = true;
  document.getElementById('fsOverlay').style.display = 'block';
  document.getElementById('fsCanvasBox').appendChild(renderer.domElement);
  document.getElementById('fsName').textContent = selected.name || selected.uuid.slice(0,8);
  document.getElementById('fsMeta').textContent = `${selected.uuid} · ${fmt(selected.blocks)} ${t('blocksUnit')} · ${selected.dim}`;
  document.getElementById('fsHover').textContent = '';
  renderComposition();
  resizeGL();
}
function closePreviewFs(){
  fsMode = false;
  document.getElementById('fsOverlay').style.display = 'none';
  const host = document.getElementById(VIEW==='recycle'?'recyclePreviewHost':'bodyPreviewHost');
  const box = document.getElementById('previewWrap');
  if (box.parentElement!==host) host.appendChild(box);
  box.insertBefore(renderer.domElement, box.firstChild);
  hideTip();
  resizeGL();
}
document.getElementById('fsOpen').addEventListener('click', openPreviewFs);
document.getElementById('fsClose').addEventListener('click', closePreviewFs);
document.getElementById('manualBack').addEventListener('mousedown',event=>{ if(event.target.id==='manualBack') closeManual(); });
document.getElementById('copyBack').addEventListener('mousedown',event=>{ if(event.target.id==='copyBack') closeDedupe(); });
document.getElementById('consistencyBack').addEventListener('mousedown',event=>{ if(event.target.id==='consistencyBack') closeConsistency(); });
window.addEventListener('resize', () => { resizeGL(); if (VIEW==='dash') renderDash(); });
