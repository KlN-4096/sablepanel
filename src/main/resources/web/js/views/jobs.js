'use strict';
/* 日志视图:面板做过的每一次操作(过程 / 状态 / 报错 / 告警)。
   数据来自 /api/jobs —— 内存里是本次运行的记录,服务端同时按启动时间落盘成
   logs/sablepanel/jobs-<启动时间>.jsonl,顶部下拉框可以切到历史文件事后查证。 */
let JOBS = null;
let jobsFile = '';          // 空 = 本次运行(内存)
let jobsOnlyFailed = false;
let jobsExpanded = new Set();

async function loadJobs(){
  const gen = srvGen();
  try {
    const result = await api('/api/jobs' + (jobsFile ? `?file=${encodeURIComponent(jobsFile)}` : ''));
    if (gen !== srvGen()) return;      // 切服后旧服的日志不能落地:job seq 在每个服都从 1 开始
    JOBS = result;
    renderJobs();
  } catch(e){ if (gen === srvGen()) toast(t('loadFail') + e.message, 'bad'); }
}
function setJobsFile(name){ jobsFile = name; jobsExpanded.clear(); loadJobs(); }
function toggleJobsFailed(){ jobsOnlyFailed = !jobsOnlyFailed; renderJobs(); }
function toggleJobRow(seq){
  jobsExpanded.has(seq) ? jobsExpanded.delete(seq) : jobsExpanded.add(seq);
  renderJobs();
}

/* 终态契约:'ok' 全部成功 / 'partial' 部分成功 / 'fail' 全部失败,由后端 outcome 字段直给。
   历史日志文件里的旧记录没有这个字段,才回落到从前那套 state + message 解析 —— 那套把
   "3 个全删失败"(message 是 0/3)当成绿色的完成,连"仅失败"筛选都找不到它。 */
function jobOutcome(job){
  if (job.outcome) return job.outcome;
  if (job.state === 'failed') return 'fail';
  const counted = /(?:^|\s)(\d+)\/(\d+)(?:\s|$)/.exec(job.message || '');
  if (counted) {
    const ok = Number(counted[1]), total = Number(counted[2]);
    if (total > 0 && ok === 0) return 'fail';
    if (ok < total) return 'partial';
  }
  return /failed=[1-9]/.test(job.message || '') ? 'partial' : 'ok';
}
function jobFailed(job){
  return job.state !== 'running' && job.state !== 'queued' && jobOutcome(job) !== 'ok';
}
function jobStateTag(job){
  if (job.state === 'running') return `<span class="tag busy"><i class="spin"></i>${esc(job.phase || t('jobsRunning'))}</span>`;
  if (job.state === 'queued') return `<span class="tag">${t('jobQueued')}</span>`;
  const outcome = jobOutcome(job);
  if (outcome === 'fail') return `<span class="tag bad">${t('jobFailed')}</span>`;
  if (outcome === 'partial') return `<span class="tag warn">${t('jobPartial')}</span>`;
  return `<span class="tag ok">${t('jobDone')}</span>`;
}
function jobCost(job){
  if (job.ms === undefined) return '';
  return job.ms < 1000 ? job.ms + ' ms' : (job.ms / 1000).toFixed(1) + ' s';
}

function renderJobs(){
  if (VIEW !== 'jobs') return;
  // JOBS 为空要显式画 loading:切服时把它清掉再重画,否则页面会一直挂着上一个服的记录
  if (!JOBS) {
    document.getElementById('jobsList').innerHTML = `<div id="listEmpty">${t('loading')}</div>`;
    document.getElementById('jobsWorkers').textContent = '';
    return;
  }
  const files = JOBS.files || [];
  document.getElementById('jobsFileSel').innerHTML =
    `<option value="">${t('jobsCurrent')}</option>` + files.map(name =>
      `<option value="${esc(name)}" ${name === jobsFile ? 'selected' : ''}>${esc(name)}</option>`).join('');
  document.getElementById('jobsWorkers').textContent =
    JOBS.workers ? t('jobsWorkers')(JOBS.workers) : '';
  document.getElementById('jobsFailedBtn').classList.toggle('on', jobsOnlyFailed);

  // 进行中的排在最前:正在跑的作业本来就是用户最想看的
  const rows = [...(JOBS.running || []), ...(JOBS.log || [])]
    .filter(job => !jobsOnlyFailed || jobFailed(job));
  const host = document.getElementById('jobsList');
  if (!rows.length) { host.innerHTML = `<div id="listEmpty"><span class="big">⬡</span>${t('jobsEmpty')}</div>`; return; }

  host.innerHTML = rows.map(job => {
    const when = new Date(job.started_at || job.queued_at).toLocaleTimeString();
    const open = jobsExpanded.has(job.seq);
    const detail = !open ? '' : `<div class="jobDetail">
        ${(job.trail || []).length ? `<div><span class="muted">${t('jobTrail')}</span> ${
          job.trail.map(step => esc(step)).join(' → ')}</div>` : ''}
        ${(job.targets || []).length ? `<div class="mono muted">${
          job.targets.map(u => esc(u)).join('<br>')}</div>` : ''}
        ${(job.warnings || []).length ? `<div class="jobWarn"><span class="muted">${t('jobWarn')}</span> ${
          job.warnings.map(w => esc(String(w))).join('; ')}</div>` : ''}
      </div>`;
    return `<div class="jobRow ${jobFailed(job) ? 'is-bad' : ''}" onclick="toggleJobRow(${job.seq})">
        <span class="jobTime mono">${when}</span>
        <span class="jobOp">${esc(job.op || '')}</span>
        <span class="jobTarget">${esc(job.name || '')}</span>
        ${jobStateTag(job)}
        <span class="jobMs mono muted">${jobCost(job)}</span>
        <span class="jobMsg muted">${esc(job.message || '')}</span>
      </div>${detail}`;
  }).join('');
}
