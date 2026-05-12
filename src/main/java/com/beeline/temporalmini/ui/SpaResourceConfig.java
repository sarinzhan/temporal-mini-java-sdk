package com.beeline.temporalmini.ui;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Serves the React SPA bundle at {@code /temporal-mini/ui/**} from the jar's
 * classpath resources. Any request that doesn't match an actual file (typical
 * SPA deep-link, e.g. {@code /temporal-mini/ui/workflows/42}) falls back to
 * {@code index.html} so React Router can take it from there.
 *
 * <p>Replaces the controller-based fallback that PathPatternParser rejects —
 * {@code **} can only appear at the end of a mapping pattern, but a custom
 * {@link PathResourceResolver} sidesteps that constraint entirely.
 */
@Configuration(proxyBeanMethods = false)
public class SpaResourceConfig implements WebMvcConfigurer {

    private static final String LOCATION = "classpath:/META-INF/resources/temporal-mini/ui/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/temporal-mini/ui/**")
                .addResourceLocations(LOCATION)
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    /** Resolves the requested file, or falls back to {@code index.html} for SPA deep links. */
    private static final class SpaFallbackResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            // Root request ("/temporal-mini/ui/") and any directory-style request
            // map directly to index.html — Spring's default index-file lookup is
            // bypassed when a custom resolver is plugged in.
            if (resourcePath.isEmpty() || resourcePath.endsWith("/")) {
                return location.createRelative("index.html");
            }
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable()) {
                return requested;
            }
            // Unknown path → SPA deep link, let React Router handle it.
            return location.createRelative("index.html");
        }
    }
}
