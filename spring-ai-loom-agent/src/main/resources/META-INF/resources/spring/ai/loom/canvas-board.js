/**
 * Canvas Board — 聊天输入区「画板」全屏绘图模态框。
 *
 * 普通 script(非 module,同 market-admin.js 惯例):自身只负责画板 UI 与绘制,
 * 「确定」导出 PNG 后经 window._loomAgent.imageUpload.processFile(file) 复用
 * 聊天附件上传链路(缩略图 + state.pendingImages + 随消息发送),不另起端点。
 *
 * 绘制模型:重绘栈 —— 每条笔画/形状/文字/组件是 shapes[] 里的一个纯数据对象
 * (带稳定 id),任何变更(绘制/擦除/移动/缩放/改字/删除/撤销/重做/清空/缩放窗口)
 * 都全量重绘;橡皮与 Delete 键为"命中整体删除"。
 * 坐标一律用 CSS 逻辑像素,canvas 物理像素按 devicePixelRatio 放大防模糊。
 *
 * Web 组件(印章式 stencil):按钮/输入框/卡片/导航栏/图片占位/复选框/单选框/下拉,
 * 点击放置默认尺寸、拖拽放置自定义尺寸,落定即入栈(可输入文案);
 * 配合「选择」工具(↖)可拖动移动、角手柄缩放、双击改文字、Delete 删除 ——
 * 每次操纵 commit 快照,与撤销/重做语义完全一致。
 */
(function () {
  "use strict";

  const ERASER_TOLERANCE = 8; // 橡皮命中容差(CSS px)
  const HANDLE_SIZE = 6; // 缩放手柄边长
  const HANDLE_HIT = 8; // 手柄命中半径
  const MIN_SIZE = 10; // 缩放最小边长

  /** Web 组件元数据:默认尺寸 / 是否有文案 / 默认文案 / 文案浮层占位符。
   * 多段文案(表格列名/页签名/面包屑)用中英文逗号分隔。 */
  const STENCILS = {
    button: { defW: 120, defH: 40, hasText: true, placeholder: "按钮文案" },
    input: { defW: 200, defH: 40, hasText: true, placeholder: "占位提示" },
    textarea: { defW: 240, defH: 96, hasText: true, defaultText: "请输入描述…", placeholder: "首行占位文案" },
    tag: { defW: 72, defH: 24, hasText: true, defaultText: "已启用", placeholder: "标签文字" },
    switch: { defW: 96, defH: 28, hasText: true, defaultText: "启用", placeholder: "开关标签" },
    imgph: { defW: 160, defH: 120, hasText: false },
    formitem: { defW: 240, defH: 64, hasText: true, defaultText: "字段名", placeholder: "字段标签" },
    checkbox: { defW: 120, defH: 24, hasText: true, placeholder: "选项" },
    radio: { defW: 120, defH: 24, hasText: true, placeholder: "选项" },
    select: { defW: 160, defH: 40, hasText: true, placeholder: "请选择" },
    table: { defW: 480, defH: 220, hasText: true, defaultText: "名称,状态,操作", placeholder: "表头列名(逗号分隔)" },
    pagination: { defW: 240, defH: 32, hasText: false },
    toolbar: { defW: 480, defH: 48, hasText: true, defaultText: "新建", placeholder: "主按钮文案" },
    navbar: { defW: 480, defH: 56, hasText: true, placeholder: "页面标题" },
    tabs: { defW: 320, defH: 40, hasText: true, defaultText: "列表,已归档,回收站", placeholder: "页签名(逗号分隔)" },
    breadcrumb: { defW: 240, defH: 24, hasText: true, defaultText: "首页,列表,详情", placeholder: "层级(逗号分隔)" },
    modal: { defW: 360, defH: 240, hasText: true, defaultText: "对话框", placeholder: "对话框标题" },
    card: { defW: 240, defH: 160, hasText: true, placeholder: "卡片标题" },
  };
  const STENCIL_TYPES = new Set(Object.keys(STENCILS));

  const CanvasBoard = {
    // ── 状态 ──
    shapes: [], // 已提交形状: {id, type:'path'|'line'|'rect'|'ellipse'|'text'|<stencil>, ...}
    undoStack: [], // shapes 历史快照(每次提交前压入)
    redoStack: [],
    draft: null, // 绘制中的形状(rubber-band 预览)
    tool: "pen",
    stencilType: "button",
    color: "#000000",
    width: 3,
    drawing: false,
    selectedId: null, // 选择工具:当前选中 shape id(瞬时 UI 态,不进撤销栈)
    manip: null, // 进行中的操纵 {mode:'move'|'resize', handle?, start, orig, shapeId, dx?, dy?}
    _idSeq: 0,

    nextId() {
      this._idSeq += 1;
      return "s" + Date.now().toString(36) + "-" + this._idSeq;
    },

    init() {
      const $ = (id) => document.getElementById(id);

      const addBtn = $("canvas-add-btn");
      if (addBtn) addBtn.addEventListener("click", () => this.open());
      $("canvas-cancel-btn")?.addEventListener("click", () => this.close());
      $("canvas-close-btn")?.addEventListener("click", () => this.close());
      $("canvas-confirm-btn")?.addEventListener("click", () => this.confirm());
      $("canvas-clear-btn")?.addEventListener("click", () => this.clear());
      $("canvas-undo-btn")?.addEventListener("click", () => this.undo());
      $("canvas-redo-btn")?.addEventListener("click", () => this.redo());

      // 工具选择(含选择工具);切换时清组件印章的 active
      document.querySelectorAll("#canvas-toolbar .canvas-tool-btn[data-tool]")
        .forEach((btn) => btn.addEventListener("click", () => {
          this.tool = btn.dataset.tool;
          document.querySelectorAll("#canvas-toolbar .canvas-tool-btn[data-tool]")
            .forEach((b) => b.classList.toggle("active", b === btn));
          document.querySelectorAll("#canvas-toolbar .canvas-stencil-btn")
            .forEach((b) => b.classList.remove("active"));
          this.commitTextInput(); // 切工具时结算未提交的文字
        }));

      // 组件面板开合:「组件 ▾」切换;选中印章/ Esc / 面板外点击 均收起
      const stencilToggle = $("canvas-stencil-toggle");
      const stencilPanel = $("canvas-stencil-panel");
      stencilToggle?.addEventListener("click", (e) => {
        e.stopPropagation();
        stencilPanel.style.display =
          stencilPanel.style.display === "none" ? "grid" : "none";
      });
      document.addEventListener("pointerdown", (e) => {
        if (!stencilPanel || stencilPanel.style.display === "none") return;
        if (stencilPanel.contains(e.target) || e.target === stencilToggle) return;
        stencilPanel.style.display = "none";
      });

      // 组件印章选择
      document.querySelectorAll("#canvas-toolbar .canvas-stencil-btn")
        .forEach((btn) => btn.addEventListener("click", () => {
          this.tool = "stencil";
          this.stencilType = btn.dataset.stencil;
          if (stencilPanel) stencilPanel.style.display = "none";
          document.querySelectorAll("#canvas-toolbar .canvas-tool-btn[data-tool]")
            .forEach((b) => b.classList.remove("active"));
          document.querySelectorAll("#canvas-toolbar .canvas-stencil-btn")
            .forEach((b) => b.classList.toggle("active", b === btn));
          this.commitTextInput();
        }));

      // 预设色板
      document.querySelectorAll("#canvas-colors .canvas-swatch")
        .forEach((sw) => sw.addEventListener("click", () => {
          this.color = sw.dataset.color;
          document.querySelectorAll("#canvas-colors .canvas-swatch")
            .forEach((s) => s.classList.toggle("active", s === sw));
        }));

      // 自定义颜色
      $("canvas-custom-color")?.addEventListener("input", (e) => {
        this.color = e.target.value;
        document.querySelectorAll("#canvas-colors .canvas-swatch")
          .forEach((s) => s.classList.remove("active"));
      });

      // 粗细滑块
      $("canvas-width")?.addEventListener("input", (e) => {
        this.width = parseInt(e.target.value, 10) || 3;
        const label = $("canvas-width-value");
        if (label) label.textContent = String(this.width);
      });

      // 画布 pointer 事件(鼠标/触摸/笔统一)
      const canvas = $("canvas-board");
      canvas.addEventListener("pointerdown", (e) => this.onPointerDown(e));
      canvas.addEventListener("pointermove", (e) => this.onPointerMove(e));
      canvas.addEventListener("pointerup", (e) => this.onPointerUp(e));
      canvas.addEventListener("pointerleave", (e) => this.onPointerUp(e));
      // 双击组件改文字(选择工具语义;印章/绘制工具下前两次 click 各自完成
      // 放置/落笔,属预期行为 —— 改文案请切 ↖ 选择工具)
      canvas.addEventListener("dblclick", (e) => this.onDblClick(e));

      // 文字浮层输入:回车提交 / Esc 放弃;失焦提交
      const textInput = $("canvas-text-input");
      textInput.addEventListener("keydown", (e) => {
        if (e.key === "Enter") {
          e.preventDefault();
          this.commitTextInput();
        } else if (e.key === "Escape") {
          textInput.dataset.discard = "1";
          this.commitTextInput();
        }
      });
      textInput.addEventListener("blur", () => this.commitTextInput());

      // 键盘:Delete/Backspace 删除选中组件;Esc 取消选中(仅模态框打开且不在输入框里时)
      document.addEventListener("keydown", (e) => {
        const overlay = $("canvas-modal-overlay");
        if (!overlay || overlay.style.display !== "flex") return;
        const t = e.target;
        if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA")) return;
        if ((e.key === "Delete" || e.key === "Backspace") && this.selectedId != null) {
          e.preventDefault();
          this.commit(this.shapes.filter((s) => s.id !== this.selectedId));
          this.selectedId = null;
          this.manip = null;
          this.redraw();
        } else if (e.key === "Escape") {
          // 面板开着时 Esc 只关面板,不清选中
          const panel = $("canvas-stencil-panel");
          if (panel && panel.style.display !== "none") {
            panel.style.display = "none";
            return;
          }
          this.selectedId = null;
          this.manip = null;
          this.redraw();
        }
      });

      // 模态框打开期间窗口缩放 → 重设画布尺寸并重绘
      window.addEventListener("resize", () => {
        const overlay = $("canvas-modal-overlay");
        if (overlay && overlay.style.display === "flex") {
          this.resizeCanvas();
          this.redraw();
        }
      });
    },

    // ── 开合 ──
    open() {
      this.reset();
      document.getElementById("canvas-modal-overlay").style.display = "flex";
      this.resizeCanvas();
      this.redraw();
    },

    close() {
      this.commitTextInput();
      document.getElementById("canvas-modal-overlay").style.display = "none";
    },

    reset() {
      this.shapes = [];
      this.undoStack = [];
      this.redoStack = [];
      this.draft = null;
      this.drawing = false;
      this.selectedId = null;
      this.manip = null;
      // 工具回到画笔(与工具栏 active 态同步)
      this.tool = "pen";
      document.querySelectorAll("#canvas-toolbar .canvas-tool-btn[data-tool]")
        .forEach((b) => b.classList.toggle("active", b.dataset.tool === "pen"));
      document.querySelectorAll("#canvas-toolbar .canvas-stencil-btn")
        .forEach((b) => b.classList.remove("active"));
      const panel = document.getElementById("canvas-stencil-panel");
      if (panel) panel.style.display = "none";
    },

    /** 画布物理像素尺寸跟随容器 × devicePixelRatio;CSS 尺寸由样式表铺满。 */
    resizeCanvas() {
      const wrap = document.getElementById("canvas-board-wrap");
      const canvas = document.getElementById("canvas-board");
      if (!wrap || !canvas) return;
      const dpr = window.devicePixelRatio || 1;
      canvas.width = Math.max(Math.round(wrap.clientWidth * dpr), 1);
      canvas.height = Math.max(Math.round(wrap.clientHeight * dpr), 1);
      const ctx = canvas.getContext("2d");
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    },

    /** CSS 逻辑尺寸(绘制坐标系)。 */
    logicalSize() {
      const canvas = document.getElementById("canvas-board");
      const dpr = window.devicePixelRatio || 1;
      return { w: canvas.width / dpr, h: canvas.height / dpr };
    },

    // ── 绘制交互 ──
    eventPos(e) {
      const rect = e.target.getBoundingClientRect();
      return { x: e.clientX - rect.left, y: e.clientY - rect.top };
    },

    onPointerDown(e) {
      e.preventDefault();
      this.commitTextInput();
      const pos = this.eventPos(e);
      e.target.setPointerCapture?.(e.pointerId);

      if (this.tool === "eraser") {
        this.drawing = true;
        this.eraseAt(pos);
        return;
      }
      if (this.tool === "select") {
        this.selectDown(pos);
        return;
      }
      if (this.tool === "text") {
        this.openTextInput({ x: pos.x, y: pos.y, color: this.color, width: this.width });
        return;
      }
      this.drawing = true;
      if (this.tool === "stencil") {
        this.draft = {
          id: this.nextId(), type: this.stencilType, color: this.color,
          x0: pos.x, y0: pos.y, x1: pos.x, y1: pos.y,
        };
      } else if (this.tool === "pen") {
        this.draft = { id: this.nextId(), type: "path", color: this.color, width: this.width, points: [[pos.x, pos.y]] };
      } else {
        this.draft = {
          id: this.nextId(), type: this.tool, color: this.color, width: this.width,
          x0: pos.x, y0: pos.y, x1: pos.x, y1: pos.y,
        };
      }
    },

    onPointerMove(e) {
      if (!this.drawing && !this.manip) return;
      e.preventDefault();
      const pos = this.eventPos(e);
      if (this.manip) {
        this.manip.dx = pos.x - this.manip.start.x;
        this.manip.dy = pos.y - this.manip.start.y;
        this.redraw();
        return;
      }
      if (this.tool === "eraser") {
        this.eraseAt(pos);
        return;
      }
      if (!this.draft) return;
      if (this.draft.type === "path") {
        this.draft.points.push([pos.x, pos.y]);
      } else {
        this.draft.x1 = pos.x;
        this.draft.y1 = pos.y;
      }
      this.redraw();
    },

    onPointerUp(e) {
      if (this.manip) {
        const m = this.manip;
        this.manip = null;
        this.drawing = false;
        if (m.dx || m.dy) {
          const final = this.transformShape(m.orig, m);
          this.commit(this.shapes.map((s) => (s.id === m.shapeId ? final : s)));
        }
        this.redraw();
        return;
      }
      if (!this.drawing) return;
      this.drawing = false;
      if (!this.draft) return;

      if (STENCIL_TYPES.has(this.draft.type)) {
        const s = this.normalizeStencil(this.draft);
        this.commit(this.shapes.concat([s]));
        this.draft = null;
        this.redraw();
        // 有文案的组件放置后立即弹浮层输入(回车写入 / Esc 或空回车跳过)
        const meta = STENCILS[s.type];
        if (meta.hasText) {
          this.openTextInput({
            x: s.x, y: s.y, color: s.color, fontSize: 14,
            targetId: s.id, placeholder: meta.placeholder, value: s.text,
          });
        }
        return;
      }
      // 单点 click 也算一个点/path 或零长度形状,直接入栈(与拖拽同一路径)
      this.commit(this.shapes.concat([this.draft]));
      this.draft = null;
      this.redraw();
    },

    // ── 选择 / 操纵 ──
    selectDown(pos) {
      // 1) 已选中组件的缩放手柄优先
      const sel = this.shapes.find((s) => s.id === this.selectedId);
      if (sel) {
        const handle = this.handleAt(sel, pos);
        if (handle) {
          this.manip = {
            mode: "resize", handle, start: pos, dx: 0, dy: 0,
            orig: this.cloneShape(sel), shapeId: sel.id,
            origBox: this.shapeBBox(sel),
          };
          this.drawing = true;
          return;
        }
      }
      // 2) 命中组件/形状本体(最上层优先)→ 选中并进入移动模式
      for (let i = this.shapes.length - 1; i >= 0; i--) {
        if (this.hitTest(this.shapes[i], pos)) {
          this.selectedId = this.shapes[i].id;
          this.manip = {
            mode: "move", start: pos, dx: 0, dy: 0,
            orig: this.cloneShape(this.shapes[i]), shapeId: this.shapes[i].id,
          };
          this.drawing = true;
          this.redraw();
          return;
        }
      }
      // 3) 空白 → 取消选中
      this.selectedId = null;
      this.manip = null;
      this.redraw();
    },

    onDblClick(e) {
      const pos = this.eventPos(e);
      for (let i = this.shapes.length - 1; i >= 0; i--) {
        const s = this.shapes[i];
        if (this.hitTest(s, pos)) {
          if (STENCIL_TYPES.has(s.type) && STENCILS[s.type].hasText) {
            this.selectedId = s.id;
            const b = this.shapeBBox(s);
            this.openTextInput({
              x: b.x, y: b.y, color: s.color, fontSize: 14,
              targetId: s.id, placeholder: STENCILS[s.type].placeholder,
              value: s.text || "",
            });
          }
          this.redraw();
          return;
        }
      }
    },

    isResizable(type) {
      return STENCIL_TYPES.has(type) || type === "rect" || type === "ellipse";
    },

    /** 命中缩放手柄:返回 'nw'|'ne'|'sw'|'se' 或 null(仅 bbox 类形状有手柄)。 */
    handleAt(shape, pos) {
      if (!this.isResizable(shape.type)) return null;
      const b = this.shapeBBox(shape);
      const corners = {
        nw: [b.x, b.y], ne: [b.x + b.w, b.y],
        sw: [b.x, b.y + b.h], se: [b.x + b.w, b.y + b.h],
      };
      for (const [name, [cx, cy]] of Object.entries(corners)) {
        if (Math.abs(pos.x - cx) <= HANDLE_HIT && Math.abs(pos.y - cy) <= HANDLE_HIT) return name;
      }
      return null;
    },

    cloneShape(s) {
      const c = Object.assign({}, s);
      if (s.points) c.points = s.points.map((p) => [p[0], p[1]]);
      return c;
    },

    /** 按操纵参数生成新形状(纯函数:不改入参)。 */
    transformShape(orig, m) {
      const s = this.cloneShape(orig);
      if (m.mode === "move") {
        this.translateShape(s, m.dx, m.dy);
        return s;
      }
      // resize:仅 bbox 类
      const b = Object.assign({}, m.origBox || this.shapeBBox(orig));
      if (m.handle === "se") { b.w += m.dx; b.h += m.dy; }
      if (m.handle === "nw") { b.x += m.dx; b.y += m.dy; b.w -= m.dx; b.h -= m.dy; }
      if (m.handle === "ne") { b.y += m.dy; b.w += m.dx; b.h -= m.dy; }
      if (m.handle === "sw") { b.x += m.dx; b.w -= m.dx; b.h += m.dy; }
      if (b.w < MIN_SIZE) { b.w = MIN_SIZE; }
      if (b.h < MIN_SIZE) { b.h = MIN_SIZE; }
      if (STENCIL_TYPES.has(s.type)) {
        s.x = b.x; s.y = b.y; s.w = b.w; s.h = b.h;
      } else {
        s.x0 = b.x; s.y0 = b.y; s.x1 = b.x + b.w; s.y1 = b.y + b.h;
      }
      return s;
    },

    translateShape(s, dx, dy) {
      switch (s.type) {
        case "path":
          s.points = s.points.map(([x, y]) => [x + dx, y + dy]);
          break;
        case "line":
        case "rect":
        case "ellipse":
          s.x0 += dx; s.y0 += dy; s.x1 += dx; s.y1 += dy;
          break;
        default: // text + stencil 类
          s.x += dx; s.y += dy;
      }
    },

    // ── 形状栈操作(快照式撤销/重做) ──
    commit(newShapes) {
      this.undoStack.push(this.shapes);
      this.shapes = newShapes;
      this.redoStack = [];
    },

    /** 撤销/重做后清 manip;选中仅在 id 已消失时清除(操纵类提交保留同 id,选中跟随)。 */
    syncSelectionAfterHistory() {
      this.manip = null;
      if (this.selectedId != null && !this.shapes.some((s) => s.id === this.selectedId)) {
        this.selectedId = null;
      }
    },

    undo() {
      if (!this.undoStack.length) return;
      this.redoStack.push(this.shapes);
      this.shapes = this.undoStack.pop();
      this.syncSelectionAfterHistory();
      this.redraw();
    },

    redo() {
      if (!this.redoStack.length) return;
      this.undoStack.push(this.shapes);
      this.shapes = this.redoStack.pop();
      this.syncSelectionAfterHistory();
      this.redraw();
    },

    clear() {
      if (!this.shapes.length) return;
      this.commit([]);
      this.selectedId = null;
      this.manip = null;
      this.redraw();
    },

    /** 橡皮:命中检测删除整笔/整个组件(最上层优先)。 */
    eraseAt(pos) {
      for (let i = this.shapes.length - 1; i >= 0; i--) {
        if (this.hitTest(this.shapes[i], pos)) {
          // 先拿住 victim 引用:commit 后 this.shapes 已是新数组,旧下标失效
          const victim = this.shapes[i];
          this.commit(this.shapes.filter((_, idx) => idx !== i));
          if (this.selectedId === victim.id) this.selectedId = null;
          this.redraw();
          return;
        }
      }
    },

    hitTest(shape, pos) {
      if (STENCIL_TYPES.has(shape.type)) {
        const b = this.shapeBBox(shape);
        const t = 4;
        return pos.x >= b.x - t && pos.x <= b.x + b.w + t
          && pos.y >= b.y - t && pos.y <= b.y + b.h + t;
      }
      const tol = Math.max(shape.width ? shape.width / 2 : 0, 0) + ERASER_TOLERANCE;
      switch (shape.type) {
        case "path":
          return shape.points.some(([x, y]) => Math.hypot(x - pos.x, y - pos.y) <= tol);
        case "line":
          return this.distToSegment(pos, shape) <= tol;
        case "rect":
        case "ellipse":
        case "text": {
          const b = this.shapeBBox(shape);
          return pos.x >= b.x - tol && pos.x <= b.x + b.w + tol
            && pos.y >= b.y - tol && pos.y <= b.y + b.h + tol;
        }
        default:
          return false;
      }
    },

    distToSegment(pos, s) {
      const dx = s.x1 - s.x0;
      const dy = s.y1 - s.y0;
      const lenSq = dx * dx + dy * dy;
      const t = lenSq === 0 ? 0 : Math.min(1, Math.max(0,
        ((pos.x - s.x0) * dx + (pos.y - s.y0) * dy) / lenSq));
      return Math.hypot(pos.x - (s.x0 + t * dx), pos.y - (s.y0 + t * dy));
    },

    shapeBBox(shape) {
      if (STENCIL_TYPES.has(shape.type)) return this.stencilBBox(shape);
      if (shape.type === "text") {
        const fontSize = this.textFontSize(shape.width);
        const canvas = document.getElementById("canvas-board");
        const ctx = canvas.getContext("2d");
        ctx.font = `${fontSize}px system-ui, sans-serif`;
        const w = ctx.measureText(shape.text || "").width;
        return { x: shape.x, y: shape.y, w, h: fontSize };
      }
      const x = Math.min(shape.x0, shape.x1);
      const y = Math.min(shape.y0, shape.y1);
      return { x, y, w: Math.abs(shape.x1 - shape.x0), h: Math.abs(shape.y1 - shape.y0) };
    },

    /** 组件 bbox:已定型(x/y/w/h)与拖拽草稿(x0..y1)双态兼容。 */
    stencilBBox(s) {
      if (s.x !== undefined && s.w !== undefined) return { x: s.x, y: s.y, w: s.w, h: s.h };
      return {
        x: Math.min(s.x0, s.x1), y: Math.min(s.y0, s.y1),
        w: Math.abs(s.x1 - s.x0), h: Math.abs(s.y1 - s.y0),
      };
    },

    /** 草稿定型:拖拽 < 6px 视为点击 → 默认尺寸居中放置。 */
    normalizeStencil(draft) {
      const meta = STENCILS[draft.type];
      let x = Math.min(draft.x0, draft.x1);
      let y = Math.min(draft.y0, draft.y1);
      let w = Math.abs(draft.x1 - draft.x0);
      let h = Math.abs(draft.y1 - draft.y0);
      if (w < 6 || h < 6) {
        w = meta.defW; h = meta.defH;
        x = draft.x0 - w / 2; y = draft.y0 - h / 2;
      }
      return {
        id: draft.id, type: draft.type, x, y, w, h, color: draft.color,
        text: meta.defaultText || "",
      };
    },

    textFontSize(lineWidth) {
      return 14 + (lineWidth || 3) * 2;
    },

    /** 多段文案切分(表格列名/页签/面包屑),中英文逗号皆可。 */
    splitLabels(text) {
      return String(text || "").split(/[,，]/).map((s) => s.trim()).filter(Boolean);
    },

    // ── 文字工具 / 组件文案浮层 ──
    openTextInput(opts) {
      const input = document.getElementById("canvas-text-input");
      input.style.display = "block";
      input.style.left = opts.x + "px";
      input.style.top = opts.y + "px";
      input.style.fontSize = (opts.fontSize || this.textFontSize(opts.width)) + "px";
      input.style.color = opts.color;
      input.placeholder = opts.placeholder || "输入文字，回车确认";
      input.value = opts.value || "";
      input.dataset.x = String(opts.x);
      input.dataset.y = String(opts.y);
      input.dataset.color = opts.color;
      input.dataset.width = String(opts.width || 3);
      input.dataset.targetId = opts.targetId || "";
      input.focus();
      input.select();
    },

    commitTextInput() {
      const input = document.getElementById("canvas-text-input");
      if (!input || input.style.display === "none") return;
      const discarded = input.dataset.discard === "1";
      const text = input.value.trim();
      const targetId = input.dataset.targetId || null;
      input.style.display = "none";
      input.dataset.discard = "";
      input.value = "";

      if (discarded) return; // Esc 放弃:保留既有文案(含组件默认文案)

      if (targetId) {
        // 组件文案:写入既有 shape(内容没变则不产生撤销步)
        const target = this.shapes.find((s) => s.id === targetId);
        if (!target || (target.text || "") === text) return;
        this.commit(this.shapes.map((s) =>
          (s.id === targetId ? Object.assign({}, s, { text }) : s)));
        this.redraw();
        return;
      }
      if (!text) return;
      this.commit(this.shapes.concat([{
        id: this.nextId(),
        type: "text",
        text,
        color: input.dataset.color || this.color,
        width: parseInt(input.dataset.width, 10) || this.width,
        x: parseFloat(input.dataset.x) || 0,
        y: parseFloat(input.dataset.y) || 0,
      }]));
      this.redraw();
    },

    // ── 渲染 ──
    redraw() {
      const canvas = document.getElementById("canvas-board");
      const ctx = canvas.getContext("2d");
      const { w, h } = this.logicalSize();
      ctx.clearRect(0, 0, w, h);
      for (const shape of this.shapes) {
        let s = shape;
        if (this.manip && shape.id === this.manip.shapeId) {
          s = this.transformShape(this.manip.orig, this.manip);
        }
        this.drawShape(ctx, s);
      }
      if (this.draft) this.drawShape(ctx, this.draft);
      this.drawSelection(ctx);
    },

    drawShape(ctx, s) {
      if (STENCIL_TYPES.has(s.type)) {
        this.drawStencil(ctx, s);
        return;
      }
      ctx.save();
      ctx.strokeStyle = s.color;
      ctx.fillStyle = s.color;
      ctx.lineWidth = s.width;
      ctx.lineCap = "round";
      ctx.lineJoin = "round";
      switch (s.type) {
        case "path": {
          if (s.points.length === 1) {
            // 单点:画一个圆点
            ctx.beginPath();
            ctx.arc(s.points[0][0], s.points[0][1], s.width / 2, 0, Math.PI * 2);
            ctx.fill();
            break;
          }
          ctx.beginPath();
          ctx.moveTo(s.points[0][0], s.points[0][1]);
          for (let i = 1; i < s.points.length; i++) ctx.lineTo(s.points[i][0], s.points[i][1]);
          ctx.stroke();
          break;
        }
        case "line":
          ctx.beginPath();
          ctx.moveTo(s.x0, s.y0);
          ctx.lineTo(s.x1, s.y1);
          ctx.stroke();
          break;
        case "rect":
          ctx.strokeRect(
            Math.min(s.x0, s.x1), Math.min(s.y0, s.y1),
            Math.abs(s.x1 - s.x0), Math.abs(s.y1 - s.y0));
          break;
        case "ellipse": {
          const cx = (s.x0 + s.x1) / 2;
          const cy = (s.y0 + s.y1) / 2;
          const rx = Math.abs(s.x1 - s.x0) / 2;
          const ry = Math.abs(s.y1 - s.y0) / 2;
          ctx.beginPath();
          ctx.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2);
          ctx.stroke();
          break;
        }
        case "text":
          ctx.font = `${this.textFontSize(s.width)}px system-ui, sans-serif`;
          ctx.textBaseline = "top";
          ctx.fillText(s.text, s.x, s.y);
          break;
      }
      ctx.restore();
    },

    /** Web 组件渲染:白底 + 当前色描边/文案,原型图观感。 */
    drawStencil(ctx, s) {
      const b = this.stencilBBox(s);
      const text = s.text || "";
      ctx.save();
      ctx.lineWidth = 2;
      ctx.strokeStyle = s.color;
      ctx.fillStyle = s.color;
      ctx.font = "14px system-ui, sans-serif";

      const rr = (x, y, w, h, r) => {
        ctx.beginPath();
        if (ctx.roundRect) {
          ctx.roundRect(x, y, w, h, Math.min(r, w / 2, h / 2));
        } else {
          ctx.rect(x, y, w, h);
        }
      };
      const roundRectPath = (r) => rr(b.x, b.y, b.w, b.h, r);
      const fillWhite = () => {
        ctx.save();
        ctx.fillStyle = "#ffffff";
        ctx.fill();
        ctx.restore();
      };

      switch (s.type) {
        case "button":
          roundRectPath(8);
          fillWhite();
          ctx.stroke();
          if (text) {
            ctx.textAlign = "center";
            ctx.textBaseline = "middle";
            ctx.fillText(text, b.x + b.w / 2, b.y + b.h / 2);
          }
          break;
        case "input":
          roundRectPath(4);
          fillWhite();
          ctx.stroke();
          if (text) {
            ctx.save();
            ctx.globalAlpha = 0.55;
            ctx.textAlign = "left";
            ctx.textBaseline = "middle";
            ctx.font = "12px system-ui, sans-serif";
            ctx.fillText(text, b.x + 10, b.y + b.h / 2, b.w - 20);
            ctx.restore();
          }
          break;
        case "card":
          roundRectPath(8);
          fillWhite();
          ctx.stroke();
          if (text) {
            ctx.textAlign = "left";
            ctx.textBaseline = "top";
            ctx.font = "600 15px system-ui, sans-serif";
            ctx.fillText(text, b.x + 12, b.y + 12, b.w - 24);
          }
          // 标题分隔线(卡片观感)
          if (b.h > 48) {
            ctx.save();
            ctx.globalAlpha = 0.35;
            ctx.beginPath();
            ctx.moveTo(b.x + 1, b.y + 40);
            ctx.lineTo(b.x + b.w - 1, b.y + 40);
            ctx.stroke();
            ctx.restore();
          }
          break;
        case "navbar":
          ctx.beginPath();
          ctx.rect(b.x, b.y, b.w, b.h);
          fillWhite();
          ctx.stroke();
          if (text) {
            ctx.textAlign = "left";
            ctx.textBaseline = "middle";
            ctx.font = "600 16px system-ui, sans-serif";
            ctx.fillText(text, b.x + 16, b.y + b.h / 2, b.w * 0.6);
          }
          break;
        case "imgph": {
          ctx.beginPath();
          ctx.rect(b.x, b.y, b.w, b.h);
          fillWhite();
          ctx.save();
          ctx.globalAlpha = 0.6;
          ctx.stroke();
          // 对角叉 = 图片占位惯例
          ctx.beginPath();
          ctx.moveTo(b.x, b.y);
          ctx.lineTo(b.x + b.w, b.y + b.h);
          ctx.moveTo(b.x + b.w, b.y);
          ctx.lineTo(b.x, b.y + b.h);
          ctx.stroke();
          ctx.restore();
          break;
        }
        case "checkbox": {
          const size = Math.min(b.h, 16);
          const cy = b.y + b.h / 2;
          ctx.strokeRect(b.x, cy - size / 2, size, size);
          if (text) {
            ctx.textAlign = "left";
            ctx.textBaseline = "middle";
            ctx.font = "13px system-ui, sans-serif";
            ctx.fillText(text, b.x + size + 8, cy, b.w - size - 8);
          }
          break;
        }
        case "radio": {
          const size = Math.min(b.h, 16);
          const cy = b.y + b.h / 2;
          ctx.beginPath();
          ctx.arc(b.x + size / 2, cy, size / 2, 0, Math.PI * 2);
          ctx.stroke();
          if (text) {
            ctx.textAlign = "left";
            ctx.textBaseline = "middle";
            ctx.font = "13px system-ui, sans-serif";
            ctx.fillText(text, b.x + size + 8, cy, b.w - size - 8);
          }
          break;
        }
        case "select":
          roundRectPath(4);
          fillWhite();
          ctx.stroke();
          if (text) {
            ctx.save();
            ctx.globalAlpha = 0.75;
            ctx.textAlign = "left";
            ctx.textBaseline = "middle";
            ctx.font = "13px system-ui, sans-serif";
            ctx.fillText(text, b.x + 10, b.y + b.h / 2, b.w - 30);
            ctx.restore();
          }
          // 右侧下拉箭头
          {
            const ax = b.x + b.w - 16;
            const ay = b.y + b.h / 2;
            ctx.beginPath();
            ctx.moveTo(ax - 4, ay - 2);
            ctx.lineTo(ax + 4, ay - 2);
            ctx.lineTo(ax, ay + 3);
            ctx.closePath();
            ctx.fill();
          }
          break;
        case "textarea": {
          rr(b.x, b.y, b.w, b.h, 4);
          fillWhite();
          ctx.stroke();
          if (text) {
            ctx.save();
            ctx.globalAlpha = 0.55;
            ctx.font = "12px system-ui, sans-serif";
            ctx.textAlign = "left";
            ctx.textBaseline = "top";
            ctx.fillText(text, b.x + 10, b.y + 8, b.w - 20);
            ctx.restore();
          }
          // 占位横线
          ctx.save();
          ctx.globalAlpha = 0.25;
          ctx.lineWidth = 1;
          for (let ly = b.y + 30; ly < b.y + b.h - 14; ly += 18) {
            ctx.beginPath();
            ctx.moveTo(b.x + 10, ly);
            ctx.lineTo(b.x + b.w - 10, ly);
            ctx.stroke();
          }
          ctx.restore();
          // 右下 resize 标记
          ctx.save();
          ctx.lineWidth = 1;
          ctx.globalAlpha = 0.6;
          ctx.beginPath();
          ctx.moveTo(b.x + b.w - 10, b.y + b.h - 4);
          ctx.lineTo(b.x + b.w - 4, b.y + b.h - 10);
          ctx.moveTo(b.x + b.w - 14, b.y + b.h - 4);
          ctx.lineTo(b.x + b.w - 4, b.y + b.h - 14);
          ctx.stroke();
          ctx.restore();
          break;
        }
        case "tag": {
          roundRectPath(b.h / 2);
          ctx.save();
          ctx.globalAlpha = 0.12;
          ctx.fill();
          ctx.restore();
          ctx.stroke();
          if (text) {
            ctx.font = "12px system-ui, sans-serif";
            ctx.textAlign = "center";
            ctx.textBaseline = "middle";
            ctx.fillText(text, b.x + b.w / 2, b.y + b.h / 2 + 1, b.w - 12);
          }
          break;
        }
        case "switch": {
          const tw = Math.min(b.w * 0.5, 44);
          const th = Math.min(b.h, 24);
          const ty = b.y + (b.h - th) / 2;
          rr(b.x, ty, tw, th, th / 2);
          ctx.save();
          ctx.globalAlpha = 0.2;
          ctx.fill();
          ctx.restore();
          ctx.stroke();
          // 滑块(开启态靠右)
          ctx.beginPath();
          ctx.arc(b.x + tw - th / 2, ty + th / 2, th / 2 - 3, 0, Math.PI * 2);
          ctx.fill();
          if (text) {
            ctx.font = "13px system-ui, sans-serif";
            ctx.textAlign = "left";
            ctx.textBaseline = "middle";
            ctx.fillText(text, b.x + tw + 8, b.y + b.h / 2, b.w - tw - 8);
          }
          break;
        }
        case "formitem": {
          ctx.font = "13px system-ui, sans-serif";
          ctx.textAlign = "left";
          ctx.textBaseline = "top";
          ctx.fillText(text || " ", b.x, b.y + 2, b.w);
          rr(b.x, b.y + 22, b.w, b.h - 22, 4);
          fillWhite();
          ctx.stroke();
          break;
        }
        case "table": {
          rr(b.x, b.y, b.w, b.h, 6);
          fillWhite();
          ctx.stroke();
          const labels = this.splitLabels(text);
          const cols = Math.max(labels.length, 1);
          const colW = b.w / cols;
          const headH = 36;
          // 表头分隔线
          ctx.beginPath();
          ctx.moveTo(b.x + 1, b.y + headH);
          ctx.lineTo(b.x + b.w - 1, b.y + headH);
          ctx.stroke();
          // 列分隔线(表头以下)
          for (let c = 1; c < cols; c++) {
            const cx0 = b.x + colW * c;
            ctx.save();
            ctx.globalAlpha = 0.5;
            ctx.beginPath();
            ctx.moveTo(cx0, b.y + headH);
            ctx.lineTo(cx0, b.y + b.h - 1);
            ctx.stroke();
            ctx.restore();
          }
          // 表头列名
          ctx.font = "600 13px system-ui, sans-serif";
          ctx.textAlign = "center";
          ctx.textBaseline = "middle";
          labels.forEach((lb, c) =>
            ctx.fillText(lb, b.x + colW * (c + 0.5), b.y + headH / 2, colW - 12));
          // 3 数据行:行分隔 + 数据淡线 + 操作列小按钮
          const rows = 3;
          const rowH = (b.h - headH) / rows;
          for (let r = 0; r < rows; r++) {
            const ry = b.y + headH + rowH * r;
            if (r > 0) {
              ctx.save();
              ctx.globalAlpha = 0.5;
              ctx.beginPath();
              ctx.moveTo(b.x + 1, ry);
              ctx.lineTo(b.x + b.w - 1, ry);
              ctx.stroke();
              ctx.restore();
            }
            for (let c = 0; c < cols - 1; c++) {
              ctx.save();
              ctx.globalAlpha = 0.25;
              ctx.lineWidth = 3;
              ctx.beginPath();
              ctx.moveTo(b.x + colW * c + 12, ry + rowH / 2);
              ctx.lineTo(b.x + colW * (c + 1) - 12, ry + rowH / 2);
              ctx.stroke();
              ctx.restore();
            }
            const ax = b.x + colW * (cols - 1);
            for (let k = 0; k < 2; k++) {
              rr(ax + 6 + k * (colW / 2 - 2), ry + rowH / 2 - 9, colW / 2 - 10, 18, 4);
              ctx.save();
              ctx.globalAlpha = 0.7;
              ctx.stroke();
              ctx.restore();
            }
          }
          break;
        }
        case "pagination": {
          const sz = Math.min(b.h, 28);
          const y0 = b.y + (b.h - sz) / 2;
          const boxes = 5; // ‹ 1 2 3 ›
          const gap = 6;
          let px = b.x + b.w - (boxes * sz + (boxes - 1) * gap); // 右对齐
          for (let i = 0; i < boxes; i++) {
            rr(px, y0, sz, sz, 4);
            if (i === 1) {
              ctx.save();
              ctx.fill();
              ctx.restore();
            } else {
              fillWhite();
              ctx.stroke();
            }
            ctx.save();
            if (i === 1) ctx.fillStyle = "#ffffff";
            ctx.font = "12px system-ui, sans-serif";
            ctx.textAlign = "center";
            ctx.textBaseline = "middle";
            const glyph = i === 0 ? "‹" : i === 4 ? "›" : String(i);
            ctx.fillText(glyph, px + sz / 2, y0 + sz / 2 + 1);
            ctx.restore();
            px += sz + gap;
          }
          break;
        }
        case "toolbar": {
          rr(b.x, b.y, b.w, b.h, 6);
          fillWhite();
          ctx.stroke();
          const pad = 8;
          const ih = b.h - pad * 2;
          // 左:搜索框 + 占位线
          const sw = b.w * 0.5;
          rr(b.x + pad, b.y + pad, sw, ih, 4);
          ctx.save();
          ctx.globalAlpha = 0.8;
          ctx.stroke();
          ctx.globalAlpha = 0.35;
          ctx.lineWidth = 3;
          ctx.beginPath();
          ctx.moveTo(b.x + pad + 10, b.y + b.h / 2);
          ctx.lineTo(b.x + pad + sw * 0.6, b.y + b.h / 2);
          ctx.stroke();
          ctx.restore();
          // 右:实心主按钮
          const bw2 = 88;
          rr(b.x + b.w - pad - bw2, b.y + pad, bw2, ih, 4);
          ctx.save();
          ctx.fill();
          if (text) {
            ctx.fillStyle = "#ffffff";
            ctx.font = "13px system-ui, sans-serif";
            ctx.textAlign = "center";
            ctx.textBaseline = "middle";
            ctx.fillText(text, b.x + b.w - pad - bw2 / 2, b.y + b.h / 2 + 1, bw2 - 12);
          }
          ctx.restore();
          break;
        }
        case "tabs": {
          const labels = this.splitLabels(text);
          const baseY = b.y + b.h - 1;
          ctx.save();
          ctx.globalAlpha = 0.4;
          ctx.beginPath();
          ctx.moveTo(b.x, baseY);
          ctx.lineTo(b.x + b.w, baseY);
          ctx.stroke();
          ctx.restore();
          const tw2 = b.w / Math.max(labels.length, 1);
          labels.forEach((lb, i) => {
            const cx0 = b.x + tw2 * i;
            ctx.save();
            ctx.font = (i === 0 ? "600 " : "") + "13px system-ui, sans-serif";
            ctx.textAlign = "center";
            ctx.textBaseline = "middle";
            if (i !== 0) ctx.globalAlpha = 0.6;
            ctx.fillText(lb, cx0 + tw2 / 2, b.y + b.h / 2 - 2, tw2 - 16);
            ctx.restore();
            if (i === 0) {
              ctx.save();
              ctx.lineWidth = 2;
              ctx.beginPath();
              ctx.moveTo(cx0 + 8, baseY);
              ctx.lineTo(cx0 + tw2 - 8, baseY);
              ctx.stroke();
              ctx.restore();
            }
          });
          break;
        }
        case "breadcrumb": {
          const labels = this.splitLabels(text);
          ctx.font = "13px system-ui, sans-serif";
          ctx.textAlign = "left";
          ctx.textBaseline = "middle";
          let tx = b.x;
          labels.forEach((lb, i) => {
            ctx.save();
            ctx.globalAlpha = i === labels.length - 1 ? 1 : 0.55;
            ctx.fillText(lb, tx, b.y + b.h / 2);
            tx += ctx.measureText(lb).width;
            ctx.restore();
            if (i < labels.length - 1) {
              ctx.save();
              ctx.globalAlpha = 0.4;
              ctx.fillText("/", tx + 6, b.y + b.h / 2);
              tx += 12 + ctx.measureText("/").width;
              ctx.restore();
            }
          });
          break;
        }
        case "modal": {
          rr(b.x, b.y, b.w, b.h, 8);
          fillWhite();
          ctx.stroke();
          const headH = 40;
          ctx.beginPath();
          ctx.moveTo(b.x + 1, b.y + headH);
          ctx.lineTo(b.x + b.w - 1, b.y + headH);
          ctx.stroke();
          // 标题 + 关闭 ×
          ctx.font = "600 14px system-ui, sans-serif";
          ctx.textAlign = "left";
          ctx.textBaseline = "middle";
          ctx.fillText(text || "", b.x + 16, b.y + headH / 2, b.w - 60);
          ctx.save();
          ctx.globalAlpha = 0.6;
          ctx.font = "16px system-ui, sans-serif";
          ctx.textAlign = "center";
          ctx.fillText("×", b.x + b.w - 20, b.y + headH / 2);
          ctx.restore();
          // 底栏 + 取消/确定
          const footH = 48;
          const fy = b.y + b.h - footH;
          ctx.save();
          ctx.globalAlpha = 0.6;
          ctx.beginPath();
          ctx.moveTo(b.x + 1, fy);
          ctx.lineTo(b.x + b.w - 1, fy);
          ctx.stroke();
          ctx.restore();
          const bw3 = 64;
          const bh3 = 28;
          const by3 = fy + (footH - bh3) / 2;
          rr(b.x + b.w - 16 - bw3 * 2 - 8, by3, bw3, bh3, 4);
          ctx.stroke();
          rr(b.x + b.w - 16 - bw3, by3, bw3, bh3, 4);
          ctx.save();
          ctx.fill();
          ctx.restore();
          break;
        }
      }
      ctx.restore();
    },

    /** 选中装饰:虚线框 + 可缩放形状的四角手柄(导出前必须清除,见 confirm)。 */
    drawSelection(ctx) {
      if (this.selectedId == null) return;
      const shape = this.shapes.find((s) => s.id === this.selectedId);
      if (!shape) return;
      let s = shape;
      if (this.manip && this.manip.shapeId === shape.id) {
        s = this.transformShape(this.manip.orig, this.manip);
      }
      const b = this.shapeBBox(s);
      ctx.save();
      ctx.strokeStyle = "#4f46e5";
      ctx.fillStyle = "#4f46e5";
      ctx.lineWidth = 1;
      ctx.setLineDash([5, 4]);
      ctx.strokeRect(b.x - 4, b.y - 4, b.w + 8, b.h + 8);
      ctx.setLineDash([]);
      if (this.isResizable(s.type)) {
        const corners = [
          [b.x - 4, b.y - 4], [b.x + b.w + 4, b.y - 4],
          [b.x - 4, b.y + b.h + 4], [b.x + b.w + 4, b.y + b.h + 4],
        ];
        for (const [hx, hy] of corners) {
          ctx.fillRect(hx - HANDLE_SIZE / 2, hy - HANDLE_SIZE / 2, HANDLE_SIZE, HANDLE_SIZE);
        }
      }
      ctx.restore();
    },

    // ── 导出 + 上传 ──
    /** 白底合成导出 PNG Blob(画布透明区在暗色界面/识图里会发黑,固定铺白)。 */
    exportPngBlob() {
      return new Promise((resolve, reject) => {
        const canvas = document.getElementById("canvas-board");
        const off = document.createElement("canvas");
        off.width = canvas.width;
        off.height = canvas.height;
        const ctx = off.getContext("2d");
        ctx.fillStyle = "#ffffff";
        ctx.fillRect(0, 0, off.width, off.height);
        ctx.drawImage(canvas, 0, 0);
        off.toBlob(
          (blob) => (blob ? resolve(blob) : reject(new Error("canvas toBlob 失败"))),
          "image/png");
      });
    },

    fileName() {
      const d = new Date();
      const p = (n) => String(n).padStart(2, "0");
      return `canvas-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}`
        + `-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}.png`;
    },

    async confirm() {
      this.commitTextInput();
      if (!this.shapes.length) {
        window._loomAgent?.showToast("画布为空,请先绘制内容", "error");
        return;
      }
      try {
        // 选中装饰(虚线框/手柄)绝不能烙进导出图
        this.selectedId = null;
        this.manip = null;
        this.redraw();
        const blob = await this.exportPngBlob();
        const file = new File([blob], this.fileName(), { type: "image/png" });
        this.close();
        this.reset();
        await window._loomAgent.imageUpload.processFile(file);
      } catch (err) {
        window._loomAgent?.showToast("画板导出失败:" + err.message, "error");
      }
    },
  };

  window.CanvasBoard = CanvasBoard;

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => CanvasBoard.init());
  } else {
    CanvasBoard.init();
  }
})();
