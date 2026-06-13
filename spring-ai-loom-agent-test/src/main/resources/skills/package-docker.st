请严格按以下三步执行：

1. 从用户消息里提取以下参数。port 和 containerPort 任何一个缺失都必须先向用户追问，
   不要瞎猜、不要用 8080 之类的兜底：

   - gitUrl：Git 仓库 URL（必填，URL 形式）
   - gitUsername：Git 用户名（公开仓库可省略，私有仓库必填）
   - gitPassword：Git 密码或 token（同上）
   - port：宿主机对外端口（必填，例如用户说"我要在 9090 访问" → port=9090）
   - containerPort：应用在容器内实际监听的端口（必填，工具无 yml 兜底，缺失会 fail）
      如果用户没明说，提示"请确认仓里 application.yml 的 server.port 是多少"，
      让用户回答后再调用。**不要替用户猜默认值**——工具对缺 port / containerPort
      会 fail-fast 反问，你直接传一个 8080 兜底反而把责任揽到自己身上
   - subDir（可选）：多模块仓库指定子模块名。如果不指定，工具会自动尝试，但可能选错——
      多模块场景下请主动向用户确认要部署哪个
   - buildTool（可选）：maven / npm / npm-frontend / pip
      缺省时工具按仓里的 marker 文件自动探测（pom.xml→maven、package.json→npm、
      requirements.txt / pyproject.toml→pip）。多模块仓同时有 pom.xml 和 package.json 时
      必须显式指定。前端项目（Vue/React 静态构建）用 npm-frontend；Node 长驻后端用 npm；
      Python 后端用 pip。
   - baseImage（可选）：java17 / java21 / nginx / python3 / node20 / node20-serve，
      或完整镜像名（如 openjdk:17-slim）。缺省按 buildTool 自动选：
      maven→java17、npm→node20、npm-frontend→node20-serve（nginx 服务静态文件）、
      pip→python3
   - runCommand（可选）：覆盖 ENTRYPOINT 的字符串数组。Node 前端通常不需要；
      Python 后端常用 ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
   - healthPath（可选）：默认 "/"；没有 context-path 时传 "/"
      容器内端口 = containerPort（已在上文设），宿主机端口 = port

   ⚠️ 重要：不要把仓库名当成 branch！只有当用户**明确说出** "用 main 分支" / "用 develop 分支" / "切到 feature-xxx 分支" 等具体分支名时才传 branch 字段。
   仓库名 ≠ 分支名（"sql-forge-demo" 是仓库名，不是分支名）。

2. 把上述参数传给 @compileAndDeploy 工具，不要在工具外做任何额外的 shell / mvn / docker 操作：
   {
     "gitUrl": "用户提供的 URL",
     "gitUsername": "<可选，私有仓库填>",
     "gitPassword": "<可选，私有仓库填>",
     "port": <port>,
     "containerPort": <containerPort>,
     "subDir": "<可选>",
     "buildTool": "<可选>",
     "baseImage": "<可选>",
     "runCommand": [<可选>],
     "healthPath": "<可选，默认 '/'; 有 context-path 时填实际路径如 sql-forge-demo>"
   }

3. 工具返回结果后：
   - 若 result.success == true：用 <a href="result.accessUrl" target="_blank">result.accessUrl</a> 展示访问链接
   - 若 result.success == false：把 result.steps 里的每一步用 Markdown 列表展示给用户，
     并在末尾附上 result.errorMessage，让用户知道卡在哪一步

注意：不要手动调用 git / mvn / docker / npm 工具，也不要拆分 @compileAndDeploy。
整个流程是 LLM → 单次工具调用 → 渲染结果。
