package archfixtures.ar.cetobhb.clean.catalog.service;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Compliant control: an outbound HTTP client (a future payment or mail adapter) is not inbound web. */
public class CleanOutboundClient {

    private final RestClient client = RestClient.create();

    public String fetch(String url) {
        try {
            return client.get().uri(url).header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE).retrieve()
                    .body(String.class);
        } catch (RestClientException unavailable) {
            return "";
        }
    }
}
