package com.example.rtbf.service;

import com.example.rtbf.util.HashUtil;
import com.example.rtbf.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 删除义务 × 保留义务冲突的挂起仲裁。
 * 关键约束：
 *  - 挂起以“主体 + 存储位置”为粒度登记，文书编号/保全范围/起止时间/责任人逐项写明
 *  - 文书按版本冻结：延期生成新版本与接续台账条目，原件原样保留，历史时点可复现
 *  - 同日到达的保全撤销与新增保全按文书生效时点排序、并列展示交法务裁定
 *  - 挂起生效前已发出的清除作业必须撤回并留痕；已物理删除的单列合规问题
 *  - 台账优先：删除作业落库前逐位置核对有效挂起，冲突时拒绝执行
 *  - 季度汇总长期挂起/多次延期/依据缺失，超法定期限无新依据自动升级
 */
@Service
public class HoldService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    // 恢复执行需重新触发影响面预览；@Lazy 打破与 WorkOrderService 的循环依赖
    private final ObjectProvider<WorkOrderService> workOrderProvider;

    @Value("${app.hold.review-lead-days:3}")
    private int reviewLeadDays;

    @Value("${app.hold.legal-limit-days:180}")
    private int legalLimitDays;

    public HoldService(NamedParameterJdbcTemplate jdbc, AuditService audit, ObjectMapper objectMapper,
                       @Lazy ObjectProvider<WorkOrderService> workOrderProvider) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.workOrderProvider = workOrderProvider;
    }

    public record HoldLocation(String assetCode, String locationRef, String locationKind,
                               String scopeDetail, String ownerPerson, OffsetDateTime expiresAt) {
    }

    public record RegisterHoldRequest(String docNo, String docType, String title, String issuingAuthority,
                                      String scopeText, OffsetDateTime effectiveAt, OffsetDateTime expiresAt,
                                      String direction, Long supersedesDocId, String ticketNo,
                                      List<HoldLocation> locations) {
    }

    public record ReviewDecisionRequest(String decision, String reason, OffsetDateTime newExpiresAt,
                                        String newDocTitle, String newScopeText) {
    }

    // ------------------------------------------------------------------
    // 1. 登记挂起/撤销文书 + 位置粒度台账（一次事务）
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> registerHold(RegisterHoldRequest req, Principal principal) {
        if (req.locations() == null || req.locations().isEmpty()) {
            throw ApiException.badRequest("挂起必须至少登记一个受影响存储位置");
        }
        if (req.expiresAt() == null || req.expiresAt().isBefore(req.effectiveAt())) {
            throw ApiException.badRequest("文书到期日必须晚于生效时点");
        }
        String direction = req.direction() == null ? "HOLD" : req.direction();
        if (!"HOLD".equals(direction) && !"RELEASE".equals(direction)) {
            throw ApiException.badRequest("direction 仅支持 HOLD / RELEASE");
        }
        if (!List.of("LABOR_DISPUTE", "LITIGATION_PRESERVATION", "REGULATORY_INSPECTION", "OTHER_LEGAL")
                 .contains(req.docType())) {
            throw ApiException.badRequest("docType 非法: " + req.docType());
        }
        for (HoldLocation loc : req.locations()) {
            if (!List.of("PRIMARY_STORAGE", "BACKUP_SNAPSHOT", "LEGAL_RETENTION", "THIRD_PARTY_COPY")
                     .contains(loc.locationKind())) {
                throw ApiException.badRequest("locationKind 非法: " + loc.locationKind());
            }
        }
        Integer version = jdbc.queryForObject(
            "SELECT coalesce(max(version_no), 0) + 1 FROM compliance.hold_document WHERE doc_no = :no",
            new MapSqlParameterSource("no", req.docNo()), Integer.class);
        String docHash = HashUtil.sha256(String.join("|",
            req.docNo(), String.valueOf(version), req.docType(), req.title(),
            String.valueOf(req.issuingAuthority()), req.scopeText(),
            canonicalTime(req.effectiveAt()), canonicalTime(req.expiresAt()), direction));
        Long docId = jdbc.queryForObject("""
            INSERT INTO compliance.hold_document
              (doc_no, version_no, doc_type, title, issuing_authority, doc_hash, scope_text,
               effective_at, expires_at, direction, supersedes_doc_id, created_by)
            VALUES (:no, :v, :dt, :title, :auth, :hash, :scope, :eff, :exp, :dir, :sup, :by)
            RETURNING id
            """, new MapSqlParameterSource()
            .addValue("no", req.docNo()).addValue("v", version)
            .addValue("dt", req.docType()).addValue("title", req.title())
            .addValue("auth", req.issuingAuthority()).addValue("hash", docHash)
            .addValue("scope", req.scopeText()).addValue("eff", req.effectiveAt())
            .addValue("exp", req.expiresAt()).addValue("dir", direction)
            .addValue("sup", req.supersedesDocId()).addValue("by", principal.getName()), Long.class);
        emitEvent(null, null, null, "HOLD_DOC_REGISTERED", principal.getName(),
            Map.of("docId", docId, "docNo", req.docNo(), "version", version,
                   "direction", direction, "effectiveAt", req.effectiveAt(),
                   "expiresAt", req.expiresAt(), "docHash", docHash));

        List<Long> entryIds = new ArrayList<>();
        List<Long> conflictIds = new ArrayList<>();
        List<Long> heldTicketIds = new ArrayList<>();
        for (HoldLocation loc : req.locations()) {
            Object[] subjectAndTicket = resolveSubjectAndTicket(req.ticketNo(), loc);
            String subjectHash = (String) subjectAndTicket[0];
            Long ticketId = (Long) subjectAndTicket[1];
            OffsetDateTime entryExpiry = loc.expiresAt() != null ? loc.expiresAt() : req.expiresAt();

            Long entryId = jdbc.query("""
                INSERT INTO compliance.hold_ledger_entry
                  (subject_hash, ticket_id, doc_id, doc_no, doc_version_no, asset_code, location_ref,
                   location_kind, scope_detail, owner_person, hold_effective_at, hold_expires_at, status)
                VALUES (:sh, :tid, :did, :no, :v, :code, :loc, :kind, :detail, :owner, :eff, :exp, 'ACTIVE')
                ON CONFLICT (subject_hash, doc_id, asset_code, location_ref) DO NOTHING
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("sh", subjectHash).addValue("tid", ticketId).addValue("did", docId)
                .addValue("no", req.docNo()).addValue("v", version)
                .addValue("code", loc.assetCode()).addValue("loc", loc.locationRef())
                .addValue("kind", loc.locationKind()).addValue("detail", loc.scopeDetail())
                .addValue("owner", loc.ownerPerson()).addValue("eff", req.effectiveAt())
                .addValue("exp", entryExpiry), rs -> rs.next() ? rs.getLong(1) : null);
            if (entryId == null) {
                // 同文书同位置重复登记：幂等跳过，不产生重复台账
                continue;
            }
            entryIds.add(entryId);
            emitEvent(subjectHash, entryId, ticketId, "HOLD_ENTRY_REGISTERED", principal.getName(),
                Map.of("entryId", entryId, "docNo", req.docNo(), "docVersion", version,
                       "assetCode", loc.assetCode(), "locationRef", loc.locationRef(),
                       "locationKind", loc.locationKind(), "owner", loc.ownerPerson(),
                       "effectiveAt", req.effectiveAt(), "expiresAt", entryExpiry,
                       "scopeDetail", String.valueOf(loc.scopeDetail())));

            // 方向规则：同日反向文书并列待裁定；异日按文书生效时点定先后
            boolean staysActive = applyDirectionRules(entryId, subjectHash, loc, docId, direction,
                                                      req, principal, conflictIds);

            // RELEASE 文书（异日自动适用或无冲突）：若主体已无有效挂起，解除工单 HELD 并重新预览
            if ("RELEASE".equals(direction)) {
                heldTicketIds.addAll(unblockSubjectTickets(subjectHash, principal));
            }

            // HOLD 文书仍然有效：建到期复核待办，撤回已发出的清除作业并把工单置 HELD
            if ("HOLD".equals(direction) && staysActive) {
                OffsetDateTime reviewDue = entryExpiry.minusDays(reviewLeadDays);
                jdbc.update("""
                    INSERT INTO compliance.hold_review (entry_id, review_due_at)
                    VALUES (:eid, :due)
                    ON CONFLICT DO NOTHING
                    """, new MapSqlParameterSource().addValue("eid", entryId).addValue("due", reviewDue));
                emitEvent(subjectHash, entryId, ticketId, "HOLD_REVIEW_DUE_CREATED", principal.getName(),
                    Map.of("entryId", entryId, "reviewDueAt", reviewDue));
                heldTicketIds.addAll(applyHoldToTickets(entryId, subjectHash, loc, req, principal));
            }
        }
        return Map.of("docId", docId, "docNo", req.docNo(), "version", version,
                      "entryCount", entryIds.size(), "entryIds", entryIds,
                      "conflictIds", conflictIds, "heldTicketIds", heldTicketIds.stream().distinct().toList(),
                      "frozenDocHash", docHash);
    }

    /**
     * 方向规则：
     *  - 同位置存在方向相反的 ACTIVE/ESCALATED 台账时，若两份文书生效时点落在同一自然日 →
     *    并列置 PENDING 生成 hold_conflict，按生效时点排序交法务裁定（HOLD 方向仍保守阻止删除）
     *  - 不在同日 → 按文书生效时点排序：晚生效者自动适用，早生效的旧台账关闭并写明原因
     *  - RELEASE 找不到任何对应挂起 → 依据缺失类合规问题
     * 返回新条目是否仍然有效（HOLD 有效时才需要撤回作业/建工单挂起）。
     */
    private boolean applyDirectionRules(long newEntryId, String subjectHash, HoldLocation loc,
                                        long newDocId, String newDirection,
                                        RegisterHoldRequest req, Principal principal,
                                        List<Long> conflictIds) {
        List<Map<String, Object>> opposite = jdbc.queryForList("""
            SELECT e.id AS entry_id, e.doc_id, e.status AS entry_status, d.effective_at, d.direction
              FROM compliance.hold_ledger_entry e
              JOIN compliance.hold_document d ON d.id = e.doc_id
             WHERE e.subject_hash = :sh AND e.asset_code = :code AND e.location_ref = :loc
               AND e.status IN ('ACTIVE','ESCALATED') AND d.direction = :dir AND e.id <> :eid
            """, new MapSqlParameterSource().addValue("sh", subjectHash).addValue("code", loc.assetCode())
                .addValue("loc", loc.locationRef())
                .addValue("dir", "HOLD".equals(newDirection) ? "RELEASE" : "HOLD")
                .addValue("eid", newEntryId));
        if (opposite.isEmpty()) {
            if ("RELEASE".equals(newDirection)) {
                openIssue("RELEASE_WITHOUT_HOLD", subjectHash, newEntryId, null,
                    "撤销文书 " + req.docNo() + " 在位置 " + loc.assetCode() + "/" + loc.locationRef()
                    + " 找不到对应有效挂起，撤销依据悬空，单列合规复核");
                emitEvent(subjectHash, newEntryId, null, "HOLD_COMPLIANCE_ISSUE_OPENED", principal.getName(),
                    Map.of("issueType", "RELEASE_WITHOUT_HOLD"));
                jdbc.update("UPDATE compliance.hold_ledger_entry SET status = 'RELEASED', released_at = now(), "
                            + "released_by = :by, release_reason = :r, updated_at = now() WHERE id = :id",
                            new MapSqlParameterSource().addValue("by", principal.getName())
                                .addValue("r", "无对应挂起的撤销文书，自动关闭并交合规复核")
                                .addValue("id", newEntryId));
                return false;
            }
            return true;
        }
        OffsetDateTime newEffective = req.effectiveAt();
        for (Map<String, Object> other : opposite) {
            long otherEntryId = ((Number) other.get("entry_id")).longValue();
            long otherDocId = ((Number) other.get("doc_id")).longValue();
            OffsetDateTime otherEffective = toOffsetDateTime(other.get("effective_at"));
            boolean sameDay = sameBusinessDay(newEffective, otherEffective);
            if (sameDay) {
                jdbc.update("UPDATE compliance.hold_ledger_entry SET adjudication_state = 'PENDING', "
                            + "updated_at = now() WHERE id IN (:a, :b)",
                            new MapSqlParameterSource().addValue("a", newEntryId).addValue("b", otherEntryId));
                long holdDocId = "HOLD".equals(newDirection) ? newDocId : otherDocId;
                long releaseDocId = "RELEASE".equals(newDirection) ? newDocId : otherDocId;
                long holdEntryId = "HOLD".equals(newDirection) ? newEntryId : otherEntryId;
                long releaseEntryId = "RELEASE".equals(newDirection) ? newEntryId : otherEntryId;
                Long conflictId = jdbc.queryForObject("""
                    INSERT INTO compliance.hold_conflict
                      (subject_hash, asset_code, location_ref, hold_doc_id, release_doc_id,
                       hold_entry_id, release_entry_id, status)
                    VALUES (:sh, :code, :loc, :hd, :rd, :he, :re, 'PENDING')
                    RETURNING id
                    """, new MapSqlParameterSource().addValue("sh", subjectHash).addValue("code", loc.assetCode())
                        .addValue("loc", loc.locationRef()).addValue("hd", holdDocId).addValue("rd", releaseDocId)
                        .addValue("he", holdEntryId).addValue("re", releaseEntryId), Long.class);
                conflictIds.add(conflictId);
                emitEvent(subjectHash, newEntryId, null, "HOLD_CONFLICT_DETECTED", principal.getName(),
                    Map.of("conflictId", conflictId, "holdEntryId", holdEntryId, "releaseEntryId", releaseEntryId,
                           "holdEffectiveAt", "HOLD".equals(newDirection) ? newEffective : otherEffective,
                           "releaseEffectiveAt", "RELEASE".equals(newDirection) ? newEffective : otherEffective,
                           "note", "同日到达，按文书生效时点排序并列，待法务裁定"));
            } else {
                boolean newLater = newEffective.isAfter(otherEffective);
                long loserEntryId = newLater ? otherEntryId : newEntryId;
                String loserDirection = newLater ? (String) other.get("direction") : newDirection;
                jdbc.update("""
                    UPDATE compliance.hold_ledger_entry SET status = 'RELEASED', released_at = now(),
                           released_by = :by, adjudication_state = 'CLEAR',
                           release_reason = :r, updated_at = now() WHERE id = :id
                    """, new MapSqlParameterSource().addValue("by", principal.getName())
                        .addValue("r", "按文书生效时点自动适用：" + (newLater ? "新文书晚生效，旧依据关闭"
                                                                                  : "新文书早生效，后登记依据关闭")
                            + "（败诉方向 " + loserDirection + "）")
                        .addValue("id", loserEntryId));
                // RELEASE 方向文书一旦适用即完成使命，同步关闭，台账中只允许 HOLD 方向保持 ACTIVE
                if (newLater && "RELEASE".equals(newDirection)) {
                    jdbc.update("""
                        UPDATE compliance.hold_ledger_entry SET status = 'RELEASED', released_at = now(),
                               released_by = :by, release_reason = :r, updated_at = now() WHERE id = :id
                        """, new MapSqlParameterSource().addValue("by", principal.getName())
                            .addValue("r", "撤销依据按生效时点适用，挂起解除").addValue("id", newEntryId));
                }
                emitEvent(subjectHash, loserEntryId, null, "HOLD_ENTRY_AUTO_CLOSED_BY_TIMEPOINT",
                    principal.getName(),
                    Map.of("closedEntryId", loserEntryId, "newEntryId", newEntryId,
                           "otherEntryId", otherEntryId, "newEffectiveAt", newEffective,
                           "otherEffectiveAt", otherEffective));
                if (!newLater) {
                    return false;
                }
            }
        }
        // 同日冲突场景下 HOLD 方向条目（无论新旧）保持有效，继续阻止删除直到裁定
        return true;
    }
    private Object[] resolveSubjectAndTicket(String ticketNo, HoldLocation loc) {
        Long ticketId = null;
        String subjectHash = null;
        if (ticketNo != null && !ticketNo.isBlank()) {
            Map<String, Object> t = jdbc.query("""
                SELECT id, subject_hash FROM compliance.work_order WHERE ticket_no = :no
                """, new MapSqlParameterSource("no", ticketNo),
                rs -> rs.next() ? Map.of("id", rs.getLong(1), "sh", rs.getString(2)) : null);
            if (t == null) {
                throw ApiException.notFound("工单不存在: " + ticketNo);
            }
            ticketId = ((Number) t.get("id")).longValue();
            subjectHash = (String) t.get("sh");
        } else {
            Map<String, Object> d = jdbc.query("""
                SELECT ticket_id, (SELECT subject_hash FROM compliance.work_order w WHERE w.id = d.ticket_id)
                FROM compliance.disposition_record d
                WHERE asset_code = :code AND location_ref = :loc
                ORDER BY id DESC LIMIT 1
                """, new MapSqlParameterSource().addValue("code", loc.assetCode())
                    .addValue("loc", loc.locationRef()),
                rs -> rs.next() ? Map.of("tid", rs.getLong(1), "sh", rs.getString(2)) : null);
            if (d == null) {
                throw ApiException.badRequest(
                    "位置 " + loc.assetCode() + "/" + loc.locationRef + " 无在途处置记录，请通过 ticketNo 指定主体");
            }
            ticketId = ((Number) d.get("tid")).longValue();
            subjectHash = (String) d.get("sh");
        }
        return new Object[]{subjectHash, ticketId};
    }

    /**
     * 挂起落到在途工单：撤回已发出的清除作业（逐条留痕），工单置 HELD。
     * 已物理删除/已收回执的作业不可撤回，单列合规复核问题。
     */
    private List<Long> applyHoldToTickets(long entryId, String subjectHash, HoldLocation loc,
                                          RegisterHoldRequest req, Principal principal) {
        List<Long> heldTickets = new ArrayList<>();
        List<Map<String, Object>> tickets = jdbc.queryForList("""
            SELECT id FROM compliance.work_order
             WHERE subject_hash = :sh AND status NOT IN ('COMPLETED','CANCELLED')
            """, new MapSqlParameterSource().addValue("sh", subjectHash));
        for (Map<String, Object> t : tickets) {
            long ticketId = ((Number) t.get("id")).longValue();
            // 与删除作业争抢同一把工单行锁：台账状态变更与删除落库并发时，后到者必须看到先到者
            jdbc.queryForObject("SELECT id FROM compliance.work_order WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource().addValue("id", ticketId), Long.class);
            List<Map<String, Object>> dispositions = jdbc.queryForList("""
                SELECT id, status FROM compliance.disposition_record
                 WHERE ticket_id = :tid AND asset_code = :code AND location_ref = :loc
                """, new MapSqlParameterSource().addValue("tid", ticketId)
                    .addValue("code", loc.assetCode()).addValue("loc", loc.locationRef()));
            for (Map<String, Object> d : dispositions) {
                long dispId = ((Number) d.get("id")).longValue();
                String status = (String) d.get("status");
                if ("EXECUTED".equals(status) || "RECEIPT_RECEIVED".equals(status)) {
                    openIssue("HOLD_AFTER_DELETION", subjectHash, entryId, ticketId,
                        "位置 " + loc.assetCode() + "/" + loc.locationRef() + " 的清除作业已终态(" + status
                        + ")，挂起文书 " + req.docNo() + " 到达过晚，需法务评估数据恢复与告知义务");
                    emitEvent(subjectHash, entryId, ticketId, "HOLD_COMPLIANCE_ISSUE_OPENED", principal.getName(),
                        Map.of("issueType", "HOLD_AFTER_DELETION", "dispositionId", dispId));
                    continue;
                }
                if ("WITHDRAWN".equals(status) || "SKIPPED".equals(status)
                    || "WAITING_RECEIPT".equals(status) || "RECEIPT_RECEIVED".equals(status)) {
                    // WAITING_RECEIPT/RECEIPT_RECEIVED 属第三方事务边界，不删本地数据，挂起期间照常收回执
                    continue;
                }
                jdbc.update("""
                    INSERT INTO compliance.hold_job_withdrawal
                      (entry_id, ticket_id, disposition_id, asset_code, location_ref,
                       previous_status, withdrawn_by, reason)
                    VALUES (:eid, :tid, :did, :code, :loc, :ps, :by, :reason)
                    ON CONFLICT (disposition_id) DO NOTHING
                    """, new MapSqlParameterSource().addValue("eid", entryId).addValue("tid", ticketId)
                        .addValue("did", dispId).addValue("code", loc.assetCode())
                        .addValue("loc", loc.locationRef()).addValue("ps", status)
                        .addValue("by", principal.getName())
                        .addValue("reason", "挂起文书 " + req.docNo() + " 生效，撤回挂起生效前已发出的清除作业"));
                jdbc.update("UPDATE compliance.disposition_record SET status = 'WITHDRAWN', "
                            + "updated_at = now() WHERE id = :id",
                            new MapSqlParameterSource().addValue("id", dispId));
                emitEvent(subjectHash, entryId, ticketId, "JOB_WITHDRAWN", principal.getName(),
                    Map.of("dispositionId", dispId, "previousStatus", status,
                           "assetCode", loc.assetCode(), "locationRef", loc.locationRef()));
            }
            int n = jdbc.update("""
                UPDATE compliance.work_order SET status = 'HELD', updated_at = now()
                 WHERE id = :id AND status <> 'HELD'
                """, new MapSqlParameterSource().addValue("id", ticketId));
            if (n > 0) {
                heldTickets.add(ticketId);
                emitEvent(subjectHash, entryId, ticketId, "TICKET_HELD", principal.getName(),
                    Map.of("ticketId", ticketId, "docNo", req.docNo()));
                audit.log(ticketId, principal.getName(), "TICKET_HELD",
                    Map.of("docNo", req.docNo(), "assetCode", loc.assetCode(),
                           "locationRef", loc.locationRef()));
            }
        }
        return heldTickets;
    }

    // ------------------------------------------------------------------
    // 2. 台账优先：删除作业逐位置核对有效挂起
    // ------------------------------------------------------------------
    public boolean isLocationBlocked(String subjectHash, String assetCode, String locationRef) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(
              SELECT 1 FROM compliance.hold_ledger_entry e
              JOIN compliance.hold_document d ON d.id = e.doc_id
             WHERE e.subject_hash = :sh AND e.asset_code = :code AND e.location_ref = :loc
               AND e.status IN ('ACTIVE','ESCALATED') AND d.direction = 'HOLD'
               AND e.hold_effective_at <= now() AND e.hold_expires_at > now())
            """, new MapSqlParameterSource().addValue("sh", subjectHash).addValue("code", assetCode)
                .addValue("loc", locationRef), Boolean.class));
    }

    /** 资产级判断：该主体在该资产的任一存储位置存在有效挂起（用于增补工单等无固定位置的作业）。 */
    public boolean isAssetBlocked(String subjectHash, String assetCode) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(
              SELECT 1 FROM compliance.hold_ledger_entry e
              JOIN compliance.hold_document d ON d.id = e.doc_id
             WHERE e.subject_hash = :sh AND e.asset_code = :code
               AND e.status IN ('ACTIVE','ESCALATED') AND d.direction = 'HOLD'
               AND e.hold_effective_at <= now() AND e.hold_expires_at > now())
            """, new MapSqlParameterSource().addValue("sh", subjectHash).addValue("code", assetCode),
            Boolean.class));
    }

    /** 工单行锁：台账状态变更与删除作业并发落库时，以台账为准。 */
    public void lockTicketForExecution(long ticketId) {
        jdbc.queryForObject("SELECT id FROM compliance.work_order WHERE id = :id FOR UPDATE",
            new MapSqlParameterSource().addValue("id", ticketId), Long.class);
        String status = jdbc.queryForObject("SELECT status FROM compliance.work_order WHERE id = :id",
            new MapSqlParameterSource().addValue("id", ticketId), String.class);
        if ("HELD".equals(status)) {
            throw ApiException.conflict("工单处于挂起仲裁(HELD)状态，台账优先：不得执行删除作业");
        }
    }

    /**
     * 删除作业执行前的挂起预扫描（独立事务）：行锁 + 逐位置核对。
     * 撞挂起时在本事务内把工单固化为 HELD 并提交——外层删除事务随后抛 409 回滚也不影响该状态。
     * 返回首个拦截位置；无挂起返回 null。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> checkExecutionBlocked(long ticketId) {
        Map<String, Object> row = jdbc.query("SELECT subject_hash FROM compliance.work_order WHERE id = :id FOR UPDATE",
            new MapSqlParameterSource().addValue("id", ticketId), rs -> rs.next() ? rsRow(rs) : null);
        if (row == null) {
            throw ApiException.notFound("工单不存在: " + ticketId);
        }
        String subjectHash = (String) row.get("subject_hash");
        List<Map<String, Object>> dispositions = jdbc.queryForList("""
            SELECT id, asset_code, location_ref, status FROM compliance.disposition_record
             WHERE ticket_id = :id
               AND status NOT IN ('EXECUTED','RECEIPT_RECEIVED','SCHEDULED','SKIPPED',
                                  'WAITING_RECEIPT','WITHDRAWN')
            """, new MapSqlParameterSource().addValue("id", ticketId));
        for (Map<String, Object> d : dispositions) {
            String assetCode = (String) d.get("asset_code");
            String locationRef = (String) d.get("location_ref");
            if (isLocationBlocked(subjectHash, assetCode, locationRef)) {
                long dispId = ((Number) d.get("id")).longValue();
                Map<String, Object> detail = Map.of("dispositionId", dispId, "assetCode", assetCode,
                                                    "locationRef", locationRef);
                int n = jdbc.update("""
                    UPDATE compliance.work_order SET status = 'HELD', updated_at = now()
                     WHERE id = :id AND status <> 'HELD'
                    """, new MapSqlParameterSource().addValue("id", ticketId));
                if (n > 0) {
                    emitEvent(subjectHash, null, ticketId, "TICKET_HELD_BY_LEDGER_BLOCK", "system",
                        Map.of("ticketId", ticketId, "blockedLocation", detail));
                    audit.log(ticketId, "system", "TICKET_HELD_BY_LEDGER_BLOCK", detail);
                }
                return detail;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 3. 法务复核：延期（新版本冻结）或恢复执行（重新触发影响面预览）
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> decideReview(long reviewId, ReviewDecisionRequest req, Principal principal) {
        Map<String, Object> review = jdbc.query("SELECT * FROM compliance.hold_review WHERE id = :id",
            new MapSqlParameterSource().addValue("id", reviewId), rs -> rs.next() ? rsRow(rs) : null);
        if (review == null) {
            throw ApiException.notFound("复核任务不存在: " + reviewId);
        }
        if (!"PENDING".equals(review.get("decision"))) {
            throw ApiException.conflict("该复核已作出结论: " + review.get("decision"));
        }
        long entryId = ((Number) review.get("entry_id")).longValue();
        Map<String, Object> entry = requireEntry(entryId);
        String subjectHash = (String) entry.get("subject_hash");

        Map<String, Object> result = new LinkedHashMap<>();
        switch (req.decision()) {
            case "EXTEND" -> {
                if (req.newExpiresAt() == null || !req.newExpiresAt().isAfter(OffsetDateTime.now())) {
                    throw ApiException.badRequest("延期必须给出晚于当前时间的新到期日");
                }
                Long newEntryId = extendWithNewVersion(entry, req, principal);
                jdbc.update("""
                    UPDATE compliance.hold_review SET decision = 'EXTEND', reviewed_at = now(),
                           reviewer = :by, reason = :r, previous_expires_at = :pe,
                           new_expires_at = :ne, new_entry_id = :neid
                     WHERE id = :id
                    """, new MapSqlParameterSource().addValue("by", principal.getName())
                        .addValue("r", req.reason()).addValue("pe", entry.get("hold_expires_at"))
                        .addValue("ne", req.newExpiresAt()).addValue("neid", newEntryId)
                        .addValue("id", reviewId));
                emitEvent(subjectHash, entryId, (Long) entry.get("ticket_id"),
                    "HOLD_REVIEW_DECIDED", principal.getName(),
                    Map.of("reviewId", reviewId, "decision", "EXTEND", "successorEntryId", newEntryId));
                result.put("successorEntryId", newEntryId);
            }
            case "RESUME" -> {
                resumeEntry(entryId, "法务复核结论：恢复执行。" + req.reason(), principal);
                jdbc.update("""
                    UPDATE compliance.hold_review SET decision = 'RESUME', reviewed_at = now(),
                           reviewer = :by, reason = :r WHERE id = :id
                    """, new MapSqlParameterSource().addValue("by", principal.getName())
                        .addValue("r", req.reason()).addValue("id", reviewId));
                emitEvent(subjectHash, entryId, (Long) entry.get("ticket_id"),
                    "HOLD_REVIEW_DECIDED", principal.getName(),
                    Map.of("reviewId", reviewId, "decision", "RESUME"));
                result.put("resumed", true);
            }
            case "ESCALATE" -> {
                jdbc.update("UPDATE compliance.hold_ledger_entry SET status = 'ESCALATED', updated_at = now() "
                            + "WHERE id = :id",
                            new MapSqlParameterSource().addValue("id", entryId));
                jdbc.update("""
                    UPDATE compliance.hold_review SET decision = 'ESCALATE', reviewed_at = now(),
                           reviewer = :by, reason = :r WHERE id = :id
                    """, new MapSqlParameterSource().addValue("by", principal.getName())
                        .addValue("r", req.reason()).addValue("id", reviewId));
                openIssue("LEGAL_LIMIT_EXCEEDED", subjectHash, entryId, (Long) entry.get("ticket_id"),
                    "法务复核升级：" + req.reason());
                emitEvent(subjectHash, entryId, (Long) entry.get("ticket_id"),
                    "HOLD_ESCALATED", principal.getName(), Map.of("reviewId", reviewId));
                result.put("escalated", true);
            }
            default -> throw ApiException.badRequest("decision 仅支持 EXTEND / RESUME / ESCALATE");
        }
        result.put("reviewId", reviewId);
        result.put("decision", req.decision());
        return result;
    }

    /** 延期：原件版本不动，生成同 doc_no 的新版本与接续台账条目。 */
    private long extendWithNewVersion(Map<String, Object> oldEntry, ReviewDecisionRequest req,
                                      Principal principal) {
        long oldDocId = ((Number) oldEntry.get("doc_id")).longValue();
        Map<String, Object> oldDoc = jdbc.query("SELECT * FROM compliance.hold_document WHERE id = :id",
            new MapSqlParameterSource().addValue("id", oldDocId), rs -> rs.next() ? rsRow(rs) : null);
        if (oldDoc == null) {
            throw ApiException.notFound("原始文书不存在，无法延期");
        }
        Integer newVersion = ((Number) oldDoc.get("version_no")).intValue() + 1;
        OffsetDateTime effectiveAt = OffsetDateTime.now();
        String title = req.newDocTitle() != null ? req.newDocTitle() : (String) oldDoc.get("title");
        String scope = req.newScopeText() != null ? req.newScopeText() : (String) oldDoc.get("scope_text");
        String docNo = (String) oldDoc.get("doc_no");
        String docHash = HashUtil.sha256(String.join("|",
            docNo, String.valueOf(newVersion), String.valueOf(oldDoc.get("doc_type")), title,
            String.valueOf(oldDoc.get("issuing_authority")), scope,
            canonicalTime(effectiveAt), canonicalTime(req.newExpiresAt()),
            String.valueOf(oldDoc.get("direction"))));
        Long newDocId = jdbc.queryForObject("""
            INSERT INTO compliance.hold_document
              (doc_no, version_no, doc_type, title, issuing_authority, doc_hash, scope_text,
               effective_at, expires_at, direction, supersedes_doc_id, created_by)
            VALUES (:no, :v, :dt, :title, :auth, :hash, :scope, :eff, :exp, :dir, :sup, :by)
            RETURNING id
            """, new MapSqlParameterSource()
            .addValue("no", docNo).addValue("v", newVersion).addValue("dt", oldDoc.get("doc_type"))
            .addValue("title", title).addValue("auth", oldDoc.get("issuing_authority"))
            .addValue("hash", docHash).addValue("scope", scope).addValue("eff", effectiveAt)
            .addValue("exp", req.newExpiresAt()).addValue("dir", oldDoc.get("direction"))
            .addValue("sup", oldDocId).addValue("by", principal.getName()), Long.class);

        long oldEntryId = ((Number) oldEntry.get("id")).longValue();
        Long newEntryId = jdbc.queryForObject("""
            INSERT INTO compliance.hold_ledger_entry
              (subject_hash, ticket_id, doc_id, doc_no, doc_version_no, asset_code, location_ref,
               location_kind, scope_detail, owner_person, hold_effective_at, hold_expires_at,
               status, extension_count)
            VALUES (:sh, :tid, :did, :no, :v, :code, :loc, :kind, :detail, :owner, :eff, :exp,
                    'ACTIVE', :ext)
            RETURNING id
            """, new MapSqlParameterSource()
            .addValue("sh", oldEntry.get("subject_hash")).addValue("tid", oldEntry.get("ticket_id"))
            .addValue("did", newDocId).addValue("no", docNo).addValue("v", newVersion)
            .addValue("code", oldEntry.get("asset_code")).addValue("loc", oldEntry.get("location_ref"))
            .addValue("kind", oldEntry.get("location_kind")).addValue("detail", oldEntry.get("scope_detail"))
            .addValue("owner", oldEntry.get("owner_person")).addValue("eff", effectiveAt)
            .addValue("exp", req.newExpiresAt())
            .addValue("ext", ((Number) oldEntry.get("extension_count")).intValue() + 1), Long.class);
        // 旧条目关闭但原样保留，挂接续指针
        jdbc.update("""
            UPDATE compliance.hold_ledger_entry SET status = 'RELEASED', successor_entry_id = :sid,
                   released_at = now(), released_by = :by,
                   release_reason = '延期接续：新版本文书 v' || :v, updated_at = now()
             WHERE id = :id
            """, new MapSqlParameterSource().addValue("sid", newEntryId).addValue("by", principal.getName())
                .addValue("v", newVersion).addValue("id", oldEntryId));
        jdbc.update("""
            INSERT INTO compliance.hold_review (entry_id, review_due_at)
            VALUES (:eid, :due)
            """, new MapSqlParameterSource().addValue("eid", newEntryId)
                .addValue("due", req.newExpiresAt().minusDays(reviewLeadDays)));
        emitEvent((String) oldEntry.get("subject_hash"), newEntryId, (Long) oldEntry.get("ticket_id"),
            "DOC_VERSION_EXTENDED", principal.getName(),
            Map.of("oldEntryId", oldEntryId, "newEntryId", newEntryId, "docNo", docNo,
                   "newVersion", newVersion, "newDocHash", docHash,
                   "note", "原件保持原样，延期另生成新版本"));
        return newEntryId;
    }

    /** 恢复执行：台账释放、撤回的作业回到待预览、工单退出 HELD 并重新触发影响面预览。 */
    @Transactional
    public List<Long> resumeEntry(long entryId, String reason, Principal principal) {
        Map<String, Object> entry = requireEntry(entryId);
        String subjectHash = (String) entry.get("subject_hash");
        jdbc.update("""
            UPDATE compliance.hold_ledger_entry SET status = 'RELEASED', released_at = now(),
                   released_by = :by, release_reason = :r, updated_at = now()
             WHERE id = :id AND status IN ('ACTIVE','ESCALATED')
            """, new MapSqlParameterSource().addValue("by", principal.getName())
                .addValue("r", reason).addValue("id", entryId));
        emitEvent(subjectHash, entryId, (Long) entry.get("ticket_id"), "HOLD_ENTRY_RESUMED",
            principal.getName(), Map.of("entryId", entryId, "reason", reason));
        return unblockSubjectTickets(subjectHash, principal);
    }

    /**
     * 主体下已无任何有效挂起时：撤回作业复位并重新触发影响面预览，工单退出 HELD。
     * 返回重新触发预览的工单 ID 列表。
     */
    private List<Long> unblockSubjectTickets(String subjectHash, Principal principal) {
        if (hasActiveHold(subjectHash)) {
            return List.of();
        }
        Principal systemActor = principal != null ? principal : () -> "legal-system";
        List<Long> ticketIds = new ArrayList<>();
        List<Map<String, Object>> heldTickets = jdbc.queryForList("""
            SELECT id FROM compliance.work_order WHERE subject_hash = :sh AND status = 'HELD'
            """, new MapSqlParameterSource().addValue("sh", subjectHash));
        for (Map<String, Object> t : heldTickets) {
            long ticketId = ((Number) t.get("id")).longValue();
            // 挂起期间被撤回的清除作业复位：带工单与位置标识的幂等键保证不会重复执行
            jdbc.update("""
                UPDATE compliance.disposition_record SET status = 'PREVIEWED', updated_at = now()
                 WHERE ticket_id = :tid AND status = 'WITHDRAWN'
                """, new MapSqlParameterSource().addValue("tid", ticketId));
            // 已部分执行的工单按挂起前所处阶段恢复，不重跑预览；尚未执行的工单才重新触发影响面预览
            boolean waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM compliance.disposition_record
                               WHERE ticket_id = :id AND status = 'WAITING_RECEIPT')
                """, new MapSqlParameterSource().addValue("id", ticketId), Boolean.class));
            boolean scheduled = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM compliance.disposition_record
                               WHERE ticket_id = :id AND status = 'SCHEDULED')
                """, new MapSqlParameterSource().addValue("id", ticketId), Boolean.class));
            Object rePreview = null;
            if (waiting) {
                jdbc.update("""
                    UPDATE compliance.work_order SET status = 'WAITING_RECEIPT', current_node = 'RECEIPT',
                           updated_at = now() WHERE id = :id
                    """, new MapSqlParameterSource().addValue("id", ticketId));
            } else if (scheduled) {
                jdbc.update("""
                    UPDATE compliance.work_order SET status = 'EXECUTING', current_node = 'EXECUTION',
                           updated_at = now() WHERE id = :id
                    """, new MapSqlParameterSource().addValue("id", ticketId));
            } else {
                jdbc.update("""
                    UPDATE compliance.work_order SET status = 'ENUM_FROZEN', current_node = 'PREVIEW',
                           updated_at = now() WHERE id = :id
                    """, new MapSqlParameterSource().addValue("id", ticketId));
                // 恢复执行必须重新触发影响面预览，而不是直接续跑删除作业
                rePreview = workOrderProvider.getObject().preview(ticketId, systemActor);
            }
            ticketIds.add(ticketId);
            emitEvent(subjectHash, null, ticketId, "TICKET_RESUMED", systemActor.getName(),
                Map.of("ticketId", ticketId, "rePreview", String.valueOf(rePreview),
                       "restoredNode", waiting ? "RECEIPT" : scheduled ? "EXECUTION" : "PREVIEW",
                       "note", "挂起解除；未执行工单已重新触发影响面预览"));
            audit.log(ticketId, systemActor.getName(), "TICKET_RESUMED_FROM_HOLD",
                Map.of("waitingReceipt", waiting, "scheduled", scheduled));
        }
        return ticketIds;
    }

    public boolean hasActiveHold(String subjectHash) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM compliance.hold_ledger_entry e
              JOIN compliance.hold_document d ON d.id = e.doc_id
             WHERE e.subject_hash = :sh AND e.status IN ('ACTIVE','ESCALATED')
               AND d.direction = 'HOLD' AND e.hold_expires_at > now())
            """, new MapSqlParameterSource().addValue("sh", subjectHash), Boolean.class));
    }

    // ------------------------------------------------------------------
    // 4. 同日冲突裁定：两份冲突依据并列展示，结论写回台账
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> adjudicateConflict(long conflictId, String ruling, String reason,
                                                  Principal principal) {
        Map<String, Object> conflict = jdbc.query("SELECT * FROM compliance.hold_conflict WHERE id = :id",
            new MapSqlParameterSource().addValue("id", conflictId), rs -> rs.next() ? rsRow(rs) : null);
        if (conflict == null) {
            throw ApiException.notFound("冲突记录不存在: " + conflictId);
        }
        if (!"PENDING".equals(conflict.get("status"))) {
            throw ApiException.conflict("该冲突已裁定: " + conflict.get("ruling"));
        }
        long holdEntryId = ((Number) conflict.get("hold_entry_id")).longValue();
        long releaseEntryId = ((Number) conflict.get("release_entry_id")).longValue();
        String subjectHash = (String) conflict.get("subject_hash");
        List<Long> resumedTickets = List.of();
        if ("HOLD_WINS".equals(ruling)) {
            jdbc.update("UPDATE compliance.hold_ledger_entry SET adjudication_state = 'HOLD_UPHELD', "
                        + "updated_at = now() WHERE id = :id",
                        new MapSqlParameterSource().addValue("id", holdEntryId));
            jdbc.update("""
                UPDATE compliance.hold_ledger_entry SET adjudication_state = 'RELEASE_UPHELD',
                       status = 'RELEASED', released_at = now(), released_by = :by,
                       release_reason = :r, updated_at = now() WHERE id = :id
                """, new MapSqlParameterSource().addValue("by", principal.getName())
                    .addValue("r", "冲突裁定撤销方败诉：" + reason).addValue("id", releaseEntryId));
        } else if ("RELEASE_WINS".equals(ruling)) {
            jdbc.update("""
                UPDATE compliance.hold_ledger_entry SET adjudication_state = 'RELEASE_UPHELD',
                       status = 'RELEASED', released_at = now(), released_by = :by,
                       release_reason = :r, updated_at = now() WHERE id = :id
                """, new MapSqlParameterSource().addValue("by", principal.getName())
                    .addValue("r", "冲突裁定挂起方败诉：" + reason).addValue("id", holdEntryId));
            jdbc.update("""
                UPDATE compliance.hold_ledger_entry SET adjudication_state = 'HOLD_UPHELD',
                       status = 'RELEASED', released_at = now(), released_by = :by,
                       release_reason = '撤销依据胜诉，挂起解除', updated_at = now() WHERE id = :id
                """, new MapSqlParameterSource().addValue("by", principal.getName())
                    .addValue("id", releaseEntryId));
            resumedTickets = unblockSubjectTickets(subjectHash, principal);
        } else {
            throw ApiException.badRequest("ruling 仅支持 HOLD_WINS / RELEASE_WINS");
        }
        jdbc.update("""
            UPDATE compliance.hold_conflict SET status = 'ADJUDICATED', ruling = :r,
                   ruling_reason = :reason, ruled_by = :by, ruled_at = now() WHERE id = :id
            """, new MapSqlParameterSource().addValue("r", ruling).addValue("reason", reason)
                .addValue("by", principal.getName()).addValue("id", conflictId));
        emitEvent(subjectHash, holdEntryId, null, "HOLD_CONFLICT_RULED", principal.getName(),
            Map.of("conflictId", conflictId, "ruling", ruling, "reason", reason,
                   "resumedTicketIds", resumedTickets));
        return Map.of("conflictId", conflictId, "ruling", ruling, "resumedTicketIds", resumedTickets);
    }

    // ------------------------------------------------------------------
    // 5. 季度末汇总 + 自动升级（长期挂起 / 多次延期 / 依据缺失）
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> runReviewCycle(Principal principal) {
        // 5.1 复核超期未办
        int overdueReviews = jdbc.update("""
            INSERT INTO compliance.hold_compliance_issue (issue_type, subject_hash, entry_id, ticket_id, detail)
            SELECT 'REVIEW_OVERDUE', e.subject_hash, e.id, e.ticket_id,
                   '法务复核应于 ' || r.review_due_at || ' 前完成，当前仍 PENDING'
              FROM compliance.hold_review r
              JOIN compliance.hold_ledger_entry e ON e.id = r.entry_id
             WHERE r.decision = 'PENDING' AND r.review_due_at < now()
               AND NOT EXISTS (SELECT 1 FROM compliance.hold_compliance_issue i
                                WHERE i.issue_type = 'REVIEW_OVERDUE' AND i.entry_id = e.id
                                  AND i.status = 'OPEN')
            """, new MapSqlParameterSource());

        // 5.2 文书到期台账未恢复：事实置 EXPIRED 并单列合规问题（不再阻止删除，但留痕问责）
        int expired = jdbc.update("""
            UPDATE compliance.hold_ledger_entry SET status = 'EXPIRED', updated_at = now()
             WHERE status = 'ACTIVE' AND hold_expires_at < now()
            """, new MapSqlParameterSource());
        int expiredIssues = jdbc.update("""
            INSERT INTO compliance.hold_compliance_issue (issue_type, subject_hash, entry_id, ticket_id, detail)
            SELECT 'DOC_EXPIRED_NOT_RESUMED', e.subject_hash, e.id, e.ticket_id,
                   '文书 ' || e.doc_no || ' v' || e.doc_version_no || ' 已于 ' || e.hold_expires_at
                   || ' 到期，台账未走恢复流程'
              FROM compliance.hold_ledger_entry e
             WHERE e.status = 'EXPIRED'
               AND NOT EXISTS (SELECT 1 FROM compliance.hold_compliance_issue i
                                WHERE i.issue_type = 'DOC_EXPIRED_NOT_RESUMED' AND i.entry_id = e.id
                                  AND i.status = 'OPEN')
            """, new MapSqlParameterSource());
        jdbc.queryForList("""
            SELECT subject_hash, id, ticket_id FROM compliance.hold_ledger_entry WHERE status = 'EXPIRED'
            """, new MapSqlParameterSource()).forEach(row -> emitEvent(
                (String) row.get("subject_hash"), ((Number) row.get("id")).longValue(),
                (Long) row.get("ticket_id"), "HOLD_ENTRY_AUTO_EXPIRED", "system",
                Map.of("entryId", row.get("id"))));
        // 到期释放后若主体已无有效挂起，同步解除工单 HELD
        jdbc.queryForList("SELECT DISTINCT subject_hash FROM compliance.hold_ledger_entry WHERE status = 'EXPIRED'",
            Map.of()).forEach(row -> unblockSubjectTickets((String) row.get("subject_hash"), principal));

        // 5.3 超过法定期限且无新依据（未延期过）的挂起自动升级
        int escalated = jdbc.update("""
            UPDATE compliance.hold_ledger_entry SET status = 'ESCALATED', updated_at = now()
             WHERE status = 'ACTIVE' AND extension_count = 0
               AND hold_effective_at < now() - (:limit || ' days')::interval
            """, new MapSqlParameterSource().addValue("limit", legalLimitDays));
        int limitIssues = jdbc.update("""
            INSERT INTO compliance.hold_compliance_issue (issue_type, subject_hash, entry_id, ticket_id, detail)
            SELECT 'LEGAL_LIMIT_EXCEEDED', e.subject_hash, e.id, e.ticket_id,
                   '挂起已超过法定 ' || :limit || ' 天上限且无新依据，自动升级合规负责人'
              FROM compliance.hold_ledger_entry e
             WHERE e.status = 'ESCALATED'
               AND NOT EXISTS (SELECT 1 FROM compliance.hold_compliance_issue i
                                WHERE i.issue_type = 'LEGAL_LIMIT_EXCEEDED' AND i.entry_id = e.id
                                  AND i.status = 'OPEN')
            """, new MapSqlParameterSource().addValue("limit", legalLimitDays));
        jdbc.queryForList("""
            SELECT subject_hash, id, ticket_id FROM compliance.hold_ledger_entry
             WHERE status = 'ESCALATED' AND extension_count = 0
            """, new MapSqlParameterSource()).forEach(row -> emitEvent(
                (String) row.get("subject_hash"), ((Number) row.get("id")).longValue(),
                (Long) row.get("ticket_id"), "HOLD_ESCALATED", "system",
                Map.of("entryId", row.get("id"), "legalLimitDays", legalLimitDays)));

        Map<String, Object> report = quarterlyReport();
        report.put("cycle", Map.of("overdueReviews", overdueReviews, "expiredEntries", expired,
                                   "expiredIssues", expiredIssues, "escalatedEntries", escalated,
                                   "legalLimitIssues", limitIssues, "legalLimitDays", legalLimitDays));
        audit.log(null, principal.getName(), "HOLD_REVIEW_CYCLE_RUN", report.get("cycle"));
        return report;
    }

    /** 季度末交合规负责人的清单：长期挂起、多次延期、依据缺失（未决合规问题）。 */
    public Map<String, Object> quarterlyReport() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("longHolds", jdbc.queryForList("""
            SELECT e.*, d.doc_type, d.title, d.direction,
                   (now() - e.hold_effective_at)::text AS age_duration
              FROM compliance.hold_ledger_entry e
              JOIN compliance.hold_document d ON d.id = e.doc_id
             WHERE e.status IN ('ACTIVE','ESCALATED')
               AND e.hold_effective_at < now() - (:limit || ' days')::interval
             ORDER BY e.hold_effective_at
            """, new MapSqlParameterSource().addValue("limit", legalLimitDays)));
        report.put("multiExtensions", jdbc.queryForList("""
            SELECT * FROM compliance.hold_ledger_entry WHERE extension_count >= 2
             ORDER BY extension_count DESC, id
            """, new MapSqlParameterSource()));
        report.put("missingBasis", jdbc.queryForList("""
            SELECT * FROM compliance.hold_compliance_issue WHERE status = 'OPEN'
             ORDER BY created_at DESC
            """, new MapSqlParameterSource()));
        report.put("escalated", jdbc.queryForList("""
            SELECT * FROM compliance.hold_ledger_entry WHERE status = 'ESCALATED' ORDER BY id
            """, new MapSqlParameterSource()));
        report.put("legalLimitDays", legalLimitDays);
        return report;
    }

    // ------------------------------------------------------------------
    // 6. 台账与证据包逐位置对账：状态与依据不许各说各话
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> reconcileLedger(Principal principal) {
        // 6.1 有挂起标记却无有效依据文书（文书哈希被篡改/行缺失）
        List<Map<String, Object>> entries = jdbc.queryForList("""
            SELECT e.*, d.doc_hash AS stored_hash, d.doc_no AS d_no, d.version_no AS d_ver,
                   d.doc_type, d.title, d.issuing_authority, d.scope_text, d.effective_at,
                   d.expires_at, d.direction
              FROM compliance.hold_ledger_entry e
              JOIN compliance.hold_document d ON d.id = e.doc_id
             WHERE e.status IN ('ACTIVE','ESCALATED')
            """, new MapSqlParameterSource());
        int basisIssues = 0;
        for (Map<String, Object> e : entries) {
            String recomputed = HashUtil.sha256(String.join("|",
                String.valueOf(e.get("d_no")), String.valueOf(e.get("d_ver")),
                String.valueOf(e.get("doc_type")), String.valueOf(e.get("title")),
                String.valueOf(e.get("issuing_authority")), String.valueOf(e.get("scope_text")),
                canonicalTime(e.get("effective_at")), canonicalTime(e.get("expires_at")),
                String.valueOf(e.get("direction"))));
            if (!recomputed.equals(e.get("stored_hash"))) {
                basisIssues += openIssue("HOLD_WITHOUT_DOC", (String) e.get("subject_hash"),
                    ((Number) e.get("id")).longValue(), (Long) e.get("ticket_id"),
                    "文书 " + e.get("d_no") + " v" + e.get("d_ver") + " 内容哈希与冻结值不符，依据真实性存疑");
            }
        }
        // 6.2 撤回记录与当前状态冲突：挂起仍然有效，作业却已脱离 WITHDRAWN（一边挂起一边删）。
        //     挂起已合法解除后的重跑删除属正常流程，历史撤回留痕保留不报问题。
        int orphanIssues = jdbc.update("""
            INSERT INTO compliance.hold_compliance_issue (issue_type, subject_hash, entry_id, ticket_id, detail)
            SELECT 'ORPHAN_WITHDRAWAL', e.subject_hash, w.entry_id, w.ticket_id,
                   '撤回记录 #' || w.id || ' 对应处置 ' || w.disposition_id || ' 状态=' || d.status
                   || '，但挂起台账 ' || e.doc_no || ' 仍处 ' || e.status || '，疑似一边挂起一边删除'
              FROM compliance.hold_job_withdrawal w
              JOIN compliance.hold_ledger_entry e ON e.id = w.entry_id
              JOIN compliance.disposition_record d ON d.id = w.disposition_id
             WHERE e.status IN ('ACTIVE','ESCALATED')
               AND d.status NOT IN ('WITHDRAWN','PREVIEWED')
               AND NOT EXISTS (SELECT 1 FROM compliance.hold_compliance_issue i
                                WHERE i.issue_type = 'ORPHAN_WITHDRAWAL' AND i.entry_id = w.entry_id
                                  AND i.status = 'OPEN')
            """, new MapSqlParameterSource());
        // 6.3 处置标了 WITHDRAWN 却没有撤回留痕
        int missingWithdrawal = jdbc.update("""
            INSERT INTO compliance.hold_compliance_issue (issue_type, subject_hash, entry_id, ticket_id, detail)
            SELECT 'ORPHAN_WITHDRAWAL',
                   (SELECT subject_hash FROM compliance.work_order w WHERE w.id = d.ticket_id),
                   NULL, d.ticket_id,
                   '处置记录 ' || d.id || ' 标记 WITHDRAWN 但缺少 hold_job_withdrawal 撤回留痕'
              FROM compliance.disposition_record d
             WHERE d.status = 'WITHDRAWN'
               AND NOT EXISTS (SELECT 1 FROM compliance.hold_job_withdrawal w WHERE w.disposition_id = d.id)
               AND NOT EXISTS (SELECT 1 FROM compliance.hold_compliance_issue i
                                WHERE i.issue_type = 'ORPHAN_WITHDRAWAL' AND i.ticket_id = d.ticket_id
                                  AND i.detail LIKE '%' || d.id || '%' AND i.status = 'OPEN')
            """, new MapSqlParameterSource());
        Long openIssues = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.hold_compliance_issue WHERE status = 'OPEN'",
            new MapSqlParameterSource(), Long.class);
        audit.log(null, principal.getName(), "HOLD_LEDGER_RECONCILED",
            Map.of("basisIssues", basisIssues, "orphanIssues", orphanIssues,
                   "missingWithdrawal", missingWithdrawal, "openIssues", openIssues));
        return Map.of("basisIssues", basisIssues, "orphanIssues", orphanIssues,
                      "missingWithdrawal", missingWithdrawal, "openIssues", openIssues);
    }

    @Transactional
    public void resolveComplianceIssue(long issueId, String note, Principal principal) {
        int n = jdbc.update("""
            UPDATE compliance.hold_compliance_issue SET status = 'RESOLVED', resolved_by = :by,
                   resolution_note = :note, resolved_at = now() WHERE id = :id AND status = 'OPEN'
            """, new MapSqlParameterSource().addValue("by", principal.getName())
                .addValue("note", note).addValue("id", issueId));
        if (n == 0) {
            throw ApiException.conflict("合规问题不存在或已解决");
        }
    }

    private int openIssue(String type, String subjectHash, Long entryId, Long ticketId, String detail) {
        Integer exists = jdbc.queryForObject("""
            SELECT count(*) FROM compliance.hold_compliance_issue
             WHERE issue_type = :t AND coalesce(entry_id, -1) = coalesce(:eid, -1)
               AND detail = :d AND status = 'OPEN'
            """, new MapSqlParameterSource().addValue("t", type).addValue("eid", entryId)
                .addValue("d", detail), Integer.class);
        if (exists != null && exists > 0) {
            return 0;
        }
        jdbc.update("""
            INSERT INTO compliance.hold_compliance_issue (issue_type, subject_hash, entry_id, ticket_id, detail)
            VALUES (:t, :sh, :eid, :tid, :d)
            """, new MapSqlParameterSource().addValue("t", type).addValue("sh", subjectHash)
                .addValue("eid", entryId).addValue("tid", ticketId).addValue("d", detail));
        return 1;
    }

    // ------------------------------------------------------------------
    // 7. 查询：台账/文书/冲突/复核/事件/时点复现/工单视图
    // ------------------------------------------------------------------
    public List<Map<String, Object>> listEntries(String subjectHash, String status) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        List<String> where = new ArrayList<>();
        if (subjectHash != null && !subjectHash.isBlank()) {
            where.add("e.subject_hash = :sh");
            p.addValue("sh", subjectHash);
        }
        if (status != null && !status.isBlank()) {
            where.add("e.status = :st");
            p.addValue("st", status);
        }
        String clause = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        return jdbc.queryForList("""
            SELECT e.*, d.doc_type, d.title, d.direction, d.effective_at AS doc_effective_at,
                   d.expires_at AS doc_expires_at, d.doc_hash
              FROM compliance.hold_ledger_entry e
              JOIN compliance.hold_document d ON d.id = e.doc_id
            %s ORDER BY e.id DESC
            """.formatted(clause), p);
    }

    public List<Map<String, Object>> listDocuments(String docNo) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = "";
        if (docNo != null && !docNo.isBlank()) {
            where = " WHERE doc_no = :no";
            p.addValue("no", docNo);
        }
        return jdbc.queryForList(
            "SELECT * FROM compliance.hold_document " + where + " ORDER BY doc_no, version_no", p);
    }

    public List<Map<String, Object>> listConflicts(String status) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = "";
        if (status != null && !status.isBlank()) {
            where = " WHERE c.status = :st";
            p.addValue("st", status);
        }
        // 两份冲突依据并列，并按文书生效时点排序（而非录入先后）
        return jdbc.queryForList("""
            SELECT c.*,
                   hd.doc_no AS hold_doc_no, hd.version_no AS hold_version,
                   hd.effective_at AS hold_effective_at, hd.title AS hold_title,
                   rd.doc_no AS release_doc_no, rd.version_no AS release_version,
                   rd.effective_at AS release_effective_at, rd.title AS release_title,
                   least(hd.effective_at, rd.effective_at) AS sort_effective_at
              FROM compliance.hold_conflict c
              JOIN compliance.hold_document hd ON hd.id = c.hold_doc_id
              JOIN compliance.hold_document rd ON rd.id = c.release_doc_id
            %s ORDER BY sort_effective_at, c.id
            """.formatted(where), p);
    }

    public List<Map<String, Object>> listReviews(String status) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = "";
        if (status != null && !status.isBlank()) {
            where = " WHERE r.decision = :st";
            p.addValue("st", status);
        }
        return jdbc.queryForList("""
            SELECT r.*, e.subject_hash, e.asset_code, e.location_ref, e.doc_no, e.hold_expires_at
              FROM compliance.hold_review r
              JOIN compliance.hold_ledger_entry e ON e.id = r.entry_id
            %s ORDER BY r.review_due_at
            """.formatted(where), p);
    }

    public List<Map<String, Object>> listComplianceIssues(String status) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = "";
        if (status != null && !status.isBlank()) {
            where = " WHERE status = :st";
            p.addValue("st", status);
        }
        return jdbc.queryForList(
            "SELECT * FROM compliance.hold_compliance_issue " + where + " ORDER BY id DESC", p);
    }

    public List<Map<String, Object>> listEvents(String subjectHash, Long ticketId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        List<String> where = new ArrayList<>();
        if (subjectHash != null && !subjectHash.isBlank()) {
            where.add("subject_hash = :sh");
            p.addValue("sh", subjectHash);
        }
        if (ticketId != null) {
            where.add("ticket_id = :tid");
            p.addValue("tid", ticketId);
        }
        String clause = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        return jdbc.queryForList(
            "SELECT * FROM compliance.hold_event " + clause + " ORDER BY event_at, id", p);
    }

    /**
     * 时点状态复现：任一时点回答“该主体当前处于什么状态、凭哪份文书、按什么范围挂起”。
     * 依据 hold_event 不可变事件流 + 冻结的文书版本坐标重建，不依赖台账当前状态。
     */
    public Map<String, Object> statusAsOf(String subjectHash, OffsetDateTime at) {
        OffsetDateTime point = at != null ? at : OffsetDateTime.now();
        List<Map<String, Object>> events = jdbc.queryForList("""
            SELECT * FROM compliance.hold_event
             WHERE subject_hash = :sh AND event_at <= :at ORDER BY event_at, id
            """, new MapSqlParameterSource().addValue("sh", subjectHash).addValue("at", point));
        List<Map<String, Object>> activeEntries = jdbc.queryForList("""
            SELECT e.*, d.doc_type, d.title, d.direction, d.doc_hash, d.effective_at AS doc_effective_at
              FROM compliance.hold_ledger_entry e
              JOIN compliance.hold_document d ON d.id = e.doc_id
             WHERE e.subject_hash = :sh AND e.hold_effective_at <= :at
               AND (e.released_at IS NULL OR e.released_at > :at)
               AND d.direction = 'HOLD'
             ORDER BY e.asset_code, e.location_ref
            """, new MapSqlParameterSource().addValue("sh", subjectHash).addValue("at", point));
        List<Map<String, Object>> effectiveAtPoint = new ArrayList<>();
        for (Map<String, Object> e : activeEntries) {
            OffsetDateTime expiry = toOffsetDateTime(e.get("hold_expires_at"));
            Map<String, Object> view = new LinkedHashMap<>(e);
            view.put("effectiveAtPoint", expiry != null && expiry.isAfter(point));
            view.put("basis", "文书 " + e.get("doc_no") + " v" + e.get("doc_version_no")
                + "（" + e.get("title") + "），范围 " + e.get("asset_code") + "/" + e.get("location_ref")
                + "，责任人 " + e.get("owner_person"));
            effectiveAtPoint.add(view);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subjectHash", subjectHash);
        result.put("asOf", point);
        result.put("held", effectiveAtPoint.stream().anyMatch(e -> Boolean.TRUE.equals(e.get("effectiveAtPoint"))));
        result.put("activeEntriesAtPoint", effectiveAtPoint);
        result.put("eventsUpToPoint", events);
        return result;
    }

    /** 工单详情内嵌视图：台账条目、撤回留痕、冲突、复核、合规问题、事件。 */
    public Map<String, Object> ticketHoldView(long ticketId) {
        Map<String, Object> view = new LinkedHashMap<>();
        Map<String, Object> ticket = jdbc.query("SELECT subject_hash FROM compliance.work_order WHERE id = :id",
            new MapSqlParameterSource().addValue("id", ticketId), rs -> rs.next() ? rsRow(rs) : null);
        if (ticket == null) {
            throw ApiException.notFound("工单不存在: " + ticketId);
        }
        String subjectHash = (String) ticket.get("subject_hash");
        view.put("entries", jdbc.queryForList("""
            SELECT e.*, d.doc_type, d.title, d.direction, d.doc_hash
              FROM compliance.hold_ledger_entry e
              JOIN compliance.hold_document d ON d.id = e.doc_id
             WHERE e.ticket_id = :tid OR e.subject_hash = :sh
             ORDER BY e.id DESC
            """, new MapSqlParameterSource().addValue("tid", ticketId).addValue("sh", subjectHash)));
        view.put("withdrawals", jdbc.queryForList(
            "SELECT * FROM compliance.hold_job_withdrawal WHERE ticket_id = :tid ORDER BY id",
            new MapSqlParameterSource().addValue("tid", ticketId)));
        view.put("conflicts", jdbc.queryForList("""
            SELECT c.*, hd.doc_no AS hold_doc_no, rd.doc_no AS release_doc_no
              FROM compliance.hold_conflict c
              JOIN compliance.hold_document hd ON hd.id = c.hold_doc_id
              JOIN compliance.hold_document rd ON rd.id = c.release_doc_id
             WHERE c.subject_hash = :sh ORDER BY c.id
            """, new MapSqlParameterSource().addValue("sh", subjectHash)));
        view.put("reviews", listReviews(null).stream()
            .filter(r -> subjectHash.equals(r.get("subject_hash"))).toList());
        view.put("issues", jdbc.queryForList("""
            SELECT * FROM compliance.hold_compliance_issue
             WHERE subject_hash = :sh OR ticket_id = :tid ORDER BY id DESC
            """, new MapSqlParameterSource().addValue("sh", subjectHash).addValue("tid", ticketId)));
        view.put("events", listEvents(subjectHash, ticketId));
        return view;
    }

    // ====================== 内部辅助 ======================

    private Map<String, Object> requireEntry(long entryId) {
        Map<String, Object> entry = jdbc.query("SELECT * FROM compliance.hold_ledger_entry WHERE id = :id",
            new MapSqlParameterSource().addValue("id", entryId), rs -> rs.next() ? rsRow(rs) : null);
        if (entry == null) {
            throw ApiException.notFound("挂起台账条目不存在: " + entryId);
        }
        return entry;
    }

    private void emitEvent(String subjectHash, Long entryId, Long ticketId, String type,
                           String actor, Object detail) {
        jdbc.update("""
            INSERT INTO compliance.hold_event (subject_hash, entry_id, ticket_id, event_type, actor, detail_json)
            VALUES (:sh, :eid, :tid, :type, :actor, CAST(:d AS jsonb))
            """, new MapSqlParameterSource().addValue("sh", subjectHash).addValue("eid", entryId)
                .addValue("tid", ticketId).addValue("type", type).addValue("actor", actor)
                .addValue("d", toJson(detail)));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private Map<String, Object> rsRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        int cols = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= cols; i++) {
            Object v = rs.getObject(i);
            // timestamptz 经通用 getObject 返回 Timestamp，统一转 UTC OffsetDateTime 便于比较与序列化
            if (v instanceof Timestamp ts) {
                v = ts.toInstant().atOffset(ZoneOffset.UTC);
            }
            m.put(rs.getMetaData().getColumnLabel(i), v);
        }
        return m;
    }

    /** 哈希输入的时间字段统一规范化为 UTC ISO-INSTANT，保证登记与事后对账逐字节一致。 */
    private String canonicalTime(Object t) {
        if (t == null) {
            return "null";
        }
        if (t instanceof OffsetDateTime o) {
            return o.toInstant().toString();
        }
        if (t instanceof Timestamp ts) {
            return ts.toInstant().toString();
        }
        return t.toString();
    }

    /** “同日”按业务所在时区（Asia/Shanghai）的自然日判断，而不是 UTC 日期。 */
    private boolean sameBusinessDay(OffsetDateTime a, OffsetDateTime b) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        return a.atZoneSameInstant(zone).toLocalDate().equals(b.atZoneSameInstant(zone).toLocalDate());
    }

    /** queryForList 与 rsRow 返回的时间类型可能不同，统一转 OffsetDateTime(UTC)。 */
    private OffsetDateTime toOffsetDateTime(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof OffsetDateTime o) {
            return o;
        }
        if (v instanceof Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC);
        }
        return OffsetDateTime.parse(v.toString());
    }
}
