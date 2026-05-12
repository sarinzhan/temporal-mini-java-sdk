package com.beeline.temporalmini.ui;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.DispatcherServlet;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(name = "temporal-mini.ui.enabled", havingValue = "true", matchIfMissing = true)
@Import({ WorkflowUiController.class, MetricsController.class, SpaController.class, SpaResourceConfig.class, WorkflowUiBanner.class })
public class TemporalMiniUiAutoConfiguration {
}
