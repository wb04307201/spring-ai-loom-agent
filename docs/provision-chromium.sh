#!/bin/bash
# 一次性在 Linux 裸机(Debian/Ubuntu)上 provision Chromium 运行环境。需要 root。
# 系统 so 库交给 Playwright CLI install-deps(按发行版维护正确包名清单、与 jar 内
# driver 版本锁定 —— 手写 apt 清单会在 Ubuntu 24.04 t64 包名改名后失效,2026-09-10
# 最终评审裁决改用官方命令)。RHEL/CentOS:install-deps 仅支持 Debian/Ubuntu,
# 请手动安装等价 so 库 + fonts-noto-cjk,装系统 chromium 包后配置
# spring.ai.loom.agent.render.chromium-path=/usr/bin/chromium-browser
set -e
JAR_PATH="${1:?用法: provision-chromium.sh /path/to/app.jar}"

# 1. CJK 字体(install-deps 不管字体;缺字体 = 中文截图全豆腐块 □□□)
apt-get update && apt-get install -y fonts-noto-cjk fonts-noto-color-emoji

# 2. 系统 so 库(Playwright 官方按发行版维护,自动处理 t64 等包名演进)
java -cp "$JAR_PATH" com.microsoft.playwright.CLI install-deps chromium

# 3. Playwright 管理的 Chromium(下载到 ~/.cache/ms-playwright/,版本与 jar 内 playwright 依赖锁定一致)
java -cp "$JAR_PATH" com.microsoft.playwright.CLI install chromium

echo "✅ provision 完成。以非 root 用户启动服务即可(root 跑 Chromium 需 --no-sandbox,不推荐)。"
