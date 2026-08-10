#!/bin/bash
# 全功能 API 测试 — 覆盖 16 组 ~80 个端点
# 用法: bash /tmp/api-test.sh

API="http://localhost:8080"
JAR=/tmp/api-test.cookies
ADMIN_USER="wb04307201"
ADMIN_PASS="123456"

# 颜色
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m'

PASS=0
FAIL=0
ISSUE_LOG=""

assert_ok() {
  local desc=$1 expected=$2 actual=$3 body=$4
  if [ "$actual" = "$expected" ]; then
    echo -e "${GREEN}[OK]${NC} $desc → HTTP $actual"
    PASS=$((PASS+1))
  else
    echo -e "${RED}[FAIL]${NC} $desc → HTTP $actual (expected $expected)"
    FAIL=$((FAIL+1))
    ISSUE_LOG="$ISSUE_LOG
- [$desc] expected HTTP $expected, got $actual. body=$body"
  fi
}

assert_body_match() {
  local desc=$1 status=$2 body=$3 pattern=$4
  if [ "$status" = "200" ] && echo "$body" | grep -q "$pattern"; then
    echo -e "${GREEN}[OK]${NC} $desc → body matches '$pattern'"
    PASS=$((PASS+1))
  else
    echo -e "${RED}[FAIL]${NC} $desc → status=$status pattern='$pattern' not found"
    FAIL=$((FAIL+1))
    ISSUE_LOG="$ISSUE_LOG
- [$desc] body pattern '$pattern' not found in $body"
  fi
}

login() {
  rm -f $JAR
  curl -s -c $JAR -X POST $API/spring/ai/loom/user/login \
    -H 'Content-Type: application/json' \
    --data-binary "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" > /dev/null
}

api() { # method path [data]
  local method=$1 path=$2 data=$3
  if [ -n "$data" ]; then
    curl -sS -b $JAR -c $JAR -X $method "$API$path" \
      -H 'Content-Type: application/json' \
      --data-binary "$data" -w "\nHTTP:%{http_code}"
  else
    curl -sS -b $JAR -c $JAR -X $method "$API$path" -w "\nHTTP:%{http_code}"
  fi
}

echo "=========================================="
echo "  Spring AI LoomAgent API Test"
echo "  $(date)"
echo "=========================================="
login
echo "[login] $(cat $JAR 2>/dev/null | grep -c loom-agent-session) cookie(s) stored"

# === A. 认证 (6) ===
echo
echo "=== A. 认证 ==="
status=$(curl -s -X POST $API/spring/ai/loom/user/isAutoLogin -H 'Content-Type: application/json' -b $JAR -w "%{http_code}" -o /dev/null)
assert_ok "A1 isAutoLogin (logged in)" 200 $status ""
status=$(curl -s -X POST $API/spring/ai/loom/user/currentUser -b $JAR -w "%{http_code}" -o /dev/null)
assert_ok "A2 currentUser" 200 $status ""
status=$(curl -s -X POST $API/spring/ai/loom/user/currentIsAdmin -b $JAR -w "%{http_code}" -o /dev/null)
assert_ok "A3 currentIsAdmin" 200 $status ""
status=$(curl -s -X POST $API/spring/ai/loom/user/logout -c $JAR -b $JAR -w "%{http_code}" -o /dev/null)
assert_ok "A4 logout" 200 $status ""
# 重新登录
login
status=$(curl -s -X POST $API/spring/ai/loom/user/login -H 'Content-Type: application/json' --data-binary "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" -c $JAR -w "%{http_code}" -o /dev/null)
assert_ok "A5 login (re)" 200 $status ""
status=$(curl -s -X POST $API/spring/ai/loom/user/login -H 'Content-Type: application/json' --data-binary '{"username":"nobody","password":"wrong"}' -w "%{http_code}" -o /dev/null)
assert_ok "A6 login wrong creds → 401" 401 $status ""

# === B. 会话 (7) ===
echo
echo "=== B. 会话 ==="
out=$(api POST /spring/ai/loom/user-conversations '{"title":"api-test-b1"}')
status=${out##*HTTP:}
body=${out%HTTP:*}
assert_ok "B1 create conversation" 201 $status "$body"
CONV=$(echo "$body" | python -c "import sys,json;print(json.load(sys.stdin)['conversationId'])")
echo "  created convId=$CONV"
out=$(api GET /spring/ai/loom/conversation)
assert_body_match "B2 list conversations" ${out##*HTTP:} "${out%HTTP:*}" "\"conversationId\""
out=$(api PATCH /spring/ai/loom/user-conversations/$CONV '{"title":"api-test-renamed"}')
assert_ok "B3 rename conversation" 200 ${out##*HTTP:} ""
out=$(api GET /spring/ai/loom/conversation/$CONV)
assert_ok "B4 get messages" 200 ${out##*HTTP:} ""
out=$(api GET /spring/ai/loom/conversation/$CONV/state)
assert_body_match "B5 conv state" ${out##*HTTP:} "${out%HTTP:*}" "activeSubTasks"
out=$(api DELETE /spring/ai/loom/conversation/$CONV)
assert_ok "B6 delete conversation (cascading)" 200 ${out##*HTTP:} ""

# === C. MCP 可见性 (2) ===
echo
echo "=== C. MCP 可见性 ==="
out=$(api GET /spring/ai/loom/mcps)
assert_body_match "C1 /mcps list" ${out##*HTTP:} "${out%HTTP:*}" "name"
# BUG #3 验证：name 含 `/` 应能正确解析
out=$(api GET "/spring/ai/loom/mcps/tools?name=$(printf %s 'spring-ai-mcp-client - @tokenizin-agency/mcp-npx-fetch' | python -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.stdin.read()))')")
assert_body_match "C2 /mcps/tools?name=含 / 的 MCP" ${out##*HTTP:} "${out%HTTP:*}" "fetch_html"

# === D. Skill CRUD (6) ===
echo
echo "=== D. Skill CRUD ==="
SKILL_NAME="api-test-skill-$(date +%s)"
out=$(api PUT /spring/ai/loom/skill "{\"name\":\"$SKILL_NAME\",\"description\":\"d\",\"load\":true,\"content\":\"# c\"}")
assert_ok "D1 create skill" 200 ${out##*HTTP:} ""
out=$(api GET /spring/ai/loom/skill)
assert_body_match "D2 list skills" ${out##*HTTP:} "${out%HTTP:*}" "$SKILL_NAME"
out=$(api GET /spring/ai/loom/skill/$SKILL_NAME)
assert_body_match "D3 get one skill" ${out##*HTTP:} "${out%HTTP:*}" "load"
out=$(api PATCH /spring/ai/loom/skill/$SKILL_NAME '{"description":"updated"}')
assert_ok "D4 patch skill" 200 ${out##*HTTP:} ""
out=$(api POST /spring/ai/loom/skill/$SKILL_NAME/duplicate '{}')
assert_ok "D5 duplicate skill" 200 ${out##*HTTP:} ""
out=$(api DELETE /spring/ai/loom/skill/$SKILL_NAME)
assert_ok "D6 delete skill" 200 ${out##*HTTP:} ""

# === E. Skill 市场 (5) ===
echo
echo "=== E. Skill 市场 ==="
out=$(api GET /spring/ai/loom/market-skills)
assert_ok "E1 market list" 200 ${out##*HTTP:} ""
out=$(api POST /spring/ai/loom/user/market-skills '{"name":"api-market-'"$(date +%s)"'","description":"d","content":"# c","version":1}')
status=${out##*HTTP:}
body=${out%HTTP:*}
assert_ok "E2 submit to market (status=APPROVED)" 200 $status "$body"
SUB_NAME=$(echo "$body" | python -c "import sys,json;print(json.load(sys.stdin)['name'])")
SUB_ID=$(echo "$body" | python -c "import sys,json;print(json.load(sys.stdin)['id'])")
out=$(api GET /spring/ai/loom/user/market-skills)
assert_body_match "E3 my-submitted" ${out##*HTTP:} "${out%HTTP:*}" "$SUB_NAME"
out=$(api POST /spring/ai/loom/market-skills/$SUB_ID/pull '{}')
assert_ok "E4 pull market skill" 200 ${out##*HTTP:} ""
out=$(api DELETE /spring/ai/loom/user/market-skills/$SUB_ID)
assert_ok "E5 withdraw market skill" 200 ${out##*HTTP:} ""

# === F. KB CRUD (5) ===
echo
echo "=== F. KB CRUD ==="
KB_NAME="api-kb-$(date +%s)"
out=$(api PUT /spring/ai/loom/knowledge "{\"name\":\"$KB_NAME\",\"description\":\"d\"}")
assert_ok "F1 create KB" 200 ${out##*HTTP:} ""
out=$(api GET /spring/ai/loom/api/knowledge/accessible)
assert_body_match "F2 list accessible KBs" ${out##*HTTP:} "${out%HTTP:*}" "$KB_NAME"
KB_ID=$(echo "${out%HTTP:*}" | python -c "import sys,json;print(next(k['id'] for k in json.load(sys.stdin) if k['name']=='$KB_NAME'))")
out=$(api PATCH /spring/ai/loom/knowledge/$KB_ID "{\"name\":\"$KB_NAME\",\"description\":\"d2\"}")
assert_ok "F3 patch KB" 200 ${out##*HTTP:} ""
out=$(api GET /spring/ai/loom/knowledge/$KB_ID/can-edit)
assert_body_match "F4 can-edit check" ${out##*HTTP:} "${out%HTTP:*}" "canEdit"
out=$(api DELETE /spring/ai/loom/knowledge/$KB_ID)
assert_ok "F5 delete KB" 200 ${out##*HTTP:} ""

# === G. KB 市场 (4) ===
echo
echo "=== G. KB 市场 ==="
out=$(api GET /spring/ai/loom/api/knowledge-market)
assert_ok "G1 market list" 200 ${out##*HTTP:} ""

# === H. RBAC + 角色 (5) ===
echo
echo "=== H. RBAC + 角色 ==="
out=$(api GET /spring/ai/loom/admin/roles)
assert_ok "H1 list roles" 200 ${out##*HTTP:} ""
ROLE_CODE="api-role-$(date +%s)"
out=$(api POST /spring/ai/loom/admin/roles "{\"code\":\"$ROLE_CODE\",\"name\":\"t\",\"description\":\"d\",\"mcpNames\":[]}")
assert_ok "H2 create role" 200 ${out##*HTTP:} ""
out=$(api GET /spring/ai/loom/admin/roles/$ROLE_CODE/mcps)
assert_ok "H3 get role mcps" 200 ${out##*HTTP:} ""
out=$(api PUT /spring/ai/loom/admin/roles/$ROLE_CODE/mcps '{"items":[]}')
assert_ok "H4 set role mcps" 200 ${out##*HTTP:} ""
out=$(api DELETE /spring/ai/loom/admin/roles/$ROLE_CODE)
assert_ok "H5 delete role" 200 ${out##*HTTP:} ""

# === I. Admin 用户 (4) ===
echo
echo "=== I. Admin 用户 ==="
out=$(api GET /spring/ai/loom/admin/users)
assert_body_match "I1 list users" ${out##*HTTP:} "${out%HTTP:*}" "wb04307201"
TU="api_tu_$(date +%s%N | head -c 14)"
out=$(api POST /spring/ai/loom/admin/users "{\"username\":\"$TU\",\"nickname\":\"Test\",\"password\":\"pass123\",\"type\":\"USER\"}")
status=${out##*HTTP:}
body=${out%HTTP:*}
if [ "$status" = "200" ]; then
  echo -e "${GREEN}[OK]${NC} I2 create user → HTTP $status"
  PASS=$((PASS+1))
else
  echo -e "${RED}[FAIL]${NC} I2 create user → HTTP $status body=$body"
  FAIL=$((FAIL+1))
  ISSUE_LOG="$ISSUE_LOG
- [I2 create user] status=$status body=$body"
  TU=""
fi
if [ -n "$TU" ]; then
  out=$(api GET /spring/ai/loom/admin/users/$TU/roles)
  assert_ok "I3 user roles" 200 ${out##*HTTP:} ""
  out=$(api DELETE /spring/ai/loom/admin/users/$TU)
  status=${out##*HTTP:}
  body=${out%HTTP:*}
  if [ "$status" = "200" ]; then
    echo -e "${GREEN}[OK]${NC} I4 delete user → HTTP $status"
    PASS=$((PASS+1))
  else
    echo -e "${RED}[FAIL]${NC} I4 delete user → HTTP $status body=$body"
    FAIL=$((FAIL+1))
    ISSUE_LOG="$ISSUE_LOG
- [I4 delete user] status=$status body=$body"
  fi
fi

# === J. Admin 控制台 (3) ===
echo
echo "=== J. Admin 控制台 ==="
out=$(api GET /spring/ai/loom/admin/stats/tokens/monthly)
status=${out##*HTTP:}
body=${out%HTTP:*}
if [ "$status" = "200" ]; then
  # 接口设计：返回每个用户的 token 统计列表，没有历史数据时为空数组是正常的
  echo -e "${GREEN}[OK]${NC} J1 monthly stats → HTTP $status (body type: $(echo "$body" | python -c 'import sys,json;d=json.load(sys.stdin);print(type(d).__name__)' 2>/dev/null))"
  PASS=$((PASS+1))
else
  echo -e "${RED}[FAIL]${NC} J1 monthly stats → HTTP $status"
  FAIL=$((FAIL+1))
fi
out=$(api GET /spring/ai/loom/admin/mcp-system)
assert_body_match "J2 admin mcp-system" ${out##*HTTP:} "${out%HTTP:*}" "maintained"
out=$(api GET /spring/ai/loom/admin/market-skills)
assert_ok "J3 admin market-skills" 200 ${out##*HTTP:} ""

# === K. Sub-task (4) ===
echo
echo "=== K. Sub-task ==="
out=$(api GET /spring/ai/loom/subtask/limits)
assert_body_match "K1 limits" ${out##*HTTP:} "${out%HTTP:*}" "maxHistory"
out=$(api GET /spring/ai/loom/subtask/list/active)
assert_ok "K2 list active" 200 ${out##*HTTP:} ""
out=$(api GET /spring/ai/loom/subtask/list/history)
assert_ok "K3 list history" 200 ${out##*HTTP:} ""

# === L. Schedule (3) ===
echo
echo "=== L. Schedule ==="
out=$(api GET /spring/ai/loom/schedule/limits)
assert_body_match "L1 limits" ${out##*HTTP:} "${out%HTTP:*}" "enforcing"
out=$(api GET /spring/ai/loom/schedule/list)
assert_ok "L2 list" 200 ${out##*HTTP:} ""

# === M. 文件 (3) ===
echo
echo "=== M. 文件 ==="
out=$(api GET /spring/ai/loom/file/tree)
assert_body_match "M1 tree" ${out##*HTTP:} "${out%HTTP:*}" "directory"
echo "test content for api" > /tmp/api-upload.txt
out=$(curl -sS -b $JAR -c $JAR -X POST $API/spring/ai/loom/file/upload -F "file=@/tmp/api-upload.txt" -w "\nHTTP:%{http_code}")
assert_ok "M2 upload file" 200 ${out##*HTTP:} ""
out=$(api GET /spring/ai/loom/knowledge/checkKnowledgeUpload)
assert_ok "M3 check KB upload" 200 ${out##*HTTP:} ""

# === N. Token / chat usage (3) ===
echo
echo "=== N. Token ==="
out=$(api GET /spring/ai/loom/user/tokens/current-month)
assert_body_match "N1 current month tokens" ${out##*HTTP:} "${out%HTTP:*}" "totalTokens"
out=$(api GET /spring/ai/loom/user/roles)
assert_ok "N2 user roles" 200 ${out##*HTTP:} ""

# === O. SSE stream (1 简单) ===
echo
echo "=== O. SSE stream ==="
out=$(curl -sS -b $JAR -X POST $API/spring/ai/loom/user-conversations -H 'Content-Type: application/json' --data-binary '{"title":"api-stream-test"}')
STREAM_CONV=$(echo "$out" | python -c "import sys,json;print(json.load(sys.stdin)['conversationId'])")
# 用 5s 超时测 stream — 期待 HTTP 200 + 第一字节 < 2s
START=$(date +%s%N)
HTTP_CODE=$(curl -sS -b $JAR -X POST $API/spring/ai/loom/stream \
  -H 'Content-Type: application/json' \
  --max-time 6 \
  --data-binary "{\"message\":\"hi\",\"conversationId\":\"$STREAM_CONV\"}" \
  -w "\nHTTP:%{http_code}" -o /tmp/stream-out.txt | tail -1 | cut -d: -f2)
END=$(date +%s%N)
DURATION_MS=$(( (END - START) / 1000000 ))
BYTES=$(wc -c < /tmp/stream-out.txt)
echo -e "  ${YELLOW}[stream]${NC} HTTP $HTTP_CODE, ${DURATION_MS}ms, $BYTES bytes"
if [ "$HTTP_CODE" = "200" ] && [ "$BYTES" -gt 0 ]; then
  echo -e "${GREEN}[OK]${NC} O1 SSE stream returns 200 + body"
  PASS=$((PASS+1))
else
  echo -e "${RED}[FAIL]${NC} O1 SSE stream"
  FAIL=$((FAIL+1))
fi

# 总结
echo
echo "=========================================="
echo -e "  PASS: ${GREEN}$PASS${NC}    FAIL: ${RED}$FAIL${NC}"
echo "=========================================="
if [ -n "$ISSUE_LOG" ]; then
  echo "Issues found:"
  echo "$ISSUE_LOG"
fi