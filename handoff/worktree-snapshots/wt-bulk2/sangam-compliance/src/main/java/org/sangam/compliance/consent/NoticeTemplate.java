// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.consent;

import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.jurisdiction.JurisdictionPack.Language;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The fixed frame of a notice in one language. Templates are pack data: {@code <noticeTemplates>/notice_<code>.properties}
 * (UTF-8) for every pack language that has a translation; further translations are added through configuration with
 * {@link Catalogue#loadDirectory(Path)} or {@link Catalogue#register}. Purpose names, data items and descriptions come
 * from the purpose registry in every language. IN ships English and Hindi; the other Eighth Schedule languages are
 * added by translation files.
 */
public record NoticeTemplate(Language language, Map<String, String> labels) {

    /** Keys every template must define. */
    public static final List<String> REQUIRED_KEYS = List.of("title", "intro", "data.heading", "purposes.heading", "withdraw",
            "rights", "complaint", "contact.heading", "consent.action");

    public NoticeTemplate {
        labels = Map.copyOf(labels);
    }

    public String label(String key, String organisation) {
        return labels.getOrDefault(key, "").replace("{organisation}", organisation == null ? "" : organisation);
    }

    public List<String> missingKeys() {
        return REQUIRED_KEYS.stream().filter(k -> labels.getOrDefault(k, "").isBlank()).toList();
    }

    /** Clauses that infringe the law (waiving the right to complain, irrevocable consent ...), from the pack's lint list. */
    public List<String> lint(List<String> forbiddenPhrases) {
        String all = String.join("\n", labels.values()).toLowerCase(Locale.ROOT);
        return forbiddenPhrases.stream().filter(p -> all.contains(p.toLowerCase(Locale.ROOT))).toList();
    }

    /** Templates of one jurisdiction pack, by language code. Thread-safe. */
    public static final class Catalogue {

        private final JurisdictionPack pack;
        private final Map<String, NoticeTemplate> templates = new ConcurrentHashMap<>();

        public Catalogue(JurisdictionPack pack) {
            this.pack = pack;
        }

        /** Templates shipped with the pack on the class path. */
        public static Catalogue bundled(JurisdictionPack pack) {
            Catalogue c = new Catalogue(pack);
            for (Language l : pack.languages()) {
                String res = pack.noticeTemplatePath() + "/notice_" + l.code() + ".properties";
                try (InputStream in = NoticeTemplate.class.getClassLoader().getResourceAsStream(res)) {
                    if (in != null) {
                        c.register(l.code(), read(new InputStreamReader(in, StandardCharsets.UTF_8)));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return c;
        }

        public JurisdictionPack pack() {
            return pack;
        }

        public void register(String languageCode, Map<String, String> labels) {
            Language l = pack.requireLanguage(languageCode);
            templates.put(l.code(), new NoticeTemplate(l, labels));
        }

        public int loadDirectory(Path dir) {
            int n = 0;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "notice_*.properties")) {
                for (Path p : ds) {
                    String name = p.getFileName().toString();
                    String code = name.substring("notice_".length(), name.length() - ".properties".length());
                    if (pack.language(code).isPresent()) {
                        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                            register(code, read(r));
                            n++;
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return n;
        }

        public Optional<NoticeTemplate> get(String languageCode) {
            return languageCode == null ? Optional.empty() : Optional.ofNullable(templates.get(languageCode));
        }

        public Set<String> languages() {
            return Set.copyOf(templates.keySet());
        }

        private static Map<String, String> read(Reader r) throws IOException {
            Properties p = new Properties();
            p.load(r);
            Map<String, String> m = new LinkedHashMap<>();
            for (String k : p.stringPropertyNames()) {
                m.put(k, p.getProperty(k));
            }
            return m;
        }
    }

    /** Catalogues for every installed pack. */
    public static final class Catalogues {
        private final Map<String, Catalogue> byPack = new ConcurrentHashMap<>();

        public Catalogue of(JurisdictionPack pack) {
            return byPack.computeIfAbsent(pack.code(), k -> Catalogue.bundled(pack));
        }
    }
}
