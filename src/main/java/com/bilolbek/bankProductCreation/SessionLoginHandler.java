package com.bilolbek.bankProductCreation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/** HTTP security adapter that persists host-validated identity in the session. */
@Component
public class SessionLoginHandler {
    private final HostTicketValidator tickets;
    private final SecurityContextRepository contexts;

    SessionLoginHandler(HostTicketValidator tickets,SecurityContextRepository contexts) {
        this.tickets=tickets; this.contexts=contexts;
    }

    BankUser login(String ticket,HttpServletRequest request,HttpServletResponse response) {
        BankUser user=tickets.validateAndConsume(ticket);
        request.getSession(true);
        request.changeSessionId();
        var authentication=UsernamePasswordAuthenticationToken.authenticated(user,null,
            List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context,request,response);
        return user;
    }

    void logout(HttpServletRequest request) {
        var session=request.getSession(false);
        if(session!=null) session.invalidate();
        SecurityContextHolder.clearContext();
    }
}
