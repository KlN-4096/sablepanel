'use strict';
/* 性能图表:窗口预设 + canvas 绘制 + 悬停交互。数据始终是 /api/stats 的内存 15 分钟窗口,
   预设只决定本地裁剪多长的尾部,切预设不重新请求 */
onServerReset(() => { CHART.span = 300; CHART.hoverIndex = -1; CHART.hoverTime = null; });
function renderChartPresets(){
  const box = document.getElementById('chartPresets');
  if (!box) return;
  box.innerHTML = CHART_PRESETS.map(([seconds, label])=>
    `<button class="${CHART.span===seconds?'on':''}" onclick="setChartPreset(${seconds})">${label}</button>`).join('');
}
function statsErrorLabel(){
  return staleLabel(STATS_ERROR, !!STATS);
}
function updateChartControls(){
  renderChartPresets();
  const meta = T.chartMeta(chartData().times.length);
  const status = statsErrorLabel();
  document.getElementById('chartMeta').textContent = status ? `${status} · ${meta}` : meta;
}
function setChartPreset(seconds){
  CHART.span = seconds; CHART.hoverIndex = -1; CHART.hoverTime = null;
  document.getElementById('chartTip').style.display='none';
  renderStats();
  drawPhysChart(document.getElementById('physChart'),true);
}
/* R3.6 系列色对齐黄铜盘:首维度(主世界)=签名黄铜,绿/蓝/紫/铜错开保证可辨 */
const DIM_COLORS = ['#d0a354','#5cab62','#5a8fd6','#a583d6','#d8735a','#55b5b2'];
const BODY_COLOR = '#e08bab';
function chartInk(){
  const cs = getComputedStyle(document.documentElement);
  return {grid:cs.getPropertyValue('--chart-grid').trim(), axis:cs.getPropertyValue('--dim').trim(),
          cross:cs.getPropertyValue('--chart-cross').trim(), halo:cs.getPropertyValue('--card').trim()};
}
/* 按 CHART.span 裁剪出要画的尾部窗口;悬停时间与绘制共用同一份裁剪结果 */
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
  if ((STATS.body_logic||[]).length) series.push({name:T.physBodies,values:slice(STATS.body_logic),color:BODY_COLOR});
  return {times:all.slice(start),series};
}
function chartGeometry(cv,big,times,width,height){
  const w=width||cv.clientWidth||cv.width, h=height||cv.clientHeight||cv.height;
  const left=big?45:1, right=big?9:1, top=big?10:1, bottom=big?22:1;
  const from=times[0], to=times[times.length-1];
  return {w,h,left,right,top,bottom,from,to:Math.max(from+1,to),plotW:Math.max(1,w-left-right),plotH:Math.max(1,h-top-bottom)};
}
function chartX(time,g){ return g.left+(time-g.from)/(g.to-g.from)*g.plotW; }
function chartHoverFrame(times,target){
  const last=times.length-1;
  if (target<=times[0]) return {time:times[0],left:0,right:0,mix:0};
  if (target>=times[last]) return {time:times[last],left:last,right:last,mix:0};
  let low=1,high=last;
  while (low<high) { const mid=Math.floor((low+high)/2); if (times[mid]<target) low=mid+1; else high=mid; }
  const right=low,left=right-1,span=times[right]-times[left];
  return {time:target,left,right,mix:span>0?(target-times[left])/span:0};
}
function chartFrameValue(frame,values){
  const left=Number(values[frame.left]);
  if (frame.left===frame.right) return left;
  const right=Number(values[frame.right]);
  return Number.isFinite(left)&&Number.isFinite(right) ? left+(right-left)*frame.mix : NaN;
}
function chartTipPosition(pointerX,pointerY,tipWidth,tipHeight,viewWidth,viewHeight,zoom=1){
  const gap=12,edge=4;
  const scale=Number.isFinite(zoom)&&zoom>0?zoom:1;
  const x=pointerX/scale,y=pointerY/scale,w=viewWidth/scale,h=viewHeight/scale;
  const left=x+gap+tipWidth<=w-edge ? x+gap : Math.max(edge,x-gap-tipWidth);
  const top=y+gap+tipHeight<=h-edge ? y+gap : Math.max(edge,y-gap-tipHeight);
  return {left,top};
}
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
  const ink=chartInk();
  if (big) {
    ctx.strokeStyle=ink.grid; ctx.lineWidth=1; ctx.fillStyle=ink.axis; ctx.font='10px monospace';
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
  if (big&&CHART.hoverTime!==null) {
    const frame=chartHoverFrame(times,CHART.hoverTime), xx=chartX(frame.time,g);
    ctx.strokeStyle=ink.cross; ctx.lineWidth=1; ctx.setLineDash([3,3]);
    ctx.beginPath(); ctx.moveTo(xx,g.top); ctx.lineTo(xx,g.top+g.plotH); ctx.stroke(); ctx.setLineDash([]);
    for (const item of series) {
      const value=chartFrameValue(frame,item.values); if (!Number.isFinite(value)) continue;
      ctx.fillStyle=item.color; ctx.beginPath(); ctx.arc(xx,y(value),3,0,Math.PI*2); ctx.fill();
      ctx.strokeStyle=ink.halo; ctx.stroke();
    }
  }
}
function chartMouseMove(event){
  const cv=document.getElementById('physChart'), {times,series}=chartData();
  if (!times.length) return;
  const rect=cv.getBoundingClientRect(), g=chartGeometry(cv,true,times,rect.width,rect.height);
  const localX=event.clientX-rect.left;
  if (localX<g.left||localX>g.left+g.plotW) { chartMouseLeave(); return; }
  const target=g.from+(localX-g.left)/g.plotW*(g.to-g.from);
  const frame=chartHoverFrame(times,target);
  CHART.hoverIndex=frame.left; CHART.hoverTime=frame.time;
  const tip=document.getElementById('chartTip');
  tip.innerHTML=`<b>${fmtDateTime(frame.time*1000)}</b>`+series.map(item=>{
    const value=chartFrameValue(frame,item.values);
    return `<div class="ctRow"><i style="background:${item.color}"></i><span>${esc(item.name)}</span><em>${Number.isFinite(value)?value.toFixed(2):'--'} ms/t</em></div>`;
  }).join('');
  tip.style.display='block';
  const zoom=parseFloat(getComputedStyle(document.documentElement).zoom)||1;
  const position=chartTipPosition(event.clientX,event.clientY,tip.offsetWidth,tip.offsetHeight,
    window.innerWidth,window.innerHeight,zoom);
  tip.style.left=position.left+'px'; tip.style.top=position.top+'px';
  drawPhysChart(cv,true);
}
function chartMouseLeave(){
  CHART.hoverIndex=-1; CHART.hoverTime=null; document.getElementById('chartTip').style.display='none';
  drawPhysChart(document.getElementById('physChart'),true);
}
function initChartInteractions(){
  const cv=document.getElementById('physChart');
  cv.addEventListener('mousemove',chartMouseMove);
  cv.addEventListener('mouseleave',chartMouseLeave);
}
