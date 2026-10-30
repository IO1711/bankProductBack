package com.bilolbek.bankProductCreation;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Local Postman stand-in for the desktop E-IMZO signing client. */
@RestController
@ConditionalOnProperty(name="app.eimzo.mode",havingValue="demo",matchIfMissing=true)
public class DemoEimzoController {
    private final DemoSigningService signing;
    DemoEimzoController(DemoSigningService signing) { this.signing=signing; }

    @PostMapping("/api/demo/eimzo/sign") public Map<String,Object> sign(@RequestBody Map<String,String> body,
            @AuthenticationPrincipal BankUser principal) {
        String sessionId=body.get("sign_session_id");
        if(sessionId==null || sessionId.isBlank()) throw WorkflowService.error(HttpStatus.BAD_REQUEST,"sign_session_id is required");
        return signing.sign(sessionId,principal);
    }
}
