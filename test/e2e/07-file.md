# 子系统 #7 — File management E2E 剧本

## 已验证用例
| # | 名称 | 操作 | 期望 | 实际 |
|---|---|---|---|---|
| Smoke | 文件管理模态框可达 | index.html click 📁 文件 | 弹出目录树 + 文件列表 | ✅ |
| E2E-1 | 目录树 | snapshot | 含 `factory-maintain-prototype` / `原型` / `prototypes` 等多个目录 | ✅ |
| E2E-2 | 文件项 | snapshot | `mes-basic-data-prototype.html` 23.33KB + 预览 + 下载按钮 | ✅ |

## 简化/跳过用例
- 实际上传文件:需要准备本地文件 + 走 input[type=file]
- 预览内容验证:需要先点击预览按钮
- 下载验证:Chrome DevTools MCP 暂无原生 download listener
