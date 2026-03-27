package passrs.browser;

import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BrowserRequest {

    private final String method;
    private final String url;
    private final List<String> headers;
    private final byte[] body;

    private BrowserRequest(String method, String url, List<String> headers, byte[] body) {
        this.method = method;
        this.url = url;
        this.headers = Collections.unmodifiableList(new ArrayList<>(headers));
        this.body = body == null ? new byte[0] : body.clone();
    }

    public static BrowserRequest of(String method, String url, List<String> headers, byte[] body) {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method is empty");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is empty");
        }
        return new BrowserRequest(method.toUpperCase(), url, headers == null ? List.of() : headers, body);
    }

    public static BrowserRequest fromHttpRequest(HttpRequest request) {
        List<String> headerLines = new ArrayList<>();
        for (HttpHeader header : request.headers()) {
            headerLines.add(header.name() + ": " + header.value());
        }
        return of(request.method(), request.url(), headerLines, request.body().getBytes());
    }

    public String method() {
        return method;
    }

    public String url() {
        return url;
    }

    public List<String> headers() {
        return headers;
    }

    public byte[] body() {
        return body.clone();
    }
}
