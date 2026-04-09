package passrs.browser;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.nio.charset.StandardCharsets;

public final class BrowserResponseMapper {

    private BrowserResponseMapper() {
    }

    public static HttpResponse toHttpResponse(BrowserResponse browserResponse) {
        byte[] bodyBytes = browserResponse.body();
        String reason = browserResponse.reason().isBlank() ? "OK" : browserResponse.reason();
        StringBuilder headers = new StringBuilder()
                .append("HTTP/1.1 ")
                .append(browserResponse.status())
                .append(' ')
                .append(reason)
                .append("\r\n");

        boolean hasContentLength = false;
        for (String headerLine : browserResponse.headers()) {
            int index = headerLine == null ? -1 : headerLine.indexOf(':');
            if (index <= 0) {
                continue;
            }
            String name = headerLine.substring(0, index).trim();
            if ("transfer-encoding".equalsIgnoreCase(name) || "content-encoding".equalsIgnoreCase(name)) {
                continue;
            }
            if ("content-length".equalsIgnoreCase(name)) {
                hasContentLength = true;
            }
            headers.append(name).append(": ").append(headerLine.substring(index + 1).trim()).append("\r\n");
        }
        if (!hasContentLength) {
            headers.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        }
        headers.append("\r\n");

        byte[] headerBytes = headers.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] responseBytes = new byte[headerBytes.length + bodyBytes.length];
        System.arraycopy(headerBytes, 0, responseBytes, 0, headerBytes.length);
        System.arraycopy(bodyBytes, 0, responseBytes, headerBytes.length, bodyBytes.length);
        return HttpResponse.httpResponse(ByteArray.byteArray(responseBytes));
    }

    public static HttpResponse toErrorResponse(Exception exception) {
        String message = "Browser bridge request failed.";
        if (exception != null && exception.getMessage() != null && !exception.getMessage().isBlank()) {
            message = message + "\r\n" + exception.getMessage();
        }
        byte[] bodyBytes = message.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 599 Browser Bridge Error\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n\r\n";
        byte[] headerBytes = headers.getBytes(StandardCharsets.ISO_8859_1);
        byte[] responseBytes = new byte[headerBytes.length + bodyBytes.length];
        System.arraycopy(headerBytes, 0, responseBytes, 0, headerBytes.length);
        System.arraycopy(bodyBytes, 0, responseBytes, headerBytes.length, bodyBytes.length);
        return HttpResponse.httpResponse(ByteArray.byteArray(responseBytes));
    }
}
