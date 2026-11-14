// SPDX-License-Identifier: Apache-2.0
package org.sangam.esign;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.sangam.integration.signature.DscSigning;

/**
 * Owner decision 165: {@link DscSigning} with PDFBox and Bouncy Castle. {@link #prepare} is
 * {@link PadesSigning#prepare} ({@code ETSI.CAdES.detached}) followed by {@link DscCms#toSign} for the same signing
 * time; {@link #complete} is {@link DscCms#assemble} (the token's signature is checked first) followed by
 * {@link PadesSigning#embed} (the prepared digest is checked again).
 */
public final class DscSigningProvider implements DscSigning {

    /** The ServiceLoader entry. */
    public static final class Factory implements DscSigning.Factory {
        @Override
        public DscSigning create() {
            return new DscSigningProvider();
        }
    }

    static final PadesSigning.Profile PROFILE = PadesSigning.Profile.CADES_DETACHED;

    @Override
    public Prepared prepare(byte[] pdf, X509Certificate signer, Instant signingTime) {
        Objects.requireNonNull(signer, "signer");
        Objects.requireNonNull(signingTime, "signingTime");
        try {
            PadesSigning.Prepared p = PadesSigning.prepare(pdf, PROFILE, null, signingTime, 0);
            DscCms.ToSign t = DscCms.toSign(p.digestSha256(), signer, signingTime);
            return new Prepared(p.pdf(), p.digestSha256(), t.signedAttributes(), t.signerCertificateSha256(),
                    t.signingTime());
        } catch (PadesSigning.SigningException e) {
            throw new Refused(e.code(), e.getMessage(), e);
        }
    }

    @Override
    public byte[] complete(Prepared prepared, X509Certificate signer, List<X509Certificate> chain, String algorithm,
                           byte[] signature) {
        Objects.requireNonNull(prepared, "prepared");
        DscCms.ToSign t = new DscCms.ToSign(prepared.signedAttributes(), prepared.preparedDigestSha256(),
                prepared.signerCertificateSha256(), prepared.signingTime());
        try {
            byte[] cms = DscCms.assemble(t, signer, chain == null ? List.of() : chain, algorithm, signature);
            return PadesSigning.embed(prepared.preparedPdf(), prepared.preparedDigestSha256(), PROFILE, cms);
        } catch (PadesSigning.SigningException e) {
            throw new Refused(e.code(), e.getMessage(), e);
        }
    }
}
