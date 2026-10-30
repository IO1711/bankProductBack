package com.bilolbek.bankProductCreation;

import java.util.HexFormat;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Postman helper that stands in for the user's local E-IMZO client. */
@Service
@ConditionalOnProperty(name="app.eimzo.mode",havingValue="demo",matchIfMissing=true)
public class DemoSigningService {
    private final SigningService signing;
    private final DemoEimzoProvider provider;
    private final OrganizationDirectory directory;

    DemoSigningService(SigningService signing,DemoEimzoProvider provider,OrganizationDirectory directory) {
        this.signing=signing; this.provider=provider; this.directory=directory;
    }

    Map<String,Object> sign(String sessionId,BankUser principal) {
        if(principal==null) throw WorkflowService.error(HttpStatus.UNAUTHORIZED,"Authentication required");
        BankUser user=directory.user(principal.user_id);
        if(user==null) throw WorkflowService.error(HttpStatus.UNAUTHORIZED,"User no longer exists in the host directory");
        var session=signing.demoSession(sessionId,user);
        return Map.of("pkcs7",provider.issue(sessionId,user.pinfl,HexFormat.of().parseHex(session.hash())),"demo",true);
    }
}
