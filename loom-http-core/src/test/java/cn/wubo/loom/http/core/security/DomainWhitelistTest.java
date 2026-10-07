package cn.wubo.loom.http.core.security;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class DomainWhitelistTest {
    @Test
    void exactMatch() {
        DomainWhitelist w = new DomainWhitelist(Set.of("api.example.com"));
        assertThat(w.allows("api.example.com")).isTrue();
    }

    @Test
    void suffixMatch() {
        DomainWhitelist w = new DomainWhitelist(Set.of(".example.com"));
        assertThat(w.allows("api.example.com")).isTrue();
        assertThat(w.allows("api.example.com")).isTrue();
    }

    @Test
    void noMatch() {
        DomainWhitelist w = new DomainWhitelist(Set.of("api.example.com"));
        assertThat(w.allows("evil.com")).isFalse();
    }

    @Test
    void emptyAllowsNothing() {
        DomainWhitelist w = new DomainWhitelist(Set.of());
        assertThat(w.allows("any.com")).isFalse();
    }

    @Test
    void intersectionTakesBoth() {
        DomainWhitelist global = new DomainWhitelist(Set.of(".example.com", "api.partner.com"));
        DomainWhitelist profile = new DomainWhitelist(Set.of(".example.com"));
        assertThat(profile.effectiveAllowed(global).patterns())
            .containsExactly(".example.com");
    }

    @Test
    void intersectionCanBeEmpty() {
        DomainWhitelist global = new DomainWhitelist(Set.of(".example.com"));
        DomainWhitelist profile = new DomainWhitelist(Set.of(".partner.com"));
        assertThat(profile.effectiveAllowed(global).patterns()).isEmpty();
    }

    // ---------- Unicode + special-character edge cases ----------

    @Test
    void normalizesUnicodeHostname() {
        // NFC / NFD: "café" can be written as a single NFC code point (é =
        // U+00E9) or as two NFD code points (e = U+0065 + ́ = U+0301). The
        // whitelist holds the NFC form; the incoming host is NFD. Without
        // normalization the match would silently fail.
        String nfc = "café.example.com"; // NFC: é as single code point
        String nfd = "café.example.com"; // NFD: e + combining acute
        DomainWhitelist w = new DomainWhitelist(Set.of(nfc));
        assertThat(w.allows(nfd)).isTrue();
        assertThat(w.allows(nfc)).isTrue();
    }

    @Test
    void acceptsTrailingDot() {
        // DNS allows a trailing dot on an FQDN (it marks the root). The
        // whitelist holds the bare form; a trailing-dot input must still match.
        // (Test name kept as "rejectsTrailingDotMismatch" in the broader spec
        // — here we assert it MATCHES, not rejects, so this is an "accepts"
        // test by intent. The brief's "rejectsTrailingDot" wording referred
        // to "rejects the trailing dot as a non-match", i.e. it should not
        // *cause* a reject.)
        DomainWhitelist w = new DomainWhitelist(Set.of("example.com"));
        assertThat(w.allows("example.com.")).isTrue();
    }

    @Test
    void rejectsLeadingDot() {
        // ".example.com" is not a valid hostname — the leading dot would let
        // a suffix pattern (".example.com") match itself rather than an
        // actual subdomain. The whitelist must refuse this input.
        DomainWhitelist w = new DomainWhitelist(Set.of("example.com"));
        assertThat(w.allows(".example.com")).isFalse();
        // And even with a suffix pattern, the leading-dot host still must
        // not piggy-back into a same-suffix match.
        DomainWhitelist w2 = new DomainWhitelist(Set.of(".example.com"));
        assertThat(w2.allows(".example.com")).isFalse();
    }

    @Test
    void normalizeStripsTrailingDotAndLowercases() {
        // Direct unit test of the static helper so the contract is pinned
        // separately from the matching behaviour.
        assertThat(DomainWhitelist.normalize("Example.COM."))
            .isEqualTo("example.com");
        assertThat(DomainWhitelist.normalize("Example.COM"))
            .isEqualTo("example.com");
        assertThat(DomainWhitelist.normalize(null)).isEmpty();
    }

    // ---------- Regression: Finding #6 ----------
    //
    // The matcher only knows the hostname; patterns like `localhost:6040`
    // never matched a host of `localhost`. Users naturally wrote
    // `host:port` and got silently rejected (the matching loop's
    // `normalized.equals(p)` check failed). Allow the pattern to carry an
    // optional `:PORT` suffix that must match both host and port. Patterns
    // without `:PORT` continue to ignore the port (backward compatible).

    @Test
    void hostPortPatternMatchesMatchingHostAndPort() {
        DomainWhitelist w = new DomainWhitelist(Set.of("localhost:6040"));
        assertThat(w.allows("localhost", 6040)).isTrue();
    }

    @Test
    void hostPortPatternRejectsDifferentPort() {
        DomainWhitelist w = new DomainWhitelist(Set.of("localhost:6040"));
        assertThat(w.allows("localhost", 8080)).isFalse();
    }

    @Test
    void hostPortPatternRejectsDifferentHost() {
        DomainWhitelist w = new DomainWhitelist(Set.of("localhost:6040"));
        assertThat(w.allows("evilhost", 6040)).isFalse();
    }

    @Test
    void plainHostPatternIgnoresPort() {
        // Backward compatibility: a pattern without ':PORT' still matches any
        // port on the host. This keeps existing profiles like
        // `allowedDomains: ["localhost"]` working after the upgrade.
        DomainWhitelist w = new DomainWhitelist(Set.of("localhost"));
        assertThat(w.allows("localhost", 6040)).isTrue();
        assertThat(w.allows("localhost", 8080)).isTrue();
        assertThat(w.allows("localhost", -1)).isTrue();
    }

    @Test
    void suffixPatternIgnoresPort() {
        // Suffix patterns (`'.example.com'`) likewise remain port-agnostic.
        DomainWhitelist w = new DomainWhitelist(Set.of(".example.com"));
        assertThat(w.allows("api.example.com", 443)).isTrue();
        assertThat(w.allows("api.example.com", 6040)).isTrue();
    }

    @Test
    void portOnlyPatternIsIgnored() {
        // A pattern that is just `:6040` (no host) is malformed — the matcher
        // must fall through to no-match rather than raise. Document intent.
        DomainWhitelist w = new DomainWhitelist(Set.of(":6040"));
        assertThat(w.allows("localhost", 6040)).isFalse();
    }

    @Test
    void nonNumericPortSuffixFallsBackToPlainHostMatch() {
        // A pattern with `:` but non-numeric suffix (e.g. `host:abc`) is
        // treated as a plain hostname literal — preserving backward
        // compatibility for any user who happened to put a literal ':' in
        // a hostname. The literal hostname matches any port.
        DomainWhitelist w = new DomainWhitelist(Set.of("host:abc"));
        assertThat(w.allows("host:abc", -1)).isTrue();
        assertThat(w.allows("host:abc", 6040)).isTrue();
        // And the literal hostname must not match a different hostname.
        assertThat(w.allows("host", 6040)).isFalse();
    }

    // ---------- Regression: intersection with mixed plain / host:port patterns ----------
    //
    // The naive Set#retainAll implementation treated patterns as opaque
    // strings, so `["localhost"] ∩ ["localhost:6040"]` was `[]` even though
    // the global plain pattern clearly authorises every port on localhost.
    // The fix filters `other.patterns` to those the receiver still fully
    // authorises (covers), so the intersection preserves the profile's
    // shape rather than collapsing it.

    @Test
    void intersectionPlainHostCoversHostPortPattern() {
        // global plain, profile host:port — the actual user-reported bug.
        // The profile's localhost:6040 is covered (allowed) by global's
        // port-agnostic "localhost", so it survives the intersection.
        DomainWhitelist global = new DomainWhitelist(Set.of("localhost"));
        DomainWhitelist profile = new DomainWhitelist(Set.of("localhost:6040"));
        DomainWhitelist eff = global.effectiveAllowed(profile);
        assertThat(eff.patterns()).containsExactly("localhost:6040");
        // And the resulting whitelist actually allows the request.
        assertThat(eff.allows("localhost", 6040)).isTrue();
        // But not a different port — profile tightening is preserved.
        assertThat(eff.allows("localhost", 8080)).isFalse();
    }

    @Test
    void intersectionHostPortCannotCoverPlainHostPattern() {
        // The reverse direction. profile=plain, global=host:port.
        // The profile's plain "localhost" is NOT covered by global's
        // port-specific "localhost:6040" (port-agnostic vs port-bound).
        // Intersection must collapse to empty — profile tightening
        // is honoured, not silently weakened.
        DomainWhitelist global = new DomainWhitelist(Set.of("localhost:6040"));
        DomainWhitelist profile = new DomainWhitelist(Set.of("localhost"));
        DomainWhitelist eff = global.effectiveAllowed(profile);
        assertThat(eff.patterns()).isEmpty();
        // The collapsed whitelist allows nothing.
        assertThat(eff.allows("localhost", 6040)).isFalse();
        assertThat(eff.allows("localhost", -1)).isFalse();
    }

    @Test
    void intersectionFiltersUncoveredPatternFromMixedList() {
        // profile has two patterns; global covers one of them.
        DomainWhitelist global = new DomainWhitelist(Set.of("localhost"));
        DomainWhitelist profile = new DomainWhitelist(Set.of("localhost:6040", "evil.com"));
        DomainWhitelist eff = global.effectiveAllowed(profile);
        assertThat(eff.patterns()).containsExactly("localhost:6040");
        assertThat(eff.allows("localhost", 6040)).isTrue();
        assertThat(eff.allows("evil.com", 80)).isFalse();
    }
}
