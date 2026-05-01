package com.beeline.temporalmini;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.beeline.temporalmini.ui.AuthController;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.*;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

@AutoConfiguration(after = DataSourceAutoConfiguration.class, before = HibernateJpaAutoConfiguration.class)
@EnableJpaRepositories(basePackageClasses = WorkflowRepository.class)
@EntityScan(basePackageClasses = WorkflowEntity.class)
@EnableScheduling
@EnableConfigurationProperties(WorkflowSecurityProperties.class)
public class TemporalMiniAutoConfiguration {

    public static final String EXECUTOR_BEAN = "workflowExecutor";

    @Bean
    public WorkflowEngine workflowEngine(List<Workflow> workflows,
                                         WorkflowRepository workflowRepository,
                                         ActivityRepository activityRepository,
                                         ObjectMapper objectMapper) {
        return new WorkflowEngine(workflows, workflowRepository, activityRepository, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(WorkflowRuntimeRegistry.class)
    public WorkflowRuntimeRegistry workflowRuntimeRegistry() {
        return new WorkflowRuntimeRegistry();
    }

    /**
     * Bounded executor for running workflows in parallel.
     * Defaults: poolSize = CPU count, queueCapacity = 100, CallerRunsPolicy
     * (so the scheduler thread itself runs the task when the queue is full —
     * gives natural back-pressure instead of dropping work).
     *
     * <p>Returned as {@link ThreadPoolTaskExecutor} (not just {@code Executor}) so
     * {@code WorkflowUiController} can read live pool metrics for {@code GET /pool}.
     */
    @Bean(name = EXECUTOR_BEAN, destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = EXECUTOR_BEAN)
    public ThreadPoolTaskExecutor workflowExecutor(
            @Value("${workflow.scheduler.pool-size:#{T(java.lang.Runtime).getRuntime().availableProcessors()}}") int poolSize,
            @Value("${workflow.scheduler.queue-capacity:100}") int queueCapacity,
            @Value("${workflow.scheduler.thread-name-prefix:wflow-}") String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean
    @ConditionalOnProperty(name = "workflow.scheduler.enabled", havingValue = "true", matchIfMissing = true)
    public WorkflowScheduler workflowScheduler(WorkflowEngine engine,
                                               WorkflowRepository workflowRepository,
                                               @Qualifier(EXECUTOR_BEAN) ThreadPoolTaskExecutor executor,
                                               WorkflowRuntimeRegistry runtimeRegistry) {
        return new WorkflowScheduler(engine, workflowRepository, executor, runtimeRegistry);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Flyway.class)
    static class TemporalMiniFlywayConfiguration {

        @Bean
        @ConditionalOnBean(DataSource.class)
        public Flyway temporalMiniFlyway(DataSource dataSource) {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration/temporal-mini")
                    .defaultSchema("wflow")
                    .createSchemas(true)
                    .table("flyway_temporal_mini_history")
                    .load();
            flyway.migrate();
            return flyway;
        }
    }

    /**
     * Optional session-based auth in front of {@code /temporal-mini/**}. Activates only
     * when (a) Spring Security is on the classpath and (b) {@code workflow.ui.security.enabled=true}.
     *
     * <p>Single in-memory user provisioned from {@link WorkflowSecurityProperties}. Login
     * is JSON via {@code POST /temporal-mini/api/auth/login} (see {@code AuthController}) —
     * no form login, no Basic Auth popup. CSRF is disabled because we expect same-origin
     * SPA usage with {@code SameSite=Lax} session cookies.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SecurityFilterChain.class)
    @ConditionalOnProperty(name = "workflow.ui.security.enabled", havingValue = "true")
    static class WorkflowSecurityConfig {

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
}
