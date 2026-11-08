// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.api;

import org.sangam.config.admin.ConfigAdminService;
import org.sangam.config.common.Finding;
import org.sangam.config.master.MasterCode;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ResolvedProfile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST ports for WS-REST (implemented by the integrator over the services of this module). Endpoints (base
 * {@code /api/v1}, problem+json errors, Idempotency-Key on mutations):
 * <pre>
 * GET    /config/profiles?scope=&amp;office=&amp;asOf=          → resolved profile (data, chain, binding, placeholders, findings)
 * GET    /config/profiles/{id}/versions                     → versions with status
 * PUT    /config/profiles/{id}/versions/{version}           → save draft (YAML or JSON body)
 * POST   /config/profiles/{id}/versions/{version}/submit
 * POST   /config/profiles/{id}/versions/{version}/approve   {presentedHash}
 * POST   /config/profiles/{id}/versions/{version}/reject    {reason}
 * POST   /config/profiles/{id}/versions/{version}/activate  {scopesToCheck[]}
 * GET    /config/profiles/{id}/history                      → change register
 * POST   /config/validate                                    {scope, office, asOf} → findings
 * GET    /config/master/{scheme}/{segment}?scope=&amp;asOf=     → codes
 * POST   /config/master/seed                                 CSV / YAML body → count
 * POST   /config/master/correction-slips                     → findings
 * POST   /config/classification/validate                     {scope, scheme, code} → findings, attributes
 * GET    /config/document-types?scope=&amp;family=             → catalogue
 * POST   /config/document-types/{id}/check                   {document} → findings
 * POST   /config/crosswalks/{id}/map                         {code, amount} → mapped
 * </pre>
 */
public final class ConfigPorts {

    private ConfigPorts() {
    }

    public interface ProfileQuery {
        ResolvedProfile resolve(String scope, String office, LocalDate asOf);

        List<Finding> validate(String scope, String office, LocalDate asOf);
    }

    public interface ProfileAdmin {
        List<Finding> saveDraft(ProfileDocument doc, String maker);

        void submit(String id, String version, String maker);

        void approve(String id, String version, String checker, String presentedHash);

        void reject(String id, String version, String checker, String reason);

        List<Finding> activate(String id, String version, String actor, List<String> scopesToCheck);

        List<ConfigAdminService.ChangeRecord> history(String id);
    }

    public interface MasterData {
        List<MasterCode> codes(String scope, String scheme, String segment, LocalDate asOf);

        int seed(String scope, String format, String body);

        List<Finding> applySlip(Map<String, Object> slip);

        Map<String, Object> validate(String scope, String scheme, String code, LocalDate asOf);
    }
}
