package cn.wubo.loom.http.core.security;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public class DomainWhitelist {
    private final Set<String> patterns;

    /**
     * Parsed view of a single pattern, splitting the optional {@code :PORT}
     * suffix from the hostname. {@code port == null} means the pattern is
     * port-agnostic (the pre-existing behaviour); {@code host == null} marks
     * a pattern that could not be parsed at all (e.g. {@code ":6040"}).
     */
    private record ParsedPattern(String host, Integer port) {}

    /** Strip the optional {@code :PORT} suffix and validate the result. */
    private static ParsedPattern parsePattern(String raw) {
        if (raw == null || raw.isEmpty()) return new ParsedPattern(null, null);
        int colonIdx = raw.lastIndexOf(':');
        if (colonIdx > 0 && colonIdx < raw.length() - 1) {
            String hostPart = raw.substring(0, colonIdx);
            String portPart = raw.substring(colonIdx + 1);
            Integer port = parsePort(portPart);
            if (port == null) {
                // Malformed :PORT (e.g. "host:abc") — fall back to plain host literal.
                return new ParsedPattern(raw, null);
            }
            return new ParsedPattern(hostPart, port);
        }
        return new ParsedPattern(raw, null);
    }

    /**
     * True iff {@code selfPatterns} allows every {@code (host, port)} pair that
     * {@code other} allows. Used by {@link #effectiveAllowed(DomainWhitelist)}
     * to filter {@code other.patterns} down to the patterns the receiver
     * still fully authorises.
     */
    private static boolean isCoveredBy(Set<String> selfPatterns, ParsedPattern other) {
        if (other.host() == null || other.host().isEmpty()) return false;
        String otherHost = normalize(other.host());
        if (otherHost.isEmpty()) return false;

        for (String sp : selfPatterns) {
            ParsedPattern self = parsePattern(sp);
            if (self.host() == null || self.host().isEmpty()) continue;
            String selfHost = normalize(self.host());

            // Does self.host cover other.host? Either they match exactly,
            // or self is a suffix pattern (".example.com") that swallows
            // other.host as a subdomain.
            boolean hostCovers = selfHost.equals(otherHost)
                || (selfHost.startsWith(".") && otherHost.endsWith(selfHost));

            if (!hostCovers) continue;

            if (other.port() == null) {
                // other is port-agnostic. self must also be port-agnostic —
                // a host:PORT pattern cannot cover "any port on this host".
                if (self.port() == null) return true;
            } else {
                // other has explicit :PORT. self can match either as plain
                // (port-agnostic, host matches) or as host:PORT exact match.
                if (self.port() == null) return true;
                if (self.port().equals(other.port())) return true;
            }
        }
        return false;
    }

    public DomainWhitelist(Set<String> patterns) {
        this.patterns = new LinkedHashSet<>(patterns);
    }

    public boolean allows(String host) {
        return allows(host, -1);
    }

    /**
     * Match {@code host} (and optionally {@code port}) against the configured
     * patterns.
     *
     * <p>Patterns may carry an optional {@code :PORT} suffix:
     * <ul>
     *   <li>{@code "localhost:6040"} — host must equal {@code "localhost"} AND
     *       port must equal {@code 6040} (URI default-port passing as -1 is
     *       acceptable when the pattern carries no port, see below).</li>
     *   <li>{@code "localhost"} (no colon) — host must equal {@code "localhost"}
     *       regardless of port. Matches the existing pre-port behaviour so
     *       existing profiles continue to work.</li>
     *   <li>{@code ".example.com"} — suffix match, port-agnostic.</li>
     * </ul>
     *
     * <p>A {@code port} of {@code -1} means "unknown / not supplied" (e.g.
     * caller couldn't determine the port from the URL). Patterns that include
     * a {@code :PORT} will not match {@code port=-1}; patterns without a port
     * continue to match.
     */
    public boolean allows(String host, int port) {
        // Reject inputs that start with '.' — these are not valid hostnames
        // and would let attackers smuggle a suffix-only match (e.g. an input
        // of ".example.com" would otherwise endsWith-match a pattern of
        // ".example.com" without ever comparing the actual hostname).
        if (host == null || host.isEmpty() || host.startsWith(".")) return false;
        String normalized = normalize(host);
        if (normalized.isEmpty()) return false;
        for (String p : patterns) {
            int colonIdx = p.lastIndexOf(':');
            if (colonIdx > 0 && colonIdx < p.length() - 1) {
                String pHost = p.substring(0, colonIdx);
                String pPortStr = p.substring(colonIdx + 1);
                Integer pPort = parsePort(pPortStr);
                if (pPort == null) {
                    // Malformed :PORT suffix — the pattern is a hostname
                    // literal that happens to contain a non-numeric colon
                    // segment (e.g. "host:abc"). Treat the whole pattern as
                    // a plain host literal, port-agnostic.
                    if (hostEqualsPlain(normalized, p)) return true;
                    continue;
                }
                if (port == pPort && hostEqualsPlain(normalized, pHost)) return true;
                // Pattern with explicit port — must match both host AND port.
                // Don't also fall through to host-only match; that would let
                // an attacker reach any port on the host once a port-specific
                // pattern exists.
                continue;
            }
            // Plain host pattern (no ':PORT' suffix). Port-agnostic.
            if (hostEqualsPlain(normalized, p)) return true;
        }
        return false;
    }

    private static Integer parsePort(String s) {
        if (s == null || s.isEmpty()) return null;
        // Reject leading zeros / signs / non-digit chars so a pattern like
        // "host:-1" or "host:00ff" cannot accidentally match.
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        if (s.length() > 1 && s.charAt(0) == '0') return null;
        try {
            int v = Integer.parseInt(s);
            if (v < 0 || v > 65535) return null;
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean hostEqualsPlain(String normalized, String pattern) {
        if (pattern == null || pattern.isEmpty()) return false;
        if (pattern.startsWith(".")) {
            return normalized.endsWith(pattern) || normalized.equals(pattern.substring(1));
        }
        return normalized.equals(pattern);
    }

    /**
     * Intersection of two whitelists in the *semantic* sense: the result
     * allows exactly the {@code (host, port)} pairs that both whitelists
     * allow jointly.
     *
     * <p>The result's {@link #patterns() pattern set} is {@code other.patterns}
     * filtered to those the receiver still fully authorises. This is more
     * permissive than a naive {@code Set#retainAll} (which silently drops
     * patterns that differ only by their {@code :PORT} suffix or that one
     * side expresses as a suffix while the other expresses as a plain host).
     * Concretely: {@code global=[localhost], profile=[localhost:6040]} used
     * to produce {@code []}, blocking all calls. It now produces
     * {@code [localhost:6040]} — the profile's narrower pattern, fully
     * covered by the global's port-agnostic pattern.
     *
     * <p>Filtering direction is one-way: the result keeps {@code other}'s
     * shape, since the spec (§7.2) says "profile tightens" — a request must
     * pass both sides, so a pattern the profile does not list cannot be
     * re-introduced by global looseness.
     */
    public DomainWhitelist effectiveAllowed(DomainWhitelist other) {
        Set<String> kept = new LinkedHashSet<>();
        for (String otherPattern : other.patterns) {
            ParsedPattern parsed = parsePattern(otherPattern);
            if (parsed == null || parsed.host() == null || parsed.host().isEmpty()) continue;
            if (isCoveredBy(this.patterns, parsed)) {
                kept.add(otherPattern);
            }
        }
        return new DomainWhitelist(kept);
    }

    public Set<String> patterns() { return Set.copyOf(patterns); }

    /**
     * Canonicalize a hostname for comparison against the whitelist:
     *
     * <ol>
     *   <li>NFC-normalize — collapses canonical-equivalent Unicode so a
     *       whitelist entry like {@code "café"} matches an input that the
     *       client constructed from the NFD form {@code "café"}.</li>
     *   <li>Strip a single trailing dot — DNS FQDN form, equivalent to the
     *       bare hostname for matching purposes.</li>
     *   <li>Lower-case — hostnames are case-insensitive (RFC 1035).</li>
     * </ol>
     *
     * Callers are still expected to reject inputs that start with a dot
     * before calling this method; {@link #allows(String)} does so explicitly.
     */
    public static String normalize(String host) {
        if (host == null) return "";
        String s = Normalizer.normalize(host, Normalizer.Form.NFC);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.toLowerCase(Locale.ROOT);
    }
}
