'use strict';
/* 性能图表:窗口预设 + canvas 绘制 + 悬停交互。数据始终是 /api/stats 的内存 15 分钟窗口,
   预设只决定本地裁剪多长的尾部,切预设不重新请求 */
onServerReset(() => { CHART.span = 300; CHART.hoverIndex = -1; });
function chartPresetLabel(seconds){
  return {300:'5 分钟',900:'15 分钟'}[seconds];
}
function renderChartPresets(){
  const box = document.getElementById('chartPresets');
  if (!box) return;
  box.innerHTML = CHART_PRESETS.map(seconds=>
    `<button class="${CHART.span===seconds?'on':''}" onclick="setChartPreset(${seconds})">${chartPresetLabel(seconds)}</button>`).join('');
}
/* STATS 派生的全部区域,一处写完:顶栏两个数字、迷你图、统计弹层、图表控件。
   这些从前只在 loadStats 成功时更新,切服清空 STATS 时没有任何人重画 —— 顶栏一直挂着
   上一个服的数字,统计弹层里还是上一个服的体,新服的统计请求要是失败就永远挂着。
   顶栏和弹层在所有视图共享,所以这里不看 VIEW。 */
function statsErrorLabel(){
  return staleLabel(STATS_ERROR, !!STATS);
}
function renderStats(){
  const status = statsErrorLabel();
  staleMark(document.getElementById('loadPill'), status);
  document.getElementById('pillCost').textContent =
    STATS ? (STATS.body_cost_total ?? 0).toFixed(2) : '--';
  document.getElementById('pillLoaded').textContent =
    STATS ? Object.values(STATS.loaded || {}).reduce((a, b) => a + b, 0) : '--';
  updateChartControls();
  drawPhysChart(document.getElementById('pillSpark'), false);
  renderStatPop();
  // 悬浮提示只在鼠标移出时隐藏。切服清空 STATS 之后图是空的,上一个服的 tooltip
  // 还能挂在上面 —— 没数据就没有可提示的东西
  if (!STATS) {
    const tip = document.getElementById('chartTip');
    tip.style.display = 'none';
    tip.innerHTML = '';
    CHART.hoverIndex = -1;
  }
}
function updateChartControls(){
  renderChartPresets();
  const meta = t('chartMeta')(chartData().times.length);
  const status = statsErrorLabel();
  document.getElementById('chartMeta').textContent = status ? `${status} · ${meta}` : meta;
}
function setChartPreset(seconds){
  CHART.span = seconds; CHART.hoverIndex = -1;
  renderStats();
}
const DIM_COLORS = ['#b98cff','#46c96b','#e0a33a','#5aa9ff','#ef7e62','#55c5c2'];
const BODY_COLOR = '#f28db2';
/* 按 CHART.span 裁剪出要画的尾部窗口;悬停索引与绘制共用同一份裁剪结果 */
function chartData(){
  if (!STATS) return {times:[],series:[]};
  const all = (STATS.t||[]).map(Number);
  let start = 0;
  if (all.length) {
    const cutoff = all[all.length-1] - CHART.span;
    while (start < all.length && all[start] < cutoff) start++;
  }
  const slice = values => (values||[]).slice(start).map(Number);
  const series = Object.entries(STATS.phys||{}).map(([name,values],index)=>
    ({name:name.replace('minecraft:',''),values:slice(values),color:DIM_COLORS[index%DIM_COLORS.length]}));
  if ((STATS.body_logic||[]).length) series.push({name:t('physBodies'),values:slice(STATS.body_logic),color:BODY_COLOR});
  return {times:all.slice(start),series};
}
function chartGeometry(cv,big,times,width,height){
  const w=width||cv.clientWidth||cv.width, h=height||cv.clientHeight||cv.height;
  const left=big?45:1, right=big?9:1, top=big?10:1, bottom=big?22:1;
  const from=times[0], to=times[times.length-1];
  return {w,h,left,right,top,bottom,from,to:Math.max(from+1,to),plotW:Math.max(1,w-left-right),plotH:Math.max(1,h-top-bottom)};
}
function chartX(time,g){ return g.left+(time-g.from)/(g.to-g.from)*g.plotW; }
function chartAxisTime(epoch){
  return new Date(epoch*1000).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'});
}
function drawPhysChart(cv,big){
  if (!cv) return;
  const rect=cv.getBoundingClientRect();
  const w=Math.round(rect.width)||cv.clientWidth||cv.width, h=Math.round(rect.height)||cv.clientHeight||cv.height;
  if (!w||!h) return;
  const ratio=Math.min(window.devicePixelRatio||1,2);
  const pw=Math.round(w*ratio), ph=Math.round(h*ratio);
  if (cv.width!==pw||cv.height!==ph) { cv.width=pw; cv.height=ph; }
  const ctx=cv.getContext('2d');
  ctx.setTransform(ratio,0,0,ratio,0,0); ctx.clearRect(0,0,w,h);
  const {times,series}=chartData();
  if (!times.length||!series.length) return;
  const g=chartGeometry(cv,big,times,w,h);
  let max=.2;
  for (const item of series) for (const value of item.values) if (Number.isFinite(value)) max=Math.max(max,value);
  const y=value=>g.top+(1-Math.max(0,value)/max)*g.plotH;
  if (big) {
    ctx.strokeStyle='#ffffff0d'; ctx.lineWidth=1; ctx.fillStyle='#79839a'; ctx.font='10px monospace';
    for (let i=0;i<=4;i++) {
      const yy=g.top+g.plotH*i/4, value=max*(1-i/4);
      ctx.beginPath(); ctx.moveTo(g.left,yy); ctx.lineTo(g.left+g.plotW,yy); ctx.stroke();
      if (i<4) ctx.fillText(value.toFixed(2),3,yy+3);
    }
    ctx.textAlign='center';
    for (let i=0;i<=3;i++) {
      const epoch=g.from+(g.to-g.from)*i/3;
      ctx.fillText(chartAxisTime(epoch),g.left+g.plotW*i/3,h-5);
    }
    ctx.textAlign='left';
  }
  for (const item of series) {
    ctx.beginPath(); let started=false;
    times.forEach((time,index)=>{
      const value=item.values[index];
      if (!Number.isFinite(value)) { started=false; return; }
      const xx=chartX(time,g), yy=y(value);
      if (!started) { ctx.moveTo(xx,yy); started=true; } else ctx.lineTo(xx,yy);
    });
    ctx.strokeStyle=item.color; ctx.lineWidth=big?1.6:1.3; ctx.stroke();
  }
  if (big&&CHART.hoverIndex>=0&&CHART.hoverIndex<times.length) {
    const index=CHART.hoverIndex, xx=chartX(times[index],g);
    ctx.strokeStyle='#dce8f566'; ctx.lineWidth=1; ctx.setLineDash([3,3]);
    ctx.beginPath(); ctx.moveTo(xx,g.top); ctx.lineTo(xx,g.top+g.plotH); ctx.stroke(); ctx.setLineDash([]);
    for (const item of series) {
      const value=item.values[index]; if (!Number.isFinite(value)) continue;
      ctx.fillStyle=item.color; ctx.beginPath(); ctx.arc(xx,y(value),3,0,Math.PI*2); ctx.fill();
      ctx.strokeStyle='#0d1118'; ctx.stroke();
    }
  }
}
function nearestChartIndex(times,target){
  let low=0,high=times.length-1;
  while (low<high) { const mid=Math.floor((low+high)/2); if (times[mid]<target) low=mid+1; else high=mid; }
  if (low>0&&Math.abs(times[low-1]-target)<=Math.abs(times[low]-target)) return low-1;
  return low;
}
function chartMouseMove(event){
  const cv=document.getElementById('physChart'), {times,series}=chartData();
  if (!times.length) return;
  const rect=cv.getBoundingClientRect(), g=chartGeometry(cv,true,times);
  const localX=event.clientX-rect.left;
  if (localX<g.left||localX>g.left+g.plotW) { chartMouseLeave(); return; }
  const target=g.from+(localX-g.left)/g.plotW*(g.to-g.from);
  CHART.hoverIndex=nearestChartIndex(times,target);
  const index=CHART.hoverIndex, start=times[index];
  const tip=document.getElementById('chartTip');
  tip.innerHTML=`<b>${new Date(start*1000).toLocaleString()}</b>`+series.map(item=>
    `<div class="ctRow"><i style="background:${item.color}"></i><span>${esc(item.name)}</span><em>${Number(item.values[index]||0).toFixed(2)} ms</em></div>`).join('');
  tip.style.display='block';
  tip.style.left=Math.min(g.w-tip.offsetWidth-6,Math.max(4,localX+12))+'px';
  tip.style.top='8px';
  drawPhysChart(cv,true);
}
function chartMouseLeave(){
  CHART.hoverIndex=-1; document.getElementById('chartTip').style.display='none';
  drawPhysChart(document.getElementById('physChart'),true);
}
function initChartInteractions(){
  const cv=document.getElementById('physChart');
  cv.addEventListener('mousemove',chartMouseMove);
  cv.addEventListener('mouseleave',chartMouseLeave);
}
