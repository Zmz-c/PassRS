package passrs;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Registration;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import passrs.browser.BrowserRequestManager;
import passrs.config.ExtensionConfig;
import passrs.hook.GlobalBrowserHttpHandler;
import passrs.relay.LocalRelayServer;
import passrs.ui.PassRsPanel;

public final class PassRsExtension implements BurpExtension, ExtensionUnloadingHandler {
    private static final String AUTHOR_NAME = "Zmz-c";
    private static final String REPOSITORY_URL = "https://github.com/Zmz-c/PassRS";

    private PassRsPanel panel;
    private GlobalBrowserHttpHandler httpHandler;
    private Registration httpHandlerRegistration;
    private LocalRelayServer relayServer;

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("PassRS");

        ExtensionConfig config = new ExtensionConfig(api.persistence().preferences());
        BrowserRequestManager browserRequestManager = new BrowserRequestManager();
        relayServer = new LocalRelayServer(config, browserRequestManager, api.logging(), message -> {
            if (panel != null) {
                panel.setStatus(message);
            }
        });
        panel = new PassRsPanel(api, config, browserRequestManager, relayServer);
        httpHandler = new GlobalBrowserHttpHandler(
                config,
                relayServer,
                api.logging(),
                panel::setStatus
        );

        api.userInterface().registerSuiteTab("PassRS", panel.uiComponent());
        httpHandlerRegistration = api.http().registerHttpHandler(httpHandler);
        api.extension().registerUnloadingHandler(this);
        api.logging().raiseInfoEvent("PassRS loaded | Author: " + AUTHOR_NAME + " | Repo: " + REPOSITORY_URL);
    }

    @Override
    public void extensionUnloaded() {
        if (httpHandlerRegistration != null && httpHandlerRegistration.isRegistered()) {
            httpHandlerRegistration.deregister();
        }
        if (httpHandler != null) {
            httpHandler.shutdown();
        }
        if (panel != null) {
            panel.shutdown();
        }
        if (relayServer != null) {
            relayServer.shutdown();
        }
    }
}
