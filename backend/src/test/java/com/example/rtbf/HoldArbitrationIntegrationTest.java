package com.example.rtbf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 挂起仲裁端到端测试：
 *  - 位置粒度挂起、已发出清除作业撤回留痕、台账优先拦截删除
 *  - 同日冲突按生效时点并列裁定；异日按生效时点自动适用
 *  - 延期生成新版本冻结原件；恢复执行重新触发影响面预览
 *  - 季度复核周期：复核超期、超法定期限自动升级
 *  - 台账逐位置对账：依据篡改/孤儿撤回单列合规问题；时点状态复现
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HoldArbitrationIntegrationTest {

    private static final String SUBJ_A =
        "f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Flyway flyway;
    private final ObjectMapper json = new ObjectMapper();

    private long ticketId;

    static {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/rtbf_test", "ccdev", "ccdev");
             java.sql.Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS compliance CASCADE");
            st.execute("DROP SCHEMA IF EXISTS biz CASCADE");
            st.execute("DROP TABLE IF EXISTS public.flyway_schema_history CASCADE");
        } catch (java.sql.SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @BeforeAll
    void resetDatabase() {
        jdbc.execute("DROP SCHEMA IF EXISTS compliance CASCADE");
        jdbc.execute("DROP SCHEMA IF EXISTS biz CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS public.flyway_schema_history CASCADE");
        flyway.migrate();
        // 先沿标识符哈希扫描归并主体（姓名不参与），再由各测试确认与建单
        post("compliance", "/api/subjects/scan", Map.of());
    }

    private HttpHeaders auth(String user) {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth(user, switch (user) {
            case "compliance" -> "Compliance@2026";
            case "operator" -> "Operator@2026";
            default -> "Viewer@2026";
        });
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private JsonNode post(String user, String path, Object body) {
        ResponseEntity<String> r = rest.exchange(path, HttpMethod.POST,
            new HttpEntity<>(body == null ? "" : write(body), auth(user)), String.class);
        assertTrue(r.getStatusCode().is2xxSuccessful(),
            () -> "POST " + path + " 失败: " + r.getStatusCode() + " " + r.getBody());
        return read(r.getBody());
    }

    private ResponseEntity<String> postRaw(String user, String path, Object body) {
        return rest.exchange(path, HttpMethod.POST,
            new HttpEntity<>(body == null ? "" : write(body), auth(user)), String.class);
    }

    private JsonNode get(String user, String path) {
        ResponseEntity<String> r = rest.exchange(path, HttpMethod.GET,
            new HttpEntity<>(auth(user)), String.class);
        assertTrue(r.getStatusCode().is2xxSuccessful(), () -> "GET " + path + " 失败: " + r.getBody());
        return read(r.getBody());
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void confirmAllMatches(String user, String subjectHash) {
        JsonNode subject = get(user, "/api/subjects/" + subjectHash);
        for (JsonNode m : subject.get("identifierMatches")) {
            post(user, "/api/identifier-matches/" + m.get("id").asLong() + "/decide",
                 Map.of("accept", true));
        }
        post(user, "/api/subjects/" + subjectHash + "/confirm", Map.of());
    }

    private Map<String, Object> location(String assetCode, String kind, OffsetDateTime expiry) {
        JsonNode ticket = get("viewer", "/api/tickets/" + ticketId);
        JsonNode target = java.util.stream.StreamSupport.stream(ticket.get("dispositions").spliterator(), false)
            .filter(d -> assetCode.equals(d.get("asset_code").asText())).findFirst().orElseThrow();
        Map<String, Object> loc = new java.util.HashMap<>();
        loc.put("assetCode", assetCode);
        loc.put("locationRef", target.get("location_ref").asText());
        loc.put("locationKind", kind);
        loc.put("scopeDetail", assetCode + " 保全范围");
        loc.put("ownerPerson", "法务-周岚");
        loc.put("expiresAt", expiry == null ? null : expiry.toString());
        return loc;
    }

    private Map<String, Object> holdDoc(String docNo, String direction, OffsetDateTime effective,
                                        OffsetDateTime expires, List<Map<String, Object>> locs) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("docNo", docNo);
        body.put("docType", "LITIGATION_PRESERVATION");
        body.put("title", "诉讼保全裁定书 " + docNo);
        body.put("issuingAuthority", "市中级人民法院");
        body.put("scopeText", "保全范围：该主体在列明存储位置的全部个人信息，暂停删除义务");
        body.put("effectiveAt", effective.toString());
        body.put("expiresAt", expires.toString());
        body.put("direction", direction);
        body.put("ticketNo", get("viewer", "/api/tickets/" + ticketId).get("ticketNo").asText());
        body.put("locations", locs);
        return body;
    }

    @Test
    @Order(1)
    void prepareTicketAtPreviewStage() {
        confirmAllMatches("compliance", SUBJ_A);
        JsonNode created = post("operator", "/api/tickets", Map.of(
            "subjectHash", SUBJ_A, "channel", "REGULATOR_LETTER", "sourceRef", "问询函-挂起测试"));
        ticketId = created.get("ticketId").asLong();
        post("operator", "/api/tickets/" + ticketId + "/freeze-enumeration", Map.of());
        long gapId = get("viewer", "/api/lineage/gaps").get(0).get("id").asLong();
        post("compliance", "/api/tickets/" + ticketId + "/gaps/" + gapId + "/confirm",
             Map.of("inScope", true));
        post("operator", "/api/tickets/" + ticketId + "/preview", Map.of());
        assertEquals("PENDING_EXECUTION",
            get("viewer", "/api/tickets/" + ticketId).get("status").asText());
    }

    @Test
    @Order(2)
    void hold_withdrawsJobs_blocksExecution_andKeepsSubjectInLedger() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8));
        Map<String, Object> body = holdDoc("PC-2026-001", "HOLD", now.withHour(9).withMinute(0).withSecond(0),
                                           now.plusDays(30), List.of(location("AST_ACCOUNT", "PRIMARY_STORAGE", null)));
        JsonNode reg = post("compliance", "/api/holds/register", body);
        assertEquals(1, reg.get("entryCount").asInt());
        assertTrue(reg.get("heldTicketIds").get(0).asLong() == ticketId);

        // 工单转 HELD，主体仍在删除台账内（工单没有被摘掉）
        assertEquals("HELD", get("viewer", "/api/tickets/" + ticketId).get("status").asText());
        // 挂起生效前已发出的清除作业被撤回并逐条留痕
        Integer withdrawn = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.disposition_record WHERE ticket_id = ? AND status = 'WITHDRAWN'",
            Integer.class, ticketId);
        assertEquals(1, withdrawn);
        Integer withdrawalRows = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.hold_job_withdrawal WHERE ticket_id = ?", Integer.class, ticketId);
        assertEquals(1, withdrawalRows);

        // 台账优先：执行删除与保留期重放都被拒绝，不能一边挂起一边继续删
        assertEquals(HttpStatus.CONFLICT,
            postRaw("operator", "/api/tickets/" + ticketId + "/execute", Map.of()).getStatusCode());
        assertEquals(HttpStatus.CONFLICT,
            postRaw("operator", "/api/tickets/" + ticketId + "/retry-retention", Map.of()).getStatusCode());
        // 业务数据原样保留
        assertEquals(2, jdbc.queryForObject(
            "SELECT count(*) FROM biz.account WHERE subject_hash = ?", Integer.class, SUBJ_A));

        // 任一时点都能回答当前处于什么状态、凭什么
        JsonNode asOf = get("viewer", "/api/holds/status-as-of?subjectHash=" + SUBJ_A);
        assertTrue(asOf.get("held").asBoolean());
        assertTrue(asOf.get("activeEntriesAtPoint").size() >= 1);
        JsonNode events = get("viewer", "/api/holds/events?ticketId=" + ticketId);
        List<String> eventTypes = java.util.stream.StreamSupport.stream(events.spliterator(), false)
            .map(e -> e.get("event_type").asText()).toList();
        assertTrue(eventTypes.contains("TICKET_HELD"));
        assertTrue(eventTypes.contains("JOB_WITHDRAWN"));
    }

    @Test
    @Order(3)
    void sameDayConflict_listedSideBySide_andAdjudicationWritesBackToLedger() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8));
        // 同日稍晚时点到达的保全撤销 → 并列冲突，而不是按录入先后直接生效
        Map<String, Object> release = holdDoc("CX-2026-001", "RELEASE",
            now.withHour(15).withMinute(0).withSecond(0), now.plusDays(30),
            List.of(location("AST_ACCOUNT", "PRIMARY_STORAGE", null)));
        JsonNode reg = post("compliance", "/api/holds/register", release);
        assertEquals(1, reg.get("conflictIds").size());

        JsonNode conflicts = get("compliance", "/api/holds/conflicts?status=PENDING");
        assertEquals(1, conflicts.size());
        long conflictId = conflicts.get(0).get("id").asLong();
        // 两份冲突依据并列，且按文书生效时点排序（挂起 09:00 在前，撤销 15:00 在后）
        assertNotNull(conflicts.get(0).get("hold_effective_at").asText());
        assertTrue(conflicts.get(0).get("hold_effective_at").asText()
            .compareTo(conflicts.get(0).get("release_effective_at").asText()) < 0);

        // 裁定支持撤销：结论写回台账，挂起解除并重新触发影响面预览
        JsonNode ruling = post("compliance", "/api/holds/conflicts/" + conflictId + "/adjudicate",
            Map.of("ruling", "RELEASE_WINS", "reason", "审查确认保全已依法撤销，恢复删除义务"));
        assertTrue(ruling.get("resumedTicketIds").get(0).asLong() == ticketId);
        assertEquals("PENDING_EXECUTION",
            get("viewer", "/api/tickets/" + ticketId).get("status").asText());
        // 撤回的作业已复位，重新预览后不再是 WITHDRAWN
        Integer withdrawn = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.disposition_record WHERE ticket_id = ? AND status = 'WITHDRAWN'",
            Integer.class, ticketId);
        assertEquals(0, withdrawn);
        Integer adjudicated = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.hold_conflict WHERE status = 'ADJUDICATED'", Integer.class);
        assertEquals(1, adjudicated);
    }

    @Test
    @Order(4)
    void differentDayRelease_appliesByEffectiveTimepointWithoutConflict() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8));
        post("compliance", "/api/holds/register", holdDoc("PC-2026-002", "HOLD",
            now.minusDays(2), now.plusDays(20),
            List.of(location("AST_PDF", "PRIMARY_STORAGE", null))));
        assertEquals("HELD", get("viewer", "/api/tickets/" + ticketId).get("status").asText());
        // 撤销文书生效时点晚两天：异日不进冲突清单，按生效时点自动适用
        post("compliance", "/api/holds/register", holdDoc("CX-2026-002", "RELEASE",
            now, now.plusDays(20), List.of(location("AST_PDF", "PRIMARY_STORAGE", null))));
        JsonNode pending = get("viewer", "/api/holds/conflicts?status=PENDING");
        assertEquals(0, pending.size());
        String closedReason = jdbc.queryForObject("""
            SELECT release_reason FROM compliance.hold_ledger_entry
             WHERE doc_no = 'PC-2026-002' LIMIT 1""", String.class);
        assertTrue(closedReason.contains("按文书生效时点自动适用"));
        // 主体已无有效挂起，工单自动退出 HELD 并重新预览
        assertEquals("PENDING_EXECUTION",
            get("viewer", "/api/tickets/" + ticketId).get("status").asText());
    }

    @Test
    @Order(5)
    void extension_freezesNewDocumentVersion_andResumeReTriggersPreview() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8));
        post("compliance", "/api/holds/register", holdDoc("PC-2026-003", "HOLD",
            now, now.plusDays(10),
            List.of(location("AST_BACKUP", "BACKUP_SNAPSHOT", now.plusDays(10)))));
        long oldEntryId = jdbc.queryForObject(
            "SELECT id FROM compliance.hold_ledger_entry WHERE doc_no = 'PC-2026-003'", Long.class);
        String originalHash = jdbc.queryForObject(
            "SELECT doc_hash FROM compliance.hold_document WHERE doc_no = 'PC-2026-003' AND version_no = 1",
            String.class);
        long reviewId = jdbc.queryForObject(
            "SELECT id FROM compliance.hold_review WHERE entry_id = ? AND decision = 'PENDING'",
            Long.class, oldEntryId);

        // 法务复核结论：延期 → 生成 v2 新版本，原件保持原样
        JsonNode decision = post("compliance", "/api/holds/reviews/" + reviewId + "/decision", Map.of(
            "decision", "EXTEND",
            "reason", "仲裁程序排期延后，保全期间续展",
            "newExpiresAt", now.plusDays(40).toString(),
            "newDocTitle", "诉讼保全裁定书 PC-2026-003（续期）"));
        long newEntryId = decision.get("successorEntryId").asLong();

        Integer versions = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.hold_document WHERE doc_no = 'PC-2026-003'", Integer.class);
        assertEquals(2, versions);
        // 原件哈希原样未动
        assertEquals(originalHash, jdbc.queryForObject(
            "SELECT doc_hash FROM compliance.hold_document WHERE doc_no = 'PC-2026-003' AND version_no = 1",
            String.class));
        Map<String, Object> oldEntry = jdbc.queryForMap(
            "SELECT status, successor_entry_id, extension_count FROM compliance.hold_ledger_entry WHERE id = ?",
            oldEntryId);
        assertEquals("RELEASED", oldEntry.get("status"));
        assertEquals(newEntryId, ((Number) oldEntry.get("successor_entry_id")).longValue());
        Map<String, Object> newEntry = jdbc.queryForMap(
            "SELECT status, extension_count, doc_version_no FROM compliance.hold_ledger_entry WHERE id = ?",
            newEntryId);
        assertEquals("ACTIVE", newEntry.get("status"));
        assertEquals(1, newEntry.get("extension_count"));
        assertEquals(2, newEntry.get("doc_version_no"));

        // 对新版本条目复核恢复执行 → 解除挂起并重新触发影响面预览
        long newReviewId = jdbc.queryForObject(
            "SELECT id FROM compliance.hold_review WHERE entry_id = ? AND decision = 'PENDING'",
            Long.class, newEntryId);
        post("compliance", "/api/holds/reviews/" + newReviewId + "/decision",
            Map.of("decision", "RESUME", "reason", "保全到期且无续期依据，恢复删除义务"));
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM compliance.hold_ledger_entry e "
            + "JOIN compliance.hold_document d ON d.id = e.doc_id "
            + "WHERE e.subject_hash = ? AND e.status IN ('ACTIVE','ESCALATED') AND d.direction='HOLD')",
            Boolean.class, SUBJ_A)));
        assertEquals("PENDING_EXECUTION",
            get("viewer", "/api/tickets/" + ticketId).get("status").asText());
    }

    @Test
    @Order(6)
    void reviewCycle_escalatesOverLimit_andLedgerReconcileFindsInconsistencies() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8));
        // 挂起生效时点在 200 天前、无延期 → 季度周期应自动升级
        post("compliance", "/api/holds/register", holdDoc("PC-2026-004", "HOLD",
            now.minusDays(200), now.plusDays(5),
            List.of(location("AST_SAMPLE", "LEGAL_RETENTION", now.plusDays(5)))));
        long sampleEntryId = jdbc.queryForObject(
            "SELECT id FROM compliance.hold_ledger_entry WHERE doc_no = 'PC-2026-004'", Long.class);
        // 复核截止人为调到过去，制造复核超期
        jdbc.update("UPDATE compliance.hold_review SET review_due_at = now() - interval '1 day' WHERE entry_id = ?",
            sampleEntryId);

        JsonNode cycle = post("compliance", "/api/holds/review-cycle", Map.of());
        assertTrue(cycle.get("cycle").get("escalatedEntries").asInt() >= 1);
        assertTrue(cycle.get("cycle").get("overdueReviews").asInt() >= 1);
        assertEquals("ESCALATED", jdbc.queryForObject(
            "SELECT status FROM compliance.hold_ledger_entry WHERE id = ?", String.class, sampleEntryId));
        Integer limitIssues = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.hold_compliance_issue "
            + "WHERE issue_type = 'LEGAL_LIMIT_EXCEEDED' AND status = 'OPEN'", Integer.class);
        assertTrue(limitIssues >= 1);

        // 篡改文书内容 → 冻结哈希对不上，对账单列“有挂起标记却无有效依据文书”
        jdbc.update("UPDATE compliance.hold_document SET title = '被篡改的标题' WHERE doc_no = 'PC-2026-004'");
        JsonNode recon = post("compliance", "/api/holds/reconcile", Map.of());
        assertTrue(recon.get("openIssues").asInt() >= 1);
        Integer basisIssues = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.hold_compliance_issue "
            + "WHERE issue_type = 'HOLD_WITHOUT_DOC' AND status = 'OPEN'", Integer.class);
        assertEquals(1, basisIssues);

        // 处置标了 WITHDRAWN 却没有撤回留痕 → 孤儿撤回问题
        long funnelDisp = jdbc.queryForObject(
            "SELECT id FROM compliance.disposition_record WHERE ticket_id = ? AND asset_code = 'AST_FUNNEL'",
            Long.class, ticketId);
        jdbc.update("UPDATE compliance.disposition_record SET status = 'WITHDRAWN' WHERE id = ?", funnelDisp);
        post("compliance", "/api/holds/reconcile", Map.of());
        Integer orphanIssues = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.hold_compliance_issue "
            + "WHERE issue_type = 'ORPHAN_WITHDRAWAL' AND status = 'OPEN'", Integer.class);
        assertTrue(orphanIssues >= 1);
        jdbc.update("UPDATE compliance.disposition_record SET status = 'PREVIEWED' WHERE id = ?", funnelDisp);

        // 未来时点复现：所有挂起均已过期，主体不再处于挂起状态
        String at = now.plusDays(200).toInstant().toString();
        JsonNode future = get("viewer",
            "/api/holds/status-as-of?subjectHash=" + SUBJ_A + "&at=" + at);
        assertFalse(future.get("held").asBoolean());
    }

    @Test
    @Order(8)
    void ledgerFirst_blocksExecutionEvenWhenTicketNotYetMarkedHeld() {
        // 先解除前序用例遗留、已被自动升级的封存（PC-2026-004），工单恢复到待执行
        long priorEntryId = jdbc.queryForObject(
            "SELECT id FROM compliance.hold_ledger_entry WHERE doc_no = 'PC-2026-004'", Long.class);
        long priorReviewId = jdbc.queryForObject(
            "SELECT id FROM compliance.hold_review WHERE entry_id = ? AND decision = 'PENDING'",
            Long.class, priorEntryId);
        post("compliance", "/api/holds/reviews/" + priorReviewId + "/decision",
            Map.of("decision", "RESUME", "reason", "监管检查结束，封存解除"));
        String status = get("viewer", "/api/tickets/" + ticketId).get("status").asText();
        assertEquals("PENDING_EXECUTION", status);
        String locationRef = jdbc.queryForObject(
            "SELECT location_ref FROM compliance.disposition_record "
            + "WHERE ticket_id = ? AND asset_code = 'AST_FEATURE' LIMIT 1", String.class, ticketId);
        // 直接往台账写一条有效挂起（模拟台账已落、工单状态尚未同步的并发窗口）
        jdbc.update("""
            INSERT INTO compliance.hold_document
              (doc_no, version_no, doc_type, title, doc_hash, scope_text,
               effective_at, expires_at, direction, created_by)
            VALUES ('PC-2026-008', 1, 'REGULATORY_INSPECTION', '监管检查封存通知',
                    'hash-008', '特征向量位置封存', now(), now() + interval '20 days', 'HOLD', 'compliance')
            """);
        Long docId = jdbc.queryForObject(
            "SELECT id FROM compliance.hold_document WHERE doc_no = 'PC-2026-008'", Long.class);
        jdbc.update("""
            INSERT INTO compliance.hold_ledger_entry
              (subject_hash, ticket_id, doc_id, doc_no, doc_version_no, asset_code, location_ref,
               location_kind, owner_person, hold_effective_at, hold_expires_at, status)
            VALUES (?, ?, ?, 'PC-2026-008', 1, 'AST_FEATURE', ?, 'PRIMARY_STORAGE',
                    '法务-周岚', now(), now() + interval '20 days', 'ACTIVE')
            """, SUBJ_A, ticketId, docId, locationRef);

        // 删除作业必须被台账拦截，且 HELD 由独立事务持久化（不随 409 回滚）
        assertEquals(HttpStatus.CONFLICT,
            postRaw("operator", "/api/tickets/" + ticketId + "/execute", Map.of()).getStatusCode());
        assertEquals("HELD", get("viewer", "/api/tickets/" + ticketId).get("status").asText());
        // 特征数据原样保留，没有一边挂起一边删
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM biz.recommend_feature WHERE subject_hash = ?", Integer.class, SUBJ_A));
    }

    @Test
    @Order(7)
    void releaseWithoutMatchingHold_isFlaggedAsMissingBasis() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8));
        JsonNode reg = post("compliance", "/api/holds/register", holdDoc("CX-2026-999", "RELEASE",
            now, now.plusDays(10), List.of(location("AST_SEARCH", "PRIMARY_STORAGE", null))));
        // 条目已登记但被自动关闭，不形成有效挂起/撤销台账
        assertEquals(1, reg.get("entryCount").asInt());
        String status = jdbc.queryForObject(
            "SELECT status FROM compliance.hold_ledger_entry WHERE doc_no = 'CX-2026-999'", String.class);
        assertEquals("RELEASED", status);
        Integer missing = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.hold_compliance_issue "
            + "WHERE issue_type = 'RELEASE_WITHOUT_HOLD' AND status = 'OPEN'", Integer.class);
        assertEquals(1, missing);
    }
}
