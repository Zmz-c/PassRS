package passrs.mcp;

import burp.api.montoya.logging.Logging;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;
import passrs.browser.BrowserRequestManager;
import passrs.config.ExtensionConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public final class LocalMcpServer {
    private static final String MCP_PATH = "/mcp";
    private static final int MAX_REQUEST_BODY_BYTES = 2 * 1024 * 1024;

    private final ExtensionConfig config;
    private final BrowserRequestManager browserRequestManager;
    private final Logging logging;
    private final Consumer<String> statusConsumer;
    private final McpProtocolHandler protocolHandler;
    private final Object serverLock = new Object();

    private volatile Undertow server;
    private volatile int port;

    public LocalMcpServer(ExtensionConfig config, BrowserRequestManager browserRequestManager,
                          Logging logging, Consumer<String> statusConsumer) {
        this.config = config;
        this.browserRequestManager = browserRequestManager;
        this.logging = logging;
        this.statusConsumer = statusConsumer == null ? ignored -> {
        } : statusConsumer;
        this.protocolHandler = new McpProtocolHandler(new McpProtocolHandler.Backend() {
            @Override
            public passrs.browser.BrowserResponse execute(passrs.browser.BrowserRequest request) {
                return LocalMcpServer.this.browserRequestManager.execute(request, LocalMcpServer.this.config.snapshot());
            }

            @Override
            public void closeBrowser() {
                LocalMcpServer.this.browserRequestManager.cancelCurrentProcess();
                LocalMcpServer.this.browserRequestManager.close(LocalMcpServer.this.config.snapshot());
            }
        }, this.statusConsumer);
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
                        .addHttpListener(0, "127.0.0.1")
                        .setHandler(this::handleExchange)
                        .build();
                undertow.start();
                port = resolveListeningPort(undertow);
                server = undertow;
                statusConsumer.accept("MCP listening on " + mcpEndpointUrl());
                logging.logToOutput("PassRS MCP listening on " + mcpEndpointUrl());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to start local MCP server", e);
            }
        }
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

    public String mcpEndpointUrl() {
        return port <= 0 ? "not running" : "http://127.0.0.1:" + port + MCP_PATH;
    }

    private int resolveListeningPort(Undertow undertow) {
        for (Undertow.ListenerInfo listenerInfo : undertow.getListenerInfo()) {
            if (listenerInfo.getAddress() instanceof InetSocketAddress address) {
                return address.getPort();
            }
        }
        throw new IllegalStateException("unable to resolve MCP port");
    }

    private void handleExchange(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(() -> handleExchange(exchange));
            return;
        }

        try {
            addCommonHeaders(exchange);
            if (!isAllowedOrigin(exchange.getRequestHeaders().getFirst("Origin"))) {
                writeResponse(exchange, 403, "text/plain; charset=UTF-8", "Origin is not allowed.");
                return;
            }
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod().toString())) {
                writeResponse(exchange, 204, "text/plain; charset=UTF-8", "");
                return;
            }
            if (!MCP_PATH.equals(exchange.getRequestPath())) {
                writeResponse(exchange, 404, "text/plain; charset=UTF-8", "PassRS MCP endpoint is " + MCP_PATH);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod().toString())) {
                writeResponse(exchange, 405, "text/plain; charset=UTF-8", "Method not allowed.");
                return;
            }
            if (!isJsonContentType(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                writeResponse(exchange, 415, "text/plain; charset=UTF-8", "Content-Type must be application/json.");
                return;
            }

            exchange.startBlocking();
            if (exchange.getRequestContentLength() > MAX_REQUEST_BODY_BYTES) {
                writeResponse(exchange, 413, "text/plain; charset=UTF-8", "Request body is too large.");
                return;
            }
            byte[] requestBytes = exchange.getInputStream().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
            if (requestBytes.length > MAX_REQUEST_BODY_BYTES) {
                writeResponse(exchange, 413, "text/plain; charset=UTF-8", "Request body is too large.");
                return;
            }
            String requestBody = new String(requestBytes, StandardCharsets.UTF_8);
            McpProtocolHandler.McpResponse response = protocolHandler.handle(requestBody);
            writeResponse(exchange, response.status(), "application/json; charset=UTF-8", response.body());
        } catch (Exception e) {
            logging.logToError("PassRS MCP failed", e);
            statusConsumer.accept("MCP error: " + safeMessage(e));
            try {
                writeResponse(exchange, 500, "application/json; charset=UTF-8",
                        "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}");
            } catch (IOException ignored) {
            }
        }
    }

    private void addCommonHeaders(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(new HttpString("Mcp-Protocol-Version"), McpProtocolHandler.PROTOCOL_VERSION);
        exchange.getResponseHeaders().put(new HttpString("Allow"), "POST, OPTIONS");
        exchange.getResponseHeaders().put(new HttpString("Cache-Control"), "no-store");
        exchange.getResponseHeaders().put(new HttpString("X-Content-Type-Options"), "nosniff");
    }

    static boolean isAllowedOrigin(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        try {
            URI origin = URI.create(value.trim());
            String scheme = origin.getScheme();
            String host = origin.getHost();
            if (scheme == null || host == null || origin.getRawUserInfo() != null
                    || origin.getRawQuery() != null || origin.getRawFragment() != null) {
                return false;
            }
            String path = origin.getRawPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                return false;
            }
            boolean localHost = "127.0.0.1".equalsIgnoreCase(host)
                    || "localhost".equalsIgnoreCase(host)
                    || "::1".equals(host)
                    || "[::1]".equals(host);
            return localHost && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static boolean isJsonContentType(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        int separator = value.indexOf(';');
        String mediaType = separator < 0 ? value : value.substring(0, separator);
        return "application/json".equalsIgnoreCase(mediaType.trim());
    }

    private void writeResponse(HttpServerExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.setStatusCode(status);
        exchange.getResponseHeaders().put(new HttpString("Content-Type"), contentType);
        exchange.setResponseContentLength(bodyBytes.length);
        try (OutputStream outputStream = exchange.getOutputStream()) {
            outputStream.write(bodyBytes);
        }
    }

    private String safeMessage(Exception exception) {
        return exception == null || exception.getMessage() == null ? "unknown error" : exception.getMessage();
    }
}
