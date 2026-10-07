package ar.cetobhb.architecture.fixtures.clean.domain;

import java.time.Clock;
import java.time.Instant;

/** Compliant control: proves the rules do not fail on everything. Uses an injected clock. */
public class CleanDomain {

    Instant at(Clock clock) {
        return Instant.now(clock);
    }
}
