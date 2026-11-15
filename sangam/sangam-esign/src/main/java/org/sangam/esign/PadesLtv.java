// SPDX-License-Identifier: Apache-2.0
package org.sangam.esign;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXCertPathBuilderResult;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509CRLEntry;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.ocsp.OCSPResponse;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.IssuingDistributionPoint;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.SingleResp;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TimeStampToken;
import org.sangam.esign.PdfSignatureValidator.Ltv;

/**
 * M-080: PAdES long-term validation (ETSI EN 319 142-1 B-LT) of one signature from the material embedded in the PDF
 * only. Nothing is fetched: the evidence is the document security store ({@code /DSS} of the catalog: {@code /Certs},
 * {@code /OCSPs}, {@code /CRLs}, and the same arrays of every {@code /VRI} entry), the certificates in the signature's
 * CMS and in its RFC 3161 signature time-stamp. A location in the DSS proves nothing by itself; only an OCSP answer or a
 * CRL whose signature verifies against the certificate path counts, so the material of every VRI entry is pooled.
 *
 * <p>Rules, in this order; the first failure decides and is reported with a stable code:
 * <ol>
 *   <li>{@value #NO_TRUST_ANCHORS}: no trust anchors are configured (nothing can be judged);</li>
 *   <li>{@value #NO_TIMESTAMP}: the signature has no verified signature time-stamp (B-LT builds on B-T: the time of
 *       the signature must be proven, the signer's own claim is not used);</li>
 *   <li>{@value #NO_DSS} / {@value #DSS_INVALID}: no DSS, or an entry that cannot be read;</li>
 *   <li>{@value #CHAIN_INCOMPLETE}: the signer certificate, or the time-stamp authority's, does not chain to a
 *       configured anchor at the time-stamp time with the embedded certificates;</li>
 *   <li>for every certificate on both paths except the anchor: an authentic revocation answer from the DSS. An OCSP
 *       answer signed by the issuer, or by a responder the issuer delegated (id-kp-OCSPSigning, valid when the answer
 *       was produced, with id-pkix-ocsp-nocheck); a full CRL of the issuer, signed by it, for this certificate's scope
 *       (no delta CRL, no indirect or reason-partitioned CRL). Any authentic answer that says revoked is
 *       {@value #REVOKED}; else one that says good and was issued at or after the time-stamp time (less the clock
 *       skew), not after now plus the skew and not after the certificate expired is required: only answers older
 *       than the signature is {@value #REVOCATION_STALE}, none at all {@value #REVOCATION_MISSING}.</li>
 * </ol>
 * Document time-stamps (B-LTA) and Adobe's {@code adbe-revocationInfoArchival} attribute are not evaluated.
 */
final class PadesLtv {

    static final String VALID = "LTV_VALID";
    static final String NO_TRUST_ANCHORS = "LTV_NO_TRUST_ANCHORS";
    static final String NO_TIMESTAMP = "LTV_NO_TIMESTAMP";
    static final String NO_DSS = "LTV_NO_DSS";
    static final String DSS_INVALID = "LTV_DSS_INVALID";
    static final String CHAIN_INCOMPLETE = "LTV_CHAIN_INCOMPLETE";
    static final String REVOCATION_MISSING = "LTV_REVOCATION_MISSING";
    static final String REVOCATION_STALE = "LTV_REVOCATION_STALE";
    static final String REVOKED = "LTV_REVOKED";

    /** The value of {@code afterLastSignature} when only validation data (the DSS) was added after the last signature. */
    static final String VALIDATION_DATA_ONLY = "VALIDATION_DATA_ONLY";
    /** The prefix of {@code afterLastSignature} when anything else changed after the last signature. */
    static final String MODIFIED_AFTER_SIGNING = "MODIFIED_AFTER_SIGNING";

    /** Tolerance for the clocks of responders, CRL issuers and the TSA (the {@code SignaturePolicy} default). */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    /** More DSS entries than this is not a document this validator reads. */
    static final int MAX_ENTRIES = 1024;

    private static final COSName DSS = COSName.getPDFName("DSS");
    private static final COSName VRI = COSName.getPDFName("VRI");
    private static final COSName[] CERTS = {COSName.getPDFName("Certs"), COSName.getPDFName("Cert")};
    private static final COSName[] OCSPS = {COSName.getPDFName("OCSPs"), COSName.getPDFName("OCSP")};
    private static final COSName[] CRLS = {COSName.getPDFName("CRLs"), COSName.getPDFName("CRL")};

    private PadesLtv() {
    }


    /** The validation material of the DSS; {@code error} set when an entry could not be read; null DSS: none. */
    record Material(List<X509Certificate> certs, List<BasicOCSPResp> ocsps, List<X509CRL> crls, String error) {
    }

    /** The DSS of a loaded document, or null when it has none. */
    static Material material(PDDocument doc) {
        COSDictionary dss = doc.getDocumentCatalog().getCOSObject().getCOSDictionary(DSS);
        if (dss == null) {
            return null;
        }
        List<X509Certificate> certs = new ArrayList<>();
        List<BasicOCSPResp> ocsps = new ArrayList<>();
        List<X509CRL> crls = new ArrayList<>();
        try {
            List<COSDictionary> holders = new ArrayList<>();
            holders.add(dss);
            COSDictionary vri = dss.getCOSDictionary(VRI);
            if (vri != null) {
                for (COSName key : vri.keySet()) {
                    if (vri.getDictionaryObject(key) instanceof COSDictionary entry) {
                        holders.add(entry);
                    }
                }
            }
            Set<COSStream> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            CertificateFactory x509 = CertificateFactory.getInstance("X.509");
            for (COSDictionary h : holders) {
                for (byte[] b : streams(h, CERTS, seen)) {
                    certs.add((X509Certificate) x509.generateCertificate(new ByteArrayInputStream(b)));
                }
                for (byte[] b : streams(h, OCSPS, seen)) {
                    BasicOCSPResp basic = ocsp(b);
                    if (basic != null) {
                        ocsps.add(basic);
                    }
                }
                for (byte[] b : streams(h, CRLS, seen)) {
                    crls.add((X509CRL) x509.generateCRL(new ByteArrayInputStream(b)));
                }
            }

        } catch (Exception e) {
            return new Material(List.of(), List.of(), List.of(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return new Material(List.copyOf(certs), List.copyOf(ocsps), List.copyOf(crls), null);
    }

    private static List<byte[]> streams(COSDictionary holder, COSName[] names, Set<COSStream> seen) throws IOException {
        List<byte[]> out = new ArrayList<>();
        for (COSName name : names) {
            COSBase value = holder.getDictionaryObject(name);
            if (value == null) {
                continue;
            }
            if (!(value instanceof COSArray array)) {
                throw new IOException("/" + name.getName() + " is not an array");
            }
            for (int i = 0; i < array.size(); i++) {
                if (!(array.getObject(i) instanceof COSStream s)) {
                    throw new IOException("/" + name.getName() + " holds an entry that is not a stream");
                }
                if (seen.add(s)) {
                    if (seen.size() > MAX_ENTRIES) {
                        throw new IOException("more than " + MAX_ENTRIES + " DSS entries");
                    }
                    try (InputStream in = s.createInputStream()) {
                        out.add(in.readAllBytes());
                    }
                }
            }
        }
        return out;
    }

    /** An OCSPResponse (as ISO 32000-2 stores it) or a bare BasicOCSPResponse; null when it carries no status. */
    private static BasicOCSPResp ocsp(byte[] der) throws IOException {
        OCSPResp resp;
        try {
            resp = new OCSPResp(OCSPResponse.getInstance(der));
        } catch (RuntimeException e) {
            return new BasicOCSPResp(org.bouncycastle.asn1.ocsp.BasicOCSPResponse.getInstance(der));
        }
        if (resp.getStatus() != OCSPResp.SUCCESSFUL) {
            return null;
        }
        try {
            return resp.getResponseObject() instanceof BasicOCSPResp b ? b : null;
        } catch (org.bouncycastle.cert.ocsp.OCSPException e) {
            throw new IOException("unreadable OCSP response: " + e.getMessage(), e);
        }
    }

    /**
     * Long-term validation of one signature.
     *
     * @param material the DSS ({@link #material}); null when the document has none
     * @param tsValid  whether the signature time-stamp verified ({@link PdfSignatureValidator})
     */
    static Ltv check(Set<TrustAnchor> anchors, X509Certificate signer, Collection<X509Certificate> cmsCerts,
                        TimeStampToken token, Boolean tsValid, Material material, Instant now) {
        List<String> evidence = new ArrayList<>();
        if (anchors.isEmpty()) {
            return new Ltv(NO_TRUST_ANCHORS, "no trust anchors are configured", evidence);
        }
        if (token == null || !Boolean.TRUE.equals(tsValid)) {
            return new Ltv(NO_TIMESTAMP, "the signature has no verified signature time-stamp", evidence);
        }
        if (material == null) {
            return new Ltv(NO_DSS, "the document has no document security store", evidence);
        }
        if (material.error() != null) {
            return new Ltv(DSS_INVALID, material.error(), evidence);
        }
        Instant at = token.getTimeStampInfo().getGenTime().toInstant();
        List<X509Certificate> tsaCerts = new ArrayList<>();
        X509Certificate tsa;
        try {
            JcaX509CertificateConverter conv = new JcaX509CertificateConverter();
            @SuppressWarnings("unchecked")
            Collection<X509CertificateHolder> all = token.getCertificates().getMatches(null);
            for (X509CertificateHolder h : all) {
                tsaCerts.add(conv.getCertificate(h));
            }
            tsa = tsaCerts.stream().filter(c -> token.getSID().match(holder(c))).findFirst().orElse(null);
        } catch (Exception e) {
            return new Ltv(CHAIN_INCOMPLETE, "the time-stamp certificates cannot be read: " + e.getMessage(), evidence);
        }
        if (tsa == null) {
            return new Ltv(CHAIN_INCOMPLETE, "the time-stamp authority certificate is not in the token", evidence);
        }
        Set<X509Certificate> pool = new LinkedHashSet<>(cmsCerts);
        pool.addAll(tsaCerts);
        pool.addAll(material.certs());
        for (X509Certificate target : List.of(signer, tsa)) {
            String role = target == signer ? "signer" : "time-stamp authority";
            List<X509Certificate> path;
            try {
                path = path(anchors, target, pool, at);
            } catch (Exception e) {
                return new Ltv(CHAIN_INCOMPLETE, "the " + role + " certificate does not chain to a configured trust"
                        + " anchor at " + at + " with the embedded certificates", evidence);
            }
            for (int i = 0; i < path.size() - 1; i++) {
                Ltv r = revocation(path.get(i), path.get(i + 1), material, at, now, evidence);
                if (r != null) {
                    return r;
                }
            }
        }
        return new Ltv(VALID, null, evidence);
    }

    /** The path from {@code target} to an anchor (inclusive of the anchor certificate) at {@code at}. */
    private static List<X509Certificate> path(Set<TrustAnchor> anchors, X509Certificate target,
                                              Collection<X509Certificate> pool, Instant at) throws Exception {
        X509CertSelector sel = new X509CertSelector();
        sel.setCertificate(target);
        PKIXBuilderParameters params = new PKIXBuilderParameters(anchors, sel);
        params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(pool)));
        // revocation is judged below from the embedded material only (nothing is fetched)
        params.setRevocationEnabled(false);
        params.setDate(Date.from(at));
        PKIXCertPathBuilderResult r = (PKIXCertPathBuilderResult) CertPathBuilder.getInstance("PKIX").build(params);
        X509Certificate anchor = r.getTrustAnchor().getTrustedCert();
        if (anchor == null) {
            throw new IllegalStateException("the trust anchor has no certificate");
        }
        List<X509Certificate> out = new ArrayList<>();
        for (var c : r.getCertPath().getCertificates()) {
            out.add((X509Certificate) c);
        }
        out.add(anchor);
        return out;
    }

    /** Null when {@code cert} has a fresh, authentic good answer; else the failure. */
    private static Ltv revocation(X509Certificate cert, X509Certificate issuer, Material m, Instant at, Instant now,
                                     List<String> evidence) {
        String who = cert.getSubjectX500Principal().getName();
        String good = null;
        boolean stale = false;
        DigestCalculatorProvider digests;
        X509CertificateHolder issuerHolder;
        try {
            digests = new JcaDigestCalculatorProviderBuilder().build();
            issuerHolder = new JcaX509CertificateHolder(issuer);
        } catch (Exception e) {
            return new Ltv(REVOCATION_MISSING, who + ": " + e.getMessage(), evidence);
        }
        for (BasicOCSPResp b : m.ocsps()) {
            for (SingleResp s : b.getResponses()) {
                try {
                    if (!s.getCertID().getSerialNumber().equals(cert.getSerialNumber())
                            || !s.getCertID().matchesIssuer(issuerHolder, digests)
                            || !authentic(b, issuer, m.certs())) {
                        continue;
                    }
                } catch (Exception e) {
                    continue;
                }
                Instant thisUpdate = s.getThisUpdate().toInstant();
                CertificateStatus st = s.getCertStatus();
                if (st instanceof RevokedStatus rs) {
                    return new Ltv(REVOKED, who + " is revoked since " + rs.getRevocationTime().toInstant()
                            + " (OCSP in the DSS)", evidence);
                }
                if (st == CertificateStatus.GOOD) {
                    if (fresh(thisUpdate, cert, at, now)) {
                        good = "OCSP good for " + who + " at " + thisUpdate;
                    } else {
                        stale = true;
                    }
                }
            }
        }
        for (X509CRL crl : m.crls()) {
            if (!usable(crl, cert, issuer)) {
                continue;
            }
            X509CRLEntry e = crl.getRevokedCertificate(cert);
            if (e != null) {
                return new Ltv(REVOKED, who + " is revoked since " + e.getRevocationDate().toInstant()
                        + " (CRL in the DSS)", evidence);
            }
            Instant thisUpdate = crl.getThisUpdate().toInstant();
            if (fresh(thisUpdate, cert, at, now)) {
                good = good != null ? good : "CRL of " + issuer.getSubjectX500Principal().getName() + " at " + thisUpdate
                        + " does not list " + who;
            } else {
                stale = true;
            }
        }
        if (good != null) {
            evidence.add(good);
            return null;
        }
        return stale
                ? new Ltv(REVOCATION_STALE, "the embedded revocation answers for " + who
                        + " were all issued before the signature time-stamp " + at, evidence)
                : new Ltv(REVOCATION_MISSING, "no authentic revocation answer for " + who + " in the DSS", evidence);
    }

    /** Issued after the signature existed (less the skew), not in the future, and while the certificate was valid. */
    private static boolean fresh(Instant thisUpdate, X509Certificate cert, Instant at, Instant now) {
        return !thisUpdate.isBefore(at.minus(CLOCK_SKEW)) && !thisUpdate.isAfter(now.plus(CLOCK_SKEW))
                && !thisUpdate.isAfter(cert.getNotAfter().toInstant());
    }

    /** Signed by the issuer, or by a responder the issuer delegated (valid when the answer was produced, no-check). */
    private static boolean authentic(BasicOCSPResp b, X509Certificate issuer, List<X509Certificate> dssCerts)
            throws Exception {
        if (b.isSignatureValid(new JcaContentVerifierProviderBuilder().build(issuer))) {
            return true;
        }
        List<X509Certificate> candidates = new ArrayList<>(dssCerts);
        JcaX509CertificateConverter conv = new JcaX509CertificateConverter();
        if (b.getCerts() != null) {
            for (X509CertificateHolder h : b.getCerts()) {
                candidates.add(conv.getCertificate(h));
            }
        }
        Date produced = b.getProducedAt();
        for (X509Certificate c : candidates) {
            List<String> eku = c.getExtendedKeyUsage();
            if (eku == null || !eku.contains(KeyPurposeId.id_kp_OCSPSigning.getId())
                    || !c.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())
                    || c.getExtensionValue(OCSPObjectIdentifiers.id_pkix_ocsp_nocheck.getId()) == null) {
                continue;
            }
            try {
                c.verify(issuer.getPublicKey());
                c.checkValidity(produced);
            } catch (Exception e) {
                continue;
            }
            if (b.isSignatureValid(new JcaContentVerifierProviderBuilder().build(c))) {
                return true;
            }
        }
        return false;
    }

    /** A full CRL of the issuer, signed by it, whose scope includes {@code cert}. */
    static boolean usable(X509CRL crl, X509Certificate cert, X509Certificate issuer) {
        if (!crl.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) {
            return false;
        }
        try {
            crl.verify(issuer.getPublicKey());
        } catch (Exception e) {
            return false;
        }
        Set<String> critical = crl.getCriticalExtensionOIDs() == null ? Set.of() : crl.getCriticalExtensionOIDs();
        if (crl.getExtensionValue(Extension.deltaCRLIndicator.getId()) != null) {
            return false;
        }
        for (String oid : critical) {
            if (!oid.equals(Extension.issuingDistributionPoint.getId())) {
                return false;
            }
        }
        byte[] idpRaw = crl.getExtensionValue(Extension.issuingDistributionPoint.getId());
        if (idpRaw == null) {
            return true;
        }
        try {
            IssuingDistributionPoint idp = IssuingDistributionPoint.getInstance(
                    ASN1OctetString.getInstance(idpRaw).getOctets());
            boolean ca = cert.getBasicConstraints() >= 0;
            if (idp.isIndirectCRL() || idp.getOnlySomeReasons() != null || idp.onlyContainsAttributeCerts()
                    || (idp.onlyContainsUserCerts() && ca) || (idp.onlyContainsCACerts() && !ca)) {
                return false;
            }
            DistributionPointName dp = idp.getDistributionPoint();
            if (dp == null) {
                return true;
            }
            if (dp.getType() != DistributionPointName.FULL_NAME) {
                return false;
            }
            List<GeneralName> scope = List.of(GeneralNames.getInstance(dp.getName()).getNames());
            byte[] cdp = cert.getExtensionValue(Extension.cRLDistributionPoints.getId());
            if (cdp == null) {
                return false;
            }
            for (DistributionPoint p : CRLDistPoint.getInstance(ASN1OctetString.getInstance(cdp).getOctets())
                    .getDistributionPoints()) {
                DistributionPointName n = p.getDistributionPoint();
                if (n != null && n.getType() == DistributionPointName.FULL_NAME) {
                    for (GeneralName g : GeneralNames.getInstance(n.getName()).getNames()) {
                        if (scope.contains(g)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static X509CertificateHolder holder(X509Certificate c) {
        try {
            return new JcaX509CertificateHolder(c);
        } catch (java.security.cert.CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * What the bytes after the last signature's byte range changed: null when there are none,
     * {@value #VALIDATION_DATA_ONLY} when they are incremental updates whose only effect is the catalog's {@code /DSS}
     * (every object of the signed revision is unchanged, the catalog differs only in {@code /DSS}, every other object is
     * new and so reachable only through it), else {@value #MODIFIED_AFTER_SIGNING} with what changed.
     */
    static String afterLastSignature(byte[] pdf, long signedEnd) {
        if (signedEnd >= pdf.length) {
            return null;
        }
        if (signedEnd <= 0) {
            return MODIFIED_AFTER_SIGNING + ": the signed revision is empty";
        }
        try (PDDocument signed = Loader.loadPDF(java.util.Arrays.copyOf(pdf, (int) signedEnd));
             PDDocument full = Loader.loadPDF(pdf)) {
            COSBase signedRoot = signed.getDocument().getTrailer().getItem(COSName.ROOT);
            COSBase fullRoot = full.getDocument().getTrailer().getItem(COSName.ROOT);
            if (!(signedRoot instanceof COSObject sr) || !(fullRoot instanceof COSObject fr)
                    || sr.getObjectNumber() != fr.getObjectNumber() || sr.getGenerationNumber() != fr.getGenerationNumber()) {
                return MODIFIED_AFTER_SIGNING + ": the document catalog was replaced";
            }
            Map<COSObjectKey, Long> before = signed.getDocument().getXrefTable();
            Map<COSObjectKey, Long> after = full.getDocument().getXrefTable();
            for (Map.Entry<COSObjectKey, Long> e : before.entrySet()) {
                COSObjectKey k = e.getKey();
                if (k.getNumber() == sr.getObjectNumber() && k.getGeneration() == sr.getGenerationNumber()) {
                    continue;
                }
                if (!Objects.equals(e.getValue(), after.get(k))) {
                    return MODIFIED_AFTER_SIGNING + ": object " + k.getNumber() + " " + k.getGeneration()
                            + " was changed or removed";
                }
            }
            COSDictionary c0 = signed.getDocumentCatalog().getCOSObject();
            COSDictionary c1 = full.getDocumentCatalog().getCOSObject();
            Set<COSName> keys = new java.util.HashSet<>(c0.keySet());
            keys.addAll(c1.keySet());
            keys.remove(DSS);
            for (COSName k : keys) {
                if (!same(c0.getItem(k), c1.getItem(k), 0)) {
                    return MODIFIED_AFTER_SIGNING + ": the catalog entry /" + k.getName() + " was changed";
                }
            }
            if (c1.getItem(DSS) == null || same(c0.getItem(DSS), c1.getItem(DSS), 0)) {
                return MODIFIED_AFTER_SIGNING + ": bytes were appended that add no validation data";
            }
            return VALIDATION_DATA_ONLY;
        } catch (IOException | RuntimeException e) {
            return MODIFIED_AFTER_SIGNING + ": the appended revision cannot be read: " + e.getMessage();
        }
    }

    /** Structural equality of two direct values; indirect references are equal when they name the same object. */
    private static boolean same(COSBase a, COSBase b, int depth) {
        if (a == null || b == null) {
            return a == b;
        }
        if (depth > 32) {
            return false;
        }
        if (a instanceof COSObject || b instanceof COSObject) {
            return a instanceof COSObject x && b instanceof COSObject y && x.getObjectNumber() == y.getObjectNumber()
                    && x.getGenerationNumber() == y.getGenerationNumber();
        }
        if (a instanceof COSStream || b instanceof COSStream) {
            return false;
        }
        if (a instanceof COSDictionary da && b instanceof COSDictionary db) {
            if (!da.keySet().equals(db.keySet())) {
                return false;
            }
            for (COSName k : da.keySet()) {
                if (!same(da.getItem(k), db.getItem(k), depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof COSArray xa && b instanceof COSArray xb) {
            if (xa.size() != xb.size()) {
                return false;
            }
            for (int i = 0; i < xa.size(); i++) {
                if (!same(xa.get(i), xb.get(i), depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        return a.getClass() == b.getClass() && a.toString().equals(b.toString());
    }
}
