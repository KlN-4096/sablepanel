'use strict';
/* SPM2 解码器:只验证协议和建立紧凑视图,不创建业务数组或 Three 对象。 */
(function (global) {
  const HEADER_BYTES = 32;
  const VERSION = 2;
  const FLAG_SHELL_BITMAP = 1;
  const U16 = 8;
  const U32 = 16;
  const MAX_RESPONSE_BYTES = 30 * 1024 * 1024;
  const MAX_VOXELS = 400_000;

  function fail(message) { throw new Error('SPM2: ' + message); }

  function parse(buffer) {
    if (!(buffer instanceof ArrayBuffer)) fail('响应不是 ArrayBuffer');
    if (buffer.byteLength < HEADER_BYTES) fail('头部截断');
    if (buffer.byteLength > MAX_RESPONSE_BYTES) fail('响应超过 30 MiB 上限');
    const view = new DataView(buffer);
    if (view.getUint8(0) !== 0x53 || view.getUint8(1) !== 0x50
      || view.getUint8(2) !== 0x4d || view.getUint8(3) !== 0x32) fail('magic 错误');
    const version = view.getUint16(4, true);
    const flags = view.getUint16(6, true);
    const headerBytes = view.getUint32(8, true);
    const metadataBytes = view.getUint32(12, true);
    const voxelCount = view.getUint32(16, true);
    const shellBytes = view.getUint32(20, true);
    const recordBytes = view.getUint16(24, true);
    const reserved = view.getUint16(26, true);
    const payloadBytes = view.getUint32(28, true);
    if (voxelCount > MAX_VOXELS) fail('体素数量超过 400000');
    if (version !== VERSION || flags !== FLAG_SHELL_BITMAP
      || headerBytes !== HEADER_BYTES || reserved !== 0) fail('头部字段无效');
    if (recordBytes !== U16 && recordBytes !== U32) fail('记录宽度无效');
    const paddedMetadata = Math.ceil(metadataBytes / 4) * 4;
    const expectedPayload = paddedMetadata + voxelCount * recordBytes + shellBytes;
    if (shellBytes !== Math.ceil(voxelCount / 8) || expectedPayload !== payloadBytes
      || HEADER_BYTES + payloadBytes !== buffer.byteLength) fail('长度不一致');
    const decoder = new TextDecoder('utf-8', {fatal:true});
    let metadata;
    try {
      metadata = JSON.parse(decoder.decode(new Uint8Array(buffer, HEADER_BYTES, metadataBytes)));
    } catch (error) {
      fail('metadata 无效');
    }
    for (let i = metadataBytes; i < paddedMetadata; i++) {
      if (view.getUint8(HEADER_BYTES + i) !== 0) fail('metadata padding 非零');
    }
    const recordsOffset = HEADER_BYTES + paddedMetadata;
    const records = new (recordBytes === U16 ? Uint16Array : Uint32Array)(
      buffer, recordsOffset, voxelCount * 4);
    const shellOffset = recordsOffset + voxelCount * recordBytes;
    const shell = new Uint8Array(buffer, shellOffset, shellBytes);
    const states = Array.isArray(metadata.states) ? metadata.states : null;
    const width = Number(metadata.width), height = Number(metadata.height), depth = Number(metadata.depth);
    if (!states || Number(metadata.voxel_count) !== voxelCount
      || !Number.isInteger(width) || !Number.isInteger(height) || !Number.isInteger(depth)
      || width < 0 || height < 0 || depth < 0) fail('metadata 与主体不一致');
    for (let index = 0; index < voxelCount; index++) {
      const offset = index * 4;
      if (records[offset] >= width || records[offset + 1] >= height || records[offset + 2] >= depth
        || records[offset + 3] >= states.length) fail('体素记录越界');
    }
    /* 旋转组:一段连续体素 + 一个绕轴角度(轴承上的 Create contraption)。
       组必须两两不重叠且有序 —— 一个体素属于两个组的话,前端逐实例施加矩阵时就没有确定答案。 */
    const groups = metadata.groups === undefined ? [] : metadata.groups;
    if (!Array.isArray(groups)) fail('groups 不是数组');
    let groupEnd = 0;
    for (const group of groups) {
      const first = Number(group && group.first), count = Number(group && group.count);
      const pivot = group && group.pivot, angle = Number(group && group.angle);
      if (!Number.isInteger(first) || !Number.isInteger(count) || count <= 0
        || first < groupEnd || first + count > voxelCount) fail('旋转组区间越界或重叠');
      if (!Array.isArray(pivot) || pivot.length !== 3 || !pivot.every(Number.isFinite)) fail('旋转组 pivot 无效');
      if (!Number.isFinite(angle) || (group.axis !== 'x' && group.axis !== 'y' && group.axis !== 'z')) {
        fail('旋转组轴或角度无效');
      }
      groupEnd = first + count;
    }
    return Object.freeze({
      recordBytes, voxelCount, metadata, records, shell,
      isShell(index) { return index >= 0 && index < voxelCount && !!(shell[index >> 3] & (1 << (index & 7))); }
    });
  }

  global.SablePreviewSpm2 = Object.freeze({parse});
})(globalThis);
