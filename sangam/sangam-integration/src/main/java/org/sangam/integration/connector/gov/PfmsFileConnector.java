// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.file.FileTransport;
import org.sangam.integration.sim.SimulatorProfile;
import org.sangam.integration.sim.pfms.PfmsFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * PFMS / IFMIS file connector (R58: file-based over SFTP, signed payment files out, status files in; IX.4 P1 and P3
 * disbursement; IX.7d SSID model). Formats are those of {@link PfmsFiles} (the real PFMS schemas are ON-REQUEST, RQ-1,
 * and only change that mapping); the transport is any {@link FileTransport} (SFTP in production, a local directory in
 * the simulator profile).
 *
 * <p><b>Operations.</b>
 * <ul>
 *   <li>{@code pay}: one payment line from {@code billId}, {@code amount} (decimal string, R33) and
 *       {@code beneficiary{name, accountNo, ifsc}}; the line's operationId is the request's operationId (R36).</li>
 *   <li>{@code payBatch}: {@code lines} = list of the same shape, each with an optional {@code operationId}
 *       (default {@code <operationId>-<n>}).</li>
 * </ul>
 * Both accept an optional {@code batchId}; the default is derived from the operationId (its first 16 hex digits,
 * without dashes), so a repeat of the same operation produces the same file name {@code PAY_<ssid>_<batchId>.csv}.
 *
 * <p><b>Capability class (a)</b> (A-2): before writing, the file name is looked up in {@code outbound},
 * {@code processed} and {@code rejected}; a file that exists already is answered {@code Success(duplicate=true)}
 * without a second write, so a repeat never pays twice (R58 "no duplicate payment"). {@link #lookup} reads the status
 * files.
 *
 * <p><b>Detached signature.</b> With {@link Signing.Mode#HMAC_SHA256} or {@link Signing.Mode#RSA_SHA256} a file
 * {@code <paymentFile>.sig} with the Base64 signature over the exact payment-file bytes is written <i>before</i> the
 * payment file, so the payment file is never visible without its signature. Keys come by reference (R73).
 */
public final class PfmsFileConnector implements SangamConnector {

    public static final String TYPE = "pfms";
    public static final String OP_PAY = "pay";
    public static final String OP_PAY_BATCH = "payBatch";
    public static final String OUTBOUND = "outbound";
    public static final String INBOUND = "inbound";
    public static final String PROCESSED = "processed";
    public static final String REJECTED = "rejected";
    public static final String ARCHIVE = "archive";
    public static final String SIGNATURE_SUFFIX = ".sig";

    static final String DUPLICATE_BATCH = "DUPLICATE_BATCH";
    static final String DUPLICATE_OPERATION_ID = "DUPLICATE_OPERATION_ID";

    private static final Logger LOG = LoggerFactory.getLogger(PfmsFileConnector.class);
    private static final Pattern BATCH_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern SSID = Pattern.compile("[A-Za-z0-9-]{1,32}");
    private static final Pattern IFSC = Pattern.compile("[A-Z]{4}0[A-Z0-9]{6}");

    /** Detached-signature settings of the payment files. */
    public record Signing(Mode mode, String hmacKeyRef, Path keyStore, String keyStorePasswordRef, String keyAlias) {

        public enum Mode {
            /** No signature file. */
            NONE,
            /** Base64 HMAC-SHA256 with a shared key ({@code hmacKeyRef}). */
            HMAC_SHA256,
            /** Base64 SHA256withRSA with the private key of a PKCS#12 key store. */
            RSA_SHA256
        }

        public Signing {
            mode = mode == null ? Mode.NONE : mode;
            if (mode == Mode.HMAC_SHA256) {
                Objects.requireNonNull(hmacKeyRef, "hmacKeyRef");
            }
            if (mode == Mode.RSA_SHA256) {
                Objects.requireNonNull(keyStore, "keyStore");
                Objects.requireNonNull(keyStorePasswordRef, "keyStorePasswordRef");
            }
        }

        public static Signing none() {
            return new Signing(Mode.NONE, null, null, null, null);
        }

        public static Signing hmac(String keyRef) {
            return new Signing(Mode.HMAC_SHA256, keyRef, null, null, null);
        }

        /** RSA signing with {@code alias} of a PKCS#12 store; a null alias takes the first key entry. */
        public static Signing rsa(Path pkcs12, String passwordRef, String alias) {
            return new Signing(Mode.RSA_SHA256, null, pkcs12, passwordRef, alias);
        }
    }

    /**
     * Configuration of one PFMS / IFMIS file exchange.
     *
     * @param id         connector instance id
     * @param transport  file transport (SFTP or local directory) with the logical directories outbound, inbound,
     *                   processed, rejected, archive
     * @param ssid       PFMS source system id (IX.7d)
     * @param schemeCode scheme code written in the file header
     * @param signing    detached-signature settings; null = none
     * @param secrets    resolver for key references
     * @param profile    live or simulator
     */
    public record Config(String id, FileTransport transport, String ssid, String schemeCode, Signing signing,
                         SecretResolver secrets, ConnectorProfile profile) {
        public Config {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(transport, "transport");
            Objects.requireNonNull(ssid, "ssid");
            Objects.requireNonNull(schemeCode, "schemeCode");
            if (!SSID.matcher(ssid).matches()) {
                throw new IllegalArgumentException("ssid must match " + SSID.pattern() + ": " + ssid);
            }
            if (!safeField(schemeCode)) {
                throw new IllegalArgumentException("schemeCode must not contain commas or line breaks");
            }
            signing = signing == null ? Signing.none() : signing;
            secrets = secrets == null ? SecretResolver.fromEnvironment() : secrets;
            profile = profile == null ? ConnectorProfile.LIVE : profile;
        }
    }

    private final Config config;
    private final ConnectorDescriptor descriptor;
    private volatile PrivateKey rsaKey;

    public PfmsFileConnector(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.descriptor = new ConnectorDescriptor(config.id(), TYPE, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                List.of(OP_PAY, OP_PAY_BATCH), config.profile());
    }

    /** Wired to the PFMS simulator of {@code sims} (started), HMAC-signed files. */
    public static PfmsFileConnector simulator(SimulatorProfile sims) {
        return simulator(sims, Signing.hmac(SimulatorProfile.PFMS_SIGNING_KEY));
    }

    public static PfmsFileConnector simulator(SimulatorProfile sims, Signing signing) {
        sims.pfms();
        return new PfmsFileConnector(new Config("pfms-sim", sims.pfmsTransport(), SimulatorProfile.PFMS_SSID,
                SimulatorProfile.PFMS_SCHEME, signing, SecretResolver.of(Map.of()), ConnectorProfile.SIMULATOR));
    }

    public Config config() {
        return config;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    /** Default batch id of an operation: the first 16 hex digits of the operationId, no dashes (no underscores). */
    public static String defaultBatchId(java.util.UUID operationId) {
        return operationId.toString().replace("-", "").substring(0, 16);
    }

    /** Payment file name of a batch. */
    public String paymentFileName(String batchId) {
        return "PAY_" + config.ssid() + "_" + batchId + ".csv";
    }

    // ---- invoke --------------------------------------------------------------------------------

    @Override
    public ConnectorResult invoke(ConnectorRequest request) {
        PfmsFiles.PaymentFile file;
        try {
            file = switch (request.operation()) {
                case OP_PAY -> new PfmsFiles.PaymentFile(config.ssid(), config.schemeCode(), batchId(request),
                        List.of(line(request.operationId().toString(), request.input())));
                case OP_PAY_BATCH -> new PfmsFiles.PaymentFile(config.ssid(), config.schemeCode(), batchId(request),
                        batchLines(request));
                default -> throw new UnsupportedOperationException(request.operation());
            };
        } catch (UnsupportedOperationException e) {
            return ConnectorResult.BusinessFailure.of("UNSUPPORTED_OPERATION",
                    "PFMS supports '" + OP_PAY + "' and '" + OP_PAY_BATCH + "', got '" + request.operation() + "'");
        } catch (IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_INPUT", e.getMessage());
        }
        String name = file.fileName();
        try {
            Optional<String> where = locate(name);
            if (where.isPresent()) {
                return new ConnectorResult.Success(output(file, where.get()), name, true);
            }
        } catch (IOException e) {
            return ConnectorResult.TransientFailure.noEffect("PFMS: cannot check for an existing file: " + e.getMessage());
        }
        byte[] content;
        try {
            content = file.toCsv().getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_INPUT", e.getMessage());
        }
        if (config.signing().mode() != Signing.Mode.NONE) {
            try {
                byte[] sig = sign(content);
                config.transport().write(OUTBOUND, name + SIGNATURE_SUFFIX, sig);
            } catch (GeneralSecurityException | IOException | RuntimeException e) {
                LOG.warn("PFMS signature for {} failed", name, e);
                return ConnectorResult.TransientFailure.noEffect("PFMS: signature not written: " + e.getMessage());
            }
        }
        try {
            config.transport().write(OUTBOUND, name, content);
        } catch (IOException e) {
            // The atomic write may or may not have completed; a repeat is harmless (existence check above).
            return new ConnectorResult.TransientFailure("PFMS: payment file write failed: " + e.getMessage(), false, null);
        }
        return new ConnectorResult.Success(output(file, OUTBOUND), name, false);
    }

    private Map<String, Object> output(PfmsFiles.PaymentFile file, String location) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", file.batchId());
        out.put("fileName", file.fileName());
        out.put("location", location);
        out.put("recordCount", file.lines().size());
        out.put("totalAmount", PfmsFiles.money(file.total()));
        out.put("signed", config.signing().mode() != Signing.Mode.NONE);
        out.put("operationIds", file.lines().stream().map(PfmsFiles.PaymentLine::operationId).toList());
        out.put("status", "SUBMITTED");
        return out;
    }

    private String batchId(ConnectorRequest request) {
        String b = request.str("batchId", defaultBatchId(request.operationId()));
        if (!BATCH_ID.matcher(b).matches()) {
            throw new IllegalArgumentException("batchId must match " + BATCH_ID.pattern() + " (no underscores): " + b);
        }
        return b;
    }

    @SuppressWarnings("unchecked")
    private static List<PfmsFiles.PaymentLine> batchLines(ConnectorRequest request) {
        Object raw = request.input().get("lines");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("payBatch needs a non-empty 'lines' list");
        }
        List<PfmsFiles.PaymentLine> out = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("lines[" + i + "] must be an object");
            }
            Map<String, Object> lm = (Map<String, Object>) m;
            String op = str(lm, "operationId");
            String opId = op == null ? request.operationId() + "-" + (i + 1) : op;
            if (!ids.add(opId)) {
                throw new IllegalArgumentException("duplicate line operationId " + opId);
            }
            out.add(line(opId, lm));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static PfmsFiles.PaymentLine line(String operationId, Map<String, Object> in) {
        String billId = str(in, "billId");
        String amountText = str(in, "amount");
        Object b = in.get("beneficiary");
        Map<String, Object> ben = b instanceof Map<?, ?> m ? (Map<String, Object>) m : in;
        String name = str(ben, b instanceof Map<?, ?> ? "name" : "beneficiaryName");
        String accountNo = str(ben, "accountNo");
        String ifsc = str(ben, "ifsc");
        if (billId == null || amountText == null || name == null || accountNo == null || ifsc == null) {
            throw new IllegalArgumentException(
                    "billId, amount, beneficiary.name, beneficiary.accountNo and beneficiary.ifsc are required");
        }
        for (String f : new String[]{operationId, billId, name, accountNo, ifsc}) {
            if (!safeField(f)) {
                throw new IllegalArgumentException("fields must not contain commas or line breaks: " + f);
            }
        }
        if (!IFSC.matcher(ifsc).matches()) {
            throw new IllegalArgumentException("not a valid IFSC: " + ifsc);
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(amountText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("amount must be a decimal string: " + amountText);
        }
        if (amount.signum() <= 0 || amount.scale() > 2) {
            throw new IllegalArgumentException("amount must be positive with at most 2 decimals: " + amountText);
        }
        return new PfmsFiles.PaymentLine(operationId, billId, name, accountNo, ifsc, amount);
    }

    /** Directory holding {@code name}: outbound, processed or rejected (in the order files move). */
    private Optional<String> locate(String name) throws IOException {
        for (String dir : List.of(OUTBOUND, PROCESSED, REJECTED)) {
            if (config.transport().exists(dir, name)) {
                return Optional.of(dir);
            }
        }
        return Optional.empty();
    }

    // ---- signatures ----------------------------------------------------------------------------

    /** Base64 detached signature over {@code content} with the configured mode. */
    byte[] sign(byte[] content) throws GeneralSecurityException, IOException {
        Signing s = config.signing();
        byte[] sig = switch (s.mode()) {
            case HMAC_SHA256 -> {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(config.secrets().require(s.hmacKeyRef()).getBytes(StandardCharsets.UTF_8),
                        "HmacSHA256"));
                yield mac.doFinal(content);
            }
            case RSA_SHA256 -> {
                Signature signer = Signature.getInstance("SHA256withRSA");
                signer.initSign(rsaKey());
                signer.update(content);
                yield signer.sign();
            }
            case NONE -> throw new IllegalStateException("signing is off");
        };
        return Base64.getEncoder().encode(sig);
    }

    private PrivateKey rsaKey() throws GeneralSecurityException, IOException {
        PrivateKey k = rsaKey;
        if (k != null) {
            return k;
        }
        Signing s = config.signing();
        char[] pw = config.secrets().require(s.keyStorePasswordRef()).toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(s.keyStore())) {
            ks.load(in, pw);
        }
        String alias = s.keyAlias();
        if (alias == null) {
            for (Enumeration<String> e = ks.aliases(); e.hasMoreElements(); ) {
                String a = e.nextElement();
                if (ks.isKeyEntry(a)) {
                    alias = a;
                    break;
                }
            }
        }
        if (alias == null) {
            throw new GeneralSecurityException("no private key entry in " + s.keyStore());
        }
        Key key = ks.getKey(alias, pw);
        if (!(key instanceof PrivateKey pk)) {
            throw new GeneralSecurityException("entry '" + alias + "' of " + s.keyStore() + " is not a private key");
        }
        rsaKey = pk;
        return pk;
    }

    // ---- lookup --------------------------------------------------------------------------------

    /**
     * Outcome by status file. Status files {@code STS_<batchId>_<seq>.csv} are read from {@code inbound} and
     * {@code archive} (the {@link PfmsStatusPoller} moves them there), in sequence order:
     * <ul>
     *   <li>the operation's line in an ACCEPTED file decides ({@code SUCCESS} → succeeded, else failed with the
     *       reason); a batch operation without its own line is aggregated over all lines;</li>
     *   <li>a REJECTED file (other than {@code DUPLICATE_BATCH}, which only refuses a re-delivery) → failed;</li>
     *   <li>payment file present but no status yet → {@link LookupResult.Unavailable} ("awaiting status");</li>
     *   <li>nothing at all → {@link LookupResult.NotFound}.</li>
     * </ul>
     */
    @Override
    public LookupResult lookup(ConnectorRequest request) {
        String batchId;
        try {
            batchId = batchId(request);
        } catch (IllegalArgumentException e) {
            return new LookupResult.Unavailable("PFMS: " + e.getMessage());
        }
        String opId = request.operationId().toString();
        try {
            List<Named> files = statusFiles(batchId);
            PfmsFiles.StatusFile rejected = null;
            String rejectedName = null;
            boolean paidElsewhere = false;
            for (Named nf : files) {
                PfmsFiles.StatusFile sf = nf.file();
                if ("ACCEPTED".equals(sf.fileStatus())) {
                    Optional<PfmsFiles.StatusLine> own = sf.lines().stream()
                            .filter(l -> l.operationId().equals(opId)).findFirst();
                    if (own.isPresent()) {
                        PfmsFiles.StatusLine l = own.get();
                        if ("FAILED".equals(l.status()) && DUPLICATE_OPERATION_ID.equals(l.reason())) {
                            paidElsewhere = true;
                            continue;
                        }
                        return found(nf.name(), sf, List.of(l));
                    }
                    if (!sf.lines().isEmpty()) {
                        return found(nf.name(), sf, sf.lines());
                    }
                } else if (!DUPLICATE_BATCH.equals(sf.reason()) && rejected == null) {
                    rejected = sf;
                    rejectedName = nf.name();
                }
            }
            if (rejected != null) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("batchId", batchId);
                out.put("statusFile", rejectedName);
                out.put("fileStatus", rejected.fileStatus());
                if (rejected.reason() != null) {
                    out.put("reason", rejected.reason());
                }
                return LookupResult.Found.failed(rejected.reason() == null ? "FILE_REJECTED" : rejected.reason(), out);
            }
            if (paidElsewhere) {
                return new LookupResult.Unavailable(
                        "PFMS reports the operationId as already paid in an earlier batch; reconcile");
            }
            if (locate(paymentFileName(batchId)).isPresent()) {
                return new LookupResult.Unavailable("awaiting status");
            }
            return new LookupResult.NotFound();
        } catch (IOException e) {
            return new LookupResult.Unavailable("PFMS: " + e.getMessage());
        }
    }

    private static LookupResult found(String fileName, PfmsFiles.StatusFile sf, List<PfmsFiles.StatusLine> lines) {
        boolean ok = lines.stream().allMatch(l -> "SUCCESS".equals(l.status()));
        String reason = lines.stream().filter(l -> !"SUCCESS".equals(l.status())).map(l -> l.reason() == null
                ? "PAYMENT_" + l.status() : l.reason()).findFirst().orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", sf.batchId());
        out.put("statusFile", fileName);
        out.put("fileStatus", sf.fileStatus());
        List<Map<String, Object>> ls = new ArrayList<>();
        for (PfmsFiles.StatusLine l : lines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("operationId", l.operationId());
            m.put("status", l.status());
            if (l.pfmsRef() != null) {
                m.put("pfmsRef", l.pfmsRef());
            }
            if (l.reason() != null) {
                m.put("reason", l.reason());
            }
            ls.add(m);
        }
        out.put("lines", ls);
        String ref = lines.size() == 1 ? lines.get(0).pfmsRef() : null;
        if (ref != null) {
            out.put("pfmsRef", ref);
        }
        return new LookupResult.Found(ok, out, ref, ok ? null : reason);
    }

    private record Named(String name, int seq, PfmsFiles.StatusFile file) {
    }

    /** Parsed status files of a batch from inbound and archive, by sequence number; unreadable files are skipped. */
    private List<Named> statusFiles(String batchId) throws IOException {
        String prefix = "STS_" + batchId + "_";
        List<Named> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String dir : List.of(INBOUND, ARCHIVE)) {
            for (String n : config.transport().list(dir)) {
                if (!n.startsWith(prefix) || !n.endsWith(".csv") || !seen.add(n)) {
                    continue;
                }
                int seq;
                try {
                    seq = Integer.parseInt(n.substring(prefix.length(), n.length() - 4));
                } catch (NumberFormatException e) {
                    continue;
                }
                try {
                    String csv = new String(config.transport().read(dir, n), StandardCharsets.UTF_8);
                    out.add(new Named(n, seq, PfmsFiles.parseStatus(csv)));
                } catch (PfmsFiles.FormatException e) {
                    LOG.warn("PFMS status file {} in {} is unreadable: {}", n, dir, e.getMessage());
                } catch (IOException e) {
                    // moved between list and read (poller archiving): read it from the other directory
                    String other = dir.equals(INBOUND) ? ARCHIVE : INBOUND;
                    if (config.transport().exists(other, n)) {
                        try {
                            String csv = new String(config.transport().read(other, n), StandardCharsets.UTF_8);
                            out.add(new Named(n, seq, PfmsFiles.parseStatus(csv)));
                        } catch (PfmsFiles.FormatException fe) {
                            LOG.warn("PFMS status file {} is unreadable: {}", n, fe.getMessage());
                        }
                    } else {
                        throw e;
                    }
                }
            }
        }
        out.sort(Comparator.comparingInt(Named::seq));
        return Collections.unmodifiableList(out);
    }

    // ---- helpers -------------------------------------------------------------------------------

    static boolean safeField(String s) {
        return s != null && s.indexOf(',') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }
}
