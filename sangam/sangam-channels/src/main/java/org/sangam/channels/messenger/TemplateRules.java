// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.messenger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Template approval rules for business-initiated messages (NG-14). The provider reviews and approves message templates
 * (WhatsApp message templates, WeChat template messages); a channel entry records which ones are approved in its optional
 * {@code templates} list, each {@code {name, languages?, params?}}. When the list is present, a send that names a template
 * not on it, in a language not listed for it, or with a different number of body parameters, fails before any call with
 * {@code TEMPLATE_NOT_APPROVED} or {@code TEMPLATE_PARAMS_MISMATCH}. When the entry has no list, the provider remains the only
 * judge (the behaviour before NG-14), and its refusal comes back as the provider's error code.
 */
public final class TemplateRules {

    public record Template(String name, List<String> languages, Integer params) {
    }

    private final List<Template> approved;

    private TemplateRules(List<Template> approved) {
        this.approved = approved;
    }

    /** No list: every template is left to the provider. */
    public static TemplateRules providerOnly() {
        return new TemplateRules(null);
    }

    /** Reads the {@code templates} field of a channel entry; absent means {@link #providerOnly()}. */
    public static TemplateRules fromEntry(Map<String, Object> doc) {
        Object v = doc.get("templates");
        if (v == null) {
            return providerOnly();
        }
        if (!(v instanceof Collection<?> list)) {
            throw new IllegalArgumentException("channel entry templates must be a list");
        }
        List<Template> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m) || m.get("name") == null || m.get("name").toString().isBlank()) {
                throw new IllegalArgumentException("each channel entry template needs a name");
            }
            List<String> langs = new ArrayList<>();
            if (m.get("languages") instanceof Collection<?> ls) {
                ls.forEach(l -> langs.add(l.toString()));
            }
            Integer params = null;
            if (m.get("params") != null) {
                try {
                    params = Integer.valueOf(m.get("params").toString());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("template " + m.get("name") + ": params must be a whole number");
                }
                if (params < 0) {
                    throw new IllegalArgumentException("template " + m.get("name") + ": params must not be negative");
                }
            }
            out.add(new Template(m.get("name").toString(), List.copyOf(langs), params));
        }
        return new TemplateRules(List.copyOf(out));
    }

    public boolean enforced() {
        return approved != null;
    }

    /**
     * Checks one template send. Returns the refusal code and reason, or empty when the send may go on.
     *
     * @param bodyParams the number of body parameters in the request, or null when the request does not say
     */
    public Optional<String[]> check(String name, String language, Integer bodyParams) {
        if (approved == null) {
            return Optional.empty();
        }
        for (Template t : approved) {
            if (!t.name().equals(name)) {
                continue;
            }
            if (!t.languages().isEmpty() && (language == null || t.languages().stream().noneMatch(l -> l.equalsIgnoreCase(language)))) {
                return Optional.of(new String[]{"TEMPLATE_NOT_APPROVED", "template " + name + " is not approved for language " + language});
            }
            int given = bodyParams == null ? 0 : bodyParams;
            if (t.params() != null && t.params() != given) {
                return Optional.of(new String[]{"TEMPLATE_PARAMS_MISMATCH", "template " + name + " takes " + t.params() + " parameters, got " + given});
            }
            return Optional.empty();
        }
        return Optional.of(new String[]{"TEMPLATE_NOT_APPROVED", "template " + name + " is not in the channel's approved templates"});
    }

    /** Number of parameters of the WhatsApp {@code body} component, or 0 when there is none. */
    static int whatsAppBodyParams(Object components) {
        if (!(components instanceof Collection<?> list)) {
            return 0;
        }
        for (Object c : list) {
            if (c instanceof Map<?, ?> m && m.get("type") != null && "body".equals(m.get("type").toString().toLowerCase(Locale.ROOT))
                    && m.get("parameters") instanceof Collection<?> ps) {
                return ps.size();
            }
        }
        return 0;
    }

    /** Number of fields in a WeChat template message {@code data} object. */
    static int weChatDataFields(Object data) {
        return data instanceof Map<?, ?> m ? m.size() : 0;
    }
}
