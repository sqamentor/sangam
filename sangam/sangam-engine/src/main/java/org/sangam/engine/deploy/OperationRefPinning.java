// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.persistence.deploy.Deployer;
import org.operaton.bpm.engine.impl.persistence.entity.DeploymentEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ResourceEntity;
import org.sangam.core.engine.RoutingPolicy;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * M-068 (W3-B REQUEST 12:23:11Z): a service or send task may bind an API contract operation with
 * {@code sangam:operationRef="contractId[@version]#operationId"}. A <b>new</b> deployment pins every ref to an exact
 * contract version, in the stored BPMN itself: the bound {@link Pinner} (the contract store, wired by the server)
 * validates the ref ({@code OperationRef.parse} and the stored contract) and returns {@code contractId@version#operationId},
 * the latest stored version when none was given; the resource bytes are rewritten in place before the deployment is
 * persisted, so a later re-parse, a restart or another node runs exactly the pinned operation. A ref the store refuses,
 * a ref on another element, or refs with no store bound refuse the whole deployment (fail closed: an unpinned ref
 * would drift with every new contract version). Stored deployments are never touched; the parse listener checks that
 * what runs is pinned ({@link #PINNED}).
 *
 * <p>A contract belongs to a package, so refs are accepted only in a package's own deploy, proven by the
 * {@link PackageDeploy} the package deploy path binds with {@link #asPackageDeploy}; a name a caller picks is never
 * proof. The package deploy identity (name prefix {@value #PACKAGE_NAME_PREFIX}, source {@value #PACKAGE_SOURCE}) is
 * reserved: a new deployment that claims it without the matching proof is refused on every deploy path.
 */
public final class OperationRefPinning {

    public static final String ATTRIBUTE = "operationRef";
    /** The pinned form: contract id, a version from 1, operation id. */
    public static final Pattern PINNED = Pattern.compile("[^@#\\s]{1,120}@[1-9][0-9]{0,8}#[^#\\s]{1,255}");
    static final Set<String> ELEMENTS = Set.of("serviceTask", "sendTask");

    /** The deployment name prefix of a package's own deploy: {@code pkg:<key>:<hash>}. Reserved. */
    public static final String PACKAGE_NAME_PREFIX = "pkg:";
    /** The deployment source of a package's own deploy. Reserved. */
    public static final String PACKAGE_SOURCE = "sangam-package";

    /**
     * Proof that the deployment running on this thread is a package's own deploy (integrator ruling on the forged
     * package deployment): set only by the package deploy path through {@link #asPackageDeploy}, never from a request.
     */
    public record PackageDeploy(String tenantId, String packageKey, String packageHash) {
        public PackageDeploy {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("a package deploy needs a tenant");
            }
            if (packageKey == null || packageKey.isEmpty() || packageKey.indexOf(':') >= 0) {
                throw new IllegalArgumentException("invalid package key " + packageKey);
            }
            if (packageHash == null || packageHash.isEmpty()) {
                throw new IllegalArgumentException("a package deploy needs the package hash");
            }
        }

        /** The engine deployment name of this package deploy. */
        public String deploymentName() {
            return PACKAGE_NAME_PREFIX + packageKey + ":" + packageHash;
        }
    }

    private static final ThreadLocal<PackageDeploy> PACKAGE_DEPLOY = new ThreadLocal<>();

    /** Runs {@code work} (the package's own engine deploy, on this thread) with {@code proof} bound, then unbinds it. */
    public static <T> T asPackageDeploy(PackageDeploy proof, java.util.function.Supplier<T> work) {
        java.util.Objects.requireNonNull(proof, "proof");
        PackageDeploy outer = PACKAGE_DEPLOY.get();
        PACKAGE_DEPLOY.set(proof);
        try {
            return work.get();
        } finally {
            if (outer == null) {
                PACKAGE_DEPLOY.remove();
            } else {
                PACKAGE_DEPLOY.set(outer);
            }
        }
    }

    /** True when a deployment name or source claims the reserved package deploy identity. */
    public static boolean claimsPackageIdentity(String deploymentName, String source) {
        return (deploymentName != null && deploymentName.startsWith(PACKAGE_NAME_PREFIX)) || PACKAGE_SOURCE.equals(source);
    }

    /** The bound proof when it is for exactly this deployment (name and tenant), else null. */
    static PackageDeploy proofFor(DeploymentEntity deployment) {
        PackageDeploy p = PACKAGE_DEPLOY.get();
        return p != null && p.deploymentName().equals(deployment.getName()) && p.tenantId().equals(deployment.getTenantId())
                ? p : null;
    }

    /**
     * Resolves a ref of a package's own deploy to its pinned form; throws {@link IllegalArgumentException} when it
     * cannot. Called only with the proof of that deploy.
     */
    @FunctionalInterface
    public interface Pinner {
        String pin(PackageDeploy packageDeploy, String ref);
    }

    private OperationRefPinning() {
    }

    /** The distinct {@code sangam:operationRef} values of a BPMN document, in document order. */
    static List<String> refs(Document doc) {
        List<String> out = new ArrayList<>();
        NodeList all = doc.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            Attr a = e.getAttributeNodeNS(RoutingPolicy.NS_URI, ATTRIBUTE);
            if (a == null) {
                continue;
            }
            if (!ELEMENTS.contains(e.getLocalName())) {
                throw new ProcessEngineException("M-068: sangam:operationRef is allowed on a service or send task only, found"
                        + " on " + e.getLocalName() + " '" + e.getAttribute("id") + "'");
            }
            if (!out.contains(a.getValue())) {
                out.add(a.getValue());
            }
        }
        return out;
    }

    /**
     * Replaces each ref value in the raw text (any namespace prefix, either quote), keeping every other byte. Fails when
     * a value cannot be found literally (for example written with character references).
     */
    static byte[] rewrite(byte[] xml, Map<String, String> pins) {
        String s = new String(xml, StandardCharsets.UTF_8);
        for (Map.Entry<String, String> p : pins.entrySet()) {
            if (p.getKey().equals(p.getValue())) {
                continue;
            }
            Matcher m = Pattern.compile("(:" + ATTRIBUTE + "\\s*=\\s*)([\"'])" + Pattern.quote(p.getKey()) + "\\2").matcher(s);
            if (!m.find()) {
                throw new ProcessEngineException("M-068: cannot pin sangam:operationRef '" + p.getKey()
                        + "': write it as plain text (no character references)");
            }
            s = m.replaceAll("$1$2" + Matcher.quoteReplacement(p.getValue()) + "$2");
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Pre-deployer: pins the refs of a new deployment, or refuses it. */
    public static final class DeployRule implements Deployer {
        private final Pinner pinner;

        /** @param pinner the contract store's pinning, or null when no store is bound */
        public DeployRule(Pinner pinner) {
            this.pinner = pinner;
        }

        @Override
        public void deploy(DeploymentEntity deployment) {
            if (!deployment.isNew()) {
                return; // stored deployments were pinned when they were deployed
            }
            PackageDeploy proof = proofFor(deployment);
            if (proof == null && claimsPackageIdentity(deployment.getName(), deployment.getSource())) {
                throw new ProcessEngineException("M-068: 422 MODEL_INVALID: the deployment name prefix "
                        + PACKAGE_NAME_PREFIX + " and the source " + PACKAGE_SOURCE + " are reserved for a package's own"
                        + " deploy (/packages); deployment '" + deployment.getName() + "' is not one");
            }
            if (deployment.getResources() == null) {
                return;
            }
            for (ResourceEntity r : deployment.getResources().values()) {
                if (r.getName() == null || !(r.getName().endsWith(".bpmn") || r.getName().endsWith(".bpmn20.xml"))
                        || r.getBytes() == null) {
                    continue;
                }
                pin(deployment, r, proof);
            }
        }

        private void pin(DeploymentEntity deployment, ResourceEntity r, PackageDeploy proof) {
            List<String> refs;
            try {
                refs = refs(ModelCodeAllowList.parse(r.getBytes()));
            } catch (ProcessEngineException e) {
                throw e;
            } catch (Exception e) {
                return; // unreadable XML is refused by the SL-160 rule and the BPMN parser with their own messages
            }
            if (refs.isEmpty()) {
                return;
            }
            if (proof == null) {
                throw new ProcessEngineException("M-068: 422 MODEL_INVALID: deployment '" + deployment.getName()
                        + "' binds contract operations " + refs + ", which only a package's own deploy (/packages) can do,"
                        + " because a contract belongs to a package");
            }
            if (pinner == null) {
                throw new ProcessEngineException("M-068: deployment '" + deployment.getName() + "' binds contract operations "
                        + refs + " but no API contract store is bound to pin them");
            }
            Map<String, String> pins = new LinkedHashMap<>();
            for (String ref : refs) {
                String pinned;
                try {
                    pinned = pinner.pin(proof, ref);
                } catch (IllegalArgumentException e) {
                    throw new ProcessEngineException("M-068: deployment '" + deployment.getName()
                            + "': sangam:operationRef '" + ref + "' refused: " + e.getMessage(), e);
                }
                if (pinned == null || !PINNED.matcher(pinned).matches()) {
                    throw new ProcessEngineException("M-068: the contract store returned '" + pinned + "' for '" + ref
                            + "', not contractId@version#operationId");
                }
                pins.put(ref, pinned);
            }
            r.setBytes(rewrite(r.getBytes(), pins));
        }
    }
}
