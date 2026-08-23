'use strict';
/* 预览二进制传输:与 core/api.js 的 JSON 约定分离,保留服务器/会话代次。 */
(function (global) {
  function endpoint(path) {
    const sep = path.includes('?') ? '&' : '?';
    const server = typeof CURSRV !== 'undefined' ? CURSRV : global.CURSRV;
    return path + (server ? sep + 'server=' + encodeURIComponent(server) : '');
  }

  function currentToken() { return typeof token !== 'undefined' ? token : (global.token || ''); }

  async function request(path, context) {
    const response = await fetch(endpoint(path), {
      headers: {'X-Token': currentToken()},
      signal: context && context.signal
    });
    const type = (response.headers.get('content-type') || '').split(';', 1)[0].toLowerCase();
    if (response.status === 202) {
      const body = await response.json().catch(() => ({}));
      return {status:'accepted', retryAfter:Number(body.retry_after) || 1};
    }
    if (response.status === 413) return {status:'too_large'};
    if (response.status === 503) {
      const body = await response.json().catch(() => ({}));
      if (body.error === 'preview_retryable') {
        return {status:'retryable', retryAfter:Number(body.retry_after) || 1};
      }
      throw new Error(String(body.error || response.status));
    }
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      const error = body.error || response.status;
      throw new Error(String(error));
    }
    if (type !== 'application/vnd.sablepanel.mesh-v2')
      throw new Error('preview_protocol_mismatch');
    const mesh = SablePreviewSpm2.parse(await response.arrayBuffer());
    if (mesh.metadata && mesh.metadata.resources && mesh.metadata.resources.status === 'busy') {
      return {status:'accepted', retryAfter:1};
    }
    return {status:'ready', mesh};
  }

  global.SablePreviewTransport = Object.freeze({request});
})(globalThis);
