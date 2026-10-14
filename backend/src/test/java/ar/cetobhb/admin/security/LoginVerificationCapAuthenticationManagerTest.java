package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.admin.security.LoginVerificationCapAuthenticationManager.LoginBusyException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * The concurrent verification cap (D-20261008-45): at most N password verifications run at once; one more attempt
 * waits at most {@value LoginVerificationCapAuthenticationManager#WAIT_MILLIS} ms for a slot and is then refused with
 * {@link LoginBusyException} WITHOUT the real manager being asked (no hash). A slot is always given back.
 */
class LoginVerificationCapAuthenticationManagerTest {

    private static final Authentication ATTEMPT = UsernamePasswordAuthenticationToken.unauthenticated("u", "p");
    private static final Authentication OK = UsernamePasswordAuthenticationToken.authenticated("u", null, List.of());

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AtomicInteger entered = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stop() {
        release.countDown();
        pool.shutdownNow();
    }

    /** A delegate that blocks inside the verification until the test releases it. */
    private AuthenticationManager blocking() {
        return authentication -> {
            entered.incrementAndGet();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return OK;
        };
    }

    private void waitUntilEntered(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (entered.get() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(entered).hasValue(count);
    }

    @Test
    void withAllPermitsHeldTheNextAttemptIsRefusedWithoutAskingTheRealManager() throws Exception {
        AuthenticationManager capped = new LoginVerificationCapAuthenticationManager(blocking(), 2);
        Future<Authentication> first = pool.submit(() -> capped.authenticate(ATTEMPT));
        Future<Authentication> second = pool.submit(() -> capped.authenticate(ATTEMPT));
        waitUntilEntered(2);

        assertThatThrownBy(() -> capped.authenticate(ATTEMPT)).isInstanceOf(LoginBusyException.class);

        assertThat(entered).as("the refused attempt never reached the real manager").hasValue(2);
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).isSameAs(OK);
        assertThat(second.get(5, TimeUnit.SECONDS)).isSameAs(OK);
    }

    @Test
    void afterTheHoldersFinishTheNextAttemptProceeds() throws Exception {
        AuthenticationManager capped = new LoginVerificationCapAuthenticationManager(blocking(), 1);
        Future<Authentication> holder = pool.submit(() -> capped.authenticate(ATTEMPT));
        waitUntilEntered(1);
        assertThatThrownBy(() -> capped.authenticate(ATTEMPT)).isInstanceOf(LoginBusyException.class);
        release.countDown();
        holder.get(5, TimeUnit.SECONDS);

        assertThat(capped.authenticate(ATTEMPT)).isSameAs(OK);
    }

    @Test
    void anAttemptThatFindsTheSlotFreedWithinTheWaitProceedsInsteadOfBeingRefused() throws Exception {
        AuthenticationManager capped = new LoginVerificationCapAuthenticationManager(blocking(), 1);
        Future<Authentication> holder = pool.submit(() -> capped.authenticate(ATTEMPT));
        waitUntilEntered(1);
        Future<Authentication> waiter = pool.submit(() -> capped.authenticate(ATTEMPT));
        Thread.sleep(40);

        release.countDown();

        assertThat(holder.get(5, TimeUnit.SECONDS)).isSameAs(OK);
        assertThat(waiter.get(5, TimeUnit.SECONDS)).as("waited for the slot, then ran").isSameAs(OK);
    }

    @Test
    void theSlotIsGivenBackWhenTheRealManagerThrows() {
        AuthenticationManager failing = authentication -> {
            throw new BadCredentialsException("Bad credentials");
        };
        AuthenticationManager capped = new LoginVerificationCapAuthenticationManager(failing, 1);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> capped.authenticate(ATTEMPT)).as("attempt %d", i + 1)
                    .isInstanceOf(BadCredentialsException.class);
        }
    }

    @Test
    void theSlotIsGivenBackWhenTheRealManagerThrowsAnUncheckedException() {
        AuthenticationManager broken = authentication -> {
            throw new IllegalStateException("boom");
        };
        AuthenticationManager capped = new LoginVerificationCapAuthenticationManager(broken, 1);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> capped.authenticate(ATTEMPT)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void anInterruptedWaitIsRefusedAndKeepsTheInterruptFlag() throws Exception {
        AuthenticationManager capped = new LoginVerificationCapAuthenticationManager(blocking(), 1);
        pool.submit(() -> capped.authenticate(ATTEMPT));
        waitUntilEntered(1);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> capped.authenticate(ATTEMPT)).isInstanceOf(LoginBusyException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(entered).hasValue(1);
    }

    @Test
    void theBusyRefusalHasAFixedMessage() {
        assertThat(new LoginBusyException().getMessage()).isEqualTo("Login verification capacity is busy");
    }
}
