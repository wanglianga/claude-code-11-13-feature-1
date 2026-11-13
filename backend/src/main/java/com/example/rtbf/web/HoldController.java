package com.example.rtbf.web;

import com.example.rtbf.service.HoldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Tag(name = "挂起仲裁（删除义务 × 保留义务冲突）")
@RestController
@RequestMapping("/api/holds")
public class HoldController {

    private final HoldService holds;

    public HoldController(HoldService holds) {
        this.holds = holds;
    }

    public record LocationRequest(@NotBlank String assetCode, @NotBlank String locationRef,
                                  @NotBlank String locationKind, String scopeDetail,
                                  @NotBlank String ownerPerson,
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime expiresAt) {
    }

    public record RegisterRequest(@NotBlank String docNo, @NotBlank String docType, @NotBlank String title,
                                  String issuingAuthority, @NotBlank String scopeText,
                                  @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime effectiveAt,
                                  @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime expiresAt,
                                  String direction, Long supersedesDocId, String ticketNo,
                                  @NotNull List<@Valid LocationRequest> locations) {
    }

    public record ReviewRequest(@NotBlank String decision, @NotBlank String reason,
                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime newExpiresAt,
                                String newDocTitle, String newScopeText) {
    }

    public record RulingRequest(@NotBlank String ruling, @NotBlank String reason) {
    }

    public record ResolveIssueRequest(@NotBlank String note) {
    }

    @Operation(summary = "登记挂起/撤销文书并按位置建台账（同日冲突自动并列；HOLD 自动撤回在途清除作业）")
    @PostMapping("/register")
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest req, Principal principal) {
        List<HoldService.HoldLocation> locations = req.locations().stream()
            .map(l -> new HoldService.HoldLocation(l.assetCode(), l.locationRef(), l.locationKind(),
                                                    l.scopeDetail(), l.ownerPerson(), l.expiresAt()))
            .toList();
        return holds.registerHold(new HoldService.RegisterHoldRequest(
            req.docNo(), req.docType(), req.title(), req.issuingAuthority(), req.scopeText(),
            req.effectiveAt(), req.expiresAt(), req.direction(), req.supersedesDocId(),
            req.ticketNo(), locations), principal);
    }

    @Operation(summary = "挂起台账（可按主体哈希/状态过滤），位置粒度、含冻结文书版本坐标")
    @GetMapping("/entries")
    public List<Map<String, Object>> entries(@RequestParam(required = false) String subjectHash,
                                             @RequestParam(required = false) String status) {
        return holds.listEntries(subjectHash, status);
    }

    @Operation(summary = "文书版本清单（同编号多版本，原件冻结）")
    @GetMapping("/documents")
    public List<Map<String, Object>> documents(@RequestParam(required = false) String docNo) {
        return holds.listDocuments(docNo);
    }

    @Operation(summary = "同日冲突清单：两份依据并列、按文书生效时点排序")
    @GetMapping("/conflicts")
    public List<Map<String, Object>> conflicts(@RequestParam(required = false) String status) {
        return holds.listConflicts(status);
    }

    @Operation(summary = "法务裁定同日冲突（HOLD_WINS / RELEASE_WINS），结论写回台账")
    @PostMapping("/conflicts/{id}/adjudicate")
    public Map<String, Object> adjudicate(@PathVariable long id, @Valid @RequestBody RulingRequest req,
                                          Principal principal) {
        return holds.adjudicateConflict(id, req.ruling(), req.reason(), principal);
    }

    @Operation(summary = "法务复核待办（到期前复核：延期新版本 / 恢复执行重预览 / 升级）")
    @GetMapping("/reviews")
    public List<Map<String, Object>> reviews(@RequestParam(required = false) String status) {
        return holds.listReviews(status);
    }

    @Operation(summary = "提交法务复核结论")
    @PostMapping("/reviews/{id}/decision")
    public Map<String, Object> reviewDecision(@PathVariable long id, @Valid @RequestBody ReviewRequest req,
                                              Principal principal) {
        return holds.decideReview(id, new HoldService.ReviewDecisionRequest(
            req.decision(), req.reason(), req.newExpiresAt(), req.newDocTitle(), req.newScopeText()), principal);
    }

    @Operation(summary = "季度末汇总 + 自动升级（长期挂起/多次延期/依据缺失/超期无新依据）")
    @PostMapping("/review-cycle")
    public Map<String, Object> reviewCycle(Principal principal) {
        return holds.runReviewCycle(principal);
    }

    @Operation(summary = "季度合规清单（长期挂起、多次延期、依据缺失）")
    @GetMapping("/quarterly-report")
    public Map<String, Object> quarterlyReport() {
        return holds.quarterlyReport();
    }

    @Operation(summary = "台账与证据包逐位置对账，状态与依据不一致单列合规复核清单")
    @PostMapping("/reconcile")
    public Map<String, Object> reconcile(Principal principal) {
        return holds.reconcileLedger(principal);
    }

    @Operation(summary = "挂起合规复核清单")
    @GetMapping("/compliance-issues")
    public List<Map<String, Object>> issues(@RequestParam(required = false) String status) {
        return holds.listComplianceIssues(status);
    }

    @Operation(summary = "合规负责人复核解决挂起类问题")
    @PostMapping("/compliance-issues/{id}/resolve")
    public Map<String, Object> resolveIssue(@PathVariable long id, @Valid @RequestBody ResolveIssueRequest req,
                                            Principal principal) {
        holds.resolveComplianceIssue(id, req.note(), principal);
        return Map.of("issueId", id, "status", "RESOLVED");
    }

    @Operation(summary = "不可变挂起事件流（可按主体/工单过滤）")
    @GetMapping("/events")
    public List<Map<String, Object>> events(@RequestParam(required = false) String subjectHash,
                                            @RequestParam(required = false) Long ticketId) {
        return holds.listEvents(subjectHash, ticketId);
    }

    @Operation(summary = "时点状态复现：任一时点回答主体处于什么状态、凭哪份文书、按什么范围挂起")
    @GetMapping("/status-as-of")
    public Map<String, Object> statusAsOf(@RequestParam String subjectHash,
                                          @RequestParam(required = false)
                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at) {
        return holds.statusAsOf(subjectHash, at);
    }

    @Operation(summary = "工单内嵌挂起视图：台账/撤回留痕/冲突/复核/合规问题/事件")
    @GetMapping("/tickets/{ticketId}/view")
    public Map<String, Object> ticketView(@PathVariable long ticketId) {
        return holds.ticketHoldView(ticketId);
    }
}
