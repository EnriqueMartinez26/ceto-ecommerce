package ar.cetobhb.support;

import java.io.IOException;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;

/**
 * Real-server base for the administration session tests: {@link AdminClient} browsers with a manual cookie jar and
 * access to the embedded Tomcat session manager, which a test needs to create a session the way no HTTP client can
 * (a pre-login session, for fixation) or to shorten a live session (idle expiry).
 *
 * <p>This uses the shared random-port context (no extra configuration), so it adds no Spring context to the run.
 */
public abstract class AbstractAdminServerIT extends AbstractServerIT {

    @Autowired
    private ApplicationContext applicationContext;

    protected AdminClient newClient() {
        return new AdminClient(port);
    }

    /** A browser that presents the given client address, so that several of them share one limiter bucket. */
    protected AdminClient newClient(String forwardedFor) {
        return new AdminClient(port, forwardedFor);
    }

    /** A browser that has bootstrapped (holds an XSRF cookie) and logged in as the test administrator. */
    protected AdminClient loggedInClient() throws IOException, InterruptedException {
        AdminClient client = newClient();
        client.bootstrap();
        AdminClient.Reply reply = client.login(AdminTestCredentials.username(), AdminTestCredentials.password());
        if (reply.status() != 204) {
            throw new AssertionError("the test login answered " + reply.status());
        }
        return client;
    }

    /** The session manager of the embedded Tomcat's only web application. */
    protected Manager sessionManager() {
        TomcatWebServer server = (TomcatWebServer) ((WebServerApplicationContext) applicationContext).getWebServer();
        for (Container host : server.getTomcat().getEngine().findChildren()) {
            for (Container child : host.findChildren()) {
                if (child instanceof Context context) {
                    return context.getManager();
                }
            }
        }
        throw new AssertionError("no web application context in the embedded Tomcat");
    }

    /** Creates an empty server-side session, as a page that opened one before login would have, and returns its id. */
    protected String createServerSession() throws IOException {
        Session session = sessionManager().createSession(null);
        return session.getId();
    }

    protected Session findServerSession(String id) throws IOException {
        return sessionManager().findSession(id);
    }
}
