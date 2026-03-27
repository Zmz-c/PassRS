package passrs.browser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BrowserResponse {

    private final int status;
    private final String reason;
    private final Map<String, String> headers;
    private final byte[] body;
    private final String finalUrl;
    private final String title;

    public BrowserResponse(int status, String reason, Map<String, String> headers, byte[] body, String finalUrl, String title) {
        this.status = status;
        this.reason = reason == null ? "" : reason;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body = body == null ? new byte[0] : body.clone();
        this.finalUrl = finalUrl == null ? "" : finalUrl;
        this.title = title == null ? "" : title;
    }

    public int status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public byte[] body() {
        return body.clone();
    }

    public String finalUrl() {
        return finalUrl;
    }

    public String title() {
        return title;
    }
}
