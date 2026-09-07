# H2 向量存储(#3)设计 spec

> 日期:2026-09-07
> 状态:已获批(分节设计 §1/§2/§3 全部确认)
> 前序:`docs/superpowers/roadmap-2026-09-four-items.md` #3 节(调研结论);#4(审批流)与 #2(文档清理)已落地
> 后继:writing-plans → SDD 实施

---

## 0. 目标与非目标

**目标**
1. 把默认向量存储的持久化后端从本地 json 文件(`~/.loom/jvector-index/docs.json` + `ids.json`)换成 **H2 数据库表** —— 与项目"所有状态入 `~/.loom/datasource`"的存储模型统一。
2. **消灭启动 re-embed**:现 `JVectorStore.loadFromDisk()` 每次启动对所有文档重调 `embeddingModel.embed()`(向量不落盘);新实现把向量序列化进 H2,启动时反序列化直接重建内存 HNSW 图,零 embedding API 调用。
3. 更适合多用户 + 知识库市场:向量与全部业务数据同库同备份域,RAM 占用与今天相同(无回归),ANN 搜索性能保留。

**非目标(YAGNI,已裁决砍掉)**
- 多用户隔离模型变更:不加 username 列、不改搜索过滤路径(**档 A:仅持久化**)。隔离继续靠应用层 `knowledge.listAccessible(username)` 鉴权 + SpEL `knowledgeId` metadata 后过滤。
- 分片懒加载索引(A2 方案)。
- 纯 SQL 暴力扫(A3 方案,JVector 依赖保留)。
- 旧 `docs.json` → H2 一次性迁移导入器(**全新库政策**,已裁决:清库重跑,知识库重传)。
- portable upsert(沿 `ADR-T05.3`:仅 PG/MySQL 采纳时再议,H2 方言 `MERGE INTO` 直接用)。
- 增量 HNSW 图构建(保留现有 rebuild-on-write 全量重建语义;一次文件上传 = 一批 add = 一次重建,与今天一致)。

## 1. 已拍板决策

| # | 决策点 | 裁决 |
|---|--------|------|
| D1 | 隔离档位 | **A:仅持久化,隔离模型不变**(不加 username 列) |
| D2 | 类策略 | **A:新类 `H2JVectorStore` 当 `@ConditionalOnMissingBean` 默认,删除旧 `JVectorStore`** |
| D3 | 迁移 | **A:不做迁移工具,全新库政策**(`rm -rf ~/.loom/datasource` 重跑;`~/.loom/jvector-index/` 目录退役) |
| D4 | 搜索机制 | **A1:H2 持久层 + 内存 JVector HNSW 索引**;搜索路径零改动 |
| D5 | 配置节 | **保留 `spring.ai.loom.agent.jvector.*` 节名**(m/efConstruction/efSearch 本来就是 JVector HNSW 引擎参数),**只删 `indexPath` 一个属性**;不改名 `vectorstore`(§3 修正,撤回 §1 改名提议) |
| D6 | 写删顺序 | **DB 先行**:先 H2 UPSERT/DELETE(事务包裹),成功后改内存;DB 失败异常上抛、内存不留幽灵行;DB 成功内存失败(OOM 类)重启自愈 |
| D7 | hydrate 时机 | **`ApplicationReadyEvent`**(避开 Flyway 建表竞态;沿用 `ScheduleRestoreListener` 先例);bean 构造时只建空图不碰 DB |
| D8 | dim 守卫 | 加载时 `row.dim != embeddingModel.dimensions()` 的行**跳过 + WARN 汇总**;同维不同模型的语义漂移作为"需清库重传"的已知限制写入文档 |

## 2. 现状(权威事实,2026-09-07 逐行核实)

- `JVectorStore`(515 行,`vectorstore/` 包)extends `AbstractObservationVectorStore`;内存三件套:`documentStore`(ConcurrentHashMap id→Document)、`documentIds`(有序 List,**index = HNSW 图节点号**)、`embeddingMap`(id→VectorFloat)+ volatile `graphIndex`;`ReentrantReadWriteLock` 并发控制。
- `persistToDisk()` 只写 text+metadata+score(`docs.json`)+ 有序 id 列表(`ids.json`),**向量不落盘**。
- `loadFromDisk()` L136-143:对每条 doc 调 `embeddingModel.embed(doc)` **重算向量** → 启动昂贵、烧 embedding 配额。
- `rebuildGraph()`:每次 add/delete 全量重建 HNSW 图(O(N),pre-existing,保留)。
- `doSimilaritySearch`:embed query → `GraphSearcher.search` ANN 取 `topK*2` 候选 → 内存 cosine 重打分 → SpEL metadata 过滤 → threshold 过滤 → 排序截断 topK。
- 接线:`LoomAgentConfiguration.RagConfiguration.jVectorStore`(L703-713),`@ConditionalOnMissingBean(VectorStore.class)` + `@Conditional(AnyEmbeddingProviderCondition.class)`;消费 `VectorStore` 接口的是 `IUpload`(`DefaultUpload`)与 `DefaultKnowledgeTool`,`IDocumentRead` 仅被 `@ConditionalOnBean(VectorStore.class)` 门控但不消费 → 换实现零改下游。
- metadata 只有 `type=knowledge` + `knowledgeId`(`DefaultDocumentRead.read`);无 username。
- 上传链:`DefaultUpload.uploadWithKnowledge` → `documentRead.read` → `vectorStore.add(documents)` → `file_document(fileId, documentId)` 映射入库;删除链:`delete(fileId)` → 按 `file_document` 反查 documentIds → `vectorStore.delete(ids)`。
- KB 市场 `pull()` = 订阅指针(`loom_user_knowledge`),**不复制向量** —— 订阅者搜的是原 knowledgeId 的同一批向量。
- schema 先例:`loom_file_content(content BLOB)`;V1.0 单一文件 append-only,全新库政策。
- embedding 模型:DashScope `text-embedding-v4`,**1024 维**(动态取 `embeddingModel.dimensions()`)。
- 测试现状:`DefaultUploadTest`/`DefaultKnowledgeToolTest` mock `VectorStore` 接口(透明);`LoomAgentPropertiesDefaultsTest.jvectorIndexPath_defaultsUnderUserHome_dot_loom` 断言 indexPath 默认值(需删);`SubTaskAndScheduleHistoryIntegrationTest` 有一行 `spring.ai.vectorstore.jvector.auto-pull=false`(非本项目属性命名空间,核实后处置)。

## 3. 架构与组件

全部新组件在 `spring-ai-loom-agent` 模块 `cn.wubo.spring.ai.loom.agent.vectorstore` 包:

| 组件 | 职责 | 依赖 |
|---|---|---|
| `H2JVectorStore`(新,~450 行) | `VectorStore` 实现:内存 JVector HNSW 图 + ConcurrentHashMap 文档缓存(照搬现 JVectorStore 内存结构与搜索路径),持久化后端换 H2 表。`AbstractObservationVectorStore` 子类;新增 `hydrate(rows)` 供 reloader 调用 | `JdbcTemplate`、`EmbeddingModel`、Jackson、`VectorRowCodec` |
| `VectorRowCodec`(新,~80 行) | 单一职责:`float[] ↔ byte[]` BLOB 序列化(little-endian,4 bytes/维)+ `Document ↔ 行字段`(id/content/metadata_json/score)映射。独立类便于单测 | Jackson |
| `H2VectorStoreReloader`(新,~60 行) | `ApplicationReadyEvent` 监听器:全量 `SELECT` → decode → dim 守卫 → `store.hydrate(validRows)` 重建内存图 | `H2JVectorStore`、`JdbcTemplate` |

**退役**:`JVectorStore.java` 删除;`LoomAgentProperties.JVectorProperties.indexPath` 删除(m/efConstruction/efSearch 保留);`~/.loom/jvector-index/` 目录不再创建/读取。

**接线**(`RagConfiguration`):`jVectorStore` bean → `h2VectorStore(EmbeddingModel, JdbcTemplate, LoomAgentProperties)`,仍 `@ConditionalOnMissingBean(VectorStore.class)`;`H2VectorStoreReloader` 注册为 `@Bean`(`@ConditionalOnBean(VectorStore.class)` 且仅当默认实现生效时——用户替换 VectorStore 时 reloader 必须不加载,见 §5 错误处理)。用户加任何 Spring AI vector store starter 依旧整体替换,扩展点语义不变。

## 4. Schema(V1.0 追加)

```sql
-- =============================================================
-- #3 H2 向量存储:H2JVectorStore 持久层(向量 + 文档 + metadata)
-- =============================================================
CREATE TABLE IF NOT EXISTS loom_vector_store (
  document_id   VARCHAR(64) PRIMARY KEY,
  content       CLOB NOT NULL,            -- chunk 文本
  metadata_json CLOB NOT NULL,            -- Spring AI Document.metadata 序列化(type/knowledgeId 等)
  embedding     BLOB NOT NULL,            -- float[dim] little-endian,1024 维 ≈ 4KB/行
  dim           INT NOT NULL,             -- 换 embedding 模型守卫(D8)
  score         DOUBLE,                   -- 可空,镜像现 docs.json 的 score 字段
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_loom_vector_store_created ON loom_vector_store(created_at);
```

- **不加** username / knowledge_id 列(D1 档 A;knowledgeId 在 metadata_json 内,SpEL 过滤照旧走内存后过滤)。
- metadata_json 用 CLOB(§1 修正:不省空间、无截断分支)。
- 容量账:1024 维 × 4B ≈ 4KB 向量 + 文本 ≈ 5-6KB/chunk;1 万 chunk ≈ 60MB H2 + 60MB RAM(RAM 与今天相同)。

## 5. 数据流

### 5.1 启动(hydrate)
1. Bean 构造:`H2JVectorStore` 只建空内存图,**不碰 DB**(消除 Flyway 竞态 + 消除启动 re-embed)。
2. `ApplicationReadyEvent` → `H2VectorStoreReloader`:`SELECT * FROM loom_vector_store` 全量 → 逐行 `VectorRowCodec.decode` → dim 守卫(不匹配行跳过 + WARN 计数,汇总一条"换模型需清库重传"日志)→ `store.hydrate(validRows)`:单个 write-lock 段内重建 `documentIds`(有序)+ `embeddingMap` + HNSW 图 + `documentStore`。
3. hydrate 完成前的搜索:返回空列表 + WARN(与今天冷启动空索引行为一致,不抛错)。

### 5.2 写入(doAdd)
新顺序(与今天的"内存先行"相反,D6):
1. 每 doc `embeddingModel.embed()` → `VectorRowCodec` 序列化(embed 仍在最前 —— 写 DB 需要向量字节)。
2. **DB 先行**:`TransactionTemplate` 包住整批 `MERGE INTO loom_vector_store KEY(document_id) VALUES (...)`;批失败整体回滚 + 异常上抛。
3. DB 成功后才改内存三结构(`documentStore` / `documentIds` / `embeddingMap` put)+ `rebuildGraph()`。
4. 失败语义:DB 失败 → 内存图不动、无幽灵行、异常上抛(`DefaultUpload` 现有 `LoomAgentRuntimeException` 包装不变);DB 成功但内存失败(OOM 类)→ 重启从 DB hydrate 自愈。

### 5.3 删除(doDelete by ids / by filter)
镜像写入:先 `DELETE FROM loom_vector_store WHERE document_id IN (...)`(filter 路径先在内存匹配出 ids,同今天),后内存三结构移除 + rebuildGraph。DB 删 0 行不算错(幂等)。

### 5.4 搜索(doSimilaritySearch)
**零改动**照搬:embed query → HNSW ANN topK×2 → 内存 cosine 重打分 → SpEL metadata 过滤 → threshold → 排序截断。不读 DB。

### 5.5 并发
沿用 `ReentrantReadWriteLock`:hydrate 持 write-lock,与并发 doAdd/doDelete 互斥;搜索持 read-lock。H2 侧单连接池 UPSERT/DELETE,无并发问题(H2 file mode MVStore 默认)。

## 6. 错误处理矩阵

| 场景 | 行为 |
|---|---|
| hydrate 时表不存在(Flyway 未跑/被禁) | ERROR 日志 + 空索引启动,**不 fail-fast**(聊天主功能不因 RAG 挂掉;与 RagConfiguration 条件化哲学一致) |
| dim 不匹配行 | 跳过 + WARN 汇总,其余行正常加载 |
| metadata_json 解析失败(脏行) | 跳过该行 + WARN(单行毒化不拖垮全量加载) |
| UPSERT 失败(DB down) | 整批回滚,异常上抛 → `DefaultUpload` 现有 `LoomAgentRuntimeException` 包装不变 → 用户上传报错,无半状态 |
| embedding API 失败(doAdd 中) | 与今天相同:异常上抛,DB 无写入(还没到 persist 步) |
| 用户替换了 VectorStore(自带 starter) | `H2VectorStoreReloader` 不加载(bean 条件:仅当容器内 VectorStore 是 `H2JVectorStore` 实例时注册/生效——实现用 `@ConditionalOnMissingBean` 链或 reloader 内 instanceof 短路,plan 阶段定) |

**观测**:`createObservationContextBuilder` collectionName `"jvector-disk-index"` → `"loom-vector-store-h2"`,provider 仍 `SIMPLE`。

## 7. 测试计划

| 层 | 测试 | 关键断言 |
|---|---|---|
| 库单元(新) | `VectorRowCodecTest` | float[]→byte[]→float[] 往返逐位相等;little-endian 序;metadata JSON 往返;score 可空 |
| 库单元(新) | `H2JVectorStoreTest`(真 H2 内存库 JdbcTemplate + 确定性 fake EmbeddingModel) | ① add→H2 行存在且 dim/embedding 字节正确;② **hydrate 后 fake model `embed()` 零调用**(no-re-embed 核心收益);③ dim 不匹配行跳过、有效行加载;④ 毒 metadata_json 行跳过不拖垮全量;⑤ hydrate 后搜索结果 == "重启"前;⑥ delete-by-ids / delete-by-filter → H2 行消失;⑦ 同 id 重 add = MERGE 更新不重复;⑧ doAdd DB 失败 → 内存图不变、异常上抛 |
| 库单元(改) | `LoomAgentPropertiesDefaultsTest` | 删 `jvectorIndexPath_*` 断言;m/efConstruction/efSearch 默认断言保留 |
| IT(新) | `H2VectorStoreIT`(test 模块,全 Spring 上下文) | Flyway 建表存在;uploadWithKnowledge → 同一 DataSource 新建第二个 store 实例 + hydrate → 搜索命中且 embed 零调用(模拟重启);`loom_vector_store` 行数 == chunk 数 |
| 透明不动 | `DefaultUploadTest` / `DefaultKnowledgeToolTest`(mock `VectorStore` 接口) | 零改动 —— 验证"面向接口换实现零波及"承诺 |
| 回归门 | 库单元 + test 模块单元(383)+ IT gate(120,清库起跑) | 全绿(#4 收紧后的三段式) |

`SubTaskAndScheduleHistoryIntegrationTest` 的 `spring.ai.vectorstore.jvector.auto-pull=false` 行:实施时核实(非本项目命名空间,疑似历史遗留 no-op),若确认 no-op 则顺手删除。

## 8. 文档触点(实施末任务统一改)

- **CLAUDE.md**:Module 表 "JVector vector store" → "H2-backed JVector vector store";`~/.loom/` 存储表**删 `jvector-index` 行**;Configuration Properties `jvector` 行去 index path;Data Layer schema 清单加 `loom_vector_store`。
- **README×2**:"built-in JVector local store" → "built-in H2-backed vector store(JVector HNSW in-memory index)"。
- **CUSTOMIZATION×2**:目录树 `vectorstore/JVectorStore.java` → `H2JVectorStore.java`;VectorStore 替换点说明更新。
- **概览图(用户要求提醒的点)**:核实结论 —— 技术栈 8 胶囊 {Spring Boot, Spring AI, JDK17, **JVector**, JGit, **H2**, Flyway, ChatMemory} 在 #3 后**全部仍准确**(JVector 依旧是 HNSW 引擎、H2 本来就是胶囊),`generate.py` 布局无向量存储细节 → **#3 不需要重生成概览图,不改 skill**。图重生成只剩 **#1(工具组 9→10)** 一个触发源,届时提醒用户。
- roadmap #3 节:🔵→✅ + 落地记录(实施完成后)。

## 9. 收尾清单

- `~/.loom/jvector-index/` 目录退役(不再创建/读取;老目录由用户手动删或 `rm -rf ~/.loom` 全清,CLAUDE.md 存储表同步)。
- V1.0 append `loom_vector_store` 表;全新库政策(清库重跑,无迁移工具,D3)。
- 已知限制写入文档:① 换 embedding 模型(同维)→ 语义漂移,需清库重传;② rebuild-on-write O(N)(pre-existing,保留);③ hydrate 前搜索返回空(冷启动窗口,毫秒~秒级)。

## 10. 实施顺序建议(供 writing-plans 参考)

T1 `VectorRowCodec` + 单测 → T2 schema(V1.0 append)+ `H2JVectorStore` + 单测 → T3 `H2VectorStoreReloader` + RagConfiguration 接线 + properties 删 indexPath + 单测改 → T4 删 `JVectorStore` + IT(`H2VectorStoreIT`)→ T5 文档同步 + 回归门。每 task 独立 commit;后端改动后注意多模块 stale-JAR 陷阱(`mvn clean install -pl` 三库模块再跑 test 模块 IT)。
