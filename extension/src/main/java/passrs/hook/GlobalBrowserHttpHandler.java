package passrs.hook;

import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.logging.Logging;
import passrs.config.ExtensionConfig;
import passrs.relay.LocalRelayServer;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class GlobalBrowserHttpHandler implements HttpHandler {
    private static final Set<String> STATIC_RESOURCE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "css", "js", "mjs", "map",
            "woff", "woff2", "ttf", "otf", "eot", "mp3", "wav", "flac", "ogg", "mp4", "avi",
            "mov", "webm", "m4a", "pdf"
    );
    private static final Set<String> NON_DOCUMENT_FETCH_DESTS = Set.of(
            "image", "style", "script", "font", "audio", "video", "track", "embed", "object",
            "manifest", "worker", "sharedworker", "serviceworker", "paintworklet", "xslt"
    );
    private final ExtensionConfig config;
    private final LocalRelayServer relayServer;
    private final Logging logging;
    private final Consumer<String> statusConsumer;

    public GlobalBrowserHttpHandler(ExtensionConfig config, LocalRelayServer relayServer,
                                    Logging logging, Consumer<String> statusConsumer) {
        this.config = config;
        this.relayServer = relayServer;
        this.logging = logging;
        this.statusConsumer = statusConsumer;
    }

    public void shutdown() {
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent requestToBeSent) {
        ExtensionConfig.Snapshot snapshot = config.snapshot();
        if (!snapshot.enabled()) {
            return RequestToBeSentAction.continueWith(requestToBeSent, requestToBeSent.annotations());
        }
        if (requestToBeSent.toolSource().isFromTool(ToolType.EXTENSIONS)) {
            return RequestToBeSentAction.continueWith(requestToBeSent, requestToBeSent.annotations());
        }
        if (!supports(requestToBeSent)) {
            return RequestToBeSentAction.continueWith(requestToBeSent, requestToBeSent.annotations());
        }
        if (!matchesSelection(requestToBeSent, snapshot)) {
            return RequestToBeSentAction.continueWith(requestToBeSent, requestToBeSent.annotations());
        }
        if (relayServer.isRelayRequest(requestToBeSent)) {
            return RequestToBeSentAction.continueWith(requestToBeSent, requestToBeSent.annotations());
        }
        if (looksLikeStandaloneStaticResource(requestToBeSent)) {
            return RequestToBeSentAction.continueWith(requestToBeSent, requestToBeSent.annotations());
        }

        HttpRequest rewritten = relayServer.rewrite(requestToBeSent.copyToTempFile());
        statusConsumer.accept("Relaying " + safeMethod(requestToBeSent) + " " + relayServer.originalUrl(rewritten));
        return RequestToBeSentAction.continueWith(rewritten, requestToBeSent.annotations());
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
        if (relayServer.isRelayRequest(responseReceived.initiatingRequest())) {
            String originalUrl = relayServer.originalUrl(responseReceived.initiatingRequest());
            logging.logToOutput("PassRS received relayed response for "
                    + safeMethod(responseReceived.initiatingRequest()) + " " + originalUrl);
            statusConsumer.accept("Response ready for " + originalUrl);
        }
        return ResponseReceivedAction.continueWith(responseReceived, responseReceived.annotations());
    }

    private boolean supports(HttpRequest request) {
        String method = safeMethod(request);
        return "GET".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method);
    }

    private boolean looksLikeStandaloneStaticResource(HttpRequest request) {
        if (!"GET".equalsIgnoreCase(safeMethod(request))) {
            return false;
        }

        String fetchDest = safeHeaderValue(request, "Sec-Fetch-Dest").toLowerCase(Locale.ROOT);
        if (NON_DOCUMENT_FETCH_DESTS.contains(fetchDest)) {
            return true;
        }

        String accept = safeHeaderValue(request, "Accept").toLowerCase(Locale.ROOT);
        boolean acceptsHtml = accept.contains("text/html") || accept.contains("application/xhtml+xml");
        if (!acceptsHtml && (accept.startsWith("image/")
                || accept.contains("text/css")
                || accept.contains("font/")
                || accept.contains("audio/")
                || accept.contains("video/"))) {
            return true;
        }

        String path = requestPath(request.url()).toLowerCase(Locale.ROOT);
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return false;
        }
        String extension = path.substring(dot + 1);
        int separator = extension.indexOf('/');
        if (separator >= 0) {
            extension = extension.substring(0, separator);
        }
        return STATIC_RESOURCE_EXTENSIONS.contains(extension);
    }

    private boolean matchesSelection(HttpRequestToBeSent requestToBeSent, ExtensionConfig.Snapshot snapshot) {
        ToolType toolType = requestToBeSent.toolSource().toolType();
        if (!snapshot.toolTypes().contains(toolType)) {
            return false;
        }
        boolean scopeMatch = switch (snapshot.scopeMode()) {
            case ALL -> true;
            case IN_SCOPE_ONLY -> requestToBeSent.isInScope();
            case OUT_OF_SCOPE_ONLY -> !requestToBeSent.isInScope();
        };
        return scopeMatch && matchesTargetHost(requestToBeSent, snapshot.targetHostRegex());
    }

    private String safeMethod(HttpRequest request) {
        try {
            return request.method();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private String safeHeaderValue(HttpRequest request, String name) {
        try {
            String value = request.headerValue(name);
            return value == null ? "" : value;
        } catch (Exception e) {
            return "";
        }
    }

    private String requestPath(String url) {
        try {
            String path = URI.create(url).getPath();
            return path == null ? "" : path;
        } catch (Exception e) {
            return "";
        }
    }

    private boolean matchesTargetHost(HttpRequest request, String targetHostRegex) {
        if (targetHostRegex == null || targetHostRegex.isBlank()) {
            return true;
        }
        String host = requestHost(request.url());
        if (host.isBlank()) {
            return false;
        }
        try {
            Pattern pattern = Pattern.compile(targetHostRegex);
            return pattern.matcher(host).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    private String requestHost(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return "";
        }
    }
}
