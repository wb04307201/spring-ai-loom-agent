# Fixture 初始化剧本

**目标**: 验证 mvn spring-boot:run + Flyway 默认 seed + Chrome 就绪
**前置**: 无
**步骤**:
1. 清库 `rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds`
2. 后台启 mvn `nohup mvn spring-boot:run -pl spring-ai-loom-agent-test -Dgpg.skip=true &`
3. 轮询 `curl /spring/ai/loom/api/features` 直到 200 OK
4. Chrome DevTools MCP `new_page` → `http://localhost:8080/spring/ai/loom/login.html`
5. take_snapshot 验证 login form 元素存在

**断言**:
- /spring/ai/loom/api/features 返回 200 + `{"knowledge":false}`(RAG 默认启用但 spec 排除 → false,确保不触发 KB 检索)
- `/admin/users` 返回 admin (wb04307201) + type=ADMIN
- `/admin/roles` 返回 base 角色
- `/mcps` 返回 4 个 MCP server (bing-search + 路由元素)
- login form 含 username textbox + password textbox + 登录按钮
- Tomcat started on port 8080 + 启动 < 30s

**失败处理**: mvn 启不起来 → abort

**关键 setup 经验**(未来回归参考):
- 每次 Bash 调用需 `export JAVA_HOME=/c/Program Files/Java/jdk-25.0.3 && export PATH=$JAVA_HOME/bin:$PATH`(每个 Bash 是独立 shell)
- **JDK 25 必须同时改 JAVA_HOME 和 PATH**(只改 JAVA_HOME 时 mvn 仍走 jdk-17)
- `mvn -pl X spring-boot:run` 默认**不编译依赖模块**,若 target/classes 陈旧会失败
- **修复陈旧 .class**: `mvn clean install -pl <dep-modules> -am -DskipTests` 强制重编并装到 repo
- **登录走 POST /spring/ai/loom/user/login**(JSON body `{username, password}`),返回 `Set-Cookie: loom-agent-session=<uuid>` + `{token, nickname}`,后续 API 需带 `Cookie: loom-agent-session=<token>`

**截图**: `docs/notes/browser-baselines-2026-09-30/login/login.png`

**清理**: 无(mvn 持续运行,后续 Task 复用)
