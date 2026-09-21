package cn.wubo.spring.ai.loom.agent.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-09-21 chat-ui-ux-fixes 三项修复契约回归（spec § 4.3-4.5）
 * <p>
 * 由于 loom-agent 没有 JS 测试环境(jsdom 等),本测试读 app.js 源文件作为
 * 字符串,断言关键代码片段存在。这锁住"代码契约"而不锁住"运行时行为"——
 * 实际 UI 验证留给端到端 Playwright。
 */
@DisplayName("Chat UI UX 修复契约回归")
class ChatUiUxFixesContractTest {

    private static String appJs;

    private static String readAppJs() throws IOException {
        if (appJs != null) return appJs;
        Path p = Paths.get("src/main/resources/META-INF/resources/spring/ai/loom/app.js");
        appJs = Files.readString(p);
        return appJs;
    }

    @Test
    @DisplayName("#2: scrollToBottom() 加 isAtBottom 守卫")
    void scrollGuardPresent() throws IOException {
        String src = readAppJs();
        assertTrue(src.contains("if (!this._isAtBottom) return"),
                "scrollToBottom() must guard on isAtBottom — current src missing this check");
        assertTrue(src.contains("this.mainContent.addEventListener(\"scroll\""),
                "mainContent scroll listener not registered for isAtBottom updates");
        assertTrue(src.contains("back-to-bottom-btn"),
                "back-to-bottom floating button class/id not found in app.js");
    }

    @Test
    @DisplayName("#1: subTaskChips 模块 + 流式回调识别 subTaskEvent")
    void subTaskChipModulePresent() throws IOException {
        String src = readAppJs();
        assertTrue(src.contains("const subTaskChips = (() =>"),
                "subTaskChips IIFE module not found");
        assertTrue(src.contains("renderStart") && src.contains("renderEnd"),
                "subTaskChips must export renderStart/renderEnd");
        assertTrue(src.contains("data.subTaskEvent"),
                "stream callback must handle data.subTaskEvent");
        // R3 fix: className/classList API uses bare class names, not CSS-selector dots
        assertTrue(src.contains("subtask-chip-running"),
                "subtask-chip-running class reference missing — chip rendering path broken");
    }

    @Test
    @DisplayName("#3: askUser 同气泡（_currentAskUserBubble + 渲染逻辑）")
    void askUserBubbleReused() throws IOException {
        String src = readAppJs();
        assertTrue(src.contains("_currentAskUserBubble"),
                "_currentAskUserBubble state field missing");
        assertTrue(src.contains("askuser-slot"),
                "askuser-slot div missing — #3 refactor not applied");
        assertTrue(src.contains("askuser-bubble"),
                "askuser-bubble class not present — #3 refactor not applied");
        assertTrue(src.contains(".askuser-slot"),
                ".askuser-slot CSS class not found in app.js");
    }
}