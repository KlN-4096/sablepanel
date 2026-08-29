'use strict';
/* Three.js 场景器官:只接收结构、资源请求和回调,不读取业务列表或服务器状态。 */
(function (global) {
  const MAX_EDGE_VOXELS = 9000;
  const PICK_THROTTLE_MS = 60;
  const MAX_PICK_TRIANGLES = 2048;
  /* 相机挪动不到一格时半透明排序结果不会变,没必要每帧重排。 */
  const RESORT_DISTANCE_SQUARED = 1;

  class PreviewRuntime {
    constructor(options) {
      this.options = options || {};
      this.host = this.options.host;
      this.scene = null; this.camera = null; this.renderer = null;
      this.lowGroups = new Map(); this.highMeshes = []; this.translucentMeshes = [];
      this.lastSortOrigin = [Infinity, Infinity, Infinity];
      this.edgeLines = null; this.gridHelper = null; this.hoverBox = null;
      this.structure = null; this.voxelIndex = new Map();
      this.fallbackValues = new Map();
      this.simplifiedReasons = new Map();
      this.pickGeometries = new Map();
      this.center = [0, 0, 0]; this.distance = 50;
      this.dragging = false; this.panning = false; this.pointer = {x:0, y:0, cx:0, cy:0};
      this.rotX = .5; this.rotY = .7; this.autoRotate = localStorage.getItem('spRot') !== '0';
      this.rotSpeed = parseFloat(localStorage.getItem('spRotSpeed') || '.18');
      this.needPick = false; this.lastPick = 0; this.fullscreen = false;
      this.worker = null; this.frame = 0; this.resizeObserver = null;
      this.textureCache = new Map();
      this.textureModes = new Map();
      this.materials = new Map();
      this.groupMatrices = new Map();
      this.unsupported = false;
      this.highFidelityAvailable = !!(global.Worker && global.createImageBitmap);
      this.budget = {triangles:1_500_000, drawCalls:1024, textureEdge:1024, atlasSize:2048,
        gpuBytes:256 * 1024 * 1024, mainMemoryBytes:512 * 1024 * 1024};
      this.lastFrameAt = 0; this.performanceWindow = null; this.lodSimplified = 0;
    }

    init() {
      if (this.renderer || !global.THREE || !this.host) return this;
      const width = this.host.clientWidth || 470, height = this.host.clientHeight || 304;
      // 不强制 low/high power; GPU 选择交给浏览器和操作系统。
      try {
        // alpha:true=透明清屏,视口底色交给 CSS 的 --viewport-bg,双主题零引擎逻辑(webui-aero 章程允许的场景参数)
        this.renderer = new THREE.WebGLRenderer({antialias:true, alpha:true});
      } catch (error) {
        this.unsupported = true;
        this.options.onStatus && this.options.onStatus('unsupported', error.message || 'WebGL 初始化失败');
        return this;
      }
      const context = this.renderer.getContext && this.renderer.getContext();
      const webgl2 = typeof WebGL2RenderingContext !== 'undefined'
        ? context instanceof WebGL2RenderingContext : context && context.constructor && context.constructor.name === 'WebGL2RenderingContext';
      if (!webgl2) {
        this.renderer.dispose(); this.renderer.domElement.remove(); this.renderer = null; this.unsupported = true;
        this.options.onStatus && this.options.onStatus('unsupported', 'webgl2_required');
        return this;
      }
      const maxTextureSize = Number(context.getParameter(context.MAX_TEXTURE_SIZE)) || 0;
      if (maxTextureSize < 4096) {
        this.budget = {triangles:500_000, drawCalls:384, textureEdge:512,
          atlasSize:Math.min(1024, maxTextureSize), gpuBytes:96 * 1024 * 1024,
          mainMemoryBytes:192 * 1024 * 1024};
      }
      this.renderer.setClearColor(0x000000, 0);
      this.renderer.setPixelRatio(Math.min(global.devicePixelRatio || 1, 2));
      this.renderer.setSize(width, height);
      this.host.insertBefore(this.renderer.domElement, this.host.firstChild);
      this.scene = new THREE.Scene();
      this.camera = new THREE.PerspectiveCamera(50, width / height, .1, 6000);
      this.scene.add(new THREE.AmbientLight(0xffffff, .34));
      this.scene.add(new THREE.HemisphereLight(0xbdd4ff, 0x39301f, .55));
      const sun = new THREE.DirectionalLight(0xffffff, 1.35); sun.position.set(1.2, 2.2, 1.4); this.scene.add(sun);
      const back = new THREE.DirectionalLight(0x88aaff, .26); back.position.set(-1.5, .6, -1.2); this.scene.add(back);
      this.bindEvents(this.renderer.domElement);
      // 页面级单例,无整体拆卸路径(terminateWorker/disposeObjects 都不碰它);
      // 将来若要销毁 runtime,记得 disconnect —— 这是唯一没有释放口的资源
      this.resizeObserver = new ResizeObserver(() => this.resize());
      this.resizeObserver.observe(this.host);
      this.loop();
      return this;
    }

    load(structure, resource) {
      this.init();
      if (this.unsupported || !this.renderer) {
        this.options.onStatus && this.options.onStatus('unsupported', 'webgl2_required');
        return;
      }
      this.disposeObjects();
      this.textureCache.clear();
      this.textureModes.clear();
      this.groupMatrices.clear();
      this.structure = structure;
      if (!structure || !structure.voxelCount) {
        this.options.onStatus && this.options.onStatus('empty');
        return;
      }
      this.buildFallback(structure);
      this.options.onStatus && this.options.onStatus('fallback');
      if (resource && resource.manifestUrl) {
        if (this.highFidelityAvailable) this.startWorker(structure, resource);
        else this.options.onStatus && this.options.onStatus('resource_unavailable', '需要 Worker 与图像解码能力');
      }
    }

    resize() {
      if (!this.renderer || !this.camera) return;
      const box = this.fullscreen ? document.getElementById('fsCanvasBox') : this.host;
      if (!box || !box.clientWidth || !box.clientHeight) return;
      this.renderer.setSize(box.clientWidth, box.clientHeight);
      this.camera.aspect = box.clientWidth / box.clientHeight;
      this.camera.updateProjectionMatrix();
    }

    toggleRotate() {
      this.autoRotate = !this.autoRotate;
      localStorage.setItem('spRot', this.autoRotate ? '1' : '0');
      this.options.onRotate && this.options.onRotate(this.autoRotate);
    }

    setRotSpeed(value) {
      this.rotSpeed = Number(value) / 100 * 1.5;
      localStorage.setItem('spRotSpeed', String(this.rotSpeed));
      if (Number(value) > 0 && !this.autoRotate) this.toggleRotate();
    }

    openFullscreen(overlay, canvasBox, name, meta) {
      if (!this.renderer || !overlay || !canvasBox) return;
      this.fullscreen = true; overlay.style.display = 'block'; canvasBox.appendChild(this.renderer.domElement);
      const title = document.getElementById('fsName'), detail = document.getElementById('fsMeta');
      if (title) title.textContent = name || '';
      if (detail) detail.textContent = meta || '';
      this.resize();
    }

    closeFullscreen() {
      if (!this.renderer || !this.host) return;
      this.fullscreen = false;
      const overlay = document.getElementById('fsOverlay'); if (overlay) overlay.style.display = 'none';
      if (this.renderer.domElement.parentElement !== this.host) {
        this.host.insertBefore(this.renderer.domElement, this.host.firstChild);
      }
      this.resize(); this.hideHover();
    }

    terminateWorker() {
      if (this.worker) { this.worker.terminate(); this.worker = null; }
    }

    disposeObjects() {
      // keepWorker 时保留 worker(及其跨体缓存);terminate 对详情页兼作"取消进行中烘焙"
      if (!this.options.keepWorker) this.terminateWorker();
      if (this.hoverBox) this.hoverBox.visible = false;   // 位置属于上一个结构
      this.structure = null;
      for (const mesh of this.lowGroups.values()) this.disposeMesh(mesh);
      this.lowGroups.clear();
      for (const mesh of this.highMeshes) this.disposeMesh(mesh);
      this.highMeshes = []; this.refreshTranslucent();
      if (this.edgeLines) { this.disposeMesh(this.edgeLines); this.edgeLines = null; }
      if (this.gridHelper) { this.disposeMesh(this.gridHelper); this.gridHelper = null; }
      this.voxelIndex.clear();
      this.fallbackValues.clear();
      this.simplifiedReasons.clear();
      this.performanceWindow = null; this.lodSimplified = 0;
      this.pickGeometries.clear();
      for (const texture of this.textureCache.values()) texture.dispose();
      for (const material of this.materials.values()) material.dispose();
      this.textureCache.clear(); this.textureModes.clear(); this.materials.clear();
    }

    buildFallback(structure) {
      const records = structure.records, metadata = structure.metadata || {};
      const width = Number(metadata.width) || 0, depth = Number(metadata.depth) || 0;
      const groups = new Map(), max = [0, 0, 0];
      // 旋转组的体素在表里连续有序,一个随下标推进的游标就够,不用逐体素查区间
      const rotations = metadata.groups || [];
      let cursor = 0;
      for (let i = 0; i < structure.voxelCount; i++) {
        const o = i * 4, x = records[o], y = records[o + 1], z = records[o + 2], state = records[o + 3];
        // 整数序号索引:此前每个体素要拼两个字符串键、查两层 Map,几万方块就是十几万次字符串分配
        this.voxelIndex.set(voxelOrdinal(x, y, z, width, depth), i);
        max[0] = Math.max(max[0], x); max[1] = Math.max(max[1], y); max[2] = Math.max(max[2], z);
        while (cursor < rotations.length && i >= rotations[cursor].first + rotations[cursor].count) cursor++;
        if (structure.isShell && !structure.isShell(i)) continue;
        if (!groups.has(state)) groups.set(state, []);
        groups.get(state).push({i, x, y, z,
          g:cursor < rotations.length && i >= rotations[cursor].first ? cursor : -1});
      }
      this.fallbackValues = groups;
      for (const state of groups.keys()) this.restoreFallback(state);
      if (structure.voxelCount <= MAX_EDGE_VOXELS) this.buildEdges(structure);
      const span = Math.max(max[0], max[1], max[2]);
      this.gridHelper = new THREE.GridHelper(Math.max(16, span * 2.2), 20, 0x22304a, 0x161d2b);
      this.gridHelper.position.set(max[0] / 2, -1.5, max[2] / 2); this.scene.add(this.gridHelper);
      this.center = [max[0] / 2, max[1] / 2, max[2] / 2]; this.distance = span * 1.8 + 8;
    }

    createFallbackGeometry() {
      const geometry = new THREE.BoxGeometry(1, 1, 1);
      const colors = new Float32Array(geometry.getAttribute('position').count * 3).fill(1);
      geometry.setAttribute('color', new THREE.BufferAttribute(colors, 3));
      return geometry;
    }

    buildEdges(structure) {
      const records = structure.records, count = structure.voxelCount;
      const edges = [[0,0,0,1,0,0],[0,1,0,1,1,0],[0,0,1,1,0,1],[0,1,1,1,1,1],[0,0,0,0,1,0],[1,0,0,1,1,0],[0,0,1,0,1,1],[1,0,1,1,1,1],[0,0,0,0,0,1],[1,0,0,1,0,1],[0,1,0,0,1,1],[1,1,0,1,1,1]];
      let visible = 0; for (let i = 0; i < count; i++) if (!structure.isShell || structure.isShell(i)) visible++;
      const positions = new Float32Array(visible * 24 * 3); let offset = 0;
      for (let i = 0; i < count; i++) {
        if (structure.isShell && !structure.isShell(i)) continue;
        const o = i * 4, x = records[o] - .5, y = records[o + 1] - .5, z = records[o + 2] - .5;
        for (const edge of edges) { positions[offset++] = x + edge[0]; positions[offset++] = y + edge[1]; positions[offset++] = z + edge[2]; positions[offset++] = x + edge[3]; positions[offset++] = y + edge[4]; positions[offset++] = z + edge[5]; }
      }
      const geometry = new THREE.BufferGeometry(); geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
      this.edgeLines = new THREE.LineSegments(geometry, new THREE.LineBasicMaterial({color:0x000000, transparent:true, opacity:.22}));
      this.scene.add(this.edgeLines);
    }

    startWorker(structure, resource) {
      /* keepWorker(缩略图队列):跨体复用同一 worker,其模块级缓存(分片/模型/纹理位图)
         才能生效。调用方契约=上一次 bake 已终态(bake_done/failed)才再 load;
         中途放弃(超时/切服)必须 terminateWorker,别让旧 bake 的消息串进新体。 */
      const reused = this.worker && this.options.keepWorker;
      const worker = reused ? this.worker : (this.worker = new Worker('/js/preview/model-worker.js'));
      if (!reused) {
        worker.onmessage = event => {
          if (worker !== this.worker) return;
          const result = event.data || {};
          if (result.type === 'progress') { this.options.onStatus && this.options.onStatus('resource_progress', result); return; }
          if (result.type === 'failed') { this.options.onStatus && this.options.onStatus('resource_failed', result.message); return; }
          if (result.type === 'bake_textures' || result.type === 'bake_state') {
            this.applyBatches(result, false); return;
          }
          if (result.type !== 'bake_done') return;
          this.applyBatches(result, true);
        };
        worker.onerror = event => {
          if (worker === this.worker) this.options.onStatus && this.options.onStatus('resource_failed', event.message || 'Worker error');
        };
      }
      const records = structure.records;
      const copy = records.buffer.slice(records.byteOffset, records.byteOffset + records.byteLength);
      try {
        worker.postMessage({type:'bake', manifestUrl:resource.manifestUrl, token:resource.token || '', server:resource.server || '',
          resourceFingerprint:resource.fingerprint || '',
          recordBytes:structure.recordBytes, records:copy, palette:structure.metadata.states || [], metadata:structure.metadata,
          budget:this.budget}, [copy]);
        if (copy.byteLength !== 0) throw new Error('浏览器不支持 Transferable ArrayBuffer');
      } catch (error) {
        worker.terminate(); if (this.worker === worker) this.worker = null;
        this.highFidelityAvailable = false;
        this.options.onStatus && this.options.onStatus('resource_unavailable', error.message || String(error));
      }
    }

    applyBatches(result, finish = true) {
      if (result.simplified) {
        this.simplifiedReasons.clear();
        for (const item of result.simplified) this.simplifiedReasons.set(item.stateIndex, item.reason || 'model_or_budget');
      }
      for (const item of result.textures || []) {
        if (!item.bitmap || this.textureCache.has(item.path)) continue;
        const texture = new THREE.Texture(item.bitmap);
        texture.flipY = false;
        texture.magFilter = THREE.NearestFilter;
        texture.generateMipmaps = item.alpha !== 'cutout';
        texture.minFilter = texture.generateMipmaps && THREE.NearestMipmapNearestFilter
          ? THREE.NearestMipmapNearestFilter : THREE.NearestFilter;
        texture.needsUpdate = true;
        if ('colorSpace' in texture && THREE.SRGBColorSpace) texture.colorSpace = THREE.SRGBColorSpace;
        this.textureCache.set(item.path, texture);
        this.textureModes.set(item.path, item.alpha || 'solid');
      }
      for (const item of result.fallback || []) {
        const mesh = this.lowGroups.get(item.stateIndex), texture = this.textureCache.get(item.texture);
        if (mesh && texture) {
          if (item.uv && mesh.geometry && mesh.geometry.getAttribute('uv')) {
            const uv = mesh.geometry.getAttribute('uv');
            for (let index = 0; index < uv.count; index++) {
              uv.setXY(index, item.uv[0] + uv.getX(index) * item.uv[2],
                item.uv[1] + uv.getY(index) * item.uv[3]);
            }
            uv.needsUpdate = true;
          }
          const values = this.fallbackValues.get(item.stateIndex) || [];
          const white = new THREE.Color();
          for (let index = 0; index < values.length; index++) {
            mesh.setColorAt(index, white.setHex(0xffffff).multiplyScalar(shadeOf(values[index])));
          }
          if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
          mesh.material.map = texture; mesh.material.color.setHex(0xffffff);
          this.applyLayer(mesh.material, item.renderType || this.textureModes.get(item.texture)); mesh.material.needsUpdate = true;
        }
      }
      const upgraded = new Set(result.upgraded || []);
      for (const state of upgraded) {
        const low = this.lowGroups.get(state); if (low) { this.disposeMesh(low); this.lowGroups.delete(state); }
      }
      const resultBatches = result.batches || [];
      for (let batchIndex = 0; batchIndex < resultBatches.length; batchIndex++) {
        const batch = resultBatches[batchIndex];
        const geometry = new THREE.BufferGeometry();
        geometry.setAttribute('position', new THREE.BufferAttribute(batch.positions, 3));
        geometry.setAttribute('normal', new THREE.BufferAttribute(batch.normals, 3));
        geometry.setAttribute('uv', new THREE.BufferAttribute(batch.uvs, 2));
        if (batch.colors) geometry.setAttribute('color', new THREE.BufferAttribute(batch.colors, 3));
        geometry.setIndex(new THREE.BufferAttribute(batch.indices, 1)); geometry.computeBoundingSphere();
        const material = this.materialFor(batch);
        const mode = batch.renderType || this.textureModes.get(batch.texture);
        const futureCalls = resultBatches.length - batchIndex - 1;
        const availableCalls = Math.max(1, this.budget.drawCalls - this.lowGroups.size
          - this.highMeshes.length - futureCalls);
        const chunks = mode === 'translucent'
          ? spatialChunks(batch.instances, availableCalls) : [batch.instances];
        if (!chunks.length) { geometry.dispose(); material.dispose(); continue; }
        let geometries = this.pickGeometries.get(batch.stateIndex);
        if (!geometries) { geometries = new Set(); this.pickGeometries.set(batch.stateIndex, geometries); }
        const metadata = this.structure.metadata || {};
        geometries.add({geometry, cells:instanceCells(batch.instances,
          Number(metadata.width) || 0, Number(metadata.depth) || 0)});
        for (let chunkIndex = 0; chunkIndex < chunks.length; chunkIndex++) {
          const instances = chunks[chunkIndex], count = instances.length / 3;
          if (!count) continue;
          const mesh = new THREE.InstancedMesh(geometry, material, count), transform = new THREE.Matrix4();
          const spin = this.groupMatrix(batch.group);
          let centerX = 0, centerY = 0, centerZ = 0;
          for (let i = 0; i < count; i++) {
            transform.makeTranslation(instances[i * 3], instances[i * 3 + 1], instances[i * 3 + 2]);
            if (spin) transform.premultiply(spin);
            mesh.setMatrixAt(i, transform);
            // 半透明排序要按转完之后的位置,否则一片转开的帆会按装配时的位置排序
            centerX += transform.elements[12]; centerY += transform.elements[13];
            centerZ += transform.elements[14];
          }
          mesh.instanceMatrix.needsUpdate = true; if (mesh.computeBoundingSphere) mesh.computeBoundingSphere();
          mesh.userData.stateIndex = batch.stateIndex;
          mesh.userData.cost = (batch.indices.length / 3) * count;
          mesh.userData.translucent = mode === 'translucent';
          mesh.userData.chunkCenter = [centerX / count, centerY / count, centerZ / count];
          mesh.userData.disposeResources = chunkIndex === 0;
          this.highMeshes.push(mesh); this.scene.add(mesh);
        }
      }
      this.refreshTranslucent();
      if (finish) {
        if (this.edgeLines) { this.disposeMesh(this.edgeLines); this.edgeLines = null; }
        this.performanceWindow = {start:performance.now(), frames:0};
        this.options.onStatus && this.options.onStatus('high', result.stats || {});
      }
    }

    /* 渲染参数相同的批次共用一个材质实例。Three 按材质对象缓存着色器程序与 uniform 状态,
       每批各 new 一个 = 每个 draw call 都强制一次全量状态切换;而图集打包之后几百个批次
       通常只落在个位数图集页上,真正需要的材质就那么几个。共享实例由 disposeObjects
       统一释放,单个网格不能替别人销毁(见 disposeMesh)。 */
    /**
     * 旋转组的整体变换 T(pivot)·R(轴,角度)·T(-pivot),整组共用,逐实例左乘。
     *
     * 后端不在体素里施加角度:整数网格表示不了任意角度(硬取整会让方块互相重叠并打出空洞),
     * 所以体素存装配姿态、角度走 metadata,真正的旋转在这里做。几何体跟着一起转,
     * 方块自身的朝向(原木的 axis、栅栏的连接面)因此自动是对的,不用另做状态旋转。
     *
     * ponytail: 旋向未做目视校准。若螺旋桨转反了,把这里的 angle 取负即可,别动别处。
     */
    groupMatrix(index) {
      if (!(index >= 0)) return null;
      if (this.groupMatrices.has(index)) return this.groupMatrices.get(index);
      const group = (((this.structure || {}).metadata || {}).groups || [])[index];
      const angle = group ? Number(group.angle) : 0;
      let matrix = null;
      if (group && angle) {
        const axis = new THREE.Vector3(group.axis === 'x' ? 1 : 0,
          group.axis === 'y' ? 1 : 0, group.axis === 'z' ? 1 : 0);
        const pivot = group.pivot;
        matrix = new THREE.Matrix4().makeTranslation(pivot[0], pivot[1], pivot[2])
          .multiply(new THREE.Matrix4().makeRotationAxis(axis, angle * Math.PI / 180))
          .multiply(new THREE.Matrix4().makeTranslation(-pivot[0], -pivot[1], -pivot[2]));
      }
      this.groupMatrices.set(index, matrix);
      return matrix;
    }

    materialFor(batch) {
      const states = this.structure && this.structure.metadata && this.structure.metadata.states;
      const emission = Number(((states && states[batch.stateIndex]) || {}).light_emission || 0);
      const emissiveIntensity = Math.max(batch.emissive ? .25 : 0, Math.min(.45, emission / 15 * .45));
      const layer = this.textureModes.get(batch.texture) || '';
      const key = [batch.texture, batch.shade === false ? 'basic' : 'lambert',
        emissiveIntensity, !!batch.colors, layer].join('|');
      const cached = this.materials.get(key);
      if (cached) return cached;
      const options = {color:0xffffff, vertexColors:!!batch.colors,
        map:this.textureCache.get(batch.texture) || null};
      if (batch.shade !== false) {
        options.emissive = emissiveIntensity > 0 ? 0xffffff : 0x000000;
        options.emissiveIntensity = emissiveIntensity;
      }
      const material = batch.shade === false
        ? new THREE.MeshBasicMaterial(options) : new THREE.MeshLambertMaterial(options);
      this.applyLayer(material, layer);
      material.userData.shared = true;
      this.materials.set(key, material);
      return material;
    }

    bindEvents(canvas) {
      canvas.addEventListener('mousedown', event => {
        if (event.button === 1) event.preventDefault();   // 中键默认行为是自动滚屏
        this.dragging = true; this.panning = event.button === 1 || event.shiftKey;
        this.pointer.px = event.clientX; this.pointer.py = event.clientY;
      });
      global.addEventListener('mouseup', () => { this.dragging = false; this.panning = false; });
      global.addEventListener('mousemove', event => {
        if (this.dragging) {
          const dx = event.clientX - this.pointer.px, dy = event.clientY - this.pointer.py;
          if (this.panning) {
            /* Shift/中键拖拽 = 平移观察中心(高结构只旋转+缩放看不到顶):沿相机平面挪,
               步长随距离缩放。center 只存实例,buildFallback 每次装载重置,不进 localStorage
               ——持久化会让缩略图实例继承详情页的平移,所有卡片集体跑偏(autoRotate 前车之鉴)。 */
            const scale = this.distance * .0015;
            const right = new THREE.Vector3().setFromMatrixColumn(this.camera.matrix, 0);
            const up = new THREE.Vector3().setFromMatrixColumn(this.camera.matrix, 1);
            this.center[0] += (up.x * dy - right.x * dx) * scale;
            this.center[1] += (up.y * dy - right.y * dx) * scale;
            this.center[2] += (up.z * dy - right.z * dx) * scale;
          } else {
            this.rotY += dx * .008; this.rotX += dy * .008;
            this.rotX = Math.max(-1.5, Math.min(1.5, this.rotX));
          }
          this.pointer.px = event.clientX; this.pointer.py = event.clientY; this.hideHover();
        } else if (event.target === this.renderer.domElement) {
          const rect = this.renderer.domElement.getBoundingClientRect();
          this.pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1; this.pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;
          this.pointer.cx = event.clientX; this.pointer.cy = event.clientY; this.needPick = true;
          // 拾取有节流,提示框的"跟手"不能等它:每次移动都把坐标喂给外层挪框
          if (this.options.onPointerMove) this.options.onPointerMove(this.pointer);
        }
      });
      canvas.addEventListener('mouseleave', () => this.hideHover());
      canvas.addEventListener('wheel', event => { event.preventDefault(); this.distance *= 1 + Math.sign(event.deltaY) * .12; this.distance = Math.max(3, Math.min(2600, this.distance)); }, {passive:false});
      canvas.addEventListener('webglcontextlost', event => this.handleContextLost(event));
    }

    loop() {
      this.frame = global.requestAnimationFrame(() => this.loop());
      if (!this.renderer || !this.camera || !this.scene) return;
      const now = performance.now();
      const elapsed = this.lastFrameAt ? now - this.lastFrameAt : 16;
      this.lastFrameAt = now;
      const dt = Math.min(.1, elapsed / 1000);
      this.samplePerformance(now, elapsed);
      if (!this.dragging && this.autoRotate) this.rotY += this.rotSpeed * dt;
      this.camera.position.set(this.center[0] + this.distance * Math.cos(this.rotX) * Math.sin(this.rotY), this.center[1] + this.distance * Math.sin(this.rotX), this.center[2] + this.distance * Math.cos(this.rotX) * Math.cos(this.rotY));
      this.camera.lookAt(...this.center);
      this.sortTranslucent();
      if (this.needPick && !this.dragging && now - this.lastPick > PICK_THROTTLE_MS) { this.needPick = false; this.lastPick = now; this.pick(); }
      this.renderer.render(this.scene, this.camera);
    }

    samplePerformance(now, elapsed) {
      const sample = this.performanceWindow;
      if (!sample || !this.highMeshes.length) return;
      if (elapsed > 250 || (typeof document !== 'undefined' && document.hidden)) {
        sample.start = now; sample.frames = 0; return;
      }
      sample.frames++;
      const duration = now - sample.start;
      if (duration < 2000) return;
      const fps = sample.frames * 1000 / duration;
      /* 只按实测帧率降级。draw call 数只是"会不会卡"的代理指标,而预算值是拍出来的 ——
         画面明明流畅却因为这个数字超标把整组方块打回纯色,是实测里最难看的一条。 */
      if (fps < 30 && this.degradeCostliest()) {
        this.performanceWindow = {start:now, frames:0};
      } else {
        this.performanceWindow = null;
      }
    }

    degradeCostliest() {
      const costs = new Map();
      for (const value of this.highMeshes) {
        const state = value.userData.stateIndex;
        costs.set(state, (costs.get(state) || 0) + (value.userData.cost || 0));
      }
      let target = null, highest = -1;
      for (const [state, cost] of costs) if (cost > highest) { target = state; highest = cost; }
      if (target === null) return false;
      const kept = [];
      for (const value of this.highMeshes) {
        if (value.userData.stateIndex !== target) { kept.push(value); continue; }
        this.disposeMesh(value);
      }
      this.highMeshes = kept; this.refreshTranslucent();
      this.pickGeometries.delete(target);
      this.restoreFallback(target);
      this.simplifiedReasons.set(target, 'performance_lod');
      this.lodSimplified++;
      this.options.onStatus && this.options.onStatus('lod', {count:this.lodSimplified});
      return true;
    }

    /* 首次降级和 LOD/上下文丢失后的恢复走同一条路径,不留两份会各自漂移的实例化代码。 */
    restoreFallback(state) {
      if (this.lowGroups.has(state)) return;
      const values = this.fallbackValues.get(state);
      if (!values || !values.length) return;
      const entry = (this.structure && this.structure.metadata
        && this.structure.metadata.states || [])[state] || {};
      const emission = Number(entry.light_emission || 0), base = Number(entry.color || 0x7f7f7f);
      const material = new THREE.MeshLambertMaterial({vertexColors:true,
        emissive:emission > 0 ? 0xffffff : 0x000000, emissiveIntensity:Math.min(.45, emission / 15 * .45)});
      const mesh = new THREE.InstancedMesh(this.createFallbackGeometry(), material, values.length);
      const matrix = new THREE.Matrix4(), color = new THREE.Color();
      for (let index = 0; index < values.length; index++) {
        const value = values[index], spin = this.groupMatrix(value.g);
        matrix.makeTranslation(value.x, value.y, value.z);
        if (spin) matrix.premultiply(spin);
        mesh.setMatrixAt(index, matrix);
        // setColorAt 把值拷进实例缓冲,复用同一个 Color 即可,不必每实例 clone
        mesh.setColorAt(index, color.setHex(base).multiplyScalar(shadeOf(value)));
      }
      mesh.instanceMatrix.needsUpdate = true; if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
      this.lowGroups.set(state, mesh); this.scene.add(mesh);
    }

    pick() {
      if (!this.structure || !this.renderer) return;
      const raycaster = new THREE.Raycaster(); raycaster.setFromCamera(this.pointer, this.camera);
      const origin = raycaster.ray.origin, direction = raycaster.ray.direction;
      const metadata = this.structure.metadata || {};
      const width = Number(metadata.width) || 0, height = Number(metadata.height) || 0, depth = Number(metadata.depth) || 0;
      const hit = rayBox(origin.x + .5, origin.y + .5, origin.z + .5,
        direction.x, direction.y, direction.z, width, height, depth);
      if (!hit) { this.hideHover(); return; }
      let distance = Math.max(0, hit[0]) + 1e-7;
      let px = origin.x + .5 + direction.x * distance;
      let py = origin.y + .5 + direction.y * distance;
      let pz = origin.z + .5 + direction.z * distance;
      let x = Math.min(width - 1, Math.max(0, Math.floor(px)));
      let y = Math.min(height - 1, Math.max(0, Math.floor(py)));
      let z = Math.min(depth - 1, Math.max(0, Math.floor(pz)));
      const sx = Math.sign(direction.x), sy = Math.sign(direction.y), sz = Math.sign(direction.z);
      const dx = sx ? 1 / Math.abs(direction.x) : Infinity;
      const dy = sy ? 1 / Math.abs(direction.y) : Infinity;
      const dz = sz ? 1 / Math.abs(direction.z) : Infinity;
      let tx = sx ? distance + ((sx > 0 ? x + 1 : x) - px) / direction.x : Infinity;
      let ty = sy ? distance + ((sy > 0 ? y + 1 : y) - py) / direction.y : Infinity;
      let tz = sz ? distance + ((sz > 0 ? z + 1 : z) - pz) / direction.z : Infinity;
      /* 悬停走的是体素网格 DDA,网格里存的是装配姿态,所以转过角度的 contraption 指不中
         (画面里它在别处)。ponytail: 真要修就得对每个旋转组用逆矩阵再打一条射线,
         为几十个方块的悬停提示不值当。 */
      const limit = Math.min(4096, width + height + depth + 3);
      for (let step = 0; step < limit && distance <= hit[1]; step++) {
        const index = this.voxelIndex.get(voxelOrdinal(x, y, z, width, depth));
        if (index !== undefined) {
          const stateIndex = this.structure.records[index * 4 + 3];
          const cellExit = Math.min(tx, ty, tz, hit[1]);
          if (!this.pickGeometries.has(stateIndex)
              || this.intersectsState(stateIndex, x, y, z, origin, direction, distance, cellExit)) {
            this.showHoverBox(x, y, z);
            this.options.onHover && this.options.onHover(index, this.pointer, this.simplifiedReasons.get(stateIndex) || '');
            return;
          }
        }
        if (tx <= ty && tx <= tz) { x += sx; distance = tx; tx += dx; }
        else if (ty <= tz) { y += sy; distance = ty; ty += dy; }
        else { z += sz; distance = tz; tz += dz; }
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= depth) break;
      }
      this.hideHover();
    }

    /* 悬停方块描边:白框叠黑框(深浅底都读得出),关深度测试保证被遮部分也可见。
       位置取体素网格坐标(实例平移即立方体中心),与悬停 DDA 同一坐标系 ——
       转过角度的旋转组 DDA 本就指不中,这里同限,不另做逆矩阵。 */
    showHoverBox(x, y, z) {
      if (!this.scene) return;
      if (!this.hoverBox) {
        const group = new THREE.Group();
        const dark = new THREE.LineSegments(
          new THREE.EdgesGeometry(new THREE.BoxGeometry(1.10, 1.10, 1.10)),
          new THREE.LineBasicMaterial({color:0x000000, transparent:true, opacity:.5, depthTest:false}));
        const lit = new THREE.LineSegments(
          new THREE.EdgesGeometry(new THREE.BoxGeometry(1.04, 1.04, 1.04)),
          new THREE.LineBasicMaterial({color:0xffffff, transparent:true, opacity:.92, depthTest:false}));
        dark.renderOrder = 9998; lit.renderOrder = 9999;
        group.add(dark); group.add(lit);
        this.hoverBox = group;
        this.scene.add(group);
      }
      this.hoverBox.position.set(x, y, z);
      this.hoverBox.visible = true;
    }

    hideHover() {
      if (this.hoverBox) this.hoverBox.visible = false;
      this.options.onHover && this.options.onHover(null);
    }
    intersectsState(state, x, y, z, origin, direction, near, far) {
      let tested = 0;
      const metadata = this.structure && this.structure.metadata || {};
      const cell = voxelOrdinal(x, y, z, Number(metadata.width) || 0, Number(metadata.depth) || 0);
      for (const entry of this.pickGeometries.get(state) || []) {
        if (!hasCell(entry.cells, cell)) continue;
        const geometry = entry.geometry;
        const position = geometry.getAttribute('position');
        const indices = geometry.index && geometry.index.array;
        if (!position || !indices) continue;
        const values = position.array;
        for (let offset = 0; offset + 2 < indices.length; offset += 3) {
          if (++tested > MAX_PICK_TRIANGLES) return true;
          const a = indices[offset] * 3, b = indices[offset + 1] * 3, c = indices[offset + 2] * 3;
          const distance = rayTriangle(origin.x - x, origin.y - y, origin.z - z,
            direction.x, direction.y, direction.z,
            values[a], values[a + 1], values[a + 2], values[b], values[b + 1], values[b + 2],
            values[c], values[c + 1], values[c + 2]);
          if (distance !== null && distance >= near - 1e-6 && distance <= far + 1e-6) return true;
        }
      }
      return false;
    }
    /* 半透明列表只在 highMeshes 变动时重建;排序只在相机真的挪过一格后重做。
       此前是每帧一次 filter + sort,60 FPS 下纯造垃圾。 */
    refreshTranslucent() {
      this.translucentMeshes = this.highMeshes.filter(mesh => mesh.userData.translucent);
      this.lastSortOrigin = [Infinity, Infinity, Infinity];
    }

    sortTranslucent() {
      const transparent = this.translucentMeshes;
      if (!transparent.length) return;
      const position = this.camera.position;
      if (distanceSquared(this.lastSortOrigin, position) < RESORT_DISTANCE_SQUARED) return;
      this.lastSortOrigin = [position.x, position.y, position.z];
      transparent.sort((left, right) => distanceSquared(right.userData.chunkCenter, position)
        - distanceSquared(left.userData.chunkCenter, position));
      for (let index = 0; index < transparent.length; index++) transparent[index].renderOrder = index + 1;
    }
    handleContextLost(event) {
      if (event && event.preventDefault) event.preventDefault();
      this.highFidelityAvailable = false;
      if (this.worker) { this.worker.terminate(); this.worker = null; }
      const states = new Set(this.highMeshes.map(mesh => mesh.userData.stateIndex));
      for (const mesh of this.highMeshes) this.disposeMesh(mesh);
      this.highMeshes = []; this.refreshTranslucent(); this.pickGeometries.clear();
      for (const mesh of this.lowGroups.values()) this.disposeMesh(mesh);
      this.lowGroups.clear();
      for (const state of this.fallbackValues.keys()) this.restoreFallback(state);
      for (const state of states) this.simplifiedReasons.set(state, 'context_lost');
      for (const texture of this.textureCache.values()) texture.dispose();
      for (const material of this.materials.values()) material.dispose();
      this.textureCache.clear(); this.textureModes.clear(); this.materials.clear();
      this.performanceWindow = null;
      this.options.onStatus && this.options.onStatus('resource_unavailable', 'WebGL 上下文已丢失，本次预览已简化');
    }
    applyLayer(material, mode) {
      if (mode === 'translucent') { material.transparent = true; material.depthWrite = false; }
      else if (mode === 'cutout' || mode === 'cutout_mipped') material.alphaTest = .5;
    }
    disposeMesh(value) {
      /* 摘出场景必须在"共享资源提前返回"之前:分块网格共用几何,资源只由第 0 块释放,
         但每一块都得离开场景图。此前 disposeObjects 只释放不摘除,切换物理体后
         旧网格留在 scene 里被 loop 每帧照画 —— 画面停在上一个体。 */
      if (this.scene) this.scene.remove(value);
      if (value.userData && value.userData.disposeResources === false) return;
      if (value.geometry) value.geometry.dispose();
      if (value.material) {
        const materials = Array.isArray(value.material) ? value.material : [value.material];
        // 共享材质跨批次复用,只能由 disposeObjects 统一释放
        materials.forEach(material => { if (!(material.userData || {}).shared) material.dispose(); });
      }
    }
  }

  function spatialChunks(instances, maxChunks) {
    if (!instances || !instances.length) return [];
    let size = 16, chunks;
    do {
      const groups = new Map();
      for (let index = 0; index < instances.length; index += 3) {
        const key = Math.floor(instances[index] / size) + ',' + Math.floor(instances[index + 1] / size)
          + ',' + Math.floor(instances[index + 2] / size);
        let values = groups.get(key); if (!values) { values = []; groups.set(key, values); }
        values.push(instances[index], instances[index + 1], instances[index + 2]);
      }
      chunks = [...groups.values()].map(values => new Float32Array(values));
      size *= 2;
    } while (chunks.length > maxChunks && size <= 65536);
    return chunks.length <= maxChunks ? chunks : [instances];
  }
  function distanceSquared(point, camera) {
    const dx = point[0] - camera.x, dy = point[1] - camera.y, dz = point[2] - camera.z;
    return dx * dx + dy * dy + dz * dz;
  }

  function voxelOrdinal(x, y, z, width, depth) { return (y * depth + z) * width + x; }
  function instanceCells(instances, width, depth) {
    const cells = new Float64Array(instances.length / 3);
    for (let index = 0; index < cells.length; index++) {
      cells[index] = voxelOrdinal(instances[index * 3], instances[index * 3 + 1], instances[index * 3 + 2], width, depth);
    }
    cells.sort(); return cells;
  }
  function hasCell(cells, value) {
    let low = 0, high = cells.length - 1;
    while (low <= high) {
      const middle = (low + high) >>> 1, current = cells[middle];
      if (current === value) return true;
      if (current < value) low = middle + 1; else high = middle - 1;
    }
    return false;
  }
  function rayTriangle(ox, oy, oz, dx, dy, dz, ax, ay, az, bx, by, bz, cx, cy, cz) {
    const e1x = bx - ax, e1y = by - ay, e1z = bz - az;
    const e2x = cx - ax, e2y = cy - ay, e2z = cz - az;
    const px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
    const determinant = e1x * px + e1y * py + e1z * pz;
    if (Math.abs(determinant) < 1e-9) return null;
    const inverse = 1 / determinant, tx = ox - ax, ty = oy - ay, tz = oz - az;
    const u = (tx * px + ty * py + tz * pz) * inverse;
    if (u < 0 || u > 1) return null;
    const qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
    const v = (dx * qx + dy * qy + dz * qz) * inverse;
    if (v < 0 || u + v > 1) return null;
    const distance = (e2x * qx + e2y * qy + e2z * qz) * inverse;
    return distance >= 0 ? distance : null;
  }
  function hash3(x, y, z) { return ((x * 73856093) ^ (y * 19349663) ^ (z * 83492791)) >>> 0; }
  function shadeOf(value) { return .93 + (hash3(value.x, value.y, value.z) % 1000) / 1000 * .14; }
  function rayBox(ox, oy, oz, dx, dy, dz, width, height, depth) {
    if (width <= 0 || height <= 0 || depth <= 0) return null;
    let near = -Infinity, far = Infinity;
    for (const [origin, direction, maximum] of [[ox,dx,width],[oy,dy,height],[oz,dz,depth]]) {
      if (Math.abs(direction) < 1e-12) {
        if (origin < 0 || origin > maximum) return null;
        continue;
      }
      let first = -origin / direction, second = (maximum - origin) / direction;
      if (first > second) [first, second] = [second, first];
      near = Math.max(near, first); far = Math.min(far, second);
      if (near > far) return null;
    }
    return far < 0 ? null : [near, far];
  }
  global.SablePreviewRuntime = PreviewRuntime;
})(globalThis);
