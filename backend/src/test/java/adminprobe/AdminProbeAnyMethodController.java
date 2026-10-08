package adminprobe;

import java.util.Map;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEST-ONLY stand-in for an administration handler that declares NO method and a path variable with a regex. It is the
 * fixture for the inventory generator: a mapping with no method answers every method (HEAD and OPTIONS included), so an
 * anonymous client must get a 401 for each of them. Registered only by {@link AdminProbeTestConfig}.
 */
@RestController
public class AdminProbeAnyMethodController {

    @RequestMapping("/api/admin/probe-any/{id:\\d+}")
    public Map<String, String> probe() {
        return Map.of("probe", AdminProbeController.SENTINEL);
    }
}
