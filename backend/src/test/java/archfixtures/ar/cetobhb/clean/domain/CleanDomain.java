package archfixtures.ar.cetobhb.clean.domain;

import java.time.Clock;
import java.time.Instant;

/** Compliant control: proves the rules do not fail on everything. Uses an injected clock. */
public class CleanDomain {

    Instant at(Clock clock) {
        return clock.instant();
    }
}
