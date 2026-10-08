package ar.cetobhb.common.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.common.web.SecurityProblem;
import org.junit.jupiter.api.Test;

/**
 * The Security problem rows are written by code that runs outside Spring MVC, so their text must come from one place
 * (REQ-ERR-06): the enum is the source and the catalog row that the documentation test lists is derived from it.
 */
class SecurityProblemTest {

    @Test
    void theEnumHasExactlyTheRowsOfSlice3() {
        // INVALID_CREDENTIALS arrives with the login slice and LOGIN_RATE_LIMITED with the limiter slice.
        assertThat(SecurityProblem.values()).containsExactly(SecurityProblem.UNAUTHENTICATED,
                SecurityProblem.FORBIDDEN, SecurityProblem.REJECTED_REQUEST);
    }

    @Test
    void everyEnumRowIsAnApiErrorRowOfTheCatalogWithTheSameStatusCodeAndDetail() {
        for (SecurityProblem problem : SecurityProblem.values()) {
            ApiError row = ApiErrorCatalog.securityRows().get(problem);

            assertThat(row).as("catalog row of " + problem).isNotNull();
            assertThat(row.status()).isEqualTo(problem.status());
            assertThat(row.code()).isEqualTo(problem.code());
            assertThat(row.detail()).isEqualTo(problem.detail());
        }
    }

    @Test
    void aRejectedRequestIsTheSameRowAsAnUnreadableBody() {
        ApiError malformed = ApiErrorCatalog.MALFORMED;

        assertThat(SecurityProblem.REJECTED_REQUEST.status()).isEqualTo(malformed.status());
        assertThat(SecurityProblem.REJECTED_REQUEST.code()).isEqualTo(malformed.code());
        assertThat(SecurityProblem.REJECTED_REQUEST.detail()).isEqualTo(malformed.detail());
    }

    @Test
    void theStatusesAndCodesAreTheDocumentedOnes() {
        assertThat(SecurityProblem.UNAUTHENTICATED.status().value()).isEqualTo(401);
        assertThat(SecurityProblem.UNAUTHENTICATED.code()).isEqualTo("UNAUTHENTICATED");
        assertThat(SecurityProblem.FORBIDDEN.status().value()).isEqualTo(403);
        assertThat(SecurityProblem.FORBIDDEN.code()).isEqualTo("FORBIDDEN");
        assertThat(SecurityProblem.REJECTED_REQUEST.status().value()).isEqualTo(400);
        assertThat(SecurityProblem.REJECTED_REQUEST.code()).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void everyDetailIsFixedNeutralTextThatNamesNothingFromTheRequest() {
        for (SecurityProblem problem : SecurityProblem.values()) {
            assertThat(problem.detail()).isNotBlank().endsWith(".").doesNotContain("%", "{", "[", "$", "password",
                    "token", "user", "csrf", "session");
        }
    }
}
