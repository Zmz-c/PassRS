package passrs.browser;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.nio.charset.StandardCharsets;

public final class BrowserResponseMapper {

    private BrowserResponseMapper() {
    }

    public static HttpResponse toHttpResponse(BrowserResponse browserResponse) {
        byte[] responseBytes = BrowserMessageCodec.buildHttpResponseBytes(
                browserResponse.status(),
                browserResponse.reason(),
                browserResponse.headers(),
                browserResponse.body()
        );
        return HttpResponse.httpResponse(ByteArray.byteArray(responseBytes));
    }

    public static HttpResponse toErrorResponse(Exception exception) {
        String message = "Browser bridge request failed.";
        if (exception != null && exception.getMessage() != null && !exception.getMessage().isBlank()) {
            message = message + "\r\n" + exception.getMessage();
        }
        byte[] bodyBytes = message.getBytes(StandardCharsets.UTF_8);
        byte[] responseBytes = BrowserMessageCodec.buildHttpResponseBytes(
                599,
                "Browser Bridge Error",
                java.util.List.of("Content-Type: text/plain; charset=UTF-8"),
                bodyBytes
        );
        return HttpResponse.httpResponse(ByteArray.byteArray(responseBytes));
    }
}
