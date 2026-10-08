package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * The session cookie is fail-closed at startup, in every profile (REQ-AUTH-04, design AD-A9): the administration
 * cookie must be {@code Secure}, {@code HttpOnly} and {@code SameSite=Strict}, and the session id may travel only in
 * the cookie. A value that is missing, blank or anything else stops the application, whichever source set it (the yml,
 * a command-line argument, an environment variable), and the failure text is fixed: it never repeats the value.
 */
class AdminSessionGuardTest {

    private static final String SECURE = "server.servlet.session.cookie.secure";
    private static final String HTTP_ONLY = "server.servlet.session.cookie.http-only";
    private static final String SAME_SITE = "server.servlet.session.cookie.same-site";
    private static final String TRACKING = "server.servlet.session.tracking-modes";

    /** The effective settings of a correct deployment (what application.yml wires). */
    private static MockEnvironment valid() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(SECURE, "true");
        environment.setProperty(HTTP_ONLY, "true");
        environment.setProperty(SAME_SITE, "strict");
        environment.setProperty(TRACKING, "cookie");
        return environment;
    }

    @Test
    void theCorrectSettingsPass() {
        assertThatCode(() -> new AdminSessionGuard(valid())).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRUE", " true ", "True"})
    void theBooleansAreReadTheWayBootReadsThem(String value) {
        MockEnvironment environment = valid();
        environment.setProperty(SECURE, value);
        environment.setProperty(HTTP_ONLY, value);

        assertThatCode(() -> new AdminSessionGuard(environment)).doesNotThrowAnyException();
    }

    @Test
    void sameSiteIsCaseInsensitiveStrict() {
        MockEnvironment environment = valid();
        environment.setProperty(SAME_SITE, "Strict");

        assertThatCode(() -> new AdminSessionGuard(environment)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"false", "FALSE", " ", "yes", "1", "no", "tru"})
    void aSecureFlagThatIsNotTrueStopsTheStartup(String value) {
        MockEnvironment environment = with(SECURE, value);

        assertThatThrownBy(() -> new AdminSessionGuard(environment)).isInstanceOf(IllegalStateException.class)
                .hasMessage(SECURE + " must be true");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"false", " ", "no"})
    void anHttpOnlyFlagThatIsNotTrueStopsTheStartup(String value) {
        MockEnvironment environment = with(HTTP_ONLY, value);

        assertThatThrownBy(() -> new AdminSessionGuard(environment)).isInstanceOf(IllegalStateException.class)
                .hasMessage(HTTP_ONLY + " must be true");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"lax", "none", "Lax", "None", " ", "strictly", "s3ntinel-same-site"})
    void aSameSiteThatIsNotStrictStopsTheStartupWithoutEchoingIt(String value) {
        MockEnvironment environment = with(SAME_SITE, value);

        assertThatThrownBy(() -> new AdminSessionGuard(environment)).isInstanceOf(IllegalStateException.class)
                .hasMessage(SAME_SITE + " must be strict");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"url", "ssl", "cookie,url", "url,cookie", "cookie,ssl", "COOKIE,URL", " "})
    void aTrackingModeThatIsNotCookieOnlyStopsTheStartup(String value) {
        MockEnvironment environment = with(TRACKING, value);

        assertThatThrownBy(() -> new AdminSessionGuard(environment)).isInstanceOf(IllegalStateException.class)
                .hasMessage(TRACKING + " must be cookie");
    }

    @Test
    void theFailureTextIsFixedAndNeverRepeatsTheValue() {
        MockEnvironment environment = valid();
        environment.setProperty(SAME_SITE, "S3ntinel-Qw83");
        environment.setProperty(TRACKING, "S3ntinel-Qw83");

        Throwable first = org.assertj.core.api.Assertions.catchThrowable(() -> new AdminSessionGuard(environment));

        assertThat(first).isInstanceOf(IllegalStateException.class);
        assertThat(first.getMessage()).doesNotContain("S3ntinel");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SERVER_SERVLET_SESSION_COOKIE_SECURE", "SERVER_SERVLET_SESSION_COOKIE_HTTPONLY",
            "SERVER_SERVLET_SESSION_COOKIE_SAMESITE", "SERVER_SERVLET_SESSION_TRACKINGMODES"})
    void aWeakerValueFromAnEnvironmentVariableStopsTheStartupToo(String variable) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("SERVER_SERVLET_SESSION_COOKIE_SECURE", "true");
        variables.put("SERVER_SERVLET_SESSION_COOKIE_HTTPONLY", "true");
        variables.put("SERVER_SERVLET_SESSION_COOKIE_SAMESITE", "strict");
        variables.put("SERVER_SERVLET_SESSION_TRACKINGMODES", "cookie");
        assertThatCode(() -> new AdminSessionGuard(withEnvironmentVariables(variables))).doesNotThrowAnyException();

        variables.put(variable, variable.endsWith("SAMESITE") ? "lax" : variable.endsWith("TRACKINGMODES") ? "url" : "false");

        assertThatThrownBy(() -> new AdminSessionGuard(withEnvironmentVariables(variables)))
                .isInstanceOf(IllegalStateException.class);
    }

    /** The valid settings with one property replaced by the value, or absent when the value is null. */
    private static MockEnvironment with(String property, String value) {
        MockEnvironment environment = new MockEnvironment();
        String[] all = {SECURE, HTTP_ONLY, SAME_SITE, TRACKING};
        String[] good = {"true", "true", "strict", "cookie"};
        for (int i = 0; i < all.length; i++) {
            if (!all[i].equals(property)) {
                environment.setProperty(all[i], good[i]);
            } else if (value != null) {
                environment.setProperty(all[i], value);
            }
        }
        return environment;
    }

    /** An environment whose only source is the given variables, bound the way Boot binds the real ones. */
    private static StandardEnvironment withEnvironmentVariables(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addLast(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        ConfigurationPropertySources.attach(environment);
        return environment;
    }
}
