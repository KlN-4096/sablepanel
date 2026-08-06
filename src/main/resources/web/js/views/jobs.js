'use strict';
/* 日志视图:面板做过的每一次操作(过程 / 状态 / 报错 / 告警)。
   数据来自 /api/jobs —— 内存里是本次运行的记录,服务端同时按启动时间落盘成
   logs/sablepanel/jobs-<启动时间>.jsonl,顶部下拉框可以切到历史文件事后查证。 */
let JOBS = null;
let JOBS_ERROR = '';        // 非空 = 上一次加载失败的原因
let jobsFile = '';          // 空 = 本次运行(内存)
let jobsOnlyFailed = false;
let jobsExpanded = new Set();

/* 日志记录与选中的历史文件都属于当前服务器 */
onServerReset(() => { JOBS = null; JOBS_ERROR = ''; jobsFile = ''; jobsExpanded.clear(); });

/* 序号键必须和 pollJobs 的 'jobs' 分开:同一个键会让两者互相作废对方的响应。
   切服后旧服的日志不能落地(job seq 在每个服都从 1 开始),这条由 load() 的代次守卫管。
   本页只在用户点击时加载,所以刷新失败弹 toast 就够了,不像总览那样需要常驻提示条 */
function loadJobs(){
  return load('joblog', () => api('/api/jobs' + (jobsFile ? `?file=${encodeURIComponent(jobsFile)}` : '')),
    result => { JOBS = result; JOBS_ERROR = ''; renderJobs(); },
    message => { JOBS_ERROR = message; toast(t('loadFail') + message, 'bad'); renderJobs(); });
}
/* 换文件先清空再拉。不清的话请求失败时 renderJobs 只在 JOBS===null 才显示错误,
   旧文件的记录就原样留在页面上冒充新文件的内容,而且没有任何提示。
   清了之后 JOBS 恒等于 jobsFile 的内容 —— 晚到的旧文件响应由 load() 的序号挡掉,
   不需要再给数据带一个来源字段 */
function setJobsFile(name){
  jobsFile = name; jobsExpanded.clear();
  JOBS = null; JOBS_ERROR = '';
  renderJobs(); loadJobs();
}
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
  // JOBS 为空要显式画:切服时把它清掉再重画,否则页面会一直挂着上一个服的记录。
  // 失败也要说出来 —— 从前只弹一下 toast,页面就永远停在"加载中…"
  if (!JOBS) {
    document.getElementById('jobsList').innerHTML = JOBS_ERROR
      ? `<div id="listEmpty"><span class="big">⚠</span>${t('loadFail')}${esc(JOBS_ERROR)}</div>`
      : `<div id="listEmpty">${t('loading')}</div>`;
    document.getElementById('jobsWorkers').innerHTML = '';
    return;
  }
  const files = JOBS.files || [];
  document.getElementById('jobsFileSel').innerHTML =
    `<option value="">${t('jobsCurrent')}</option>` + files.map(name =>
      `<option value="${esc(name)}" ${name === jobsFile ? 'selected' : ''}>${esc(name)}</option>`).join('');
  // 同一个文件刷新失败:旧记录照常显示,但要说明它是上一次的结果
  const workers = JOBS.workers ? t('jobsWorkers')(JOBS.workers) : '';
  document.getElementById('jobsWorkers').innerHTML = JOBS_ERROR
    ? `<span style="color:var(--bad)">${t('staleData')}${esc(JOBS_ERROR)}</span> ${esc(workers)}`
    : esc(workers);
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
