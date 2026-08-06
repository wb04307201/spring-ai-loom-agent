# Skill Picker / IO 设计

日期：2026-08-06
作者：Claude (brainstorming + 用户协同)
状态：待用户审阅

## 1. 背景与目标

用户希望在聊天输入框中通过 `/` 命令精准选中某个 Skill（不是依赖 LLM 渐进式披露），并希望同时具备：

1. 技能下载（导出为标准 `.skill.md`）
2. 技能导入（解析标准 frontmatter 并入库）
3. 通过 LLM Tool 创建/修改自己的 Skill（已有，提交 `2196f00`）

本次设计同时实现 `1` 和 `2`，并修正已存在的部分前端草稿（位置错误 / 事件绑定错误）。

## 2. 范围

### 2.1 本次实现

- 后端：`ChatRequestRecord` 新增 `selectedSkillName` 字段，`DefaultChat.stream()` 注入 system prompt
- 后端：失败兜底（解析失败仅 warn，不抛错）
- 前端：textarea 增强——`/` 触发浮层，键盘导航、过滤、确认
- 前端：标签条 + 点击 `×` / Backspace（textarea 为空时）清空
- 前端：发送成功清空，失败保留
- 前端：Skill 详情新增「下载」按钮（标准 frontmatter + 原样正文）
- 前端：技能库侧栏新增「导入」按钮（单文件）
- 前端：解析 frontmatter，同名自建 Skill 三选项弹窗（覆盖 / 另存为 / 取消）
- 前端：锁定 Skill（ROLE_GRANTED / MARKET_PULLED）拒绝覆盖，提示改名

### 2.2 暂不实现（YAGNI）

- 批量多文件导入
- ZIP / 目录导入
- 完整 YAML 库（多行、引用、转义）
- Skill 工具调用与显式选择并存模式
- Skill 选择持久化到 localStorage

## 3. 架构

### 3.1 数据流

```text
[textarea 输入 /]
       ↓
[slash picker 浮层]
       ↓
[Enter 选中 → state.selectedSkill = {name, description}]
       ↓
[输入框上方显示 [name ×] 标签条]
       ↓
[chat.send() 携带 selectedSkillName]
       ↓
[POST /spring/ai/loom/stream]
       ↓
[DefaultChat.stream]
       ↓
[ISkillStorage.get(name, username)  // 权限校验]
       ↓
[追加到 dynamicSystemPrompt]
       ↓
[Spring AI → 流式返回]
       ↓
[成功 / 失败回调：清空或保留标签条]
```

### 3.2 文件改动

#### 后端

| 文件 | 改动 |
|------|------|
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/ChatRequestRecord.java` | 新增 `String selectedSkillName` 字段（record 新位置） |
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChat.java` | `stream()` 末尾追加 Skill 注入；解析失败仅 `log.warn` |
| `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChatTest.java` | 3 个新用例：null / 合法 / 非法 |

#### 前端

| 文件 | 改动 |
|------|------|
| `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/index.html` | 输入框上方增加 `<div id="chat-selected-skill">`；技能库侧栏增加「导入」按钮 + 隐藏 file input |
| `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js` | slash picker 浮层、键盘导航、标签条；`state.selectedSkill`；`chat.send()` 提交时携带；成功/失败回调处理 |
| `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js` | 把 `handleDownload` / `handleImport` / `_parseSkillFrontmatter` / `_doImportFile` / `_submitImport` / `_showImportConflictDialog` / `_findFreeName` 从 `bindAllEvents` 内部搬到 `skills` 对象 |
| `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js` | 修正 `addIf('#skill-import-file-input', 'change', handler)` 为 `safeBindById(..., 'change', handler)` |
| `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/style.css` | 新增 `.chat-selected-skill`、`.slash-picker`、`.slash-picker-item` 样式 |

## 4. 后端详细设计

### 4.1 ChatRequestRecord

```java
public record ChatRequestRecord(String message,
                                String conversationId,
                                List<String> mcps,
                                List<String> enabledKnowledgeIds,
                                List<String> fileIds,
                                String selectedSkillName) {
}
```

向后兼容：未传时为 `null`，与现有调用一致。

### 4.2 DefaultChat 注入

在 `stream()` 现有逻辑完成后、调用 `chatClient.prompt().system(dynamicSystemPrompt)` 之前插入：

```java
if (chatRequestRecord.selectedSkillName() != null
        && !chatRequestRecord.selectedSkillName().isBlank()) {
    try {
        SkillRecord skill = skillStorage.get(
                chatRequestRequest.selectedSkillName().trim(), username);
        if (skill != null && skill.content() != null) {
            StringBuilder extra = new StringBuilder();
            extra.append("\n\n【本轮用户选择的 Skill】\n");
            extra.append("name: ").append(skill.name()).append("\n");
            extra.append("description: ").append(skill.description() == null ? "" : skill.description()).append("\n");
            extra.append("\n").append(skill.content());
            dynamicSystemPrompt = dynamicSystemPrompt + extra;
        }
    } catch (LoomAgentRuntimeException ex) {
        log.warn("selectedSkillName 解析失败: name={}, user={}, msg={}",
                chatRequestRecord.selectedSkillName(), username, ex.getMessage());
    }
}
```

### 4.3 失败兜底

- `skillStorage.get` 抛任何异常 → `log.warn` 后继续聊天，不影响 system prompt
- 不向客户端透传 Skill 解析失败（避免与现有 chat 错误处理耦合）

## 5. 前端详细设计

### 5.1 state

```js
state.selectedSkill = null; // {name, description} | null
state.slashPicker = {
    open: false,
    query: '',        // / 之后的过滤词
    items: [],        // 当前候选 Skill 列表
    activeIndex: 0
};
```

### 5.2 slash picker 行为

| 事件 | 触发条件 | 行为 |
|------|---------|------|
| keydown `/` | `ta.value` 末尾或最开头 + 光标在该位置 | 打开 picker；从 `state._skillListCache` 取所有 `load` 不限的 Skill |
| keydown `↑` | picker 打开 | activeIndex = max(0, activeIndex-1) |
| keydown `↓` | picker 打开 | activeIndex = min(items.length-1, activeIndex+1) |
| keydown `Enter` | picker 打开 | 选中 activeIndex 项；写入 `state.selectedSkill`；移除 `/query` 文本；渲染标签条 |
| keydown `Tab` | picker 打开 | 同 Enter |
| keydown `Esc` | picker 打开 | 关闭 picker，**保留** 用户输入的 `/query` 文本 |
| `input` | picker 打开 | 重新过滤 items |

### 5.3 标签条

HTML 容器（输入框上方）：

```html
<div id="chat-selected-skill" class="chat-selected-skill hidden">
    <span class="skill-tag">note-health</span>
    <button class="skill-tag-close" title="移除">×</button>
</div>
```

行为：

- `×` 点击 → `state.selectedSkill = null`，隐藏
- `keydown Backspace` 且 `ta.value` 为空 → 同上
- `chat.send()` 成功回调 → 隐藏
- `chat.send()` 失败回调 → 保留
- 用户切换对话（`state.conversationId` 变化）→ 清空（避免跨会话污染）

### 5.4 技能下载

```js
function downloadSkillAsMd(skill) {
    const name = (skill.name || '').replace(/[\r\n]+/g, ' ').trim();
    const description = (skill.description || '').replace(/[\r\n]+/g, ' ').trim();
    const body = (skill.content || '').replace(/\r\n/g, '\n');
    const md =
        '---\n' +
        `name: ${name}\n` +
        `description: ${description}\n` +
        '---\n' +
        '\n' + body + '\n';
    // 触发浏览器下载
    const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${name || 'untitled'}.skill.md`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    showToast('已下载 ' + a.download, 'success');
}
```

注意：正文中**不**自动插入 `# name` 标题，避免重复。锁定 Skill 提示改为：

> 已下载；如需导入为可编辑 Skill，请使用新的名称。

### 5.5 技能导入

#### 解析 frontmatter

```js
function parseSkillFrontmatter(text) {
    const m = text.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/);
    if (!m) return { error: '请使用标准 Skill 格式：---\\nname: x\\ndescription: y\\n---\\n<内容>' };
    const yaml = m[1], body = m[2];
    const nameMatch = yaml.match(/^name:\s*(.+?)\s*$/m);
    const descMatch = yaml.match(/^description:\s*(.+?)\s*$/m);
    const name = nameMatch ? nameMatch[1].trim() : '';
    if (!name) return { error: 'frontmatter 必须有 name: 字段' };
    if (name.length > 128) return { error: 'name 长度超过 128' };
    const description = descMatch ? descMatch[1].trim() : '';
    if (!description) return { error: 'frontmatter 必须有 description: 字段' };
    return { name, description, content: body.replace(/^\n+/, '') };
}
```

不支持：YAML 多行字符串、引用、转义、BOM 容忍（v1 不做）。

#### 流程

```text
1. 触发「导入」按钮 → 隐藏 file input.click()
2. 用户选择 .md / .skill.md
3. 读取文件文本
4. 解析 frontmatter
5. 调用 /spring/ai/loom/skill/{name} 探测是否存在
6. 不存在 → 直接 POST /skill/upsert
7. 存在且为 USER_CREATED → 弹出 3 选项（覆盖 / 另存为 / 取消）
8. 存在且为 ROLE_GRANTED / MARKET_PULLED → 提示改名后导入
9. 失败 → toast
```

#### 3 选项弹窗

复用 `#confirm-modal`，临时新增「另存为」按钮。清理时恢复原状（避免破坏其他场景）。

## 6. 测试

### 6.1 后端

- `ChatRequestRecord.selectedSkillName=null` → 行为与改动前一致
- 合法 `selectedSkillName` → `dynamicSystemPrompt` 包含 `【本轮用户选择的 Skill】` 与 Skill content
- 非法 `selectedSkillName`（不存在） → `log.warn`，`dynamicSystemPrompt` 不变，请求继续

### 6.2 前端

- 输入 `/` → 浮层打开
- 输入 `no` → 浮层过滤到 `note-health`
- `Enter` 选中 → 标签条出现，textarea 不含 `/query`
- 发送成功 → 标签条消失
- 发送失败 → 标签条保留
- `×` 点击 → 标签条消失
- `Backspace` 在 textarea 为空 → 标签条消失
- 切换对话 → 标签条消失
- 导入 / 导出流程通过浏览器实测

## 7. 风险与权衡

| 风险 | 缓解 |
|------|------|
| system prompt 变长增加 token | Skill content 受限于 Skill 作者；通常 1-5 KB；不限制大小但记录到日志便于排查 |
| 用户用 `/` 但不想触发 picker（如路径 `/tmp/foo`） | v1 不区分，路径场景罕见；如需要后续可加白名单 |
| YAML 解析脆弱 | 文档明确「最小 frontmatter」；不承诺兼容复杂 YAML |
| 锁定的 Skill 也能注入 | 期望行为：「读」不改权限，注入是读 |
| 用户选择后切换对话造成污染 | 在 `conversation.switchTo` 内清空 `state.selectedSkill` |

## 8. 开放问题

无。

## 9. 变更日志

- 2026-08-06 初稿
