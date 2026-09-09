#!/bin/bash
# 一次性在 Linux 裸机(Debian/Ubuntu)上 provision Chromium 运行环境。需要 root。
# RHEL/CentOS 用户:把 apt-get 换成 yum/dnf 对应包(见注释),Chromium 装系统包后
# 配置 spring.ai.loom.agent.render.chromium-path=/usr/bin/chromium-browser
set -e
JAR_PATH="${1:?用法: provision-chromium.sh /path/to/app.jar}"

# 1. 系统 so 库 + CJK 字体(缺字体 = 中文截图全豆腐块 □□□)
apt-get update && apt-get install -y \
  fonts-noto-cjk fonts-noto-color-emoji \
  libnss3 libgbm1 libxkbcommon0 libasound2 libatk1.0-0 libatk-bridge2.0-0 \
  libcups2 libdrm2 libxcomposite1 libxdamage1 libxrandr2 libpango-1.0-0 \
  libxfixes3 libxext6 libx11-6 libxcb1 libatspi2.0-0

# 2. Playwright 管理的 Chromium(下载到 ~/.cache/ms-playwright/,版本与 jar 内 playwright 依赖锁定一致)
java -cp "$JAR_PATH" com.microsoft.playwright.CLI install chromium

echo "✅ provision 完成。以非 root 用户启动服务即可(root 跑 Chromium 需 --no-sandbox,不推荐)。"
