// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.oidc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Settings of the security web layer. A plain bean: {@code sangam-server} binds it with
 * {@code @ConfigurationProperties("sangam.security")} (Spring Boot is not a dependency of this module).
 *
 * <pre>
 * sangam.security.issuer-uri=https://keycloak.example/realms/sangam
 * sangam.security.audiences=sangam-api
 * sangam.security.client-id=sangam-api            # Keycloak client whose client roles are mapped
 * sangam.security.tenant-claim=tenant
 * sangam.security.jit-provisioning-tenants=dept-a
 * sangam.security.mfa-required-tenants=dept-a      # R67 MFA pass-through: acr/amr must show MFA
 * sangam.security.dev-mode=true                     # dev profile only
 * sangam.security.dev-tokens.alice=dept-a|alice|tenant-admin|section-a
 * </pre>
 */
public class SecurityProperties {

    /** A static development token: tenant, person, roles, groups. */
    public record DevToken(String tenant, String person, Set<String> roles, Set<String> groups) {
        /** Parses {@code tenant|person|role1,role2|group1,group2}. */
        public static DevToken parse(String spec) {
            String[] p = spec.split("\\|", -1);
            if (p.length < 2) {
                throw new IllegalArgumentException("dev token must be tenant|person[|roles[|groups]]");
            }
            return new DevToken(p[0], p[1], p.length > 2 ? csv(p[2]) : Set.of(), p.length > 3 ? csv(p[3]) : Set.of());
        }

        private static Set<String> csv(String s) {
            return s.isBlank() ? Set.of() : Set.of(s.split(","));
        }
    }

    private String issuerUri;
    private String jwkSetUri;
    private List<String> audiences = new ArrayList<>();
    private String clientId;
    private String tenantClaim = "tenant";
    private String groupsClaim = "groups";
    private long clockSkewSeconds = 60;
    private List<String> jitProvisioningTenants = new ArrayList<>();
    private List<String> mfaRequiredTenants = new ArrayList<>();
    private List<String> mfaAcrValues = new ArrayList<>(List.of("mfa", "aal2", "aal3", "urn:mace:incommon:iap:silver"));
    private boolean devMode;
    private boolean production;
    private Map<String, String> devTokens = new LinkedHashMap<>();
    private List<String> publicPaths = new ArrayList<>(List.of("/actuator/health/**", "/actuator/info"));
    private long loginBatchMillis = 1000;

    public String getIssuerUri() { return issuerUri; }
    public void setIssuerUri(String v) { issuerUri = v; }
    public String getJwkSetUri() { return jwkSetUri; }
    public void setJwkSetUri(String v) { jwkSetUri = v; }
    public List<String> getAudiences() { return audiences; }
    public void setAudiences(List<String> v) { audiences = v; }
    public String getClientId() { return clientId; }
    public void setClientId(String v) { clientId = v; }
    public String getTenantClaim() { return tenantClaim; }
    public void setTenantClaim(String v) { tenantClaim = v; }
    public String getGroupsClaim() { return groupsClaim; }
    public void setGroupsClaim(String v) { groupsClaim = v; }
    public long getClockSkewSeconds() { return clockSkewSeconds; }
    public void setClockSkewSeconds(long v) { clockSkewSeconds = v; }
    public List<String> getJitProvisioningTenants() { return jitProvisioningTenants; }
    public void setJitProvisioningTenants(List<String> v) { jitProvisioningTenants = v; }
    public List<String> getMfaRequiredTenants() { return mfaRequiredTenants; }
    public void setMfaRequiredTenants(List<String> v) { mfaRequiredTenants = v; }
    public List<String> getMfaAcrValues() { return mfaAcrValues; }
    public void setMfaAcrValues(List<String> v) { mfaAcrValues = v; }
    public boolean isDevMode() { return devMode; }
    public void setDevMode(boolean v) { devMode = v; }
    public boolean isProduction() { return production; }
    public void setProduction(boolean v) { production = v; }
    public Map<String, String> getDevTokens() { return devTokens; }
    public void setDevTokens(Map<String, String> v) { devTokens = v; }
    public List<String> getPublicPaths() { return publicPaths; }
    public void setPublicPaths(List<String> v) { publicPaths = v; }
    public long getLoginBatchMillis() { return loginBatchMillis; }
    public void setLoginBatchMillis(long v) { loginBatchMillis = v; }

    /** Dev static tokens are honoured only in dev mode and never on a production profile. */
    public boolean devTokensActive() {
        return devMode && !production && devTokens != null && !devTokens.isEmpty();
    }
}
