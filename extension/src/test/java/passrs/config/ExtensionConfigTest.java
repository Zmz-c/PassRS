package passrs.config;

import burp.api.montoya.core.ToolType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionConfigTest {

    @Test
    void loadsDefensiveDefaultsWhenPreferencesAreEmpty() {
        ExtensionConfig.Snapshot snapshot = new ExtensionConfig(new InMemoryPreferences()).snapshot();

        assertThat(snapshot.enabled()).isTrue();
        assertThat(snapshot.browserType()).isEqualTo("edge");
        assertThat(snapshot.timeoutMs()).isEqualTo(15000L);
        assertThat(snapshot.scopeMode()).isEqualTo(ExtensionConfig.ScopeMode.ALL);
        assertThat(snapshot.toolTypes()).doesNotContain(ToolType.PROXY, ToolType.EXTENSIONS);
    }

    @Test
    void saveSanitizesBrowserTimeoutToolsAndPaths() {
        ExtensionConfig config = new ExtensionConfig(new InMemoryPreferences());

        ExtensionConfig.Snapshot snapshot = config.save(
                false,
                "firefox",
                "\"~/browser.exe\"",
                "'python'",
                42L,
                ExtensionConfig.ScopeMode.IN_SCOPE_ONLY,
                Set.of(ToolType.REPEATER, ToolType.EXTENSIONS),
                true,
                "  example\\.com  "
        );

        assertThat(snapshot.enabled()).isFalse();
        assertThat(snapshot.browserType()).isEqualTo("edge");
        assertThat(snapshot.browserPath()).contains("browser.exe");
        assertThat(snapshot.pythonPath()).isEqualTo("python");
        assertThat(snapshot.timeoutMs()).isEqualTo(15000L);
        assertThat(snapshot.scopeMode()).isEqualTo(ExtensionConfig.ScopeMode.IN_SCOPE_ONLY);
        assertThat(snapshot.toolTypes()).containsExactlyInAnyOrder(ToolType.REPEATER);
        assertThat(snapshot.loadStaticResources()).isTrue();
        assertThat(snapshot.targetHostRegex()).isEqualTo("example\\.com");
    }
}
