package passrs.config;

import burp.api.montoya.core.ToolType;
import burp.api.montoya.persistence.Preferences;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public final class ExtensionConfig {

    private static final String KEY_ENABLED = "passrs.hook.enabled";
    private static final String KEY_BROWSER_TYPE = "passrs.browser.type";
    private static final String KEY_BROWSER_PATH = "passrs.browser.path";
    private static final String KEY_PYTHON_PATH = "passrs.python.path";
    private static final String KEY_TIMEOUT_MS = "passrs.timeout.ms";
    private static final String KEY_SCOPE_MODE = "passrs.hook.scope.mode";
    private static final String KEY_TOOL_TYPES = "passrs.hook.tool.types";
    private static final String KEY_LOAD_STATIC_RESOURCES = "passrs.browser.load.static.resources";
    private static final String KEY_TARGET_HOST_REGEX = "passrs.hook.target.host.regex";

    private static final long DEFAULT_TIMEOUT_MS = 15000L;
    private static final String DEFAULT_BROWSER_TYPE = "edge";
    private static final ScopeMode DEFAULT_SCOPE_MODE = ScopeMode.ALL;

    private final Preferences preferences;

    public ExtensionConfig(Preferences preferences) {
        this.preferences = preferences;
    }

    public synchronized Snapshot snapshot() {
        boolean enabled = preferences.getBoolean(KEY_ENABLED) == null || preferences.getBoolean(KEY_ENABLED);
        String browserType = sanitizeBrowserType(preferences.getString(KEY_BROWSER_TYPE));
        String browserPath = trimToEmpty(preferences.getString(KEY_BROWSER_PATH));
        String pythonPath = trimToEmpty(preferences.getString(KEY_PYTHON_PATH));
        long timeoutMs = sanitizeTimeout(preferences.getLong(KEY_TIMEOUT_MS));
        ScopeMode scopeMode = sanitizeScopeMode(preferences.getString(KEY_SCOPE_MODE));
        Set<ToolType> toolTypes = sanitizeToolTypes(preferences.getString(KEY_TOOL_TYPES));
        boolean loadStaticResources = Boolean.TRUE.equals(preferences.getBoolean(KEY_LOAD_STATIC_RESOURCES));
        String targetHostRegex = trimToEmpty(preferences.getString(KEY_TARGET_HOST_REGEX));
        return new Snapshot(enabled, browserType, browserPath, pythonPath, timeoutMs, scopeMode,
                toolTypes, loadStaticResources, targetHostRegex);
    }

    public synchronized Snapshot save(boolean enabled, String browserType, String browserPath, String pythonPath,
                                      long timeoutMs, ScopeMode scopeMode, Set<ToolType> toolTypes,
                                      boolean loadStaticResources, String targetHostRegex) {
        Snapshot snapshot = new Snapshot(
                enabled,
                sanitizeBrowserType(browserType),
                sanitizePath(browserPath),
                sanitizePath(pythonPath),
                sanitizeTimeout(timeoutMs),
                sanitizeScopeMode(scopeMode == null ? null : scopeMode.name()),
                sanitizeToolTypes(toolTypes),
                loadStaticResources,
                trimToEmpty(targetHostRegex)
        );
        preferences.setBoolean(KEY_ENABLED, snapshot.enabled());
        preferences.setString(KEY_BROWSER_TYPE, snapshot.browserType());
        preferences.setString(KEY_BROWSER_PATH, snapshot.browserPath());
        preferences.setString(KEY_PYTHON_PATH, snapshot.pythonPath());
        preferences.setLong(KEY_TIMEOUT_MS, snapshot.timeoutMs());
        preferences.setString(KEY_SCOPE_MODE, snapshot.scopeMode().name());
        preferences.setString(KEY_TOOL_TYPES, serializeToolTypes(snapshot.toolTypes()));
        preferences.setBoolean(KEY_LOAD_STATIC_RESOURCES, snapshot.loadStaticResources());
        preferences.setString(KEY_TARGET_HOST_REGEX, snapshot.targetHostRegex());
        return snapshot;
    }

    private String sanitizeBrowserType(String browserType) {
        if ("chrome".equalsIgnoreCase(browserType)) {
            return "chrome";
        }
        return DEFAULT_BROWSER_TYPE;
    }

    private long sanitizeTimeout(Long timeoutMs) {
        if (timeoutMs == null) {
            return DEFAULT_TIMEOUT_MS;
        }
        return sanitizeTimeout(timeoutMs.longValue());
    }

    private long sanitizeTimeout(long timeoutMs) {
        if (timeoutMs < 1000L || timeoutMs > 300000L) {
            return DEFAULT_TIMEOUT_MS;
        }
        return timeoutMs;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private ScopeMode sanitizeScopeMode(String value) {
        if (value != null) {
            for (ScopeMode mode : ScopeMode.values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
        }
        return DEFAULT_SCOPE_MODE;
    }

    private Set<ToolType> sanitizeToolTypes(String storedValue) {
        if (storedValue == null || storedValue.isBlank()) {
            return defaultToolTypes();
        }
        if ("-".equals(storedValue.trim())) {
            return Set.of();
        }
        LinkedHashSet<ToolType> result = Arrays.stream(storedValue.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(this::parseToolType)
                .filter(toolType -> toolType != null && toolType != ToolType.EXTENSIONS)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return Set.copyOf(result);
    }

    private Set<ToolType> sanitizeToolTypes(Set<ToolType> toolTypes) {
        if (toolTypes == null) {
            return defaultToolTypes();
        }
        LinkedHashSet<ToolType> result = toolTypes.stream()
                .filter(toolType -> toolType != null && toolType != ToolType.EXTENSIONS)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return Set.copyOf(result);
    }

    private Set<ToolType> defaultToolTypes() {
        LinkedHashSet<ToolType> defaults = new LinkedHashSet<>();
        for (ToolType toolType : ToolType.values()) {
            if (toolType != ToolType.EXTENSIONS && toolType != ToolType.PROXY) {
                defaults.add(toolType);
            }
        }
        return Set.copyOf(defaults);
    }

    private ToolType parseToolType(String value) {
        for (ToolType toolType : ToolType.values()) {
            if (toolType.name().equalsIgnoreCase(value)) {
                return toolType;
            }
        }
        return null;
    }

    private String serializeToolTypes(Set<ToolType> toolTypes) {
        Set<ToolType> sanitized = sanitizeToolTypes(toolTypes);
        if (sanitized.isEmpty()) {
            return "-";
        }
        return sanitized.stream()
                .map(ToolType::name)
                .collect(Collectors.joining(","));
    }

    private String sanitizePath(String value) {
        String trimmed = stripWrappingQuotes(trimToEmpty(value));
        if (trimmed.isEmpty()) {
            return "";
        }
        String expanded = expandHome(trimmed);
        if (!looksLikeFilesystemPath(expanded)) {
            return expanded;
        }
        try {
            return Path.of(expanded).toAbsolutePath().normalize().toString();
        } catch (Exception ignored) {
            return expanded;
        }
    }

    private String expandHome(String value) {
        if (value.equals("~")) {
            return System.getProperty("user.home", value);
        }
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home", ""), value.substring(2)).toString();
        }
        return value;
    }

    private boolean looksLikeFilesystemPath(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.startsWith(".")
                || value.startsWith("~")
                || value.contains("/")
                || value.contains("\\")
                || value.endsWith(".app")
                || value.matches("^[A-Za-z]:.*");
    }

    private String stripWrappingQuotes(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String result = value.trim();
        if (result.length() >= 2) {
            char first = result.charAt(0);
            char last = result.charAt(result.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                result = result.substring(1, result.length() - 1).trim();
            }
        }
        return result;
    }

    public enum ScopeMode {
        ALL,
        IN_SCOPE_ONLY,
        OUT_OF_SCOPE_ONLY
    }

    public record Snapshot(boolean enabled, String browserType, String browserPath, String pythonPath,
                           long timeoutMs, ScopeMode scopeMode, Set<ToolType> toolTypes,
                           boolean loadStaticResources, String targetHostRegex) {
    }
}
