package cn.wubo.loom.http.core.profile;

import cn.wubo.loom.http.core.system.System;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates a {@link Profile} (and optionally a {@link System}) at load
 * time and at write time. Separates hard errors (which block
 * {@code addProfile} / {@code updateProfile}) from soft warnings
 * (logged but tolerated for backwards compatibility with existing files).
 *
 * <h2>Hard errors (block save)</h2>
 * <ul>
 *   <li>{@code auth.type} must be one of {@code bearer | basic |
 *       apiKey-header | apiKey-query | none}.</li>
 *   <li>bearer: {@code auth.token} must be non-blank.</li>
 *   <li>basic: {@code auth.username} AND {@code auth.password} must be non-blank.</li>
 *   <li>apiKey-header / apiKey-query: {@code auth.keyName} AND
 *       {@code auth.value} must be non-blank.</li>
 *   <li>{@code system.baseUrl} (when present) must parse as a valid
 *       {@code http://} or {@code https://} URL.</li>
 *   <li>Custom / default header keys must be non-blank (empty-string
 *       keys produce invalid HTTP and usually indicate a typo).</li>
 * </ul>
 *
 * <h2>Soft warnings (logged, not fatal)</h2>
 * <ul>
 *   <li>Placeholder syntax must be well-formed — every {@code ${...}}
 *       must have a matching closing brace. A dangling {@code ${} is the
 *       most common authoring mistake; the result is silent "0-char
 *       value" at request time, which is the worst kind of failure.</li>
 * </ul>
 *
 * <h2>Backwards compatibility</h2>
 * ProfileService.reload() only logs warnings — it never throws, so a
 * previously-saved profile with a typo keeps loading. New writes (via
 * the {@code addProfile} / {@code updateProfile} tools) reject hard
 * errors so authors fix the typo at the source rather than at use time.
 */
public class ProfileValidator {

    private static final Logger log = LoggerFactory.getLogger(ProfileValidator.class);

    private static final Set<String> VALID_AUTH_TYPES = Set.of(
        "bearer", "basic", "apiKey-header", "apiKey-query", "none");

    /** Matches an unclosed {@code ${...}} placeholder (no matching brace). */
    private static final Pattern UNCLOSED_PLACEHOLDER = Pattern.compile("\\$\\{[^}]*$");

    public enum Severity { ERROR, WARNING }

    public record ValidationError(Severity severity, String field, String message) {
        @Override
        public String toString() {
            return "[" + severity + "] " + field + ": " + message;
        }
    }

    /**
     * Validate a profile. Returns the list of findings (errors + warnings),
     * empty if the profile is clean.
     */
    public List<ValidationError> validate(Profile p) {
        List<ValidationError> errors = new ArrayList<>();
        if (p == null) {
            errors.add(new ValidationError(Severity.ERROR, "profile", "profile is null"));
            return errors;
        }
        validateAuth(p, errors);
        validateHeaders(p, errors);
        validatePlaceholders(p, errors);
        return errors;
    }

    /**
     * Validate a system (call after {@link #validate(Profile)} for the
     * profile that backs the system). Returns the list of findings.
     */
    public List<ValidationError> validateSystem(System s) {
        List<ValidationError> errors = new ArrayList<>();
        if (s == null) {
            errors.add(new ValidationError(Severity.ERROR, "system", "system is null"));
            return errors;
        }
        validateBaseUrl(s, errors);
        return errors;
    }

    /**
     * Convenience: validate both, return merged list. Use this from the
     * addProfile / registerSystem tools where caller has both objects
     * in hand.
     */
    public List<ValidationError> validateAll(Profile p, System s) {
        List<ValidationError> errors = new ArrayList<>(validate(p));
        errors.addAll(validateSystem(s));
        return errors;
    }

    /**
     * True iff there is at least one ERROR-severity finding. Use this
     * to decide whether to block a save.
     */
    public boolean hasErrors(List<ValidationError> findings) {
        return findings.stream().anyMatch(f -> f.severity() == Severity.ERROR);
    }

    private void validateAuth(Profile p, List<ValidationError> errors) {
        Profile.Auth auth = p.getAuth();
        if (auth == null) return; // auth is optional (e.g. unauthenticated system)

        String type = auth.getType();
        if (type == null || type.isBlank()) {
            errors.add(new ValidationError(Severity.ERROR, "auth.type",
                "must be one of " + VALID_AUTH_TYPES + " (got null/blank)"));
            return;
        }
        if (!VALID_AUTH_TYPES.contains(type)) {
            errors.add(new ValidationError(Severity.ERROR, "auth.type",
                "must be one of " + VALID_AUTH_TYPES + " (got '" + type + "')"));
            return;
        }

        // Type-specific required fields.
        switch (type) {
            case "bearer" -> {
                if (isBlank(auth.getToken())) {
                    errors.add(new ValidationError(Severity.ERROR, "auth.token",
                        "required for auth.type=bearer"));
                }
            }
            case "basic" -> {
                if (isBlank(auth.getUsername())) {
                    errors.add(new ValidationError(Severity.ERROR, "auth.username",
                        "required for auth.type=basic"));
                }
                if (isBlank(auth.getPassword())) {
                    errors.add(new ValidationError(Severity.ERROR, "auth.password",
                        "required for auth.type=basic"));
                }
            }
            case "apiKey-header", "apiKey-query" -> {
                if (isBlank(auth.getKeyName())) {
                    errors.add(new ValidationError(Severity.ERROR, "auth.keyName",
                        "required for auth.type=" + type));
                }
                if (isBlank(auth.getValue())) {
                    errors.add(new ValidationError(Severity.ERROR, "auth.value",
                        "required for auth.type=" + type));
                }
            }
            case "none" -> { /* no required fields */ }
            default -> { /* unreachable — covered by Set#contains check above */ }
        }
    }

    private void validateHeaders(Profile p, List<ValidationError> errors) {
        validateHeaderMap("customHeaders", p.getCustomHeaders(), errors);
        validateHeaderMap("defaultHeaders", p.getDefaultHeaders(), errors);
    }

    private void validateHeaderMap(String fieldName, Map<String, String> headers,
                                  List<ValidationError> errors) {
        if (headers == null) return;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                errors.add(new ValidationError(Severity.ERROR, fieldName,
                    "header key must be non-blank (got null/empty)"));
            }
        }
    }

    private void validateBaseUrl(System s, List<ValidationError> errors) {
        String url = s.getBaseUrl();
        if (url == null || url.isBlank()) return; // optional — system can rely on profile.baseUrl
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                errors.add(new ValidationError(Severity.ERROR, "system.baseUrl",
                    "must be http:// or https:// (got '" + url + "')"));
                return;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                errors.add(new ValidationError(Severity.ERROR, "system.baseUrl",
                    "must include a host (got '" + url + "')"));
            }
        } catch (URISyntaxException ex) {
            errors.add(new ValidationError(Severity.ERROR, "system.baseUrl",
                "not a valid URI: " + ex.getMessage()));
        }
    }

    private void validatePlaceholders(Profile p, List<ValidationError> errors) {
        // Soft warnings only — these don't block save (backwards compat
        // for profiles that have well-formed values not yet exercised at
        // runtime). Authors see them in the log on reload.
        scanPlaceholder(p.getName(), p.getDescription(), errors);
        scanPlaceholder(p.getName(), p.getBaseUrl(), errors);
        Profile.Auth auth = p.getAuth();
        if (auth != null) {
            scanPlaceholder(p.getName(), auth.getToken(), errors);
            scanPlaceholder(p.getName(), auth.getUsername(), errors);
            scanPlaceholder(p.getName(), auth.getPassword(), errors);
            scanPlaceholder(p.getName(), auth.getValue(), errors);
        }
        scanHeaderPlaceholders(p.getName(), p.getCustomHeaders(), errors);
        scanHeaderPlaceholders(p.getName(), p.getDefaultHeaders(), errors);
    }

    private void scanPlaceholder(String profileName, String value, List<ValidationError> errors) {
        if (value == null) return;
        // Count '${' and '}' — a missing closing brace leaves the count
        // mismatched. Cheap heuristic; precise bracket-counting is out of
        // scope for the warning tier.
        int open = 0;
        int idx = 0;
        while ((idx = value.indexOf("${", idx)) != -1) {
            open++;
            idx += 2;
        }
        int close = 0;
        idx = 0;
        while ((idx = value.indexOf('}', idx)) != -1) {
            close++;
            idx += 1;
        }
        if (open != close) {
            errors.add(new ValidationError(Severity.WARNING, "profile",
                "unbalanced ${} placeholder syntax (open=" + open + " close=" + close
                    + ") — common authoring mistake, value will resolve to '' at runtime"));
        }
    }

    private void scanHeaderPlaceholders(String profileName, Map<String, String> headers,
                                        List<ValidationError> errors) {
        if (headers == null) return;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            scanPlaceholder(profileName, e.getValue(), errors);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
