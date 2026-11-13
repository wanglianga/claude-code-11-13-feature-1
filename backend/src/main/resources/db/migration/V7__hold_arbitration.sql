-- =====================================================================
-- V7：删除义务 × 保留义务冲突的挂起仲裁
-- 设计要点：
--   1) 挂起以“主体 + 存储位置”为粒度登记，文书编号/保全范围/起止时间/责任人逐项写明
--   2) 文书按版本冻结：延期只生成新版本，原件原样保留，任何时点可凭 hold_event 复现
--   3) 备份快照、法定留存凭证、第三方副本各有各的到期日（位置级 hold_expires_at）
--   4) 保全撤销与新增保全同日到达：按文书生效时点排序、并列挂起待法务裁定
--   5) 挂起生效前已发出的清除作业必须撤回并留痕（hold_job_withdrawal）
--   6) 台账与证据包逐位置对账，状态与依据不一致单列 hold_compliance_issue
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 挂起依据文书（版本化、内容哈希冻结；延期生成新版本，原件永不改写）
-- ---------------------------------------------------------------------
CREATE TABLE compliance.hold_document (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doc_no              varchar(128) NOT NULL,
    version_no          integer NOT NULL DEFAULT 1,
    doc_type            varchar(24) NOT NULL
                        CHECK (doc_type IN ('LABOR_DISPUTE','LITIGATION_PRESERVATION',
                                            'REGULATORY_INSPECTION','OTHER_LEGAL')),
    title               varchar(256) NOT NULL,
    issuing_authority   varchar(256),
    -- 原件关键内容哈希：登记即冻结，事后任何篡改都可被对账发现
    doc_hash            varchar(64) NOT NULL,
    scope_text          text NOT NULL,
    -- 文书生效时点：同日多份文书一律按此时点排序，而非录入先后
    effective_at        timestamptz NOT NULL,
    expires_at          timestamptz NOT NULL,
    -- HOLD=保全/新增挂起依据；RELEASE=保全撤销依据
    direction           varchar(16) NOT NULL CHECK (direction IN ('HOLD','RELEASE')),
    -- 撤销文书指向被撤销的原始文书
    supersedes_doc_id   bigint REFERENCES compliance.hold_document(id),
    created_by          varchar(64) NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (doc_no, version_no)
);
CREATE INDEX idx_hold_doc_subject_no ON compliance.hold_document (doc_no);

-- ---------------------------------------------------------------------
-- 2. 挂起台账：位置粒度。同一主体的备份/法定留存/第三方副本逐行登记、各自到期
-- ---------------------------------------------------------------------
CREATE TABLE compliance.hold_ledger_entry (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_hash        varchar(64) NOT NULL,
    ticket_id           bigint REFERENCES compliance.work_order(id),
    doc_id              bigint NOT NULL REFERENCES compliance.hold_document(id),
    -- 登记时冻结的文书版本坐标：文书后续出新版本不影响本条目历史含义
    doc_no              varchar(128) NOT NULL,
    doc_version_no      integer NOT NULL,
    asset_code          varchar(64) NOT NULL,
    location_ref        varchar(256) NOT NULL,
    location_kind       varchar(24) NOT NULL
                        CHECK (location_kind IN ('PRIMARY_STORAGE','BACKUP_SNAPSHOT',
                                                 'LEGAL_RETENTION','THIRD_PARTY_COPY')),
    scope_detail        varchar(512),
    owner_person        varchar(128) NOT NULL,
    hold_effective_at   timestamptz NOT NULL,
    hold_expires_at     timestamptz NOT NULL,
    status              varchar(20) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE','RELEASED','EXPIRED','ESCALATED')),
    -- 同日冲突裁定状态：PENDING 时挂起保守生效（阻止删除），裁定后落最终结论
    adjudication_state varchar(20) NOT NULL DEFAULT 'CLEAR'
                        CHECK (adjudication_state IN ('CLEAR','PENDING',
                                                      'HOLD_UPHELD','RELEASE_UPHELD')),
    extension_count     integer NOT NULL DEFAULT 0,
    -- 延期接续：指向新版本生成的新台账条目，旧条目关闭但原样保留
    successor_entry_id  bigint REFERENCES compliance.hold_ledger_entry(id),
    released_at         timestamptz,
    released_by         varchar(64),
    release_reason      varchar(512),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (subject_hash, doc_id, asset_code, location_ref)
);
CREATE INDEX idx_hold_ledger_subject ON compliance.hold_ledger_entry (subject_hash);
CREATE INDEX idx_hold_ledger_ticket ON compliance.hold_ledger_entry (ticket_id);
CREATE INDEX idx_hold_ledger_status ON compliance.hold_ledger_entry (status, hold_expires_at);
CREATE INDEX idx_hold_ledger_location ON compliance.hold_ledger_entry (asset_code, location_ref);

-- ---------------------------------------------------------------------
-- 3. 法务复核：到期前必须复核；结论要么延期（出新文书版本）要么恢复执行
-- ---------------------------------------------------------------------
CREATE TABLE compliance.hold_review (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id            bigint NOT NULL REFERENCES compliance.hold_ledger_entry(id),
    review_due_at       timestamptz NOT NULL,
    reviewed_at         timestamptz,
    reviewer            varchar(64),
    decision            varchar(20) NOT NULL DEFAULT 'PENDING'
                        CHECK (decision IN ('PENDING','EXTEND','RESUME','ESCALATE')),
    reason              varchar(512),
    previous_expires_at timestamptz,
    new_expires_at      timestamptz,
    new_doc_id          bigint REFERENCES compliance.hold_document(id),
    new_entry_id        bigint REFERENCES compliance.hold_ledger_entry(id),
    created_at          timestamptz NOT NULL DEFAULT now()
);
-- 每条台账同时只允许有一个待办法务复核
CREATE UNIQUE INDEX uq_hold_review_one_pending
    ON compliance.hold_review (entry_id) WHERE decision = 'PENDING';
CREATE INDEX idx_hold_review_due ON compliance.hold_review (review_due_at, decision);

-- ---------------------------------------------------------------------
-- 4. 删除作业撤回留痕：挂起生效前已发出的清除作业逐条撤回，不能一边挂起一边删
-- ---------------------------------------------------------------------
CREATE TABLE compliance.hold_job_withdrawal (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id            bigint NOT NULL REFERENCES compliance.hold_ledger_entry(id),
    ticket_id           bigint NOT NULL,
    disposition_id      bigint NOT NULL,
    asset_code          varchar(64) NOT NULL,
    location_ref        varchar(256) NOT NULL,
    previous_status     varchar(24) NOT NULL,
    withdrawn_by        varchar(64) NOT NULL,
    reason              varchar(512) NOT NULL,
    withdrawn_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_hold_withdrawal_ticket ON compliance.hold_job_withdrawal (ticket_id);
CREATE UNIQUE INDEX uq_hold_withdrawal_disp ON compliance.hold_job_withdrawal (disposition_id);

-- ---------------------------------------------------------------------
-- 5. 同日冲突：保全撤销 vs 新增保全并列展示，按生效时点排序交法务裁定
-- ---------------------------------------------------------------------
CREATE TABLE compliance.hold_conflict (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_hash        varchar(64) NOT NULL,
    asset_code          varchar(64) NOT NULL,
    location_ref        varchar(256) NOT NULL,
    hold_doc_id         bigint NOT NULL REFERENCES compliance.hold_document(id),
    release_doc_id      bigint NOT NULL REFERENCES compliance.hold_document(id),
    hold_entry_id       bigint NOT NULL REFERENCES compliance.hold_ledger_entry(id),
    release_entry_id    bigint NOT NULL REFERENCES compliance.hold_ledger_entry(id),
    status              varchar(16) NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','ADJUDICATED')),
    ruling              varchar(16) CHECK (ruling IN ('HOLD_WINS','RELEASE_WINS')),
    ruling_reason       varchar(512),
    ruled_by            varchar(64),
    ruled_at            timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_hold_conflict_status ON compliance.hold_conflict (status);

-- ---------------------------------------------------------------------
-- 6. 合规复核清单：台账与证据包逐位置对账，状态与依据各说各话的单列在此
-- ---------------------------------------------------------------------
CREATE TABLE compliance.hold_compliance_issue (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    issue_type          varchar(40) NOT NULL
                        CHECK (issue_type IN ('HOLD_WITHOUT_DOC','DOC_EXPIRED_NOT_RESUMED',
                                              'REVIEW_OVERDUE','LEGAL_LIMIT_EXCEEDED',
                                              'ORPHAN_WITHDRAWAL','HOLD_AFTER_DELETION',
                                              'RELEASE_WITHOUT_HOLD')),
    subject_hash        varchar(64),
    entry_id            bigint REFERENCES compliance.hold_ledger_entry(id),
    ticket_id           bigint,
    detail              varchar(1024) NOT NULL,
    status              varchar(16) NOT NULL DEFAULT 'OPEN'
                        CHECK (status IN ('OPEN','RESOLVED')),
    resolved_by         varchar(64),
    resolution_note     varchar(512),
    created_at          timestamptz NOT NULL DEFAULT now(),
    resolved_at         timestamptz
);
CREATE INDEX idx_hold_issue_status ON compliance.hold_compliance_issue (status, issue_type);

-- ---------------------------------------------------------------------
-- 7. 不可变挂起事件流：任何历史时点都能复现“当时处于什么状态、凭什么”
-- ---------------------------------------------------------------------
CREATE TABLE compliance.hold_event (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 文书级事件（登记时主体尚未解析）允许为空；位置/工单级事件必须带主体
    subject_hash        varchar(64),
    entry_id            bigint,
    ticket_id           bigint,
    event_type          varchar(48) NOT NULL,
    actor               varchar(64) NOT NULL,
    detail_json         jsonb,
    event_at            timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_hold_event_subject ON compliance.hold_event (subject_hash, event_at);
CREATE INDEX idx_hold_event_ticket ON compliance.hold_event (ticket_id);

-- ---------------------------------------------------------------------
-- 8. 既有状态机扩展：工单可挂起；处置记录可被撤回
-- ---------------------------------------------------------------------
ALTER TABLE compliance.work_order DROP CONSTRAINT work_order_status_check;
ALTER TABLE compliance.work_order ADD CONSTRAINT work_order_status_check
    CHECK (status IN ('CREATED','ENUM_FROZEN','PENDING_EXECUTION','EXECUTING',
                      'WAITING_RECEIPT','RECONCILIATION','COMPLETED',
                      'CANCELLED','RETURNED','HELD'));

ALTER TABLE compliance.disposition_record DROP CONSTRAINT disposition_record_status_check;
ALTER TABLE compliance.disposition_record ADD CONSTRAINT disposition_record_status_check
    CHECK (status IN ('PENDING','PREVIEWED','SCHEDULED','EXECUTED','FAILED',
                      'WAITING_RECEIPT','RECEIPT_RECEIVED','SKIPPED','WITHDRAWN'));
