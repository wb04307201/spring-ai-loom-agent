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

    @Test
    @DisplayName("#3: askUser 退化到上一个 AI 气泡（fix lastBotBubble fallback）")
    void askUserFallsBackToLastBotBubble() throws IOException {
        // 回归 bug:2026-09-21 端到端验证发现 askUserCards.render 在
        // _currentAskUserBubble === null 时无条件新建独立 chat-item,导致
        // AI 第一次回复(renderBotMessage)+ 两轮 askUser 变成 3 个独立气泡。
        // 修复:render 应退化到上一个 .chat-item-left .bubble(renderBotMessage 已建),
        // 补 .askuser-slot 并指向现有 bot-content/origin/actions,与 subTaskChips.renderStart
        // 的 lastBotBubble() 对称。
        String src = readAppJs();
        // 关键 fallback:查询 .chat-item-left .bubble 集合(与 subTaskChips.lastBotBubble 一致)
        assertTrue(src.contains(".chat-item-left .bubble"),
                "askUserCards.render must fall back to last .chat-item-left .bubble");
        // 必须把 .askuser-slot 插入到现有 .bot-content 前 —— 否则流式答案会写到 askuser-slot
        // 后面,视觉割裂。插入到 .bot-content 前意味着 askUser 卡片出现在 AI 文本上方
        assertTrue(src.contains("insertBefore(slot, botContent)"),
                "askuser-slot must be inserted before existing bot-content (above AI text)");
        // 必须指向现有 bot-content(不能新建 askuser-bot-content-${qid})—— 否则流式
        // 答案写不到 AI 原本要写的元素里
        assertTrue(src.contains("ui._currentAnswerEl = botContent"),
                "_currentAnswerEl must point at existing bot-content, not new askuser-bot-content");
    }
}