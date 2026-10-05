package com.tx.api.test.generator.studio.api;

import com.tx.api.test.generator.studio.audit.StudioAuditService;
import com.tx.api.test.generator.studio.dto.AuditEventView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final StudioAuditService auditService;

    public AuditController(StudioAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/cases/{caseId}")
    public List<AuditEventView> caseHistory(@PathVariable String caseId,
                                            @RequestParam(defaultValue = "20") int limit) {
        return auditService.listForCase(caseId, limit);
    }

    @GetMapping("/specs/{specId}")
    public List<AuditEventView> specHistory(@PathVariable String specId,
                                          @RequestParam(defaultValue = "30") int limit) {
        return auditService.listForSpec(specId, limit);
    }
}
