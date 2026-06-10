用户希望"测试自动编译"——克隆代码 → 编译 → 构建镜像 → 启动容器 → 拿到访问链接。

请严格按以下三步执行：

1. 从用户消息里提取以下参数。port 和 containerPort 任何一个缺失都必须先向用户追问，
   不要瞎猜、不要用 8080 之类的兜底：

   - gitUrl：Git 仓库 URL（必填，URL 形式）
   - gitUsername：Git 用户名（公开仓库可省略，私有仓库必填）
   - gitPassword：Git 密码或 token（同上）
   - port：宿主机对外端口（必填，例如用户说"我要在 9090 访问" → port=9090）
   - containerPort：应用在容器内实际监听的端口（必填）
      如果用户没明说，提示"请确认仓里 application.yml 的 server.port 是多少"，
      让用户回答后再调用。如果仓没有 application.yml 或没有 server.port 字段，
      默认 8080 并告知用户
   - subDir（可选）：多模块仓库指定子模块名。如果不指定，工具会自动尝试，但可能选错——
      多模块场景下请主动向用户确认要部署哪个
   - baseImage（可选）：java17 / java21 / nginx / python3 或完整镜像名
   - runCommand（可选）：覆盖 ENTRYPOINT 的字符串数组
   - healthPath（可选）：默认 "/"；没有 context-path 时传 "/"

   ⚠️ 重要：不要把仓库名当成 branch！只有当用户**明确说出** "用 main 分支" / "用 develop 分支" / "切到 feature-xxx 分支" 等具体分支名时才传 branch 字段。
   仓库名 ≠ 分支名（"sql-forge-demo" 是仓库名，不是分支名）。

2. 把上述参数传给 @compileAndDeploy 工具，不要在工具外做任何额外的 shell / mvn / docker 操作：
   {
     "gitUrl": "用户提供的 URL",
     "gitUsername": "用户名（可省略）",
     "gitPassword": "密码（可省略）",
     "port": <port>,
     "containerPort": <containerPort>,
     "subDir": "<可选>",
     "baseImage": "<可选>",
     "runCommand": [<可选>],
     "healthPath": "/"
   }

3. 工具返回结果后：
   - 若 result.success == true：用 <a href="result.accessUrl" target="_blank">result.accessUrl</a> 展示访问链接
   - 若 result.success == false：把 result.steps 里的每一步用 Markdown 列表展示给用户，
     并在末尾附上 result.errorMessage，让用户知道卡在哪一步

注意：不要手动调用 git / mvn / docker 工具，也不要拆分 @compileAndDeploy。
整个流程是 LLM → 单次工具调用 → 渲染结果。