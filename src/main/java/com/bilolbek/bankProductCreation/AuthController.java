package com.bilolbek.bankProductCreation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    record TicketRequest(@NotBlank String ticket) { }
    private final SessionLoginHandler sessions;
    AuthController(SessionLoginHandler sessions) { this.sessions=sessions; }

    @GetMapping("/api/auth/csrf") public Map<String,String> csrf(HttpServletRequest request) {
        CsrfToken token=(CsrfToken)request.getAttribute(CsrfToken.class.getName());
        if(token==null) throw WorkflowService.error(HttpStatus.INTERNAL_SERVER_ERROR,"CSRF token unavailable");
        return Map.of("header_name",token.getHeaderName(),"token",token.getToken());
    }

    @PostMapping("/api/auth/sso") public Map<String,Object> sso(@Valid @RequestBody TicketRequest body,
            HttpServletRequest request,HttpServletResponse response) {
        BankUser user=sessions.login(body.ticket(),request,response);
        return Map.of("user",ApiMapper.user(user),"authenticated",true);
    }

    @PostMapping("/api/auth/logout") public Map<String,Boolean> logout(HttpServletRequest request) {
        sessions.logout(request);
        return Map.of("authenticated",false);
    }
}
