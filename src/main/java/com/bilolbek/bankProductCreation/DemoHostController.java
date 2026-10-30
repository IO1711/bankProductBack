package com.bilolbek.bankProductCreation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Test-only host API. A bank deployment supplies its own ticket validator and directory. */
@RestController
@ConditionalOnProperty(name="app.host.mode",havingValue="demo",matchIfMissing=true)
public class DemoHostController {
    record TicketIssueRequest(@NotBlank String user_id) { }
    private final DemoHostProvider host;
    DemoHostController(DemoHostProvider host) { this.host=host; }

    @GetMapping("/api/demo/users") public List<Map<String,Object>> users() {
        return host.demoUsers().stream().map(ApiMapper::user).toList();
    }

    @PostMapping("/api/demo/host/tickets") public Map<String,Object> issue(@Valid @RequestBody TicketIssueRequest body) {
        return Map.of("ticket",host.issueTicket(body.user_id()),"expires_in_seconds",60,"demo",true);
    }
}
