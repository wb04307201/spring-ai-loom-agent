package cn.wubo.loom.http.core.profile;

import cn.wubo.loom.http.core.profile.ProfileValidator.Severity;
import cn.wubo.loom.http.core.profile.ProfileValidator.ValidationError;
import cn.wubo.loom.http.core.system.System;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileValidatorTest {

    private final ProfileValidator validator = new ProfileValidator();

    private static Profile profile(Profile.Auth auth) {
        Profile p = new Profile();
        p.setName("test");
        if (auth != null) p.setAuth(auth);
        return p;
    }

    private static Profile.Auth auth(String type, String token, String username,
                                     String password, String keyName, String value) {
        Profile.Auth a = new Profile.Auth();
        a.setType(type);
        a.setToken(token);
        a.setUsername(username);
        a.setPassword(password);
        a.setKeyName(keyName);
        a.setValue(value);
        return a;
    }

    // ---------- auth.type validation ----------

    @Test
    void nullAuthIsOk() {
        // No auth block is allowed (e.g. an unauthenticated system).
        Profile p = new Profile();
        p.setName("test");
        p.setAuth(null);  // explicit null bypasses the default `new Auth()`
        assertThat(validator.validate(p)).isEmpty();
    }

    @Test
    void authTypeBlankIsError() {
        List<ValidationError> findings = validator.validate(
            profile(auth(null, null, null, null, null, null)));
        assertThat(findings).anyMatch(f -> f.severity() == Severity.ERROR
            && f.field().equals("auth.type"));
    }

    @Test
    void authTypeUnknownIsError() {
        List<ValidationError> findings = validator.validate(
            profile(auth("beareer", "x", null, null, null, null)));
        assertThat(findings).anyMatch(f -> f.severity() == Severity.ERROR
            && f.field().equals("auth.type")
            && f.message().contains("beareer"));
    }

    @Test
    void authTypeBearerMissingTokenIsError() {
        List<ValidationError> findings = validator.validate(
            profile(auth("bearer", "", null, null, null, null)));
        assertThat(findings).anyMatch(f -> f.severity() == Severity.ERROR
            && f.field().equals("auth.token"));
    }

    @Test
    void authTypeBasicMissingBothIsError() {
        Profile.Auth a = auth("basic", null, "", "", null, null);
        List<ValidationError> findings = validator.validate(profile(a));
        assertThat(findings).extracting(ValidationError::field)
            .contains("auth.username", "auth.password");
    }

    @Test
    void authTypeApiKeyMissingKeyAndValueIsError() {
        Profile.Auth a = auth("apiKey-header", null, null, null, "", "");
        List<ValidationError> findings = validator.validate(profile(a));
        assertThat(findings).extracting(ValidationError::field)
            .contains("auth.keyName", "auth.value");
    }

    @Test
    void authTypeNoneNeedsNoFields() {
        assertThat(validator.validate(profile(auth("none", null, null, null, null, null))))
            .isEmpty();
    }

    @Test
    void authTypeBearerWithTokenIsClean() {
        assertThat(validator.validate(
            profile(auth("bearer", "eyJ...", null, null, null, null))))
            .isEmpty();
    }

    // ---------- header map validation ----------

    @Test
    void emptyHeaderKeyIsError() {
        Profile p = profile(null);
        p.setCustomHeaders(Map.of("", "value", "X-Tenant", "staging"));
        List<ValidationError> findings = validator.validate(p);
        assertThat(findings).anyMatch(f -> f.severity() == Severity.ERROR
            && f.field().equals("customHeaders"));
    }

    // ---------- baseUrl validation (via validateSystem) ----------

    @Test
    void systemBaseUrlMustBeHttpOrHttps() {
        System s = new System();
        s.setName("x");
        s.setBaseUrl("ftp://example.com");
        List<ValidationError> findings = validator.validateSystem(s);
        assertThat(findings).anyMatch(f -> f.severity() == Severity.ERROR
            && f.field().equals("system.baseUrl")
            && f.message().contains("ftp"));
    }

    @Test
    void systemBaseUrlMustIncludeHost() {
        System s = new System();
        s.setName("x");
        // Scheme without authority — Java URI parser rejects outright, so
        // the validator surfaces the "not a valid URI" message rather than
        // the "must include host" message. Either is acceptable; what
        // matters is that the bad URL is flagged.
        s.setBaseUrl("http://");
        List<ValidationError> findings = validator.validateSystem(s);
        assertThat(findings).anyMatch(f -> f.severity() == Severity.ERROR
            && f.field().equals("system.baseUrl"));
    }

    @Test
    void systemBaseUrlValidHttpsIsClean() {
        System s = new System();
        s.setName("x");
        s.setBaseUrl("https://api.example.com:8443/v1");
        assertThat(validator.validateSystem(s)).isEmpty();
    }

    // ---------- placeholder warning ----------

    @Test
    void unclosedPlaceholderIsWarningNotError() {
        // Common authoring mistake: profile author typed "${env" instead
        // of "${env:...}". The validator flags it as WARNING so it shows
        // up in the log on reload but does not block addProfile — this is
        // a soft check for backwards compat.
        Profile p = profile(auth("bearer", "${env:TOKEN", null, null, null, null));
        List<ValidationError> findings = validator.validate(p);
        assertThat(findings).anyMatch(f -> f.severity() == Severity.WARNING);
        assertThat(validator.hasErrors(findings)).isFalse();
    }
}
