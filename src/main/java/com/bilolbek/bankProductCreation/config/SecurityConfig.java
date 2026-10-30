package com.bilolbek.bankProductCreation.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration(proxyBeanMethods=false)
public class SecurityConfig {
    @Bean SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,SecurityContextRepository repository) throws Exception {
        http.securityContext(context->context.securityContextRepository(repository))
            .authorizeHttpRequests(auth->auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/auth/sso","/api/auth/csrf","/api/demo/host/tickets","/api/demo/users","/verify/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(errors->errors
                .authenticationEntryPoint((request,response,exception)->problem(response,HttpStatus.UNAUTHORIZED,"Authentication required"))
                .accessDeniedHandler((request,response,exception)->problem(response,HttpStatus.FORBIDDEN,"Access denied or CSRF token missing")))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    private static void problem(HttpServletResponse response,HttpStatus status,String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\""+status.getReasonPhrase()+
            "\",\"status\":"+status.value()+",\"detail\":\""+detail+"\"}");
    }
}
