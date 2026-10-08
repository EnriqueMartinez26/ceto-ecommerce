package adminprobe;

import java.util.Map;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEST-ONLY stand-in for a real administration handler (review fix batch 5). It lives in the top-level package
 * {@code adminprobe}, outside the component-scanned {@code ar.cetobhb} tree, and is registered only by
 * {@link AdminProbeTestConfig}, so the shared application contexts and {@code EndpointInventoryIT} never see it.
 * It answers 200 with a fixed sentinel on every method, which turns "anonymous requests are stopped" into a falsifiable
 * claim: if any path variant reached the handler, the sentinel would be in the body.
 */
@RestController
public class AdminProbeController {

    /** Appears in the body of every response that reached the handler, and nowhere else. */
    public static final String SENTINEL = "ADMIN-PROBE-SENTINEL-7c1f9e";

    @RequestMapping(path = {"/api/admin/probe", "/api/admin/probe/{x}"}, method = {RequestMethod.GET,
            RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public Map<String, String> probe() {
        return Map.of("probe", SENTINEL);
    }
}
