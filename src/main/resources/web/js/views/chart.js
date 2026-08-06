'use strict';
/* 性能图表:预设/日期区间控件 + canvas 绘制 + 悬停/滚轮交互 */
function chartDuration(seconds){
  const value = Math.max(1, Math.round(seconds));
  if (value < 60) return `${value} 秒`;
  if (value < 3600) return `${Math.round(value/60)} 分钟`;
  if (value < 86400) return `${Math.round(value/3600)} 小时`;
  return `${Math.round(value/86400)} 天`;
}
function chartPresetLabel(seconds){
  return {300:'5 分钟',900:'15 分钟',3600:'1 小时',21600:'6 小时',
    86400:'24 小时',604800:'7 天',2592000:'30 天'}[seconds];
}
function renderChartPresets(){
  const box = document.getElementById('chartPresets');
  if (!box) return;
  box.innerHTML = CHART_PRESETS.map(seconds=>
    `<button class="${CHART.live&&CHART.preset===seconds?'on':''}" onclick="setChartPreset(${seconds})">${chartPresetLabel(seconds)}</button>`).join('');
}
function localDateInput(epochSecond){
  const date = new Date(epochSecond * 1000 - new Date(epochSecond * 1000).getTimezoneOffset() * 60000);
  return date.toISOString().slice(0,16);
}
/* STATS 派生的全部区域,一处写完:顶栏两个数字、迷你图、统计弹层、图表控件。
   这些从前只在 loadStats 成功时更新,切服清空 STATS 时没有任何人重画 —— 顶栏一直挂着
   上一个服的数字,统计弹层里还是上一个服的体,新服的统计请求要是失败就永远挂着。
   顶栏和弹层在所有视图共享,所以这里不看 VIEW。 */
function renderStats(){
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
  const now = Math.floor(Date.now()/1000);
  const from = CHART.from || now - CHART.span;
  const to = CHART.to || now;
  if (!['chartFrom','chartTo'].includes(document.activeElement && document.activeElement.id)) {
    document.getElementById('chartFrom').value = localDateInput(from);
    document.getElementById('chartTo').value = localDateInput(to);
  }
  const live = document.getElementById('chartLiveBtn');
  if (live) { live.disabled = CHART.live; live.classList.toggle('primary', !CHART.live); }
  renderChartPresets();
  const points = STATS && STATS.t ? STATS.t.length : 0;
  const step = STATS && STATS.step_seconds ? chartDuration(STATS.step_seconds) : chartDuration(1);
  const aggregation = STATS && STATS.aggregation === 'peak' ? t('chartPeak') : t('chartRaw');
  document.getElementById('chartMeta').textContent = t('chartMeta')(points, step, aggregation);
}
function setChartPreset(seconds){
  CHART.span = seconds; CHART.live = true; CHART.preset = seconds; CHART.hoverIndex = -1;
  loadStats();
}
function returnChartLive(){
  CHART.live = true; CHART.preset = CHART_PRESETS.includes(CHART.span) ? CHART.span : null; CHART.hoverIndex = -1;
  loadStats();
}
function applyChartDates(){
  const from = Math.floor(new Date(document.getElementById('chartFrom').value).getTime()/1000);
  const requestedTo = Math.floor(new Date(document.getElementById('chartTo').value).getTime()/1000);
  const to = Math.min(requestedTo, Math.floor(Date.now()/1000));
  if (!Number.isFinite(from) || !Number.isFinite(to) || from >= to) { toast(t('chartInvalid'),'bad'); return; }
  CHART.from = from; CHART.to = to; CHART.span = to-from; CHART.live = false; CHART.preset = null; CHART.hoverIndex = -1;
  loadStats();
}
const DIM_COLORS = ['#b98cff','#46c96b','#e0a33a','#5aa9ff','#ef7e62','#55c5c2'];
const BODY_COLOR = '#f28db2';
function chartSeries(){
  if (!STATS) return [];
  const series = Object.entries(STATS.phys||{}).map(([name,values],index)=>
    ({name:name.replace('minecraft:',''),values:(values||[]).map(Number),color:DIM_COLORS[index%DIM_COLORS.length]}));
  if ((STATS.body_logic||[]).length) series.push({name:t('physBodies'),values:STATS.body_logic.map(Number),color:BODY_COLOR});
  return series;
}
function chartGeometry(cv,big,times,width,height){
  const w=width||cv.clientWidth||cv.width, h=height||cv.clientHeight||cv.height;
  const left=big?45:1, right=big?9:1, top=big?10:1, bottom=big?22:1;
  const from=big?(CHART.from||times[0]):times[0], to=big?(CHART.to||times[times.length-1]):times[times.length-1];
  return {w,h,left,right,top,bottom,from,to:Math.max(from+1,to),plotW:Math.max(1,w-left-right),plotH:Math.max(1,h-top-bottom)};
}
function chartX(time,g){ return g.left+(time-g.from)/(g.to-g.from)*g.plotW; }
function chartAxisTime(epoch,span){
  const date=new Date(epoch*1000);
  return span>86400 ? date.toLocaleString([], {month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'})
    : date.toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'});
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
  const times=(STATS&&STATS.t||[]).map(Number), series=chartSeries();
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
      ctx.fillText(chartAxisTime(epoch,g.to-g.from),g.left+g.plotW*i/3,h-5);
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
  const cv=document.getElementById('physChart'), times=(STATS&&STATS.t||[]).map(Number);
  if (!times.length) return;
  const rect=cv.getBoundingClientRect(), g=chartGeometry(cv,true,times);
  const localX=event.clientX-rect.left;
  if (localX<g.left||localX>g.left+g.plotW) { chartMouseLeave(); return; }
  const target=g.from+(localX-g.left)/g.plotW*(g.to-g.from);
  CHART.hoverIndex=nearestChartIndex(times,target);
  const index=CHART.hoverIndex, step=Number(STATS.step_seconds||1), start=times[index];
  const interval=step>1 ? `<div class="cloneReason">${t('chartInterval')(`${new Date(start*1000).toLocaleString()} - ${new Date((start+step)*1000).toLocaleString()}`)}</div>` : '';
  const tip=document.getElementById('chartTip');
  tip.innerHTML=`<b>${new Date(start*1000).toLocaleString()}</b>${interval}`+chartSeries().map(item=>
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
function chartWheel(event){
  if (!STATS) return;
  event.preventDefault();
  const cv=document.getElementById('physChart'), rect=cv.getBoundingClientRect();
  const ratio=Math.max(0,Math.min(1,(event.clientX-rect.left-45)/Math.max(1,rect.width-54)));
  const oldSpan=Math.max(60,CHART.to-CHART.from), newSpan=Math.round(Math.max(60,Math.min(2592000,oldSpan*(event.deltaY>0?1.35:1/1.35))));
  const anchor=CHART.from+oldSpan*ratio, now=Math.floor(Date.now()/1000);
  let from=Math.round(anchor-newSpan*ratio), to=from+newSpan;
  if (to>now) { from-=to-now; to=now; }
  CHART.from=from; CHART.to=to; CHART.span=newSpan; CHART.live=false; CHART.preset=null; CHART.hoverIndex=-1;
  updateChartControls();
  clearTimeout(CHART.fetchTimer); CHART.fetchTimer=setTimeout(loadStats,180);
}
function initChartInteractions(){
  const cv=document.getElementById('physChart');
  cv.addEventListener('mousemove',chartMouseMove);
  cv.addEventListener('mouseleave',chartMouseLeave);
  cv.addEventListener('wheel',chartWheel,{passive:false});
}
