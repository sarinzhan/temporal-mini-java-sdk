package com.beeline.temporalmini.autoconfigure;

import com.beeline.temporalmini.WorkflowSecurityProperties;
import com.beeline.temporalmini.ui.AuthController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Optional session-based auth in front of {@code /temporal-mini/**}.
 * Activates only when Spring Security is on the classpath AND
 * {@code workflow.ui.security.enabled=true}.
 *
 * <p>Login via {@code POST /temporal-mini/api/auth/login} (JSON, no form/Basic).
 * CSRF disabled — expects same-origin SPA with {@code SameSite=Lax} session cookies.
 */
@AutoConfiguration
@ConditionalOnClass(SecurityFilterChain.class)
@ConditionalOnProperty(name = "workflow.ui.security.enabled", havingValue = "true")
@EnableConfigurationProperties(WorkflowSecurityProperties.class)
public class WorkflowSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PasswordEncoder.class)
    public PasswordEncoder workflowPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    @ConditionalOnMissingBean(UserDetailsService.class)
    public UserDetailsService workflowUserDetailsService(WorkflowSecurityProperties props) {
        UserDetails user = User.withUsername(props.getUsername())
                .password(props.getPassword())
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    @ConditionalOnMissingBean(AuthenticationManager.class)
    public AuthenticationManager workflowAuthenticationManager(UserDetailsService uds, PasswordEncoder enc) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(uds);
        provider.setPasswordEncoder(enc);
        return new ProviderManager(provider);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityContextRepository.class)
    public SecurityContextRepository workflowSecurityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public AuthController workflowAuthController(AuthenticationManager authManager,
                                                  SecurityContextRepository repo) {
        return new AuthController(authManager, repo);
    }

    @Bean
    public SecurityFilterChain workflowSecurityFilterChain(HttpSecurity http,
                                                            SecurityContextRepository repo) throws Exception {
        http
                .securityMatcher("/temporal-mini/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/temporal-mini/api/auth/login").permitAll()
                        .anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .securityContext(sc -> sc.securityContextRepository(repo))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"Not authenticated\"}");
                }));
        return http.build();
    }
}
