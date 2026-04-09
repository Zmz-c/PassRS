package passrs.browser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BrowserResponse {

    private final int status;
    private final String reason;
    private final List<String> headers;
    private final byte[] body;
    private final String finalUrl;
    private final String title;

    public BrowserResponse(int status, String reason, List<String> headers, byte[] body, String finalUrl, String title) {
        this.status = status;
        this.reason = reason == null ? "" : reason;
        this.headers = Collections.unmodifiableList(new ArrayList<>(headers == null ? List.of() : headers));
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

    public List<String> headers() {
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
