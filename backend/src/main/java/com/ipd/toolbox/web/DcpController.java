package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.Decision;
import com.ipd.toolbox.service.DcpService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/dcp")
public class DcpController {

    private final DcpService service;

    public DcpController(DcpService service) {
        this.service = service;
    }

    @GetMapping("/gates/{stageGateId}/overview")
    public Result<DcpService.Overview> overview(@PathVariable Long stageGateId) {
        return Result.ok(service.overview(stageGateId));
    }

    public record ReviewRequest(String conclusion, String reason, Long linkedRiskId, LocalDate commitmentDue) {
    }

    @PostMapping("/gates/{stageGateId}/review")
    public Result<Decision> review(@PathVariable Long stageGateId, @RequestBody ReviewRequest req) {
        return Result.ok(service.review(stageGateId, req.conclusion(), req.reason(),
                req.linkedRiskId(), req.commitmentDue()));
    }
}
