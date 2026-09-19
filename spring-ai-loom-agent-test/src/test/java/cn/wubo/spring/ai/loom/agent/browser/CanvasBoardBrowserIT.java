package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 聊天画板(Canvas Board)浏览器 IT。
 *
 * <p><b>校准点(经 index.html / app.js / canvas-board.js 源码核实):</b>
 * <ol>
 *   <li><b>入口按钮</b>:{@code #canvas-add-btn} 位于输入区 {@code .input-row},
 *       紧邻 {@code #image-add-btn}(+ 上传按钮)右侧,同款 {@code .image-add-btn}
 *       样式类。<b>恒可见</b>(2026-09-19 解耦:聊天附件/画板不依赖 RAG,
 *       无 embedding 部署同样显示);{@link #waitForUploadGate} 保留作渲染就绪兜底。</li>
 *   <li><b>模态框</b>:{@code #canvas-modal-overlay}(沿用 {@code .modal-overlay}
 *       惯例,showModal/hideModal 设 inline {@code display:flex/none}),内含
 *       画布 {@code <canvas id="canvas-board">}、取消 {@code #canvas-cancel-btn}、
 *       确定 {@code #canvas-confirm-btn}。canvas-board.js 为普通 script
 *       (先于 app.js module 求值,DOMContentLoaded 后绑定事件)。</li>
 *   <li><b>就绪门</b>:app.js 为 type=module 延迟脚本,统一等
 *       {@code window._loomAgent}(模块求值完成)后再交互,同
 *       IndexInteractionsBrowserIT 惯例。</li>
 *   <li><b>画布静止裁定</b>:{@code .canvas-modal-content} 显式
 *       {@code animation: none}(style.css)—— 通用 {@code .modal-content} 的
 *       slideUp 入场动画会在拖拽期间位移画布,导致笔画按事件瞬间坐标记录成
 *       偏移曲线、后续命中检测(橡皮/像素断言)失准。所有绘制交互依赖
 *       模态框打开后画布<b不发生位移</b>,该规则不可回退。</li>
 *   <li><b>Web 组件印章</b>:{@code .canvas-stencil-btn[data-stencil]} 8 件
 *       (button/input/card/navbar/imgph/checkbox/radio/select)。点击放置=默认
 *       尺寸居中(拖拽 &lt;6px 判定),拖拽放置=自定义尺寸;定型后入栈为带 id 的
 *       复合 shape,有文案的组件立即弹 {@code #canvas-text-input} 浮层(targetId
 *       写入既有 shape,文案未变不产生撤销步)。像素指纹采样区为画布中心
 *       300×100,放置类断言须落在该区内。</li>
 *   <li><b>选择工具操纵</b>:{@code [data-tool=select]} —— 点本体选中并移动、
 *       四角手柄(bbox 类 shape)缩放、双击改文字、Delete 删除、Esc 取消;
 *       选中态是瞬时 UI 态不进撤销栈,但撤销/重做保留同 id 形状的选中
 *       ({@code syncSelectionAfterHistory}),仅 id 消失才清除。每次操纵一次
 *       commit 快照 → 移动/缩放/删除/改字各自可独立撤销。</li>
 * </ol>
 *
 * <p><b>数据隔离</b>:上传用例经真实 POST 落盘 {@code ./target/e2e-files/users}
 * (BrowserTestBase 覆盖 users-base-path),用例内移除缩略图清场;其余用例
 * 仅模态框开合,无服务端写入。
 */
@DisplayName("画板:入口按钮 / 全屏模态框开合 / 绘制上传为聊天附件")
class CanvasBoardBrowserIT extends BrowserTestBase {

    private static final Page.WaitForFunctionOptions WF_10S =
            new Page.WaitForFunctionOptions().setTimeout(10_000);

    private Page openIndex(BrowserContext ctx) {
        Page page = newPage(ctx);
        page.navigate(baseUrl + UI + "index.html");
        page.waitForSelector("#textarea");
        // 就绪门:模块求值完成(bindAllEvents 已同步跑完,所有按钮 handler 就位)
        page.waitForFunction("() => !!window._loomAgent", null, WF_10S);
        return page;
    }

    /** 输入行渲染就绪兜底等待(按钮自 2026-09-19 解耦后恒可见,不再承担门控语义)。 */
    private void waitForUploadGate(Page page) {
        page.waitForFunction(
                "() => { const b = document.getElementById('image-add-btn');"
                        + " return !!b && getComputedStyle(b).display !== 'none'; }",
                null, WF_10S);
    }

    @Test
    @DisplayName("入口按钮与 + 同门可见;点击开模态框,取消关闭")
    void canvasButtonVisibleAndModalOpensCloses() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openIndex(ctx);
            waitForUploadGate(page);

            // 入口按钮存在、可见,且位于 .input-row 内(与 + 同排)
            assertThat(page.isVisible("#canvas-add-btn"))
                    .as("canvas 按钮与 + 按钮同门放行后可见").isTrue();
            assertThat((Boolean) page.evaluate(
                    "() => !!document.querySelector('.input-row #canvas-add-btn')"))
                    .as("canvas 按钮挂在 .input-row(输入框下按钮排)").isTrue();

            // 点击 → 全屏模态框打开,画布就位且有实际尺寸
            page.click("#canvas-add-btn");
            page.waitForFunction(
                    "() => document.getElementById('canvas-modal-overlay').style.display === 'flex'",
                    null, WF_10S);
            assertThat(page.isVisible("#canvas-board")).as("画布可见").isTrue();
            assertThat((Boolean) page.evaluate(
                    "() => { const c = document.getElementById('canvas-board');"
                            + " return c.width > 100 && c.height > 100; }"))
                    .as("画布已按容器尺寸初始化").isTrue();

            // 取消 → 关闭
            page.click("#canvas-cancel-btn");
            page.waitForFunction(
                    "() => document.getElementById('canvas-modal-overlay').style.display === 'none'",
                    null, WF_10S);

            assertThat(consoleErrorsOf(page)).as("全程 console 无 error").isEmpty();
        }
    }

    @Test
    @DisplayName("鼠标拖拽绘制改变画布像素;确定 → PNG 上传为聊天附件缩略图")
    void drawAndConfirmUploadsAsChatAttachment() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openIndex(ctx);
            waitForUploadGate(page);
            openCanvasModal(page);

            // 记录绘制前画布中心区域像素指纹,拖拽画一条线
            String before = drawCenterLine(page);

            String after = canvasPixelFingerprint(page);
            assertThat(after).as("拖拽绘制后画布像素发生变化").isNotEqualTo(before);

            // 确定 → 模态框关闭 + 缩略图出现 + pendingImages 拿到 fileId(真实上传落地)
            page.click("#canvas-confirm-btn");
            page.waitForFunction(
                    "() => document.getElementById('canvas-modal-overlay').style.display === 'none'",
                    null, WF_10S);
            page.waitForSelector("#image-thumbnails .image-thumbnail img",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
            page.waitForFunction(
                    "() => window._loomAgent.state.pendingImages.length === 1"
                            + " && !!window._loomAgent.state.pendingImages[0].fileId",
                    null, WF_10S);
            String fileName = (String) page.evaluate(
                    "() => window._loomAgent.state.pendingImages[0].fileName");
            assertThat(fileName).as("附件名为 canvas-时间戳.png").matches("canvas-\\d{8}-\\d{6}\\.png");

            // 清场:移除缩略图,避免影响同 context 后续断言
            page.click("#image-thumbnails .image-thumbnail .thumbnail-remove");

            assertThat(consoleErrorsOf(page)).as("全程 console 无 error").isEmpty();
        }
    }

    @Test
    @DisplayName("空画布点确定 → toast 提示且不关闭、不上传")
    void emptyCanvasConfirmIsRejected() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openIndex(ctx);
            waitForUploadGate(page);
            openCanvasModal(page);

            page.click("#canvas-confirm-btn");

            // 模态框保持打开,无缩略图产生,出现 toast
            assertThat((Boolean) page.evaluate(
                    "() => document.getElementById('canvas-modal-overlay').style.display === 'flex'"))
                    .as("空画布确定不关闭模态框").isTrue();
            assertThat(page.locator("#image-thumbnails .image-thumbnail").count())
                    .as("未产生附件缩略图").isZero();
            page.waitForSelector("#toast-notification.show",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));

            page.click("#canvas-cancel-btn");
            assertThat(consoleErrorsOf(page)).as("全程 console 无 error").isEmpty();
        }
    }

    /** 画布中心 200×200 区域像素指纹(非零像素数 + 求和),用于绘制前后对比。 */
    private String canvasPixelFingerprint(Page page) {
        return (String) page.evaluate(
                "() => { const c = document.getElementById('canvas-board');"
                        + " const ctx = c.getContext('2d');"
                        + " const x0 = Math.max(0, Math.floor(c.width/2) - 150);"
                        + " const y0 = Math.max(0, Math.floor(c.height/2) - 50);"
                        + " const d = ctx.getImageData(x0, y0, 300, 100).data;"
                        + " let sum = 0, nonEmpty = 0;"
                        + " for (let i = 0; i < d.length; i += 4) {"
                        + "   if (d[i+3] > 0) { nonEmpty++; sum += d[i] + d[i+1] + d[i+2]; } }"
                        + " return nonEmpty + ':' + sum; }");
    }

    /** 在画布中心画一条水平线(拖拽 200px),返回绘制前指纹。 */
    private String drawCenterLine(Page page) {
        String before = canvasPixelFingerprint(page);
        com.microsoft.playwright.options.BoundingBox box =
                page.locator("#canvas-board").boundingBox();
        double cx = box.x + box.width / 2;
        double cy = box.y + box.height / 2;
        page.mouse().move(cx - 100, cy);
        page.mouse().down();
        page.mouse().move(cx + 100, cy,
                new com.microsoft.playwright.Mouse.MoveOptions().setSteps(20));
        page.mouse().up();
        return before;
    }

    private void openCanvasModal(Page page) {
        page.click("#canvas-add-btn");
        page.waitForFunction(
                "() => document.getElementById('canvas-modal-overlay').style.display === 'flex'",
                null, WF_10S);
    }

    /** 打开组件分类弹出面板(印章按钮收纳其中)。 */
    private void openStencilPanel(Page page) {
        page.click("#canvas-stencil-toggle");
        page.waitForFunction(
                "() => document.getElementById('canvas-stencil-panel').style.display !== 'none'",
                null, WF_10S);
    }

    @Test
    @DisplayName("撤销还原像素、重做恢复;橡皮点击整笔擦除")
    void undoRedoAndEraserRemoveStrokes() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openIndex(ctx);
            waitForUploadGate(page);
            openCanvasModal(page);

            String empty = canvasPixelFingerprint(page);
            String before = drawCenterLine(page);
            String drawn = canvasPixelFingerprint(page);
            assertThat(drawn).as("前置:笔画已落画布").isNotEqualTo(empty);
            assertThat(before).isEqualTo(empty);

            // 撤销 → 像素回到空画布
            page.click("#canvas-undo-btn");
            assertThat(canvasPixelFingerprint(page)).as("撤销后画布还原").isEqualTo(empty);

            // 重做 → 笔画恢复
            page.click("#canvas-redo-btn");
            assertThat(canvasPixelFingerprint(page)).as("重做后笔画恢复").isEqualTo(drawn);

            // 橡皮:选中工具后点击笔画中线 → 整笔删除
            page.click("#canvas-toolbar .canvas-tool-btn[data-tool='eraser']");
            // 诊断层 1:工具状态是否切换成功
            String tool = (String) page.evaluate("() => window.CanvasBoard.tool");
            assertThat(tool).as("诊断:工具已切换为橡皮").isEqualTo("eraser");

            com.microsoft.playwright.options.BoundingBox box =
                    page.locator("#canvas-board").boundingBox();
            page.mouse().click(box.x + box.width / 2, box.y + box.height / 2);

            // 诊断层 2:形状栈是否已被橡皮清空(区分 hitTest 失败 vs 重绘失败)
            Object shapeCount = page.evaluate("() => window.CanvasBoard.shapes.length");
            assertThat(shapeCount).as("诊断:橡皮点击后整笔已出栈").isEqualTo(0);

            assertThat(canvasPixelFingerprint(page)).as("橡皮命中整笔擦除").isEqualTo(empty);

            page.click("#canvas-cancel-btn");
            assertThat(consoleErrorsOf(page)).as("全程 console 无 error").isEmpty();
        }
    }

    @Test
    @DisplayName("文字工具:点击画布出浮层输入,回车提交为可擦除形状")
    void textToolCommitsOnEnter() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openIndex(ctx);
            waitForUploadGate(page);
            openCanvasModal(page);

            String empty = canvasPixelFingerprint(page);

            // 选文字工具 → 点画布中心 → 浮层输入框出现在点击处
            page.click("#canvas-toolbar .canvas-tool-btn[data-tool='text']");
            com.microsoft.playwright.options.BoundingBox box =
                    page.locator("#canvas-board").boundingBox();
            page.mouse().click(box.x + box.width / 2 - 100, box.y + box.height / 2 - 50);
            page.waitForSelector("#canvas-text-input:not([style*='display: none'])",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));

            // 输入并回车提交 → 浮层隐藏、像素变化(文字落画布)
            page.locator("#canvas-text-input").fill("画板IT");
            page.keyboard().press("Enter");
            page.waitForFunction(
                    "() => document.getElementById('canvas-text-input').style.display === 'none'",
                    null, WF_10S);
            assertThat(canvasPixelFingerprint(page)).as("文字已渲染到画布").isNotEqualTo(empty);

            // 撤销 → 文字形状出栈,画布还原
            page.click("#canvas-undo-btn");
            assertThat(canvasPixelFingerprint(page)).as("文字可撤销").isEqualTo(empty);

            page.click("#canvas-cancel-btn");
            assertThat(consoleErrorsOf(page)).as("全程 console 无 error").isEmpty();
        }
    }

    // ============= Web 组件(印章式 stencil + 选择操纵) =============

    @Test
    @DisplayName("组件印章:点击放置按钮组件,浮层输入文案;像素落地")
    void stencilButtonPlacementAndLabelEditing() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openIndex(ctx);
            waitForUploadGate(page);
            openCanvasModal(page);

            String empty = canvasPixelFingerprint(page);

            // 选「按钮」组件印章 → 点击画布中心(像素指纹采样区内)→ 默认尺寸组件入栈
            openStencilPanel(page);
            page.click("#canvas-toolbar .canvas-stencil-btn[data-stencil='button']");
            // 选中印章后面板自动收起,进入放置态
            page.waitForFunction(
                    "() => document.getElementById('canvas-stencil-panel').style.display === 'none'",
                    null, WF_10S);
            com.microsoft.playwright.options.BoundingBox box =
                    page.locator("#canvas-board").boundingBox();
            page.mouse().click(box.x + box.width / 2, box.y + box.height / 2);
            page.waitForFunction(
                    "() => window.CanvasBoard.shapes.length === 1"
                            + " && window.CanvasBoard.shapes[0].type === 'button'",
                    null, WF_10S);

            // 放置后自动弹文案浮层,输入按钮文案回车 → 写入 shape.text
            page.waitForSelector("#canvas-text-input:not([style*='display: none'])",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
            page.locator("#canvas-text-input").fill("立即提交");
            page.keyboard().press("Enter");
            page.waitForFunction(
                    "() => window.CanvasBoard.shapes[0].text === '立即提交'", null, WF_10S);

            assertThat(canvasPixelFingerprint(page)).as("组件已渲染到画布").isNotEqualTo(empty);

            page.click("#canvas-cancel-btn");
            assertThat(consoleErrorsOf(page)).as("全程 console 无 error").isEmpty();
        }
    }

    @Test
    @DisplayName("选择工具:点选组件 → 拖动移动 → 角手柄缩放 → Delete 删除 → 撤销还原")
    void selectToolMoveResizeDeleteUndo() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openIndex(ctx);
            waitForUploadGate(page);
            openCanvasModal(page);

            // 拖拽放置一张自定义尺寸的卡片组件
            openStencilPanel(page);
            page.click("#canvas-toolbar .canvas-stencil-btn[data-stencil='card']");
            com.microsoft.playwright.options.BoundingBox box =
                    page.locator("#canvas-board").boundingBox();
            page.mouse().move(box.x + 200, box.y + 120);
            page.mouse().down();
            page.mouse().move(box.x + 440, box.y + 300,
                    new com.microsoft.playwright.Mouse.MoveOptions().setSteps(10));
            page.mouse().up();
            page.waitForFunction(
                    "() => window.CanvasBoard.shapes.length === 1"
                            + " && window.CanvasBoard.shapes[0].type === 'card'",
                    null, WF_10S);
            // 卡片文案浮层:Esc 跳过(组件保留,无文案)
            page.keyboard().press("Escape");
            page.waitForFunction(
                    "() => document.getElementById('canvas-text-input').style.display === 'none'",
                    null, WF_10S);

            double[] orig = shapeBBoxOf(page, 0);
            assertThat(orig[2]).as("拖拽放置宽度 ≈240").isBetween(230.0, 250.0);
            assertThat(orig[3]).as("拖拽放置高度 ≈180").isBetween(170.0, 190.0);

            // 选择工具 → 点击组件内部 → 选中
            page.click("#canvas-toolbar .canvas-tool-btn[data-tool='select']");
            page.mouse().click(box.x + orig[0] + orig[2] / 2, box.y + orig[1] + orig[3] / 2);
            page.waitForFunction(
                    "() => window.CanvasBoard.selectedId != null"
                            + " && window.CanvasBoard.selectedId === window.CanvasBoard.shapes[0].id",
                    null, WF_10S);

            // 拖动移动 +50/+40
            double cx = box.x + orig[0] + orig[2] / 2;
            double cy = box.y + orig[1] + orig[3] / 2;
            page.mouse().move(cx, cy);
            page.mouse().down();
            page.mouse().move(cx + 50, cy + 40,
                    new com.microsoft.playwright.Mouse.MoveOptions().setSteps(8));
            page.mouse().up();
            double[] moved = shapeBBoxOf(page, 0);
            assertThat(moved[0]).as("移动后 x +50").isCloseTo(orig[0] + 50, org.assertj.core.data.Offset.offset(3.0));
            assertThat(moved[1]).as("移动后 y +40").isCloseTo(orig[1] + 40, org.assertj.core.data.Offset.offset(3.0));
            assertThat(moved[2]).isEqualTo(orig[2]);
            assertThat(moved[3]).isEqualTo(orig[3]);

            // 撤销移动 → 回到原位
            page.click("#canvas-undo-btn");
            double[] undone = shapeBBoxOf(page, 0);
            assertThat(undone[0]).as("撤销后 x 还原").isCloseTo(orig[0], org.assertj.core.data.Offset.offset(0.5));
            assertThat(undone[1]).as("撤销后 y 还原").isCloseTo(orig[1], org.assertj.core.data.Offset.offset(0.5));
            // 重做恢复移动(为后续步骤保持 moved 状态)
            page.click("#canvas-redo-btn");
            moved = shapeBBoxOf(page, 0);

            // SE 角手柄缩放 +40/+40(手柄在 bbox 右下角)
            page.mouse().move(box.x + moved[0] + moved[2], box.y + moved[1] + moved[3]);
            page.mouse().down();
            page.mouse().move(box.x + moved[0] + moved[2] + 40, box.y + moved[1] + moved[3] + 40,
                    new com.microsoft.playwright.Mouse.MoveOptions().setSteps(8));
            page.mouse().up();
            double[] resized = shapeBBoxOf(page, 0);
            assertThat(resized[2]).as("SE 手柄拉宽 +40").isCloseTo(moved[2] + 40, org.assertj.core.data.Offset.offset(3.0));
            assertThat(resized[3]).as("SE 手柄拉高 +40").isCloseTo(moved[3] + 40, org.assertj.core.data.Offset.offset(3.0));
            assertThat(resized[0]).as("SE 缩放不动 x").isCloseTo(moved[0], org.assertj.core.data.Offset.offset(3.0));

            // Delete 键删除选中组件
            page.mouse().click(box.x + resized[0] + resized[2] / 2, box.y + resized[1] + resized[3] / 2);
            page.keyboard().press("Delete");
            page.waitForFunction("() => window.CanvasBoard.shapes.length === 0", null, WF_10S);

            // 撤销删除 → 组件回来
            page.click("#canvas-undo-btn");
            page.waitForFunction("() => window.CanvasBoard.shapes.length === 1", null, WF_10S);

            page.click("#canvas-cancel-btn");
            assertThat(consoleErrorsOf(page)).as("全程 console 无 error").isEmpty();
        }
    }

    @Test
    @DisplayName("双击组件改文字:浮层预填旧值,新值写入 shape.text")
    void stencilDblclickEditsLabel() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openIndex(ctx);
            waitForUploadGate(page);
            openCanvasModal(page);

            // 放置一个按钮组件,文案「保存」
            openStencilPanel(page);
            page.click("#canvas-toolbar .canvas-stencil-btn[data-stencil='button']");
            com.microsoft.playwright.options.BoundingBox box =
                    page.locator("#canvas-board").boundingBox();
            page.mouse().click(box.x + 300, box.y + 200);
            page.waitForFunction(
                    "() => window.CanvasBoard.shapes.length === 1", null, WF_10S);
            page.waitForSelector("#canvas-text-input:not([style*='display: none'])",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
            page.locator("#canvas-text-input").fill("保存");
            page.keyboard().press("Enter");
            page.waitForFunction(
                    "() => window.CanvasBoard.shapes[0].text === '保存'", null, WF_10S);

            // 选择工具 → 双击组件 → 浮层预填旧文案
            page.click("#canvas-toolbar .canvas-tool-btn[data-tool='select']");
            double[] b = shapeBBoxOf(page, 0);
            page.mouse().click(box.x + b[0] + b[2] / 2, box.y + b[1] + b[3] / 2,
                    new com.microsoft.playwright.Mouse.ClickOptions().setClickCount(2));
            page.waitForSelector("#canvas-text-input:not([style*='display: none'])",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
            assertThat(page.inputValue("#canvas-text-input")).as("双击浮层预填旧文案").isEqualTo("保存");

            // 改文案回车 → 写回 shape.text,且不新增形状
            page.locator("#canvas-text-input").fill("取消");
            page.keyboard().press("Enter");
            page.waitForFunction(
                    "() => window.CanvasBoard.shapes.length === 1"
                            + " && window.CanvasBoard.shapes[0].text === '取消'",
                    null, WF_10S);

            page.click("#canvas-cancel-btn");
            assertThat(consoleErrorsOf(page)).as("全程 console 无 error").isEmpty();
        }
    }

    @Test
    @DisplayName("CRUD 组件:表格默认多段表头(Esc 保留),双击改表头;表单字段默认标签")
    void crudStencilsTableHeadersAndFormitem() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openIndex(ctx);
            waitForUploadGate(page);
            openCanvasModal(page);

            String empty = canvasPixelFingerprint(page);

            // 拖拽放置表格组件 → 默认多段表头「名称,状态,操作」
            openStencilPanel(page);
            page.click("#canvas-toolbar .canvas-stencil-btn[data-stencil='table']");
            com.microsoft.playwright.options.BoundingBox box =
                    page.locator("#canvas-board").boundingBox();
            page.mouse().move(box.x + 200, box.y + 100);
            page.mouse().down();
            page.mouse().move(box.x + 680, box.y + 320,
                    new com.microsoft.playwright.Mouse.MoveOptions().setSteps(10));
            page.mouse().up();
            page.waitForFunction(
                    "() => window.CanvasBoard.shapes.length === 1"
                            + " && window.CanvasBoard.shapes[0].type === 'table'",
                    null, WF_10S);
            // 文案浮层预填默认表头;Esc 放弃 → 默认表头保留(不被清空)
            page.waitForSelector("#canvas-text-input:not([style*='display: none'])",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
            assertThat(page.inputValue("#canvas-text-input")).isEqualTo("名称,状态,操作");
            page.keyboard().press("Escape");
            page.waitForFunction(
                    "() => window.CanvasBoard.shapes[0].text === '名称,状态,操作'", null, WF_10S);
            assertThat(canvasPixelFingerprint(page)).as("表格已渲染").isNotEqualTo(empty);

            // 双击表格改表头(逗号分段渲染到列)—— 操纵语义在选择工具下
            page.click("#canvas-toolbar .canvas-tool-btn[data-tool='select']");
            double[] tb = shapeBBoxOf(page, 0);
            page.mouse().click(box.x + tb[0] + tb[2] / 2, box.y + tb[1] + tb[3] / 2,
                    new com.microsoft.playwright.Mouse.ClickOptions().setClickCount(2));
            page.waitForSelector("#canvas-text-input:not([style*='display: none'])",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
            page.locator("#canvas-text-input").fill("ID,姓名,操作");
            page.keyboard().press("Enter");
            page.waitForFunction(
                    "() => window.CanvasBoard.shapes[0].text === 'ID,姓名,操作'", null, WF_10S);

            // 点击放置表单字段组件 → 默认标签「字段名」
            openStencilPanel(page);
            page.click("#canvas-toolbar .canvas-stencil-btn[data-stencil='formitem']");
            page.mouse().click(box.x + box.width / 2, box.y + box.height - 80);
            page.waitForFunction(
                    "() => window.CanvasBoard.shapes.length === 2"
                            + " && window.CanvasBoard.shapes[1].type === 'formitem'"
                            + " && window.CanvasBoard.shapes[1].text === '字段名'",
                    null, WF_10S);
            page.keyboard().press("Escape"); // 放弃默认文案浮层

            page.click("#canvas-cancel-btn");
            assertThat(consoleErrorsOf(page)).as("全程 console 无 error").isEmpty();
        }
    }

    /** 读 shapes[i] 的 bbox [x,y,w,h](CSS 逻辑像素)。 */
    private double[] shapeBBoxOf(Page page, int i) {
        return page.evaluate(
                "(i) => { const b = window.CanvasBoard.shapeBBox(window.CanvasBoard.shapes[i]);"
                        + " return [b.x, b.y, b.w, b.h]; }", i) instanceof java.util.List<?> l
                ? new double[]{((Number) l.get(0)).doubleValue(), ((Number) l.get(1)).doubleValue(),
                        ((Number) l.get(2)).doubleValue(), ((Number) l.get(3)).doubleValue()}
                : new double[4];
    }
}
