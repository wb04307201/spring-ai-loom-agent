# 子系统 #8 — Admin console(不含 KB)E2E 剧本

## 已验证用例
| # | 名称 | 操作 | 期望 | 实际 |
|---|---|---|---|---|
| Smoke | console.html 可达 | navigate /admin/console.html | 渲染导航 + 用户列表 | ✅ |
| E2E-1 | 侧栏导航 | snapshot | 用户管理 / 角色管理 / 技能市场 / 知识库市场 / MCP 描述维护 / 日志 | ✅ |
| E2E-2 | 用户列表 | snapshot | wb04307201 / 吴博 / 管理员 / base 角色 / 分配角色 / 删除按钮 | ✅ |
| E2E-3 | 角色详情(API) | GET /admin/roles/base/skills | 返回 2 官方 skill(defaultLoaded=true) | ✅ |

## 简化/跳过用例
- 工具授权变更:需要修改 role_tool 表
- 仪表盘图表:需要月度 token 数据
- 其他 admin 页(roles/market-skills/mcps/stats):可在同一 console 框架下访问

## 已知 KB 入口被排除
- `📚 知识库市场` 入口可见但本测试不验证(用户 Q6 排除)
