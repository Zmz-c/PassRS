package passrs.ui;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.time.Instant;

public record HistoryEntry(
        Instant time,
        HttpRequest request,
        HttpResponse response,
        String method,
        String url,
        String status,
        String finalUrl,
        String title
) {
}
