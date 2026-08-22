(function () {
  "use strict";

  const tableContainer = document.getElementById("user-table-container");
  const adminUsername = document.getElementById("admin-username");
  const createBtn = document.getElementById("create-user-btn");
  const refreshBtn = document.getElementById("refresh-btn");

  const createModal = document.getElementById("create-user-modal");
  const createClose = document.getElementById("create-user-close");
  const createCancel = document.getElementById("create-cancel-btn");
  const createSubmit = document.getElementById("create-submit-btn");
  const newUsername = document.getElementById("new-username");
  const newNickname = document.getElementById("new-nickname");
  const newPassword = document.getElementById("new-password");
  const newType = document.getElementById("new-type");
  const createError = document.getElementById("create-error");

  const confirmOverlay = document.getElementById("confirm-modal");
  const confirmTitle = document.getElementById("confirm-title");
  const confirmMessage = document.getElementById("confirm-message");
  const confirmOk = document.getElementById("confirm-ok");
  const confirmCancel = document.getElementById("confirm-cancel");
  const confirmClose = document.getElementById("confirm-close");

  const toastEl = document.getElementById("toast-notification");

  // 1. 进入页面：先校验管理员身份
  async function bootstrap() {
    try {
      const resp = await fetch("/spring/ai/loom/user/currentIsAdmin", {
        method: "POST",
        credentials: "include",
        redirect: "manual",
      });
      // 0 = opaque redirect (browser blocked it due to credentials/cookie). Treat as "not admin"
      if (resp.type === "opaqueredirect" || resp.status === 0) {
        window.location.replace("/spring/ai/loom/index.html");
        return;
      }
      if (resp.status === 401 || resp.status === 302 || resp.status === 303) {
        window.location.replace("/spring/ai/loom/index.html");
        return;
      }
      // If response is HTML (redirect target), body is index.html — treat as not admin
      const ct = resp.headers.get("content-type") || "";
      if (ct.includes("text/html")) {
        window.location.replace("/spring/ai/loom/index.html");
        return;
      }
      const isAdmin = await resp.json();
      if (isAdmin !== true) {
        window.location.replace("/spring/ai/loom/index.html");
        return;
      }
      const me = await postJson("/spring/ai/loom/user/currentUser");
      adminUsername.textContent = `${me.nickname || me.username}（${me.type === "ADMIN" ? "管理员" : "用户"}）`;
      await loadUsers();
    } catch (e) {
      // 网络错误 / JSON 解析错误 = 强制跳走
      window.location.replace("/spring/ai/loom/index.html");
    }
  }

  async function loadUsers() {
    tableContainer.innerHTML = '<div class="loading-indicator">加载中...</div>';
    try {
      const list = await fetch("/spring/ai/loom/admin/users", {
        credentials: "include",
      });
      if (list.status === 401) {
        window.location.replace("/spring/ai/loom/login.html");
        return;
      }
      if (!list.ok) {
        tableContainer.innerHTML = `<div class="empty-state">加载失败：HTTP ${list.status}</div>`;
        return;
      }
      const users = await list.json();
      renderTable(users);
    } catch (e) {
      tableContainer.innerHTML = `<div class="empty-state">加载失败：${e.message}</div>`;
    }
  }

  function renderTable(users) {
    if (!users || users.length === 0) {
      tableContainer.innerHTML = '<div class="empty-state">暂无用户</div>';
      return;
    }
    const rows = users
      .map((u) => {
        const typeLabel = u.type === "ADMIN" ? "管理员" : "普通用户";
        return `<tr data-username="${escapeHtml(u.username)}" data-type="${escapeHtml(u.type)}">
 <td><strong>${escapeHtml(u.username)}</strong></td>
 <td>${escapeHtml(u.nickname || "")}</td>
 <td><span class="type-badge ${u.type}">${typeLabel}</span></td>
 <td>
 <button class="secondary-btn assign-role-btn" data-username="${escapeHtml(u.username)}" data-type="${escapeHtml(u.type)}">分配角色</button>
 <button class="delete-btn" data-username="${escapeHtml(u.username)}">删除</button>
 </td>
 </tr>`;
      })
      .join("");
    tableContainer.innerHTML = `
 <table class="user-table">
 <thead>
 <tr><th>用户名</th><th>昵称</th><th>类型</th><th>操作</th></tr>
 </thead>
 <tbody>${rows}</tbody>
 </table>`;
    // 绑定删除按钮
    tableContainer.querySelectorAll(".delete-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        const username = btn.getAttribute("data-username");
        confirmDialog({
          title: "删除用户",
          message: `确定要删除用户「${username}」吗？此操作不可撤销。`,
          okText: "删除",
        }).then((ok) => {
          if (!ok) return;
          deleteUser(username);
        });
      });
    });
    // 绑定"分配角色"按钮
    tableContainer.querySelectorAll(".assign-role-btn").forEach((btn) => {
      btn.addEventListener("click", () =>
        openAssignRole(
          btn.getAttribute("data-username"),
          btn.getAttribute("data-type"),
        ),
      );
    });
  }

  async function deleteUser(username) {
    try {
      const resp = await fetch(
        `/spring/ai/loom/admin/users/${encodeURIComponent(username)}`,
        {
          method: "DELETE",
          credentials: "include",
        },
      );
      const text = await resp.text();
      if (!resp.ok) {
        let msg = `删除失败：HTTP ${resp.status}`;
        try {
          msg = JSON.parse(text).message || msg;
        } catch (_) {}
        showToast(msg, "error");
        return;
      }
      showToast("用户已删除", "success");
      await loadUsers();
    } catch (e) {
      showToast("删除失败：" + e.message, "error");
    }
  }

  function openCreate() {
    newUsername.value = "";
    newNickname.value = "";
    newPassword.value = "";
    newType.value = "USER";
    createError.style.display = "none";
    createModal.style.display = "flex";
    setTimeout(() => newUsername.focus(), 0);
  }

  function closeCreate() {
    createModal.style.display = "none";
  }

  async function submitCreate() {
    const username = newUsername.value.trim();
    const nickname = newNickname.value.trim();
    const password = newPassword.value;
    const type = newType.value;
    if (!username || !nickname || !password) {
      createError.textContent = "请填写所有字段";
      createError.style.display = "block";
      return;
    }
    if (password.length < 6) {
      createError.textContent = "密码至少 6 位";
      createError.style.display = "block";
      return;
    }
    createSubmit.disabled = true;
    try {
      const resp = await fetch("/spring/ai/loom/admin/users", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify({ username, nickname, password, type }),
      });
      const text = await resp.text();
      if (!resp.ok) {
        let msg = `创建失败：HTTP ${resp.status}`;
        try {
          msg = JSON.parse(text).message || msg;
        } catch (_) {}
        createError.textContent = msg;
        createError.style.display = "block";
        return;
      }
      closeCreate();
      showToast("用户创建成功", "success");
      await loadUsers();
    } catch (e) {
      createError.textContent = "网络错误：" + e.message;
      createError.style.display = "block";
    } finally {
      createSubmit.disabled = false;
    }
  }

  // 通用确认弹窗
  function confirmDialog({
    title,
    message,
    okText = "确定",
    cancelText = "取消",
  }) {
    return new Promise((resolve) => {
      confirmTitle.textContent = title;
      confirmMessage.textContent = message;
      confirmOk.textContent = okText;
      confirmCancel.textContent = cancelText;
      confirmOverlay.style.display = "flex";
      const onOk = () => {
        cleanup();
        confirmOverlay.style.display = "none";
        resolve(true);
      };
      const onCancel = () => {
        cleanup();
        confirmOverlay.style.display = "none";
        resolve(false);
      };
      confirmOk.onclick = onOk;
      confirmCancel.onclick = onCancel;
      confirmClose.onclick = onCancel;
      function cleanup() {
        confirmOk.onclick = null;
        confirmCancel.onclick = null;
        confirmClose.onclick = null;
      }
    });
  }

  // Toast
  function showToast(text, type = "success") {
    toastEl.textContent = text;
    toastEl.className = "toast show " + type;
    setTimeout(() => {
      toastEl.className = "toast";
    }, 2500);
  }

  async function postJson(url, body) {
    const resp = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: body ? JSON.stringify(body) : null,
    });
    if (resp.status === 401) {
      window.location.replace("/spring/ai/loom/login.html");
      throw new Error("未登录");
    }
    if (!resp.ok) {
      const text = await resp.text();
      throw new Error(`HTTP ${resp.status} ${text.substring(0, 200)}`);
    }
    return await resp.json();
  }

  function escapeHtml(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  // 事件绑定
  createBtn.addEventListener("click", openCreate);
  refreshBtn.addEventListener("click", loadUsers);
  createClose.addEventListener("click", closeCreate);
  createCancel.addEventListener("click", closeCreate);
  createSubmit.addEventListener("click", submitCreate);
  confirmOverlay.addEventListener("click", (e) => {
    if (e.target === confirmOverlay) confirmClose.click();
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      if (createModal.style.display === "flex") closeCreate();
      if (confirmOverlay.style.display === "flex") confirmClose.click();
    }
  });

  // ========== 分配角色（在控制台内直接弹窗，不用跳页） ==========
  const assignModal = document.getElementById("assign-role-modal");
  const assignTitle = document.getElementById("assign-role-title");
  const assignHint = document.getElementById("assign-role-hint");
  const assignList = document.getElementById("assign-role-list");
  const assignErr = document.getElementById("assign-role-error");
  const assignSave = document.getElementById("assign-role-save");
  const assignClose = document.getElementById("assign-role-close");
  const assignCancel = document.getElementById("assign-role-cancel");
  let assignTarget = null; // {username, type}

  async function openAssignRole(username, type) {
    assignTarget = { username, type };
    assignErr.style.display = "none";
    assignTitle.textContent = `分配角色：${username}`;
    if (type === "ADMIN") {
      assignHint.textContent =
        "管理员账号默认拥有全部 MCP 服务，无需分配角色。";
      assignList.innerHTML = "";
      assignSave.style.display = "none";
      assignModal.style.display = "flex";
      return;
    }
    assignHint.textContent =
      "勾选要分配给该用户的角色（可多选）。用户实际可用的 MCP = 所有已选角色授权 MCP 的并集。";
    assignSave.style.display = "";
    assignList.innerHTML = '<div class="loading-indicator">加载中...</div>';
    assignModal.style.display = "flex";
    try {
      const [allRoles, myRoles] = await Promise.all([
        fetch("/spring/ai/loom/admin/roles", { credentials: "include" }).then(
          (r) => (r.ok ? r.json() : []),
        ),
        fetch(
          `/spring/ai/loom/admin/users/${encodeURIComponent(username)}/roles`,
          { credentials: "include" },
        ).then((r) => (r.ok ? r.json() : [])),
      ]);
      renderAssignRoleList(allRoles || [], myRoles || []);
    } catch (e) {
      assignList.innerHTML = `<div class="empty-state">加载失败：${escapeHtml(e.message)}</div>`;
    }
  }

  function renderAssignRoleList(allRoles, myRoles) {
    if (!allRoles || allRoles.length === 0) {
      assignList.innerHTML =
        '<div style="color: var(--text-muted); padding: 8px;">系统暂无任何角色，请先到<a href="roles.html">角色管理</a>创建。</div>';
      return;
    }
    const mySet = new Set(myRoles);
    assignList.innerHTML = allRoles
      .map(
        (r) => `
 <label style="display: flex; align-items: center; gap: 8px; padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; cursor: pointer; background: ${mySet.has(r.code) ? "#f0fdf4" : "#fff"};">
 <input type="checkbox" class="assign-role-cb" value="${escapeHtml(r.code)}" ${mySet.has(r.code) ? "checked" : ""}>
 <span style="flex: 1;">
 <strong style="font-size: 13px;">${escapeHtml(r.code)}</strong>
 <span style="color: var(--text-muted); font-size: 12px; margin-left: 8px;">${escapeHtml(r.name || "")}</span>
 ${r.system ? '<span class="type-badge ADMIN" style="margin-left: 6px;">系统</span>' : ""}
 </span>
 <span style="color: var(--text-muted); font-size: 12px;">${escapeHtml(r.description || "")}</span>
 </label>
 `,
      )
      .join("");
  }

  function closeAssignRole() {
    assignModal.style.display = "none";
    assignTarget = null;
  }

  assignSave.addEventListener("click", async () => {
    if (!assignTarget) return;
    const checked = Array.from(
      assignList.querySelectorAll(".assign-role-cb:checked"),
    ).map((cb) => cb.value);
    assignSave.disabled = true;
    assignErr.style.display = "none";
    try {
      const r = await fetch(
        `/spring/ai/loom/admin/users/${encodeURIComponent(assignTarget.username)}/roles`,
        {
          method: "PUT",
          credentials: "include",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ roleCodes: checked }),
        },
      );
      if (!r.ok) {
        const t = await r.text();
        assignErr.textContent = `保存失败：${t || "HTTP " + r.status}`;
        assignErr.style.display = "block";
        return;
      }
      showToast(
        `已为「${assignTarget.username}」分配 ${checked.length} 个角色`,
        "success",
      );
      closeAssignRole();
    } catch (e) {
      assignErr.textContent = "网络错误：" + e.message;
      assignErr.style.display = "block";
    } finally {
      assignSave.disabled = false;
    }
  });

  assignClose.addEventListener("click", closeAssignRole);
  assignCancel.addEventListener("click", closeAssignRole);

  bootstrap();
})();
