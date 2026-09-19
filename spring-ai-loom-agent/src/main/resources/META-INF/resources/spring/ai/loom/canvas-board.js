/**
 * Canvas Board — 聊天输入区「画板」全屏绘图模态框。
 *
 * 普通 script(非 module,同 market-admin.js 惯例):自身只负责画板 UI 与绘制,
 * 「确定」导出 PNG 后经 window._loomAgent.imageUpload.processFile(file) 复用
 * 聊天附件上传链路(缩略图 + state.pendingImages + 随消息发送),不另起端点。
 *
 * 绘制模型:重绘栈 —— 每条笔画/形状/文字是 shapes[] 里的一个纯数据对象,
 * 任何变更(绘制/擦除/撤销/重做/清空/缩放)都全量重绘,橡皮为"命中整笔删除"。
 * 坐标一律用 CSS 逻辑像素,canvas 物理像素按 devicePixelRatio 放大防模糊。
 */
(function () {
  "use strict";

  const ERASER_TOLERANCE = 8; // 橡皮命中容差(CSS px)

  const CanvasBoard = {
    // ── 状态 ──
    shapes: [], // 已提交形状: {type:'path'|'line'|'rect'|'ellipse'|'text', ...}
    undoStack: [], // shapes 历史快照(每次提交前压入)
    redoStack: [],
    draft: null, // 绘制中的形状(rubber-band 预览)
    tool: "pen",
    color: "#000000",
    width: 3,
    drawing: false,

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

      // 工具选择
      document.querySelectorAll("#canvas-toolbar .canvas-tool-btn[data-tool]")
        .forEach((btn) => btn.addEventListener("click", () => {
          this.tool = btn.dataset.tool;
          document.querySelectorAll("#canvas-toolbar .canvas-tool-btn[data-tool]")
            .forEach((b) => b.classList.toggle("active", b === btn));
          this.commitTextInput(); // 切工具时结算未提交的文字
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

      // 文字浮层输入:回车/失焦提交
      const textInput = $("canvas-text-input");
      textInput.addEventListener("keydown", (e) => {
        if (e.key === "Enter") {
          e.preventDefault();
          this.commitTextInput();
        }
      });
      textInput.addEventListener("blur", () => this.commitTextInput());

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
      if (this.tool === "text") {
        this.openTextInput(pos);
        return;
      }
      this.drawing = true;
      if (this.tool === "pen") {
        this.draft = { type: "path", color: this.color, width: this.width, points: [[pos.x, pos.y]] };
      } else {
        this.draft = {
          type: this.tool, color: this.color, width: this.width,
          x0: pos.x, y0: pos.y, x1: pos.x, y1: pos.y,
        };
      }
    },

    onPointerMove(e) {
      if (!this.drawing) return;
      e.preventDefault();
      const pos = this.eventPos(e);
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
      if (!this.drawing) return;
      this.drawing = false;
      if (this.draft) {
        // 单点 click 也算一个点/path 或零长度形状,直接入栈(与拖拽同一路径)
        this.commit(this.shapes.concat([this.draft]));
        this.draft = null;
        this.redraw();
      }
    },

    // ── 形状栈操作(快照式撤销/重做) ──
    commit(newShapes) {
      this.undoStack.push(this.shapes);
      this.shapes = newShapes;
      this.redoStack = [];
    },

    undo() {
      if (!this.undoStack.length) return;
      this.redoStack.push(this.shapes);
      this.shapes = this.undoStack.pop();
      this.redraw();
    },

    redo() {
      if (!this.redoStack.length) return;
      this.undoStack.push(this.shapes);
      this.shapes = this.redoStack.pop();
      this.redraw();
    },

    clear() {
      if (!this.shapes.length) return;
      this.commit([]);
      this.redraw();
    },

    /** 橡皮:命中检测删除整笔(最上层优先)。 */
    eraseAt(pos) {
      for (let i = this.shapes.length - 1; i >= 0; i--) {
        if (this.hitTest(this.shapes[i], pos)) {
          this.commit(this.shapes.filter((_, idx) => idx !== i));
          this.redraw();
          return;
        }
      }
    },

    hitTest(shape, pos) {
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

    textFontSize(lineWidth) {
      return 14 + (lineWidth || 3) * 2;
    },

    // ── 文字工具 ──
    openTextInput(pos) {
      const input = document.getElementById("canvas-text-input");
      input.style.display = "block";
      input.style.left = pos.x + "px";
      input.style.top = pos.y + "px";
      input.style.fontSize = this.textFontSize(this.width) + "px";
      input.style.color = this.color;
      input.value = "";
      input.dataset.x = String(pos.x);
      input.dataset.y = String(pos.y);
      input.dataset.color = this.color;
      input.dataset.width = String(this.width);
      input.focus();
    },

    commitTextInput() {
      const input = document.getElementById("canvas-text-input");
      if (!input || input.style.display === "none") return;
      const text = input.value.trim();
      input.style.display = "none";
      if (!text) return;
      this.commit(this.shapes.concat([{
        type: "text",
        text,
        color: input.dataset.color || this.color,
        width: parseInt(input.dataset.width, 10) || this.width,
        x: parseFloat(input.dataset.x) || 0,
        y: parseFloat(input.dataset.y) || 0,
      }]));
      input.value = "";
      this.redraw();
    },

    // ── 渲染 ──
    redraw() {
      const canvas = document.getElementById("canvas-board");
      const ctx = canvas.getContext("2d");
      const { w, h } = this.logicalSize();
      ctx.clearRect(0, 0, w, h);
      for (const shape of this.shapes) this.drawShape(ctx, shape);
      if (this.draft) this.drawShape(ctx, this.draft);
    },

    drawShape(ctx, s) {
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
