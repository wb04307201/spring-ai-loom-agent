# Knowledge Market Architecture

## Overview

The Spring AI LoomAgent knowledge market enables sharing and role-based distribution of knowledge bases across users. This document covers the file storage abstraction, knowledge market workflow, RBAC integration, and permission model.

## File Storage Architecture

### `IFileStorage` Interface

```
IFileStorage
├── save(knowledgeId, fileName, inputStream, mimeType) → location
├── read(location) → byte[]
├── delete(location)
└── deleteByKnowledgeId(knowledgeId)
```

The `IFileStorage` interface abstracts binary file content storage for knowledge base documents. It supports two built-in implementations, registered with `@ConditionalOnMissingBean` for replacement:

| Implementation | Storage Backend | Location Identifier |
|---|---|---|
| `DatabaseFileStorage` | H2 `loom_file_content` BLOB table | `fileId` |
| `DiskFileStorage` | Local filesystem `~/.loom/knowledge/{username}/{knowledgeId}/` | Absolute file path |

**`DatabaseFileStorage`** stores files as BLOBs in the `loom_file_content` table with `file_id`, `content`, `mime_type`, and `knowledge_id` columns. Suitable for small-scale deployments and single-node setups.

**`DiskFileStorage`** stores files on the local filesystem under the knowledge base path. Suitable for large files and multi-node deployments with shared storage.

## Knowledge Market Flow

### Database Tables

```
knowledge              loom_market_knowledge         loom_user_knowledge
┌────────────────┐     ┌────────────────────────┐    ┌───────────────────────────┐
│ id             │     │ id                     │    │ username                  │
│ username       │     │ username               │    │ market_knowledge_id       │
│ name           │     │ name                   │    │ source (USER_CREATED/     │
│ description    │     │ description            │    │   MARKET_PULLED/           │
└────────────────┘     │ status (PENDING/         │    │   ROLE_GRANTED)          │                       │   APPROVED/REJECTED)     │    │ locked (BOOLEAN)         │
                       │ submitted_at             │    └───────────────────────────┘
                       │ reviewed_at              │
                       │ reviewed_by              │    loom_role_knowledge
                       │ review_comment           │    ┌───────────────────────────┐
                       └────────────────────────┘    │ role_code                 │
                                                     │ market_knowledge_id       │
                                                     │ default_enabled           │
                                                     │ sort_order                │
                                                     └───────────────────────────┘
```

### Workflow

```
User A (Creator)                    Admin                    User B (Subscriber)
     │                                │                              │
     │  1. insert(name, desc)         │                              │
     ├───────────────────────────────►│                              │
     │  knowledge table               │                              │
     │                                │                              │
     │  2. submit(knowledgeId)        │                              │
     ├───────────────────────────────►│                              │
     │  → loom_market_knowledge       │                              │
     │    (status=PENDING)            │                              │
     │                                │                              │
     │                                │  3. approve(marketId)        │
     │                                ├─────────────────────────────►│
     │                                │  → status=APPROVED           │
     │                                │                              │
     │                                │  4. setRoleKnowledges(role,  │
     │                                │     [marketId, enabled])     │
     │                                ├─────────────────────────────►│
     │                                │  → loom_role_knowledge       │
     │                                │                              │
     │                                │  5. syncUserKnowledge(userB) │
     │                                ├─────────────────────────────►│
     │                                │  → loom_user_knowledge       │
     │                                │    (source=ROLE_GRANTED)     │
     │                                │                              │
     │  6. listAccessible(userB) ───────────────────────────────────►│
     │  → own + MARKET_PULLED + ROLE_GRANTED                         │
     │  canEdit → false (not creator)                                │
```

### Service Interfaces

**`IKnowledgeMarketService`** — Market browsing, submission, approval, pull:
- `submit(knowledgeId)` — Submit own knowledge base to market (PENDING)
- `approve/reject(marketKnowledgeId)` — Admin approval/rejection
- `withdraw(marketKnowledgeId)` — User withdraws own submission
- `pull(username, marketKnowledgeId)` — User subscribes from market
- `listApproved(page, size)` — Browse approved market items
- `listPending()` — Admin: list pending approvals
- `listMyPulled(username)` — User's market subscriptions

**`IKnowledgeRoleAdmin`** — Role-based knowledge distribution:
- `setRoleKnowledges(roleCode, items)` — Overwrite role's knowledge bindings
- `getRoleKnowledges(roleCode)` — List role's knowledge bindings
- `syncUserKnowledge(username)` — Sync role knowledge to user's `loom_user_knowledge` (ROLE_GRANTED, locked=TRUE)

### Key Constraints

- **Unique submission**: `(username, name)` unique constraint on `loom_market_knowledge` prevents duplicate submissions
- **Duplicate rejection**: Rejected items can be re-submitted (the service deletes REJECTED rows before re-insert)
- **Locked subscriptions**: Role-granted subscriptions are `locked=TRUE`, preventing override by market pulls
- **Name conflict resolution**: When syncing role knowledge, existing non-locked subscriptions with the same name are upgraded to ROLE_GRANTED

## RBAC Integration

### Tables

| Table | Purpose |
|---|---|
| `role` | Role definitions (code, name, is_system, description) |
| `user_role` | User-to-role mapping (username, role_code) |
| `loom_role_knowledge` | Role-to-market-knowledge mapping with sort order and default_enabled flag |
| `loom_user_knowledge` | User's knowledge subscriptions (source tracks origin: USER_CREATED / MARKET_PULLED / ROLE_GRANTED) |

### Access Resolution (`IKnowledge.listAccessible`)

The `listAccessible(username)` method merges three sources into a unified list:

1. **Own knowledge bases** — from `knowledge` table where `username = ?`
2. **Market-pulled** — from `loom_user_knowledge` (source='MARKET_PULLED') joined with `loom_market_knowledge`
3. **Role-granted** — from `loom_user_knowledge` (source='ROLE_GRANTED') joined with `loom_market_knowledge`

Deduplication is by knowledge ID (`seenIds` Set).

## RAG Filter Logic

### Change: Removed Username Filter

The RAG search filter in `DefaultKnowledgeTool.searchKnowledge` was optimized to filter by `knowledgeId` only, removing the redundant `username` filter.

**Before** — SpEL filter included both `knowledgeId` and `username`:

```java
// 'type' eq 'knowledge' and 'metadata.knowledgeId' eq '{knowledgeId}' and 'metadata.username' eq '{username}'
```

**After** — SpEL filter contains only `knowledgeId` and `type`:

```java
// 'type' eq 'knowledge' and 'metadata.knowledgeId' eq '{knowledgeId}'
```

The filter expression now contains:
- `knowledgeId` — target specific knowledge base
- `type='knowledge'` — vector store type filter

Username is NOT included in the filter because `listAccessible()` already gates which knowledge IDs are visible to the user. This simplifies the SpEL filter expression and reduces vector store query complexity.

## Permission Model

### Edit Permission (`IKnowledge.canEdit`)

Edit permission is **creator-only**: only the original creator (the row owner in the `knowledge` table) can edit a knowledge base. Subscribers (via market pull or role grant) have read-only access.

```java
boolean canEdit(String knowledgeId) {
    // Checks: knowledge WHERE id = ? AND username = currentUser
    // Returns true only if the current user is the original creator
}
```

| Access Type | Can Edit? | Source |
|---|---|---|
| Creator (original owner) | Yes | `knowledge.username = currentUser` |
| Market-pulled subscriber | No | `loom_user_knowledge.source = 'MARKET_PULLED'` |
| Role-granted user | No | `loom_user_knowledge.source = 'ROLE_GRANTED', locked=TRUE` |

### Frontend Behavior

The frontend conditionally shows edit/delete buttons based on the `canEdit` endpoint response. Subscribers see read-only view of knowledge bases with search/retrieve capability but no edit controls.

## Admin Knowledge Market UI

`admin/knowledge-market.html` (with `admin/knowledge-market.js`) — added in **V1.1.37**. Before that the page was a 404 (the JS file was missing, the HTML referenced it). The admin UI provides full CRUD + approval workflow without going through the REST API:

| Action | UI | Backend endpoint |
| --- | --- | --- |
| List all (PENDING / APPROVED / REJECTED) | main table (with `pending-count-label` badge) | `GET /admin/market-skills` |
| New knowledge (bypass approval → `APPROVED`) | "+ 新建知识库" → modal (name / description / content / version / status) | `POST /admin/market-skills` (body: `MarketSkillUpsertRequest`) |
| Edit (any field) | row "编辑" button | `PUT /admin/market-skills/{id}` |
| Delete (cascade → `user_knowledge` + `role_knowledge`) | row "×" button → confirm modal | `DELETE /admin/market-skills/{id}` |
| Approve a PENDING submission | row "通过" button | `POST /admin/market-skills/{id}/approve` |
| Reject a PENDING submission | row "拒绝" button | `POST /admin/market-skills/{id}/reject` (body: `{comment}`) |

The edit modal **requires `content`** (no classpath: lookup at runtime). Toast feedback on every action (`成功` / `失败 + 后端 message`). Description column is line-clamped to 80 chars (admin skill list has the same).

## Configuration

All paths are under `~/.loom/`:

| Property | Default | Purpose |
|---|---|---|
| `spring.ai.loom.agent.fileBasePath` | `~/.loom/file` | User file uploads |
| `spring.ai.loom.agent.knowledgeBasePath` | `~/.loom/knowledge` | Knowledge base document files |
| `spring.ai.loom.agent.datasourceDir` | `~/.loom/datasource` | H2 database files |
| `spring.ai.loom.agent.jvector.indexPath` | `~/.loom/jvector-index` | HNSW vector index |

## Flyway Migrations

| Migration | Content |
|---|---|
| `V1.0__init.sql` | Base schema: `knowledge`, `knowledge_file`, `user_info`, `role`, `user_role` |
| `V2.0__subtask_and_schedule.sql` | Sub-task/history/schedule tables, conversation sidebar columns, `SPRING_AI_CHAT_MEMORY` widen |
| `V3.0__knowledge_market.sql` | Market tables: `loom_market_knowledge`, `loom_user_knowledge`, `loom_role_knowledge`, `loom_file_content` |
