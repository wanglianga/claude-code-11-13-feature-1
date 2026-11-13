# 候选人注销（被遗忘权）工单与删除作业平台

招聘平台把候选人注销当成**要管到底的工单**，而不是在主库执行一条 `DELETE` 就关单。本平台围绕监管问询函场景，
覆盖从主体归并、范围枚举、不可变快照、四类处置、回执对账到证据包出具的完整闭环。

- 后端：Spring Boot 3.5（Java 25）+ Spring Security + JdbcTemplate + Flyway + PostgreSQL 18 + springdoc-openapi
- 前端：Vue 3 + Vite 6 + Element Plus + Pinia + Vue Router（HTTP Basic 登录）
- 部署：Dockerfile + docker-compose（PostgreSQL + 后端 + 前端 Nginx 反代）一键启动

## 一、需求口径与实现对照

| 题目要求 | 实现位置 |
| --- | --- |
| 按手机号/邮箱/证件号哈希把多账号、多版简历归并为候选主体，**姓名相同不构成合并依据** | `SubjectService` 联合查找（连通分量），姓名仅用于展示，不进入主体哈希 |
| 合规专员逐条确认标识符匹配，再确认主体 | `/api/identifier-matches/{id}/decide`、`/api/subjects/{hash}/confirm`，存在待决匹配时禁止确认主体 |
| 注销渠道、授权撤回时点、15 个工作日期限随工单记录 | `work_order` 表；`WorkdayUtil` 跳过周末计算法定到期日 |
| 范围枚举沿血缘**实时全图遍历**（不用静态资产清单，避免新接入表漏掉） | `LineageService.traverse()` 从根资产 BFS，血缘断链不跨越、转人工确认点 |
| 接入登记与血缘注册绑定为一次操作 | `/api/assets/register` 单事务，父资产缺失整体回滚（有集成测试） |
| 枚举结果执行前冻结为**不可变快照**；事后补登记只生成增补工单，不能追溯改写 | `enum_snapshot/snapshot_item` 带清单哈希；`supplement_ticket` 独立处置，原快照条目数与哈希不变（有集成测试） |
| 四类处置分别记账：物理清除 / 重算聚合 / 等待保留期 / 已流出通知 | `disposition_record.category`，漏斗重算记录 before/after/diff，报表不会“自己跳一下” |
| 同主体多渠道重复提交按“主体哈希+渠道”幂等并单 | `work_order.idempotency_key` 唯一约束，重复提交返回 `idempotentHit` |
| 删除作业按“工单+存储位置”幂等重放，备份不被清两遍 | `disposition_record.idempotency_key` 唯一约束 + 已终态位置跳过 |
| 执行前影响面预览、可撤回延迟窗口、候选人撤回、漏登记/回执未到退回当前节点 | `/preview`（设置 `cancel_before`）、`/cancel`、`/return`、`/resume`；退回保留已确认主体与冻结结论 |
| 第三方销毁不在本地事务边界，凭回执推进，回执未到只能挂着 | `notification_outbox` + `WAITING_RECEIPT` 状态；无回执时对账接口拒绝关单 |
| 逐位置对账，对不上的单列交合规复核 | `reconciliation_issue`，存在 OPEN 问题阻止关单（有集成测试） |
| 样本进过模型、参数无法回退；是否重训与效果变化写入模型卡，与物理删除分开展示 | `model_card` 表与独立页签，记录决策、前后效果、理由 |
| 抽样反查哈希、执行时间、证据包索引统一落库 | `recheck_hash`、`executed_at`、`evidence_package`（含 SHA-256 包哈希与落盘 JSON） |
| 删除义务×保留义务冲突的挂起仲裁：以**位置为粒度**登记，文书编号/保全范围/起止时间/责任人逐项写明，备份/法定留存/第三方副本各自到期 | `hold_ledger_entry`（`location_kind` 四类、位置级 `hold_expires_at`）；`/api/holds/register` |
| 挂起不摘掉删除台账，也不一挂了之：撤回挂起生效前已发出的清除作业并逐条留痕，工单置 `HELD` | `hold_job_withdrawal` + 处置状态 `WITHDRAWN`；`JOB_WITHDRAWN` 事件；已物理删除的单列 `HOLD_AFTER_DELETION` |
| 台账优先：状态变更与删除作业并发落库以台账为准，删除指令带工单+位置幂等去重 | `WorkOrderService.execute` 工单行锁 + `HoldService.isLocationBlocked` 逐位置拦截，冲突转 `HELD`（有集成测试） |
| 文书按版本冻结，延期另生成新版本、原件原样；任意历史时点可复现凭哪份文书、按什么范围挂起 | `hold_document(doc_no,version_no,doc_hash)` 不可变；`hold_event` 事件流 + `/api/holds/status-as-of` |
| 同日保全撤销与新增保全按**文书生效时点**排序而非录入先后，两份冲突依据并列交法务裁定 | `hold_conflict` + `adjudication_state`；异日则按生效时点自动适用；`/conflicts/{id}/adjudicate` |
| 到期前法务复核：继续挂起（延期补理由、出新版本）或恢复执行并**重新触发影响面预览** | `hold_review`；`/reviews/{id}/decision`（EXTEND/RESUME/ESCALATE） |
| 季度末汇总长期挂起/多次延期/依据缺失；超法定期限无新依据自动升级，避免无限期搁置 | `/api/holds/review-cycle`、`/quarterly-report`；`ESCALATED` 状态与 `LEGAL_LIMIT_EXCEEDED` 问题 |
| 台账与证据包逐位置对账：有挂起无依据文书、文书到期未恢复、孤儿撤回等单列合规复核清单 | `/api/holds/reconcile` → `hold_compliance_issue`；挂起台账同步写入证据包 `holdLedger` |

## 二、快速启动

### 方式 A：Docker Compose 一键启动（推荐）

```bash
docker compose up -d --build
```

- 前端操作台：http://localhost:8088
- 后端 API：http://localhost:8080
- Swagger UI：http://localhost:8080/swagger-ui/index.html （或经前端 http://localhost:8088/swagger-ui/index.html）
- PostgreSQL：localhost:5432，库/用户/密码 `rtbf` 环境见 compose（默认 `ccdev/ccdev`）
- Flyway 在后端启动时自动建 schema（`compliance`、`biz`）并写入演示数据

停止：`docker compose down`（加 `-v` 同时清除数据卷）。

### 方式 B：本地开发启动（使用容器内预装运行时）

前置：本机 PostgreSQL 已运行，先建库：

```bash
PGPASSWORD=ccdev psql -h 127.0.0.1 -U ccdev -d postgres -c "CREATE DATABASE rtbf;"
```

后端（默认读取 `jdbc:postgresql://127.0.0.1:5432/rtbf`，可用 `DB_URL` 等环境变量覆盖）：

```bash
cd backend
mvn spring-boot:run
# 或打包后运行：mvn package -DskipTests && java -jar target/rtbf-console.jar
```

前端（Vite 已配置 `/api` 代理到 127.0.0.1:8080）：

```bash
cd frontend
npm install --registry=https://registry.npmmirror.com
npm run dev          # http://localhost:5173
```

## 三、测试账号（HTTP Basic / 登录页通用）

| 角色 | 用户名 | 密码 | 权限 |
| --- | --- | --- | --- |
| 合规专员 | `compliance` | `Compliance@2026` | 主体确认、断链纳入/排除、对账复核、模型卡决策、证据包 |
| 运维操作员 | `operator` | `Operator@2026` | 枚举冻结、预览、执行、回执登记、资产登记、增补执行 |
| 只读审计 | `viewer` | `Viewer@2026` | 全量只读 |

## 四、演示数据与验证方式

演示库内置三个主体：

- **张伟 A**（主体哈希 `f08840a3…94c8c`）：两个账号 `ACC-1001/1002`，靠证件号哈希归并；有 PDF、搜索文档、特征向量、
  训练样本、3 条投递、已到期/未到期导出、未到期备份、第三方外发、断链遗留磁带——覆盖全部四类处置。
- **李娜 B**（`d6cd18bb…4ae48`）：单账号，含未到期导出，适合演示撤回注销与保留期。
- **张伟 C**（`8dc68703…29fd7`）：**与 A 同名但标识符哈希完全不同**，系统必须识别为两个独立主体。

### 推荐验证路径（前端）

1. 用 `compliance` 登录 → “主体归并确认” → 点“扫描账号并归并主体”，确认出现 3 个主体且两个张伟未被合并；
   逐条接受标识符匹配后“确认主体”。
2. 用 `operator` 登录 → “注销工单” → 选择张伟 A、渠道“监管问询函”建单；**重复提交一次**观察幂等并单提示。
3. 进工单处置台：冻结枚举快照（12 项，含 1 个断链 `PENDING_HUMAN`）→ 合规确认断链纳入 → 影响面预览
   （四类处置计数 8/1/2/1，可撤回窗口 30 分钟）→ 执行删除作业。
4. “处置记账”页签查看漏斗汇总 `before={JOB-01=2,JOB-02=2,JOB-03=1}`、`after` 与每职位 `-1` 差额；
   备份位置为 `SCHEDULED`（等保留期）。
5. “第三方回执”页签登记回执 → “对账复核”执行对账 → 工单关单 → 生成证据包。
6. “模型卡”页签对 `job-match-embedding` 做重训决策；“审计时间线”查看全过程留痕。
7. 对李娜 B 重复建单并在预览后“撤回注销”，核对其业务数据原样保留。
8. “资产与血缘”页用不存在的父资产编码登记，观察整事务回滚；冻结后接入新资产（如 `AST_INTERVIEW_VIDEO`），
   在工单里生成并执行增补工单，回查原快照仍为 12 条。
9. **挂起仲裁（“挂起仲裁”菜单 + 工单“挂起仲裁”页签）**：
   1. 另建一张张伟 A 工单并走到“影响面预览”（不要执行）；在“挂起仲裁台”登记一份诉讼保全 `HOLD` 文书，
      位置选 `AST_ACCOUNT`（主存储），填文书编号、保全范围、生效/到期时点、责任人。
   2. 回工单可见状态变 `挂起仲裁中(HELD)`、顶部红色横幅列出有效挂起位置，处置记账里 `AST_ACCOUNT` 变 `挂起撤回(WITHDRAWN)`，
      “挂起仲裁”页签有一条撤回留痕；此时点“执行删除作业”被 409 拦截，业务数据一条不少。
   3. 在“挂起仲裁台”用**同一天更晚生效时点**登记一份 `RELEASE` 撤销文书 → 自动进入“同日冲突裁定”，两份依据并列、
      按生效时点排序；点“支持撤销”，工单自动解除 HELD、撤回作业复位并**重新触发影响面预览**。
   4. 再登记一份 `HOLD` 文书，在“法务复核”页签对其待办点“延期(新版本)”：观察文书版本出现 v2、原件哈希不变、
      旧台账 `RELEASED` 并挂接续条目；再对新版本点“恢复执行”。
   5. “季度合规汇总”里点“跑季度复核周期”和“台账逐位置对账”，查看长期挂起/多次延期/依据缺失清单与自动升级；
      在“时点状态复现”输入主体哈希与一个未来时点，验证任意时点都能回答“处于什么状态、凭哪份文书、按什么范围挂起”。

### 命令行快速验证（挂起仲裁）

```bash
B=http://127.0.0.1:8080; AUTH_C="-u compliance:Compliance@2026"; AUTH_O="-u operator:Operator@2026"
# 先按上文建单/冻结/预览取得 ticketId，再登记位置粒度挂起（location_ref 取处置记录里的存储位置）
curl -s $AUTH_C -X POST $B/api/holds/register -H 'Content-Type: application/json' -d '{
  "docNo":"PC-2026-001","docType":"LITIGATION_PRESERVATION","title":"诉讼保全裁定书",
  "scopeText":"保全账号主表","effectiveAt":"2026-11-13T09:00:00+08:00",
  "expiresAt":"2026-12-13T18:00:00+08:00","direction":"HOLD","ticketNo":"<工单ticket_no>",
  "locations":[{"assetCode":"AST_ACCOUNT","locationRef":"<处置记录location_ref>",
                "locationKind":"PRIMARY_STORAGE","ownerPerson":"法务-周岚"}]}'
curl -s -u viewer:Viewer@2026 "$B/api/holds/status-as-of?subjectHash=<主体哈希>"   # 时点状态复现
curl -s -o /dev/null -w "%{http_code}\n" $AUTH_O -X POST $B/api/tickets/<id>/execute  # 期望 409
curl -s $AUTH_C -X POST $B/api/holds/review-cycle                                     # 季度复核+自动升级
curl -s $AUTH_C -X POST $B/api/holds/reconcile                                        # 台账逐位置对账
```

### 命令行快速验证（curl）

```bash
# 归并
curl -s -u compliance:Compliance@2026 -X POST http://127.0.0.1:8080/api/subjects/scan
# 工单列表 / Swagger JSON
curl -s -u viewer:Viewer@2026 http://127.0.0.1:8080/api/tickets
curl -s http://127.0.0.1:8080/v3/api-docs | jq '.info.title, (.paths|keys|length)'
```

### 自动化集成测试

```bash
# 测试使用独立库 rtbf_test（需先建库），测试类启动前自动 DROP SCHEMA 重建
PGPASSWORD=ccdev psql -h 127.0.0.1 -U ccdev -d postgres -c "CREATE DATABASE rtbf_test;"
cd backend && mvn test
```

7 个原生命周期用例覆盖：未认证 401、同名不同人不归并、A 主体全生命周期（幂等并单/断链挂起/四类处置/重算差额/
重放幂等/回执/模型卡/对账关单/证据包）、撤回数据不动、登记事务回滚、增补不改写快照、对账发现篡改并阻止关单。

另一个测试类 `HoldArbitrationIntegrationTest` 的 8 个用例覆盖挂起仲裁：
位置粒度挂起撤回已发作业并拦截删除、主体仍留在台账、同日冲突按生效时点并列裁定并写回台账、
异日撤销按生效时点自动适用、延期冻结新版本文书且原件不动、恢复执行重新触发预览、
季度周期对超期挂起自动升级、文书篡改与孤儿撤回被对账单列、无对应挂起的撤销依据悬空告警、任意时点状态复现、
台账已落但工单未同步的并发窗口下删除仍被拦截且 HELD 由独立事务持久化。

## 五、实际执行过的验证结论（本环境）

以下均为在本容器内实际执行并通过的结果，非推测：

1. `mvn test`：两个测试类共 **Tests run: 15, Failures: 0, Errors: 0, Skipped: 0**，全部通过（原生命周期 7 + 挂起仲裁 8）。
2. 全新空库从零应用迁移：V1~V7 共 7 个迁移全部成功；打包产物 `java -jar rtbf-console.jar` 正常启动，Flyway 迁到 v7。
3. jar 上完整跑通张伟 A 工单：冻结 12 项 → 断链确认 → 预览（四类 8/1/2/1）→ 执行（10 执行/1 排期/1 等回执）
   → 回执登记 → 对账 `passed=true` 关单 → 证据包生成（64 位 SHA-256 哈希）。
4. 幂等性实测：同主体同渠道二次建单返回 `idempotentHit=true` 并回原工单；删除作业二次执行 `executed=0`，无重复清除。
5. 数据隔离实测：处置后张伟 A 主表/简历等残留为 0，其他两个主体账号数不变；漏斗 `JOB-03` 由 1 重算为 0 且差额留痕。
6. 撤回实测：李娜 B 预览后撤回，工单 `CANCELLED`，账号与简历数据原样保留。
7. 增补实测：快照冻结后接入 `AST_INTERVIEW_VIDEO` 并执行增补，删除生效，原快照条目数仍为 12。
8. Swagger UI HTTP 200；前端 `npm run build` 成功。
9. **挂起仲裁冒烟（真实 HTTP 链路）**：预览阶段登记 `AST_ACCOUNT` 位置挂起 → 工单转 `HELD`、该处置变 `WITHDRAWN` 并生成撤回留痕、
   执行删除返回 **409** 且业务数据 0 变动；同日更晚时点登记撤销 → 生成 1 起 `PENDING` 冲突且两份依据按生效时点 09:00/15:00 排序；
   裁定 `RELEASE_WINS` → 工单回 `PENDING_EXECUTION`、撤回清零、重新预览后执行返回 `executed=10`；
   季度周期与台账对账接口 200，证据包内含 `holdLedger` 挂起台账。

> 说明：本容器未安装 docker 命令，`docker compose up` 未在本容器内实际执行；Dockerfile/compose 已按标准多阶段
> 构建编写，后端镜像构建逻辑与本地 `mvn package` 产物一致，前端镜像与 `npm run build` 产物一致。

## 六、目录结构

```
.
├── docker-compose.yml          # 一键启动 postgres + backend + frontend
├── backend/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/rtbf/
│       │   ├── config/         # Security 三角色、OpenAPI
│       │   ├── service/        # SubjectService / LineageService / WorkOrderService / HoldService / AuditService
│       │   ├── web/            # REST 控制器（含 HoldController）与全局异常
│       │   └── util/           # SHA-256 主体哈希、脱敏、工作日计算
│       └── resources/db/migration/  # V1~V7 Flyway 迁移（V7 为挂起仲裁六表 + 状态机扩展）
└── frontend/
    ├── Dockerfile + nginx.conf # Nginx 静态托管并反代 /api、/swagger-ui
    └── src/views/              # 登录、主体归并、工单列表、工单处置台、挂起仲裁台、资产血缘
```

## 七、关键设计边界

- **快照不可变是硬约束**：冻结后只允许更新断链快照项的纳入/排除状态，不允许增删条目；补登记资产走增补工单。
- **第三方回执是异步边界**：本地数据库事务无法覆盖合作方销毁动作，工单状态机在 `WAITING_RECEIPT` 挂起，
  禁止无回执人工推进。
- **模型参数不承诺回退**：训练样本物理删除与模型参数处置分表陈列，模型卡如实记录重训决策与效果变化，供法务出具说明。
- **对账是关单前置条件**：任何 OPEN 对账问题都会阻止工单进入 `COMPLETED`。
- **挂起仲裁是台账优先而非标记优先**：不存在孤零零的挂起标记——每条挂起都能回答“凭哪份文书、哪个版本、什么范围、谁负责、何时到期”；
  删除作业落库前逐位置核对有效挂起并以工单行锁串行化，挂起期间已发出的清除作业必须撤回留痕，解除后重新触发影响面预览而非直接续删；
  文书原件哈希冻结、延期只追加新版本，配合不可变事件流可复现任意历史时点的挂起状态。
