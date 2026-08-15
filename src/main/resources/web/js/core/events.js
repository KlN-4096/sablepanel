'use strict';
/* TLS EVENT -> 网关 SSE。事件只表示快照失效，页面仍从 /api/bodies 拉取权威数据。 */
let eventStreamGeneration = 0;
let eventStreamAbort = null;
let eventReconnectTimer = null;
let eventRefreshTimer = null;

function stopEventStream(){
  eventStreamGeneration++;
  if (eventStreamAbort) eventStreamAbort.abort();
  eventStreamAbort = null;
  if (eventReconnectTimer) clearTimeout(eventReconnectTimer);
  eventReconnectTimer = null;
  if (eventRefreshTimer) clearTimeout(eventRefreshTimer);
  eventRefreshTimer = null;
}

function startEventStream(){
  stopEventStream();
  if (!authenticated || document.hidden) return;
  connectEventStream(eventStreamGeneration, 0);
}

async function connectEventStream(generation, attempt){
  if (generation !== eventStreamGeneration || !authenticated || document.hidden) return;
  const controller = new AbortController();
  let opened = false;
  eventStreamAbort = controller;
  try {
    const response = await fetch('/api/events', {
      headers:{'X-Token':token}, cache:'no-store', signal:controller.signal
    });
    if (generation !== eventStreamGeneration) return;
    if (response.status === 401) { showLogin(T.loginChanged); return; }
    if (!response.ok || !response.body) throw new Error(String(response.status));
    opened = true;
    await readEventStream(response.body, generation);
    throw new Error('event stream closed');
  } catch(e) {
    if (controller.signal.aborted || generation !== eventStreamGeneration) return;
    const delay = opened ? 1000 : Math.min(30000, 1000 * (2 ** Math.min(attempt, 5)));
    const nextAttempt = opened ? 0 : attempt + 1;
    eventReconnectTimer = setTimeout(() => connectEventStream(generation, nextAttempt), delay);
  }
}

async function readEventStream(stream, generation){
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  try {
    while (generation === eventStreamGeneration) {
      const {value, done} = await reader.read();
      if (done) return;
      buffer += decoder.decode(value, {stream:true});
      buffer = buffer.replace(/\r\n/g, '\n');
      if (buffer.length > 65536) throw new Error('event frame too large');
      let boundary;
      while ((boundary = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        handleEventBlock(block);
      }
    }
  } finally {
    reader.cancel().catch(()=>{});
  }
}

function handleEventBlock(block){
  const lines = block.split('\n');
  const eventLine = lines.find(line => line.startsWith('event:'));
  const event = eventLine ? eventLine.slice(6).trim() : '';
  if (event === 'bodies') {
    const raw = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).trim()).join('\n');
    let source = '';
    try { source = raw ? JSON.parse(raw).server || '' : ''; } catch (_) { return; }
    const current = CURSRV || ((SERVERS.find(server => server.self) || {}).id || '');
    if (source && current && source !== current) return;
    scheduleEventRefresh();
  }
}

function scheduleEventRefresh(){
  if (eventRefreshTimer) clearTimeout(eventRefreshTimer);
  eventRefreshTimer = setTimeout(() => {
    eventRefreshTimer = null;
    if (authenticated && !document.hidden) loadBodies();
  }, 100);
}
