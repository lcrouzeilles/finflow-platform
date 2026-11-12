package com.finflow.transaction_service.api.admin;

import com.finflow.transaction_service.service.OutboxAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/outbox")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Outbox administration", description = "Inspect and retry events that exhausted their retries")
public class OutboxAdminController {

    private final OutboxAdminService outboxAdminService;

    public OutboxAdminController(OutboxAdminService outboxAdminService) {
        this.outboxAdminService = outboxAdminService;
    }

    @GetMapping("/failed")
    @Operation(summary = "List outbox events that exhausted their retries, oldest failure first")
    public List<OutboxEventResponse> listFailed(
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit
    ) {
        return outboxAdminService.findExhausted(limit).stream()
                .map(OutboxEventResponse::from)
                .toList();
    }

    @PostMapping("/{eventId}/retry")
    @Operation(summary = "Put an exhausted outbox event back in the queue with a fresh retry budget")
    public OutboxEventResponse retry(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID eventId
    ) {
        String operator = jwt != null ? jwt.getSubject() : "unknown";
        return OutboxEventResponse.from(outboxAdminService.retry(eventId, operator));
    }
}
