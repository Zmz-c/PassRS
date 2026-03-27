package passrs.relay;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.logging.Logging;
import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.HttpString;
import io.undertow.UndertowOptions;
import passrs.browser.BrowserRequest;
import passrs.browser.BrowserRequestManager;
import passrs.browser.BrowserResponse;
import passrs.config.ExtensionConfig;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class LocalRelayServer {

    public static final String HEADER_RELAY_MARKER = "X-PassRS-Relay";
    public static final String HEADER_ORIGINAL_URL = "X-PassRS-Original-Url";
    public static final String HEADER_ORIGINAL_METHOD = "X-PassRS-Original-Method";
    public static final String HEADER_FINAL_URL = "X-PassRS-Final-Url";
    public static final String HEADER_PAGE_TITLE = "X-PassRS-Page-Title";

    private static final String RELAY_KEYSTORE_RESOURCE = "relay/passrs-relay.p12";
    private static final char[] RELAY_KEYSTORE_PASSWORD = "passrs-local".toCharArray();
    private static final String RELAY_MARKER_VALUE = "1";
    private static final String RELAY_PATH = "/passrs-relay";

    private final ExtensionConfig config;
    private final BrowserRequestManager browserRequestManager;
    private final Logging logging;
    private final Consumer<String> statusConsumer;
    private final Object serverLock = new Object();

    private volatile Undertow server;
    private volatile int port;

    public LocalRelayServer(ExtensionConfig config, BrowserRequestManager browserRequestManager,
                            Logging logging, Consumer<String> statusConsumer) {
        this.config = config;
        this.browserRequestManager = browserRequestManager;
        this.logging = logging;
        this.statusConsumer = statusConsumer;
        start();
    }

    public void start() {
        synchronized (serverLock) {
            if (server != null) {
                return;
            }
            try {
                Undertow undertow = Undertow.builder()
                        .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
                        .addHttpsListener(0, "127.0.0.1", buildServerSslContext())
                        .setHandler(this::handleExchange)
                        .build();
                undertow.start();
                port = resolveListeningPort(undertow);
                server = undertow;
                statusConsumer.accept("Relay listening on " + relayBaseUrl());
                logging.logToOutput("PassRS relay listening on " + relayBaseUrl());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to start local relay server", e);
            }
        }
    }

    public void restart() {
        shutdown();
        start();
    }

    public void shutdown() {
        synchronized (serverLock) {
            Undertow current = server;
            server = null;
            port = 0;
            if (current != null) {
                current.stop();
            }
        }
    }

    public String relayBaseUrl() {
        return "https://127.0.0.1:" + port;
    }

    public boolean isRelayRequest(HttpRequest request) {
        return request != null && RELAY_MARKER_VALUE.equals(request.headerValue(HEADER_RELAY_MARKER));
    }

    public String originalUrl(HttpRequest request) {
        if (request == null) {
            return "";
        }
        String original = request.headerValue(HEADER_ORIGINAL_URL);
        if (original != null && !original.isBlank()) {
            return original;
        }
        try {
            return request.url();
        } catch (Exception e) {
            return "";
        }
    }

    public HttpRequest rewrite(HttpRequest request) {
        String originalUrl = request.url();
        String originalMethod = request.method();
        HttpService relayService = HttpService.httpService("127.0.0.1", port, true);
        byte[] body = request.body() == null ? new byte[0] : request.body().getBytes();
        byte[] relayPayload = encodeRelayPayload(request, originalUrl, originalMethod, body);

        StringBuilder raw = new StringBuilder()
                .append("POST ")
                .append(RELAY_PATH)
                .append(" HTTP/1.1\r\n")
                .append("Host: 127.0.0.1:").append(port).append("\r\n")
                .append(HEADER_RELAY_MARKER).append(": ").append(RELAY_MARKER_VALUE).append("\r\n")
                .append(HEADER_ORIGINAL_URL).append(": ").append(sanitizeHeaderValue(originalUrl)).append("\r\n")
                .append(HEADER_ORIGINAL_METHOD).append(": ").append(sanitizeHeaderValue(originalMethod)).append("\r\n")
                .append("Content-Type: text/plain; charset=UTF-8\r\n")
                .append("Content-Length: ").append(relayPayload.length).append("\r\n\r\n");

        byte[] head = raw.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] bytes = new byte[head.length + relayPayload.length];
        System.arraycopy(head, 0, bytes, 0, head.length);
        System.arraycopy(relayPayload, 0, bytes, head.length, relayPayload.length);
        return HttpRequest.httpRequest(relayService, ByteArray.byteArray(bytes)).copyToTempFile();
    }

    private SSLContext buildServerSslContext() throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream inputStream = LocalRelayServer.class.getClassLoader()
                .getResourceAsStream(RELAY_KEYSTORE_RESOURCE)) {
            if (inputStream == null) {
                throw new IOException("relay keystore resource not found");
            }
            keyStore.load(inputStream, RELAY_KEYSTORE_PASSWORD);
        }

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, RELAY_KEYSTORE_PASSWORD);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, null);
        return sslContext;
    }

    private int resolveListeningPort(Undertow undertow) {
        for (Undertow.ListenerInfo listenerInfo : undertow.getListenerInfo()) {
            if (listenerInfo.getAddress() instanceof InetSocketAddress address) {
                return address.getPort();
            }
        }
        throw new IllegalStateException("unable to resolve relay port");
    }

    private void handleExchange(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(() -> handleExchange(exchange));
            return;
        }

        exchange.startBlocking();
        try {
            RelayRequest request = readRequest(exchange);
            if (!RELAY_MARKER_VALUE.equals(firstHeader(request.headers(), HEADER_RELAY_MARKER))) {
                writePlainResponse(exchange, 404, "PassRS relay endpoint.");
                return;
            }

            BrowserRequest browserRequest = decodeRelayPayload(request.body());
            if (browserRequest == null) {
                String originalUrl = firstHeader(request.headers(), HEADER_ORIGINAL_URL);
                String originalMethod = firstHeader(request.headers(), HEADER_ORIGINAL_METHOD);
                if (originalUrl == null || originalUrl.isBlank()) {
                    writePlainResponse(exchange, 400, "Missing original URL.");
                    return;
                }
                if (originalMethod == null || originalMethod.isBlank()) {
                    originalMethod = request.method();
                }
                browserRequest = BrowserRequest.of(originalMethod, originalUrl, toHeaderLines(request.headers()), request.body());
            }

            String originalUrl = browserRequest.url();
            String originalMethod = browserRequest.method();
            BrowserResponse browserResponse = browserRequestManager.execute(browserRequest, config.snapshot());
            writeBrowserResponse(exchange, browserResponse);
            statusConsumer.accept("Completed " + originalMethod + " " + originalUrl);
            logging.logToOutput("PassRS relayed " + originalMethod + " " + originalUrl + " -> " + browserResponse.status());
        } catch (Exception e) {
            logging.logToError("PassRS relay failed", e);
            statusConsumer.accept("Relay error: " + safeMessage(e));
            try {
                writeError(exchange, e);
            } catch (IOException ignored) {
            }
        }
    }

    private RelayRequest readRequest(HttpServerExchange exchange) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        HeaderMap headerMap = exchange.getRequestHeaders();
        for (HeaderValues values : headerMap) {
            List<String> items = new ArrayList<>();
            for (String value : values) {
                items.add(value);
            }
            headers.put(values.getHeaderName().toString(), items);
        }
        byte[] body = exchange.getInputStream().readAllBytes();
        return new RelayRequest(exchange.getRequestMethod().toString(), headers, body);
    }

    private byte[] encodeRelayPayload(HttpRequest request, String originalUrl, String originalMethod, byte[] body) {
        StringBuilder payload = new StringBuilder()
                .append("METHOD=").append(encodeRelayText(originalMethod)).append('\n')
                .append("URL=").append(encodeRelayText(originalUrl)).append('\n');
        List<HttpHeader> headers = request.headers();
        payload.append("HEADER_COUNT=").append(headers.size()).append('\n');
        for (int i = 0; i < headers.size(); i++) {
            HttpHeader header = headers.get(i);
            String line = header == null ? "" : sanitizeHeaderName(header.name()) + ": " + sanitizeHeaderValue(header.value());
            payload.append("HEADER_").append(i).append('=').append(encodeRelayText(line)).append('\n');
        }
        payload.append("BODY=").append(Base64.getEncoder().encodeToString(body));
        return payload.toString().getBytes(StandardCharsets.UTF_8);
    }

    private BrowserRequest decodeRelayPayload(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        String text = new String(body, StandardCharsets.UTF_8);
        for (String line : text.split("\\R")) {
            int index = line.indexOf('=');
            if (index <= 0) {
                continue;
            }
            fields.put(line.substring(0, index), line.substring(index + 1));
        }

        String method = decodeRelayText(fields.get("METHOD"));
        String url = decodeRelayText(fields.get("URL"));
        if (method.isBlank() || url.isBlank()) {
            return null;
        }

        int headerCount = parsePositiveInt(fields.get("HEADER_COUNT"));
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < headerCount; i++) {
            String header = decodeRelayText(fields.get("HEADER_" + i));
            if (!header.isBlank()) {
                headers.add(header);
            }
        }

        byte[] requestBody;
        try {
            requestBody = Base64.getDecoder().decode(fields.getOrDefault("BODY", ""));
        } catch (IllegalArgumentException e) {
            requestBody = new byte[0];
        }
        return BrowserRequest.of(method, url, headers, requestBody);
    }

    private List<String> toHeaderLines(Map<String, List<String>> headers) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.regionMatches(true, 0, "X-PassRS-", 0, "X-PassRS-".length())) {
                continue;
            }
            for (String value : entry.getValue()) {
                result.add(name + ": " + value);
            }
        }
        return result;
    }

    private void writeBrowserResponse(HttpServerExchange exchange, BrowserResponse browserResponse) throws IOException {
        byte[] body = browserResponse.body();
        exchange.setStatusCode(browserResponse.status() < 100 ? 500 : browserResponse.status());
        AtomicBoolean hasContentLength = new AtomicBoolean(false);
        for (Map.Entry<String, String> entry : browserResponse.headers().entrySet()) {
            String name = sanitizeHeaderName(entry.getKey());
            if (name.isBlank()
                    || name.equalsIgnoreCase("transfer-encoding")
                    || name.equalsIgnoreCase("content-encoding")
                    || name.equalsIgnoreCase("connection")) {
                continue;
            }
            if (name.equalsIgnoreCase("content-length")) {
                hasContentLength.set(true);
            }
            exchange.getResponseHeaders().add(new HttpString(name), sanitizeHeaderValue(entry.getValue()));
        }
        exchange.getResponseHeaders().put(new HttpString(HEADER_FINAL_URL), sanitizeHeaderValue(browserResponse.finalUrl()));
        exchange.getResponseHeaders().put(new HttpString(HEADER_PAGE_TITLE), sanitizeHeaderValue(browserResponse.title()));
        if (!hasContentLength.get()) {
            exchange.setResponseContentLength(body.length);
        }
        try (OutputStream outputStream = exchange.getOutputStream()) {
            outputStream.write(body);
        }
    }

    private void writeError(HttpServerExchange exchange, Exception exception) throws IOException {
        String message = "PassRS relay failed.";
        if (exception != null && exception.getMessage() != null && !exception.getMessage().isBlank()) {
            message = message + "\n" + exception.getMessage();
        }
        writePlainResponse(exchange, 599, message);
    }

    private void writePlainResponse(HttpServerExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.setStatusCode(status < 100 ? 500 : status);
        exchange.getResponseHeaders().put(new HttpString("Content-Type"), "text/plain; charset=UTF-8");
        exchange.setResponseContentLength(body.length);
        try (OutputStream outputStream = exchange.getOutputStream()) {
            outputStream.write(body);
        }
    }

    private String sanitizeHeaderName(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", "").replace("\n", "").trim();
    }

    private String sanitizeHeaderValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", "").replace("\n", "").trim();
    }

    private String encodeRelayText(String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private String decodeRelayText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private int parsePositiveInt(String value) {
        try {
            return Math.max(Integer.parseInt(value == null ? "0" : value.trim()), 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String firstHeader(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                List<String> values = entry.getValue();
                if (values == null || values.isEmpty()) {
                    return "";
                }
                return values.get(0);
            }
        }
        return "";
    }

    private String safeMessage(Exception exception) {
        return exception == null || exception.getMessage() == null ? "unknown error" : exception.getMessage();
    }

    private record RelayRequest(String method, Map<String, List<String>> headers, byte[] body) {
    }
}
