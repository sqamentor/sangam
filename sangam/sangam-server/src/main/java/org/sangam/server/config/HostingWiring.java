// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.server.security.TrustedProxyFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Generic hosting rules (SL-18): the server owns no browser-facing host name, trusts forwarding headers only from
 * configured proxies, and on a production profile refuses to start with a loopback default in any externally used URL.
 */
@Configuration(proxyBeanMethods = false)
public class HostingWiring {

    /** Loopback host names that must never reach production configuration. */
    static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]", "0.0.0.0");

    /**
     * URL properties that point at something other than this process. Self-call URLs (for example
     * {@code sangam.ai.mcp.rest-base-url}, which defaults to this pod's own loopback port) are excluded on purpose.
     */
    static final List<String> EXTERNAL_URL_PROPERTIES = List.of("spring.datasource.url", "sangam.security.issuer-uri",
            "sangam.security.jwk-set-uri", "spring.security.oauth2.resourceserver.jwt.issuer-uri",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri", "sangam.cell.public-url",
            "sangam.cell.control-plane-url", "sangam.public-base-url");

    static final List<String> SELF_CALL = List.of("sangam.ai.mcp.rest-base-url");

    @Bean
    public FilterRegistrationBean<TrustedProxyFilter> sangamTrustedProxyFilter(Environment env) {
        List<String> proxies = list(env.getProperty("sangam.http.trusted-proxies", ""));
        List<String> headers = list(env.getProperty("sangam.http.client-ip-headers", "X-Forwarded-For"));
        FilterRegistrationBean<TrustedProxyFilter> reg = new FilterRegistrationBean<>(new TrustedProxyFilter(proxies, headers));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.setName("sangamTrustedProxyFilter");
        return reg;
    }

    @Bean
    public static org.springframework.beans.factory.config.BeanFactoryPostProcessor sangamProductionUrlGuard(Environment env) {
        return bf -> {
            List<String> bad = loopbackViolations(env);
            if (!bad.isEmpty()) {
                throw new IllegalStateException("sangam.security.production=true but these externally used URLs are unset "
                        + "defaults or point at a loopback host (configure them explicitly): " + bad);
            }
        };
    }

    /** Violations of the production URL rule; empty when not production. */
    static List<String> loopbackViolations(Environment env) {
        if (!env.getProperty("sangam.security.production", Boolean.class, false)) {
            return List.of();
        }
        Set<String> keys = new TreeSet<>(EXTERNAL_URL_PROPERTIES);
        if (env instanceof ConfigurableEnvironment ce) {
            for (PropertySource<?> ps : ce.getPropertySources()) {
                if (ps instanceof EnumerablePropertySource<?> eps) {
                    for (String n : eps.getPropertyNames()) {
                        String l = n.toLowerCase(Locale.ROOT);
                        if (l.startsWith("sangam.") && (l.endsWith("-url") || l.endsWith("base-url") || l.endsWith(".url")
                                || l.endsWith("-uri")) && !SELF_CALL.contains(l)) {
                            keys.add(n);
                        }
                    }
                }
            }
        }
        List<String> bad = new ArrayList<>();
        for (String k : keys) {
            String v;
            try {
                v = env.getProperty(k);
            } catch (RuntimeException e) {
                continue;
            }
            if (v != null && isLoopback(v)) {
                bad.add(k + "=" + v);
            }
        }
        if (env.getProperty("spring.datasource.url", "").isBlank()) {
            bad.add("spring.datasource.url (unset)");
        }
        return bad;
    }

    /** True when the URL (http(s), jdbc:..., smtp, ...) names a loopback host. */
    static boolean isLoopback(String url) {
        String s = url.trim();
        if (s.isEmpty()) {
            return false;
        }
        String low = s.toLowerCase(Locale.ROOT);
        for (String h : LOOPBACK) {
            if (low.contains("//" + h + ":") || low.contains("//" + h + "/") || low.endsWith("//" + h)
                    || low.contains("@" + h + ":") || low.contains("@//" + h)) {
                return true;
            }
        }
        try {
            String u = low.startsWith("jdbc:") ? low.substring(5) : low;
            String host = URI.create(u).getHost();
            return host != null && LOOPBACK.contains(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static List<String> list(String csv) {
        return csv == null ? List.of() : Arrays.stream(csv.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
}
