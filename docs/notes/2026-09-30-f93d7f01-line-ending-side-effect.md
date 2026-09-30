# f93d7f01 隐式行尾副作用说明

**日期**: 2026-09-30
**作者**: (Claude + 项目维护者协作)
**关联 commit**: [`f93d7f01`](https://github.com/.../commit/f93d7f01) — `fix(2.0): adapt to Spring AI 2.0.1 + Spring Boot 4.1.1 API breaks`

## 背景

`f93d7f01` 是 Spring AI 1.x → 2.0.1 + Spring Boot 3.x → 4.1.1 + JDK 17 → 25 的迁移 commit。diff stats 显示涉及 11 个文件、646 增 574 删;**4 个独立 MCP server 文件的 raw diff 行数被行尾规范化隐式膨胀**:

| 文件 | raw diff | CRLF-aware diff | 实质改动 |
|---|---|---|---|
| `loom-compile-mcp/.../LoomCompileMcpService.java` | 78 ±78 | **1 ±1** | import 包改名 |
| `loom-file-mcp/.../LoomFileMcpService.java` | 141 ±141 | **1 ±1** | import 包改名 |
| `loom-git-mcp/.../LoomGitMcpService.java` | 185 ±185 | **1 ±1** | import 包改名 |
| `loom-maven-mcp/.../LoomMavenMcpService.java` | 132 ±132 | **1 ±1** | import 包改名 |

(其中 CRLF-aware diff 用 `git diff --ignore-cr-at-eol --ignore-space-at-eol --ignore-all-space` 计算)

## 副作用

4 个 MCP server 文件的整文件行尾在 `f93d7f01` 中从 **CRLF → LF**,但 commit message 未声明这一变更。推测来源是迁移过程中 IDE 自动保存触发的规范化 — 这些文件原本是 Windows 上保存的 CRLF 格式,IDE 在编辑后保存时统一为 LF。仓库其余 Java 文件原本就是 LF,因此 `f93d7f01` 之后整个仓库的 Java 源码行尾达成一致(LF)。

## 影响范围

- **零功能影响**:Java 编译、Spring AI 运行时、LLM tool 调度均不依赖源文件行尾字节。
- **git blame 噪声**:`git blame` 在这 4 个文件上,**几乎所有行**都会指向 `f93d7f01`,即便实际语义只有 1 行 import 改名 — 因为 git 在行尾变更时把整文件视作 modified,blame 重新指向最新 commit。
- **跨开发者体验**:Windows 上 CRLF 默认的编辑器在未配置 `.gitattributes` 时会再再次产生 CRLF 文件,污染后续 commit。

## 已采取的对策

**2026-09-30** 在 `.gitattributes` 中追加一行:

```
*.java text eol=lf
```

效果:从今往后,任何对 `.java` 文件的 `git checkout` / `git add` 都将自动规范化到 LF,与开发者本地 `core.autocrlf` 设置解耦。这是 Spring Boot、Spring Framework、JDK 自身等大型 Java 项目的标准做法。

## 为什么不用 rebase 拆 f93d7f01

考虑过 `git rebase -i` 把 f93d7f01 拆为「行尾规范化」 + 「仅 import 改名」两个 commit,以让 `git blame` 精确指认。**未采用**,因为:
1. 改 `f93d7f01` 的 commit hash 会破坏任何在其基础上做的 cherry-pick / branch / tag
2. 行尾副作用**已经在历史中**,只能记录不能撤销
3. `.gitattributes` 规则 + 本 note 已经让"行尾规范化"成为可发现、可追溯的事实

## 参考链接

- `git help gitattributes` — 行尾属性语义
- `git log --follow -p loom-git-mcp/src/main/java/cn/wubo/loom/git/mcp/LoomGitMcpService.java` — 单独查看 LoomGitMcpService.java 历史
- 仓库根 `.gitattributes` — 当前锁定规则