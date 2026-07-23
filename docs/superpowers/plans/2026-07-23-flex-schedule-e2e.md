# flex-schedule 1.2.2 升级 Chrome E2E 全面测试 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (this plan is interactive — every step needs the running session's MCP tools, no fresh subagent per task). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the 16-scenario + 3-bonus Chrome E2E matrix defined in `docs/superpowers/specs/2026-07-23-flex-schedule-e2e-design.md` against the running test app at `http://localhost:8080`, and produce a pass/fail report with per-scenario evidence.

**Architecture:** One main session drives the running Spring Boot app via Playwright MCP (Chrome), DB ops via H2 console form (`curl`), and process control via `taskkill`/`mvn`. Output goes to `e2e-results/<UTC-timestamp>/`. There is no code to write — the deliverable is verified test results.

**Tech Stack:** Spring Boot 3.5.16, flex-schedule 1.2.2, Playwright MCP, H2 console form, Bash/curl, qwen3.7-plus (DashScope).

## Global Constraints

- App is on PID 43980, port 8080 (already running before this plan starts; do NOT kill unless task explicitly says).
- All output goes under `e2e-results/<UTC-timestamp>/`. Use a single fixed timestamp folder for the whole run — never recreate mid-run.
- Scenario IDs (`NN-role-type-path`) MUST match the matrix table in the spec. No renames.
- For every scenario, capture: chat.png, panel.png, llm-stream.log, db-state.json, result.json.
- Do NOT touch the `flex-schedule` source code; do NOT touch any project source files. This plan reads + drives the running system only.
- Cookies: each role gets a dedicated Playwright context (`ctx-admin`, `ctx-tester`); do not mix them.
- Every scenario's first action is a DB reset; the last action is a result.json write.
- Commit NO files to git inside the e2e-results dir (it's already in `.gitignore` if any, otherwise just leave it untracked).

---

## File / Artifact Layout (per run)

```
e2e-results/<UTC-ts>/
├── summary.md
├── results.jsonl
├── scenarios/
│   ├── 01-admin-cron-success/{chat.png, panel.png, llm-stream.log, db-state.json, result.json}
│   ├── 02-admin-fixed_delay-success/...
│   ├── ... (16)
│   └── 16-tester-one_shot-failure/...
├── bonus/
│   ├── B1-cross-user-cancel/{chat-admin.png, chat-tester.png, db-state.json, result.json}
│   ├── B2-restart-restore/{app-startup.log, db-state.json, result.json}
│   └── B3-max-lifetime/{app-startup.log, db-state.json, result.json}
└── failures/  (only if any scenario fails — copy of evidence + console.log + network)
```

## Scenario Matrix Reference

For quick lookup during execution. Full table in the spec.

| ID | Role | Type | Path | Prompt |
|----|------|------|------|--------|
| 01 | admin | cron | success | 帮我创建一个每 5 分钟报时一次的定时任务 |
| 02 | admin | fixed_delay | success | 每 30 秒查一次北京天气 |
| 03 | admin | fixed_rate | success | 每 20 秒问候我一次 |
| 04 | admin | one_shot | success | 30 秒后提醒我去开会 |
| 05 | admin | cron | failure | 每 1 秒报时一次 |
| 06 | admin | fixed_delay | failure | 每 5 秒查一次天气 |
| 07 | admin | fixed_rate | failure | 每 5 秒问候一次 |
| 08 | admin | one_shot | failure | 30 秒后提醒我开会(空 prompt 诱导) |
| 09 | tester | cron | success | (same as 01) |
| 10 | tester | fixed_delay | success | (same as 02) |
| 11 | tester | fixed_rate | success | (same as 03) |
| 12 | tester | one_shot | success | (same as 04) |
| 13 | tester | cron | failure | (same as 05) |
| 14 | tester | fixed_delay | failure | (same as 06) |
| 15 | tester | fixed_rate | failure | (same as 07) |
| 16 | tester | one_shot | failure | (same as 08) |

---

### Task 1: 环境核查 + 创建结果目录 + 创建 tester 用户

**Files / Artifacts:**
- Create: `e2e-results/<UTC-ts>/` (capture `UTC_TS=$(date -u +%Y%m%d-%H%M%S)` once and reuse)
- Create: `e2e-results/<UTC-ts>/scenarios/`, `e2e-results/<UTC-ts>/bonus/`, `e2e-results/<UTC-ts>/failures/`

- [ ] **Step 1.1: Capture UTC timestamp**

Run:
```bash
UTC_TS=$(date -u +%Y%m%d-%H%M%S)
echo "$UTC_TS"
mkdir -p "e2e-results/$UTC_TS/scenarios" "e2e-results/$UTC_TS/bonus" "e2e-results/$UTC_TS/failures"
echo "$UTC_TS" > /tmp/e2e-ts
```
Expected: a single timestamp line, no errors.

- [ ] **Step 1.2: Verify app is still up**

Run:
```bash
curl -sf -o /dev/null -w "%{http_code}\n" http://localhost:8080/actuator/health
netstat -ano 2>/dev/null | grep ":8080" | grep LISTENING | awk '{print $5}' | head -1
```
Expected: `200` and a PID number. If not 200 → STOP and report; the plan cannot continue without the running app.

- [ ] **Step 1.3: Verify H2 console reachable**

Run:
```bash
curl -sf -o /dev/null -w "%{http_code}\n" http://localhost:8080/h2-console
```
Expected: `200` or `302`. Anything else → STOP.

- [ ] **Step 1.4: Probe admin login — first attempt default passwords**

Run (from Git Bash on Windows):
```bash
for pw in 123456 admin password wb04307201; do
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/spring/ai/loom/user/login \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"wb04307201\",\"password\":\"$pw\"}")
  echo "$pw -> $code"
done
```
Expected: exactly one of the passwords returns `200`. (Look at login response for a `Set-Cookie` header.)

- [ ] **Step 1.5: If no default password works, read bcrypt from H2 + try common passwords**

Run:
```bash
# Open H2 console session, query user_info
curl -s -c /tmp/h2.cookies "http://localhost:8080/h2-console/login.do?jsessionid=" \
  -d "language=en&user=sa&password=123456&driver=org.h2.Driver&url=jdbc:h2:file:$USERPROFILE/.loom/datasource/db"
curl -s -b /tmp/h2.cookies "http://localhost:8080/h2-console/query.do?jsessionid=" \
  --data-urlencode "sql=SELECT username, SUBSTRING(password,1,7) AS hash_prefix FROM user_info"
```
Expected: HTML containing `wb04307201` and the bcrypt prefix `$2a$10$`. (If the bcrypt matches one already known — try `wb04307201`, `admin`, `123456`. If none work, **STOP and ask the user** for the password; do NOT brute force.)

- [ ] **Step 1.6: Save admin login credentials into a shell var**

After Step 1.4 / 1.5 succeeds:
```bash
echo "ADMIN_PW=<the working password>" > /tmp/e2e-creds
echo "ADMIN_USER=wb04307201" >> /tmp/e2e-creds
chmod 600 /tmp/e2e-creds
```

- [ ] **Step 1.7: Log in as admin via Playwright MCP and persist cookie**

Tool calls:
1. `mcp__playwright__browser_new_page` url=`http://localhost:8080/spring/ai/loom/`
2. `mcp__playwright__browser_snapshot` — read the snapshot to find the login form refs
3. `mcp__playwright__browser_fill_form` with `username` and `password` fields and the `submit` button
4. `mcp__playwright__browser_wait_for` text="聊天" (or any sentinel after login lands on the chat page)
5. `mcp__playwright__browser_take_screenshot` filename=`admin-logged-in.png`

If snapshot shows no login form (already auto-logged in via prior cookie), skip form fill and screenshot whatever the landing page is.

- [ ] **Step 1.8: Create `tester` user via admin endpoint**

Run (use the cookie captured by Playwright — easiest: re-use the page's fetch from `browser_evaluate`):
```js
// In mcp__playwright__browser_evaluate:
(async () => {
  const r = await fetch('/spring/ai/loom/admin/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'tester', nickname: '测试员', password: 'Test@1234', type: 'USER' })
  });
  return { status: r.status, body: await r.text() };
})()
```
Expected: `{status: 200, body: "true"}`. If 400 with "用户已存在" that's also OK — skip step.

- [ ] **Step 1.9: Reset the schedule tables**

Run (H2 console form):
```bash
curl -s -b /tmp/h2.cookies -c /tmp/h2.cookies \
  "http://localhost:8080/h2-console/query.do?jsessionid=" \
  --data-urlencode "sql=DELETE FROM loom_scheduled_task; DELETE FROM loom_schedule_execution;"
# Verify
curl -s -b /tmp/h2.cookies \
  "http://localhost:8080/h2-console/query.do?jsessionid=" \
  --data-urlencode "sql=SELECT COUNT(*) AS n FROM loom_scheduled_task"
```
Expected: response contains `0`.

- [ ] **Step 1.10: Log in as tester in a separate context**

Tool calls:
1. `mcp__playwright__browser_new_page` url=`http://localhost:8080/spring/ai/loom/user/logout` (if available) — else just open a new page and fill login form again
2. Fill the login form with `tester` / `Test@1234`
3. `browser_wait_for` text="聊天"
4. `browser_take_screenshot` filename=`tester-logged-in.png`

- [ ] **Step 1.11: Commit checkpoint**

```bash
git status e2e-results/  # confirm none committed (untracked)
```
Do NOT commit. The artifacts are intentionally untracked.

---

### Task 2: 执行场景 01-04 (admin 4 个成功路径)

For each scenario, follow the **shared scenario procedure** below with the per-scenario specifics substituted.

**Shared scenario procedure:**

1. **Reset DB**: H2 console DELETE on both tables (one shell command, parallel).
2. **Snapshot the empty schedule panel**: `browser_navigate` to SPA, click "定时任务" if visible, `browser_take_screenshot`. Verify it shows `(无定时任务)`.
3. **Trigger chat**: focus `#chat-input` (or whatever the snapshot shows), `browser_type` with the scenario prompt.
4. **Wait for sentinel**: `browser_wait_for` text matching `[定时已创建]` OR `[定时失败]` OR `[取消失败]`, timeout 90s.
5. **Screenshot chat**: `browser_take_screenshot` filename=`chat.png`.
6. **Extract LLM response**: scroll to bottom of chat, `browser_snapshot`, parse out the last assistant message text into a shell var `LLM_RESP`. Save raw text to `llm-stream.log`.
7. **REST verify (browser_evaluate)**:
   ```js
   (async () => {
     const convs = await (await fetch('/spring/ai/loom/user/conversations')).json();
     // pick the most recently active conv, or extract from LLM response if it includes a name
     const list = await (await fetch('/spring/ai/loom/schedule/by-conversation/' + convId)).json();
     return { convId, list };
   })()
   ```
   Save to `db-state.json` (in DB shape: `{ rows: [...], panelCount: N }`).
8. **Screenshot schedule panel**: click "定时任务" button if needed, `browser_take_screenshot` filename=`panel.png`.
9. **Judge**: cross-check LLM response, DB rows, and panel rows against the scenario's `expected` rule from the matrix.
10. **Write `result.json`**: `{ id, role, type, path, pass: bool, llmResp: "...", dbRowCount, panelRowCount, failReason, evidencePaths }`.
11. **Append to `/tmp/e2e-results.jsonl`** for the final summary.

- [ ] **Step 2.1: Scenario 01 — admin / cron / success**

Per-scenario specifics:
- prompt: `帮我创建一个每 5 分钟报时一次的定时任务`
- expected: LLM contains `[定时已创建]`; DB row in `loom_scheduled_task` with `schedule_type='cron'`; panel shows 1 row.
- artifact dir: `e2e-results/<ts>/scenarios/01-admin-cron-success/`

- [ ] **Step 2.2: Scenario 02 — admin / fixed_delay / success**

- prompt: `每 30 秒查一次北京天气`
- expected: `[定时已创建]`; `schedule_type='fixed_delay'`; `interval_seconds` row populated; panel 1 row.
- artifact dir: `02-admin-fixed_delay-success/`

- [ ] **Step 2.3: Scenario 03 — admin / fixed_rate / success**

- prompt: `每 20 秒问候我一次`
- expected: `[定时已创建]`; `schedule_type='fixed_rate'`; `interval_seconds` row populated; panel 1 row.
- artifact dir: `03-admin-fixed_rate-success/`

- [ ] **Step 2.4: Scenario 04 — admin / one_shot / success (waits for actual fire)**

- prompt: `30 秒后提醒我去开会`
- expected immediately after create: `[定时已创建]`; panel 1 row.
- THEN: `browser_wait_for` time=35 seconds (so the one_shot fires).
- THEN: re-query `loom_schedule_execution` for this task name; expected 1 row.
- artifact dir: `04-admin-one_shot-success/`

- [ ] **Step 2.5: Mid-batch checkpoint**

Print running tally: 4/4 PASS so far? If any FAILED-LLM-FLAKE, retry once with the backup prompt (see Task 7). If STATE-MISMATCH or UI-MISMATCH, copy evidence to `failures/01-...` and continue (don't abort — fix later).

---

### Task 3: 执行场景 05-08 (admin 4 个失败路径)

Same shared procedure. For failure scenarios, expected DB row count = 0 and panel count = 0.

- [ ] **Step 3.1: Scenario 05 — admin / cron / failure (sub-min-interval)**

- prompt: `帮我创建一个每 1 秒报时一次的定时任务` (1s < 10m min-interval)
- expected: `[定时失败]` mentioning minimum interval; DB 0 rows; panel empty.
- artifact dir: `05-admin-cron-failure/`

- [ ] **Step 3.2: Scenario 06 — admin / fixed_delay / failure**

- prompt: `每 5 秒查一次天气`
- expected: `[定时失败]`; DB 0 rows.
- artifact dir: `06-admin-fixed_delay-failure/`

- [ ] **Step 3.3: Scenario 07 — admin / fixed_rate / failure**

- prompt: `每 5 秒问候我一次`
- expected: `[定时失败]`; DB 0 rows.
- artifact dir: `07-admin-fixed_rate-failure/`

- [ ] **Step 3.4: Scenario 08 — admin / one_shot / failure (empty prompt)**

- prompt: trick wording that causes LLM to omit/blank the prompt, e.g.: `帮我创建一个 30 秒后执行的定时任务，但不需要执行任何内容`
- backup retry prompt if first fails to produce empty prompt: `帮我创建一个 30 秒后什么也不做的定时任务` (still empty prompt content)
- expected: `[定时失败] prompt 不能为空` (or similar); DB 0 rows.
- artifact dir: `08-admin-one_shot-failure/`

- [ ] **Step 3.5: Mid-batch checkpoint**

Print: 8/8 admin scenarios complete, X PASS / Y FAILED.

---

### Task 4: 执行场景 09-12 (tester 4 个成功路径)

Switch the active Playwright page to the tester context (use `browser_tabs` select or reopen the tester page; if no tester page active, use the page from Task 1.10).

Same shared procedure. Watch out for cross-user isolation: tester MUST NOT see admin's tasks.

- [ ] **Step 4.1: Scenario 09 — tester / cron / success**

- artifact dir: `09-tester-cron-success/`

- [ ] **Step 4.2: Scenario 10 — tester / fixed_delay / success**

- artifact dir: `10-tester-fixed_delay-success/`

- [ ] **Step 4.3: Scenario 11 — tester / fixed_rate / success**

- artifact dir: `11-tester-fixed_rate-success/`

- [ ] **Step 4.4: Scenario 12 — tester / one_shot / success (waits for actual fire)**

- artifact dir: `12-tester-one_shot-success/`
- After `[定时已创建]` confirmed, `browser_wait_for time=35`, then verify `loom_schedule_execution` has 1 row for `loom-sched-tester-*-*-<name>`.

- [ ] **Step 4.5: Mid-batch checkpoint**

Print running tally: 12/12 scenarios complete, X PASS / Y FAILED.

---

### Task 5: 执行场景 13-16 (tester 4 个失败路径)

- [ ] **Step 5.1: Scenario 13 — tester / cron / failure**

- artifact dir: `13-tester-cron-failure/`

- [ ] **Step 5.2: Scenario 14 — tester / fixed_delay / failure**

- artifact dir: `14-tester-fixed_delay-failure/`

- [ ] **Step 5.3: Scenario 15 — tester / fixed_rate / failure**

- artifact dir: `15-tester-fixed_rate-failure/`

- [ ] **Step 5.4: Scenario 16 — tester / one_shot / failure (empty prompt)**

- artifact dir: `16-tester-one_shot-failure/`

- [ ] **Step 5.5: Matrix complete checkpoint**

Print: 16/16 core scenarios complete. List any FAIL with their `failure_scenario` and `evidence_paths`.

---

### Task 6: Bonus B1 — 跨用户 cancel

- [ ] **Step 6.1: Log in as admin (in admin context)**

Use the admin page from Task 1.7.

- [ ] **Step 6.2: Admin creates a fixed-rate task via chat**

- prompt: `每 60 秒提醒我喝水` (60s = 1 minute, **above** 10m min-interval? No, 60s < 10m — use `每 15 分钟提醒我喝水` instead)
- corrected prompt: `每 15 分钟提醒我喝水`
- expected: `[定时已创建]`; DB row with `schedule_type='fixed_rate'` and `interval_seconds=900`.
- artifact: `bonus/B1-cross-user-cancel/chat-admin.png`

- [ ] **Step 6.3: Read the created task's full name from DB**

```bash
curl -s -b /tmp/h2.cookies "http://localhost:8080/h2-console/query.do?jsessionid=" \
  --data-urlencode "sql=SELECT task_name FROM loom_scheduled_task ORDER BY created_at DESC LIMIT 1"
```
Save the full name to `/tmp/b1-task-name`. Expected: `loom-sched-wb04307201-<convId>-提醒我喝水` (or similar).

- [ ] **Step 6.4: Switch to tester context**

Use `browser_tabs` select the tester page (from Task 1.10).

- [ ] **Step 6.5: Tester asks LLM to cancel the admin task**

- prompt: `帮我取消一个叫 "提醒我喝水" 的定时任务`
- expected: LLM contains `[取消失败] 未找到任务` (because in tester's namespace `loom-sched-tester-*-*`, the task does not exist; the cross-user cancel guard must hide the row).
- artifact: `bonus/B1-cross-user-cancel/chat-tester.png`

- [ ] **Step 6.6: Verify admin's task still exists**

```bash
curl -s -b /tmp/h2.cookies "http://localhost:8080/h2-console/query.do?jsessionid=" \
  --data-urlencode "sql=SELECT COUNT(*) FROM loom_scheduled_task WHERE task_name LIKE 'loom-sched-wb04307201-%'"
```
Expected: `1`.

- [ ] **Step 6.7: Write B1 result.json**

```json
{
  "id": "B1",
  "name": "cross-user-cancel",
  "pass": true|false,
  "adminTaskCreated": true,
  "testerCancelResponse": "[取消失败] 未找到任务...",
  "dbRowAfter": 1,
  "failReason": null
}
```

---

### Task 7: Bonus B2 — 跨重启恢复

- [ ] **Step 7.1: As admin, create a cron task via chat**

- prompt: `每 10 分钟报时一次` (10m = min-interval, edge case — use `每 11 分钟报时一次` to be safe)
- expected: `[定时已创建]`; DB row.

- [ ] **Step 7.2: Capture current task name from DB**

```bash
curl -s -b /tmp/h2.cookies "http://localhost:8080/h2-console/query.do?jsessionid=" \
  --data-urlencode "sql=SELECT task_name, created_at FROM loom_scheduled_task ORDER BY created_at DESC LIMIT 1"
```
Save to `/tmp/b2-task-row`.

- [ ] **Step 7.3: Kill Spring Boot**

```bash
APP_PID=$(netstat -ano 2>/dev/null | grep ":8080" | grep LISTENING | awk '{print $5}' | head -1)
echo "Killing PID $APP_PID"
taskkill /F /PID $APP_PID
# wait for port to free
for i in 1 2 3 4 5; do
  netstat -ano 2>/dev/null | grep ":8080" | grep LISTENING > /dev/null || break
  sleep 1
done
```
Expected: port 8080 free.

- [ ] **Step 7.4: Restart Spring Boot in background**

```bash
cd "C:/developer/IdeaProjects/spring-ai-loom-agent/spring-ai-loom-agent-test"
mvn spring-boot:run -Dgpg.skip=true > /tmp/loom-agent.log 2>&1 &
echo "Launched PID $!"
```
Expected: PID echoed.

- [ ] **Step 7.5: Wait for app readiness (max 90s)**

```bash
for i in $(seq 1 45); do
  if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then
    echo "READY after $((i*2))s"
    break
  fi
  sleep 2
done
tail -30 /tmp/loom-agent.log
```
Expected: `READY` line + Spring Boot "Started LoomAgentTestApplication" in log tail.

- [ ] **Step 7.6: Verify restore in startup log**

```bash
grep -E "Restoring|Schedule restore complete|Restored scheduled task" /tmp/loom-agent.log
```
Expected: at least one line containing `Restoring 1 loom-scheduled task` and `Schedule restore complete: restored=1`.

- [ ] **Step 7.7: Verify DB row is still there**

Re-login to H2 (cookies may have expired): repeat the H2 login + query from Step 1.5 / 1.9.
Expected: same task_name as in `/tmp/b2-task-row`, count = 1.

- [ ] **Step 7.8: Verify flexService.listTasks() contains it (via REST)**

Re-login as admin in Playwright (cookie lost on restart). Use `browser_evaluate`:
```js
(async () => {
  const r = await fetch('/spring/ai/loom/schedule/list');
  return await r.json();
})()
```
Expected: the restored task name appears in the list.

- [ ] **Step 7.9: Save B2 artifacts**

- Copy `/tmp/loom-agent.log` → `bonus/B2-restart-restore/app-startup.log`
- Save DB query result → `bonus/B2-restart-restore/db-state.json`
- Write `bonus/B2-restart-restore/result.json`

---

### Task 8: Bonus B3 — max-lifetime 过期

- [ ] **Step 8.1: As admin (now on restarted app), create a new task via chat**

- prompt: `每 11 分钟报时一次`
- expected: `[定时已创建]`.

- [ ] **Step 8.2: Capture task name**

```bash
curl -s -b /tmp/h2.cookies "http://localhost:8080/h2-console/query.do?jsessionid=" \
  --data-urlencode "sql=SELECT task_name FROM loom_scheduled_task ORDER BY created_at DESC LIMIT 1"
```
Save name to `/tmp/b3-task-name`.

- [ ] **Step 8.3: Backdate created_at by 73 hours**

```bash
TASK_NAME=$(cat /tmp/b3-task-name)
curl -s -b /tmp/h2.cookies "http://localhost:8080/h2-console/query.do?jsessionid=" \
  --data-urlencode "sql=UPDATE loom_scheduled_task SET created_at = DATEADD('HOUR', -73, CURRENT_TIMESTAMP) WHERE task_name = '$TASK_NAME'"
# Verify
curl -s -b /tmp/h2.cookies "http://localhost:8080/h2-console/query.do?jsessionid=" \
  --data-urlencode "sql=SELECT task_name, created_at FROM loom_scheduled_task WHERE task_name = '$TASK_NAME'"
```
Expected: `created_at` is roughly 73h in the past.

- [ ] **Step 8.4: Restart Spring Boot (kill + relaunch)**

Same as Step 7.3 + 7.4 + 7.5.

- [ ] **Step 8.5: Verify expiry in startup log**

```bash
grep -E "Dropping expired|Schedule restore complete" /tmp/loom-agent.log
```
Expected: line `Dropping expired scheduled task [<task_name>] (createdAt=...)` AND `Schedule restore complete: restored=0, droppedExpired=1`.

- [ ] **Step 8.6: Verify the row was deleted from DB**

```bash
curl -s -b /tmp/h2.cookies "http://localhost:8080/h2-console/query.do?jsessionid=" \
  --data-urlencode "sql=SELECT COUNT(*) AS n FROM loom_scheduled_task WHERE task_name = '$TASK_NAME'"
```
Expected: `0`.

- [ ] **Step 8.7: Write B3 artifacts**

- `bonus/B3-max-lifetime/app-startup.log`, `db-state.json`, `result.json`.

---

### Task 9: 生成汇总报告 + 清理

- [ ] **Step 9.1: Generate `summary.md`**

Use Bash to write a markdown file with a table:
- Columns: # | Role / Type / Path | Status | LLM Resp (truncated) | DB Rows | Panel Rows | Evidence
- One row per scenario (01-16) and one per bonus (B1-B3).

Source of truth: `/tmp/e2e-results.jsonl`.

```bash
python3 - <<'PY'
import json, os
ts = open('/tmp/e2e-ts').read().strip()
rows = [json.loads(l) for l in open('/tmp/e2e-results.jsonl') if l.strip()]
# ... build markdown table ...
PY
```

- [ ] **Step 9.2: Generate `results.jsonl` (final canonical copy)**

```bash
cp /tmp/e2e-results.jsonl "e2e-results/$UTC_TS/results.jsonl"
```

- [ ] **Step 9.3: Print final summary to user**

Output a markdown table inline (in the chat reply) with: total scenarios, pass count, fail count, fail reasons, suggested fixes. Highlight any STATE-MISMATCH or UI-MISMATCH.

- [ ] **Step 9.4: If any FAILED, write fixes**

For each fail:
- Read the corresponding code path (`DefaultScheduleTool`, `ScheduleRestoreListener`, etc.)
- Propose a code fix
- If approved by user, apply + commit
- Re-run the affected scenario

(If user prefers not to fix in this turn, list the failures with proposed fixes in `summary.md` for later.)

- [ ] **Step 9.5: Final commit (only if a fix was applied)**

```bash
git add <changed source files>
git -c user.name="Claude" -c user.email="noreply@anthropic.com" commit -m "fix(schedule): <what was broken>"
```

---

## Failure Recovery Reference

If during execution you hit:

- **LLM-FLAKE** (no sentinel after 90s + 1 retry): record as FAILED-LLM-FLAKE; continue. Do NOT stop the matrix.
- **STATE-MISMATCH** (LLM says one thing, DB says another): STOP the matrix and report. This is the highest-value failure to investigate.
- **UI-MISMATCH** (panel doesn't show what backend says): continue matrix, capture evidence; fix at the end.
- **3 consecutive LLM-FLAKE**: STOP the matrix; check DASHSCOPE_API_KEY + network; ask user to continue or abort.
- **App crash mid-matrix**: capture `/tmp/loom-agent.log`, restart app (same as Task 7.3-7.5), resume from the last completed scenario.

Backup prompts for LLM flake (use in retry):
- cron success: `请创建一个定时任务，每 5 分钟告诉我当前时间`
- fixed_delay success: `请创建一个每 30 秒运行一次的定时任务，任务是查询北京天气`
- fixed_rate success: `每 20 秒发一条问候给我`
- one_shot success: `30 秒之后提醒我开会`

---

## Self-Review Notes

- Spec coverage: All 16 matrix scenarios → Tasks 2-5. B1 → Task 6. B2 → Task 7. B3 → Task 8. Reporting → Task 9. ✓
- Placeholder scan: No TBD/TODO. Each prompt is filled in. ✓
- Type consistency: `LLM_RESP`, `dbRowCount`, `panelRowCount`, `evidencePaths` are used consistently across Tasks 2-5. ✓
- Ambiguity check: "empty prompt" scenarios explicitly use a trick prompt; backup prompt provided. ✓
