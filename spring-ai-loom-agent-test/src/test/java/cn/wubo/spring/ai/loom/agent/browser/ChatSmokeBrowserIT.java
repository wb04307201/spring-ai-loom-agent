package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LLM 烟雾浏览器 IT:真实 DashScope 模型走完整 SSE 聊天链路
 * (前端 #textarea → #send-btn → POST /spring/ai/loom/stream → 流式渲染 AI 气泡)。
 *
 * <p>只测链路通,不测内容:LLM 非确定性(可能调 ITimeTool 也可能直接回答),
 * 因此不断言工具调用发生,只断言 AI 回复非空、渲染健康(无原始 script)、无 JS 异常。
 *
 * <p>env 守卫:DASHSCOPE_PERSON_TOKEN_API_KEY 未设置 → assumeTrue 跳过(合法结果)。
 *
 * <p><b>SSE 完成信号核实结论</b>(读 app.js send()/enableSend()/disableSend()/setStopButtonVisible()):
 * <ul>
 *   <li>流开始:disableSend() → #send-btn disabled + textContent="发送中..." + #stop-btn display:inline-block;</li>
 *   <li>流结束:ON_COMPLETE 与 error 回调都调 enableSend() + setStopButtonVisible(false)
 *       → #send-btn 恢复 enabled + textContent="发送消息" + #stop-btn display:none。</li>
 * </ul>
 * 页面初始态与"流结束"态相同(send-btn enabled + stop-btn 隐藏),所以必须先 waitForFunction
 * 观察到"流开始"信号,再等"流结束"信号,避免把初始态误判为完成。error 回调同样触发结束信号,
 * 因此额外断言气泡文本不含"发送失败"(app.js 错误分支会往气泡追加该前缀)。
 */
@DisplayName("LLM 烟雾:真实 SSE 聊天流渲染 + 工具链路不崩(无 API key 则跳过)")
class ChatSmokeBrowserIT extends BrowserTestBase {

    @BeforeAll
    void requireApiKey() {
        assumeTrue(System.getenv("DASHSCOPE_PERSON_TOKEN_API_KEY") != null
                        && !System.getenv("DASHSCOPE_PERSON_TOKEN_API_KEY").isBlank(),
                "跳过:DASHSCOPE_PERSON_TOKEN_API_KEY 未设置");
    }

    @Test
    void chatStreamRendersAssistantBubble() {
        long start = System.currentTimeMillis();
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "index.html");
            page.waitForSelector("#textarea");
            page.fill("#textarea", "现在几点了?请调用时间工具回答,一句话即可。");
            page.click("#send-btn");

            // ① 流开始信号:#send-btn 禁用 + #stop-btn 可见(disableSend(),真实 LLM 流
            //    通常 30-90s,不会在轮询前就结束;30s 内连"开始"都没观察到 = 前端链路断)
            page.waitForFunction(
                    "() => { const s = document.getElementById('send-btn');"
                            + " const t = document.getElementById('stop-btn');"
                            + " return s && s.disabled && t && t.style.display !== 'none'; }",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(30_000).setPollingInterval(500));

            // ② 流结束信号:enableSend() + setStopButtonVisible(false)(总超时 120s)
            page.waitForFunction(
                    "() => { const s = document.getElementById('send-btn');"
                            + " const t = document.getElementById('stop-btn');"
                            + " return s && !s.disabled && s.textContent.trim() === '发送消息'"
                            + " && t && t.style.display === 'none'; }",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(120_000).setPollingInterval(1_000));

            // ③ brief 稳定判定:AI 气泡文本连续 3 次采样(2s 间隔)不变且非空
            //    (qwen enable_thinking 先渲染 reasoning 段无妨 —— 只要求最终文本非空,不断言内容)
            page.waitForSelector(".chat-item-left .bubble");
            String prev = "";
            String cur = "";
            int stable = 0;
            for (int i = 0; i < 30 && stable < 3; i++) {
                cur = page.locator(".chat-item-left .bubble").last().innerText();
                stable = cur.equals(prev) && !cur.isBlank() ? stable + 1 : 0;
                prev = cur;
                if (stable < 3) page.waitForTimeout(2_000);
            }

            assertThat(cur).as("AI 回复非空").isNotBlank();
            // 流结束信号在 error 回调同样触发,用错误渲染前缀甄别"真完成 vs 失败完成"
            assertThat(cur).as("AI 回复不是发送失败错误").doesNotContain("发送失败");
            assertThat(page.locator(".chat-item-right .bubble").count())
                    .as("用户消息气泡存在").isGreaterThanOrEqualTo(1);
            // markdown-renderer.js 消毒后渲染:气泡 HTML 不含原始 <script
            assertThat(page.locator(".chat-item-left .bubble").last().innerHTML())
                    .doesNotContain("<script");
            // 无 JS 异常:consoleErrorsOf 收集 console error + pageerror(未捕获异常)。
            // 实测全绿无需甄别 SSE 网络类 noise;若未来出现 favicon/资源 404 之类
            // "Failed to load resource" 噪声,应在此按前缀过滤并注明理由,而非放宽断言。
            assertThat(consoleErrorsOf(page)).isEmpty();

            System.out.printf("[ChatSmokeBrowserIT] SSE 全链路耗时 %d ms,AI 回复 %d 字符%n",
                    System.currentTimeMillis() - start, cur.length());
        }
    }
}
