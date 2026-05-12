package com.beeline.temporalmini.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Login / logout / me endpoints for the SPA. Exposes JSON, never redirects — the
 * frontend handles routing to the login page itself.
 *
 * <p>Only registered when {@code workflow.ui.security.enabled=true} (see
 * {@code WorkflowSecurityAutoConfiguration}).
 */
@RestController
@RequestMapping("/temporal-mini/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;

    public AuthController(@Lazy AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
    }

    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req,
                                   HttpServletRequest httpReq,
                                   HttpServletResponse httpRes) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.username(), req.password()));
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            SecurityContextHolder.setContext(ctx);
            // Persist into session so subsequent requests see the user as authenticated.
            securityContextRepository.saveContext(ctx, httpReq, httpRes);
            return ResponseEntity.ok(Map.of("username", auth.getName()));
        } catch (AuthenticationException ex) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        return ResponseEntity.ok(Map.of("username", auth.getName()));
    }
}
