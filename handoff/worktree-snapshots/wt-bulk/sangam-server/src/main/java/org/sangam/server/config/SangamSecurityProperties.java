// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code sangam.security.*}: dev static token, OIDC claim names, CORS (ARCHITECTURE section 4 "Auth"). */
@ConfigurationProperties("sangam.security")
public class SangamSecurityProperties {

    private final DevToken devToken = new DevToken();
    private final Claims claims = new Claims();
    private final Cors cors = new Cors();

    public DevToken getDevToken() {
        return devToken;
    }

    public Claims getClaims() {
        return claims;
    }

    public Cors getCors() {
        return cors;
    }

    /** Static bearer token for development only; refused on the {@code prod} profile. */
    public static class DevToken {
        private boolean enabled = false;
        private String token;
        private String tenant = "dev";
        private String principal = "dev-admin";
        private List<String> groups = new ArrayList<>(List.of("sangam-user"));

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }

        public String getPrincipal() {
            return principal;
        }

        public void setPrincipal(String principal) {
            this.principal = principal;
        }

        public List<String> getGroups() {
            return groups;
        }

        public void setGroups(List<String> groups) {
            this.groups = groups;
        }
    }

    /**
     * JWT claim names and audience (deploy README section 10; env {@code SANGAM_SECURITY_OIDC_*}). Roles fall back to
     * {@code groups}, then Keycloak {@code realm_access.roles}. A blank audience disables the {@code aud} check.
     */
    public static class Claims {
        private String tenant = "tenant";
        private String roles = "roles";
        private String seats = "seats";
        private String audience = "sangam-server";
        private String principal = "preferred_username";

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }

        public String getRoles() {
            return roles;
        }

        public void setRoles(String roles) {
            this.roles = roles;
        }

        public String getSeats() {
            return seats;
        }

        public void setSeats(String seats) {
            this.seats = seats;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public String getPrincipal() {
            return principal;
        }

        public void setPrincipal(String principal) {
            this.principal = principal;
        }
    }

    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>();

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }
}
