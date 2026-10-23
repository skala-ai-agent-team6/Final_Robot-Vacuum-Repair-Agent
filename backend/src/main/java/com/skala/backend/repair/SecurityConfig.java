package com.skala.backend.repair;

import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.cors.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(12); }
    @Bean UserDetailsService userDetails(UserRepository users) { return username -> {
        var u=users.findByEmail(username).orElseThrow(()->new UsernameNotFoundException("Invalid credentials"));
        return User.withUsername(u.email).password(u.passwordHash).disabled(!u.active).roles(u.role).build();
    }; }
    @Bean SecurityFilterChain security(HttpSecurity http,@Value("${app.internal-token}") String serviceToken,@Value("${app.cors-origin}") String origin) throws Exception {
        if(serviceToken.length()<32) throw new IllegalArgumentException("INTERNAL_SERVICE_TOKEN requires at least 32 characters");
        var csrfHandler=new CsrfTokenRequestAttributeHandler();
        http.cors(c -> c.configurationSource(request -> {
            var cfg=new CorsConfiguration(); cfg.setAllowedOrigins(List.of(origin)); cfg.setAllowedMethods(List.of("GET","POST","PUT","OPTIONS"));
            cfg.setAllowedHeaders(List.of("Content-Type","X-CSRF-TOKEN","Last-Event-ID","Idempotency-Key")); cfg.setAllowCredentials(true); return cfg;
        })).csrf(c->c.csrfTokenRequestHandler(csrfHandler).ignoringRequestMatchers("/internal/**"))
        .authorizeHttpRequests(a->a.requestMatchers("/api/auth/csrf","/api/auth/login","/swagger-ui.html","/swagger-ui","/swagger-ui/**","/v3/api-docs","/webjars/swagger-ui/**").permitAll()
            .requestMatchers("/internal/**").hasRole("SERVICE").anyRequest().authenticated())
        .formLogin(f->f.loginProcessingUrl("/api/auth/login")
            .successHandler((req,res,auth)->{res.setContentType("application/json");res.getWriter().write("{\"authenticated\":true}");})
            .failureHandler((req,res,e)->{res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"code\":\"INVALID_CREDENTIALS\",\"status\":401}");}))
        .logout(l->l.logoutUrl("/api/auth/logout").logoutSuccessHandler((req,res,a)->res.setStatus(204)))
        .exceptionHandling(e->e.authenticationEntryPoint((req,res,x)->{res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"status\":401}");})
            .accessDeniedHandler((req,res,x)->{res.setStatus(403);res.setContentType("application/json");res.getWriter().write("{\"code\":\"FORBIDDEN_OR_CSRF\",\"status\":403}");}))
        .addFilterBefore(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
                if(req.getRequestURI().substring(req.getContextPath().length()).startsWith("/internal/")) {
                    String token=req.getHeader("Authorization");
                    if(token==null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),("Bearer "+serviceToken).getBytes(StandardCharsets.UTF_8))) {res.setStatus(401);return;}
                    var context=SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(new UsernamePasswordAuthenticationToken("service",null,List.of(new SimpleGrantedAuthority("ROLE_SERVICE"))));
                    SecurityContextHolder.setContext(context);
                }
                chain.doFilter(req,res);
            }
        },UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
