package com.beeline.temporalmini.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Convenience redirect from {@code /temporal-mini} → {@code /temporal-mini/ui/} so
 * legacy bookmarks land on the new UI. The SPA itself is served by
 * {@link SpaResourceConfig} via the static-resource pipeline; we don't try to
 * forward deep links from a controller because Spring's PathPatternParser
 * disallows {@code **} in the middle of a mapping pattern.
 */
@Controller
public class SpaController {

    @GetMapping({"/temporal-mini", "/temporal-mini/", "/temporal-mini/ui"})
    public RedirectView root() {
        return new RedirectView("/temporal-mini/ui/");
    }
}
