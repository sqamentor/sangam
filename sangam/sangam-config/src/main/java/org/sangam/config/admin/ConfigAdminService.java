// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.admin;

import org.sangam.config.common.ConfigException;
import org.sangam.config.common.Finding;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileRepository;
import org.sangam.config.profile.ProfileRepository.Status;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.config.profile.ProfileValidator;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Configuration change control: draft → submitted → approved → active (or rejected), with maker ≠ checker, the
 * approval bound to the content hash, validation before submission and activation, and an append-only change register
 * (IFS-075 change register, IFMS-STATES 6.5 step 4, generalised). Every transition is recorded.
 */
public final class ConfigAdminService {

    /** One entry of the change register. */
    public record ChangeRecord(String profileId, String version, String action, String actor, Instant at,
                               String contentHash, String reason) {
    }

    /** Append-only store of change records. */
    public interface ChangeLog {
        void append(ChangeRecord r);

        List<ChangeRecord> history(String profileId);
    }

    public static final class InMemoryChangeLog implements ChangeLog {
        private final List<ChangeRecord> rows = new CopyOnWriteArrayList<>();

        @Override
        public void append(ChangeRecord r) {
            rows.add(r);
        }

        @Override
        public List<ChangeRecord> history(String profileId) {
            return rows.stream().filter(r -> r.profileId().equals(profileId)).toList();
        }
    }

    private final ProfileRepository repo;
    private final ProfileResolver resolver;
    private final ProfileValidator validator;
    private final ChangeLog log;
    private final Clock clock;

    public ConfigAdminService(ProfileRepository repo, ProfileResolver resolver, ProfileValidator validator, ChangeLog log,
                              Clock clock) {
        this.repo = repo;
        this.resolver = resolver;
        this.validator = validator;
        this.log = log;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public List<Finding> saveDraft(ProfileDocument doc, String maker) {
        List<Finding> f = validator.validateDocument(doc);
        repo.get(doc.id(), doc.version()).ifPresent(s -> {
            if (s.status() != Status.DRAFT && s.status() != Status.REJECTED) {
                throw new ConfigException("VERSION_FROZEN", doc.ref() + " is " + s.status() + "; create a new version");
            }
        });
        repo.save(doc, Status.DRAFT);
        record(doc, "DRAFT_SAVED", maker, null);
        return f;
    }

    public void submit(String id, String version, String maker) {
        ProfileRepository.Stored s = require(id, version, Status.DRAFT, Status.REJECTED);
        List<Finding> f = validator.validateDocument(s.document());
        if (Finding.anyError(f)) {
            throw new ConfigException("PROFILE_INVALID", s.document().ref() + " has errors", Finding.errors(f));
        }
        repo.setStatus(id, version, Status.SUBMITTED);
        record(s.document(), "SUBMITTED", maker, null);
    }

    /**
     * Approves a submitted version. {@code presentedHash} is the content hash the checker saw; a different stored
     * hash means the document changed after review (refused).
     */
    public void approve(String id, String version, String checker, String presentedHash) {
        ProfileRepository.Stored s = require(id, version, Status.SUBMITTED);
        String maker = log.history(id).stream().filter(r -> r.version().equals(version) && r.action().equals("SUBMITTED"))
                .reduce((a, b) -> b).map(ChangeRecord::actor).orElse(null);
        if (checker == null || checker.equals(maker)) {
            throw new ConfigException("SOD_VIOLATION", "the checker must differ from the maker");
        }
        if (presentedHash == null || !presentedHash.equals(s.hash())) {
            throw new ConfigException("APPROVAL_INVALIDATED", "content changed after review");
        }
        repo.setStatus(id, version, Status.APPROVED);
        record(s.document(), "APPROVED", checker, null);
    }

    public void reject(String id, String version, String checker, String reason) {
        ProfileRepository.Stored s = require(id, version, Status.SUBMITTED);
        repo.setStatus(id, version, Status.REJECTED);
        record(s.document(), "REJECTED", checker, reason);
    }

    /**
     * Activates an approved version after validating the chains that include it on its effective-from date: always
     * the document's own scope (an office overlay {@code scope/office} as that office of its scope; SL-121), then each
     * listed dependent scope. Running cases keep their bound version; new cases pick this one from its effective date.
     * A refusal is recorded ({@code ACTIVATION_REFUSED}), so other nodes see the change marker move (SL-120).
     */
    public List<Finding> activate(String id, String version, String actor, List<String> scopesToCheck) {
        ProfileRepository.Stored s = require(id, version, Status.APPROVED);
        repo.setStatus(id, version, Status.ACTIVE);
        resolver.invalidate();
        // Default effective date: today in the document scope's business zone (office overlay id "scope/office").
        String ownScope = id.contains("/") ? id.substring(0, id.indexOf('/')) : id;
        String ownOffice = id.contains("/") ? id.substring(id.indexOf('/') + 1) : null;
        LocalDate from = s.document().effectiveFrom() != null ? s.document().effectiveFrom()
                : new org.sangam.config.locale.ProfileBusinessClock(resolver, clock).today(ownScope);
        List<Finding> all = new ArrayList<>(validator.validate(resolver, ownScope, ownOffice, from));
        for (String scope : scopesToCheck == null ? List.<String>of() : scopesToCheck) {
            if (ownOffice == null && scope.equals(ownScope)) {
                continue; // already validated as the document's own scope
            }
            all.addAll(validator.validate(resolver, scope, null, from));
        }
        if (Finding.anyError(all)) {
            repo.setStatus(id, version, Status.APPROVED);
            resolver.invalidate();
            List<Finding> errors = Finding.errors(all);
            record(s.document(), "ACTIVATION_REFUSED", actor, errors.stream().map(Finding::code).distinct()
                    .collect(java.util.stream.Collectors.joining(",")));
            throw new ConfigException("ACTIVATION_REFUSED", "dependent chains have errors", errors);
        }
        changed(List.of(record(s.document(), "ACTIVATED", actor, null)));
        return all;
    }

    public void retire(String id, String version, String actor, String reason) {
        ProfileRepository.Stored s = require(id, version, Status.ACTIVE);
        repo.setStatus(id, version, Status.RETIRED);
        resolver.invalidate();
        changed(List.of(record(s.document(), "RETIRED", actor, reason)));
    }

    /** Loads shipped reference data (already approved at release time) as ACTIVE, recorded in the register. */
    public void importActive(List<ProfileDocument> docs, String actor) {
        List<ChangeRecord> imported = new ArrayList<>();
        for (ProfileDocument d : docs) {
            repo.save(d, Status.ACTIVE);
            imported.add(record(d, "IMPORTED", actor, d.source()));
        }
        resolver.invalidate();
        if (!imported.isEmpty()) {
            changed(imported);
        }
    }

    public List<ChangeRecord> history(String id) {
        return log.history(id);
    }

    private ProfileRepository.Stored require(String id, String version, Status... allowed) {
        ProfileRepository.Stored s = repo.get(id, version)
                .orElseThrow(() -> new ConfigException("NOT_FOUND", "no profile " + id + "@" + version));
        for (Status a : allowed) {
            if (s.status() == a) {
                return s;
            }
        }
        throw new ConfigException("STATE_INVALID", id + "@" + version + " is " + s.status());
    }

    private ChangeRecord record(ProfileDocument d, String action, String actor, String reason) {
        ChangeRecord r = new ChangeRecord(d.id(), d.version(), action, actor, clock.instant(), d.contentHash(), reason);
        log.append(r);
        return r;
    }

    /**
     * Told after a change to the profiles in force ({@code ACTIVATED}, {@code RETIRED}, {@code IMPORTED}) is stored and
     * the resolver's cache dropped, so a listener resolves the new chain (DEC-225-4: the tenants' own delegation
     * tables are re-deployed from it).
     */
    @FunctionalInterface
    public interface ActiveChangeListener {
        void changed(List<ChangeRecord> changes);
    }

    private final List<ActiveChangeListener> listeners = new CopyOnWriteArrayList<>();

    public void onActiveChange(ActiveChangeListener listener) {
        listeners.add(java.util.Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Each listener in turn. The change is already in force and recorded, so a listener that fails cannot undo it: its
     * failure is logged and the other listeners still run; a listener reports its own refusals (DEC-225-4 audits them).
     */
    private void changed(List<ChangeRecord> changes) {
        for (ActiveChangeListener l : listeners) {
            try {
                l.changed(List.copyOf(changes));
            } catch (RuntimeException e) {
                System.getLogger(ConfigAdminService.class.getName()).log(System.Logger.Level.ERROR,
                        "profile change listener failed after " + changes.size() + " change(s): " + e);
            }
        }
    }
}
