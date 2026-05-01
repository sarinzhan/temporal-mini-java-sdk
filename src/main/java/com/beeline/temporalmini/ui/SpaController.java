package com.beeline.temporalmini.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Wires the React SPA into Spring's dispatcher:
 *
 * <ul>
 *   <li>{@code /temporal-mini} and {@code /temporal-mini/} redirect to {@code /temporal-mini/ui/}
 *       so old bookmarks land on the new UI.</li>
 *   <li>Any deep link under {@code /temporal-mini/ui/...} that doesn't contain a dot
 *       (i.e. is not an asset request) forwards to the SPA's {@code index.html}.
 *       The dot exclusion lets requests for {@code /assets/index-XXXX.js},
 *       {@code favicon.ico}, etc. fall through to the static-resource handler.</li>
 * </ul>
 */
@Controller
public class SpaController {

    @GetMapping({"/temporal-mini", "/temporal-mini/"})
    public RedirectView root() {
        return new RedirectView("/temporal-mini/ui/");
    }

    @GetMapping(value = {
            "/temporal-mini/ui",
            "/temporal-mini/ui/",
            "/temporal-mini/ui/{path:[^.]*}",
            "/temporal-mini/ui/**/{path:[^.]*}"
    })
    public String spa() {
        return "forward:/temporal-mini/ui/index.html";
    }
}
