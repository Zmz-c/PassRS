package passrs.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ToolType;
import passrs.browser.BrowserRequestManager;
import passrs.config.ExtensionConfig;
import passrs.mcp.LocalMcpServer;
import passrs.relay.LocalRelayServer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Color;
import java.io.File;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public final class PassRsPanel {
    private static final String AUTHOR_NAME = "Zmz-c";
    private static final String REPOSITORY_URL = "https://github.com/Zmz-c/PassRS";

    private final MontoyaApi api;
    private final ExtensionConfig config;
    private final BrowserRequestManager browserRequestManager;
    private final LocalRelayServer relayServer;
    private final LocalMcpServer mcpServer;

    private final JPanel root;
    private final JCheckBox enabledCheckBox;
    private final JComboBox<String> browserTypeCombo;
    private final JComboBox<String> scopeModeCombo;
    private final JTextField browserPathField;
    private final JTextField pythonPathField;
    private final JTextField timeoutField;
    private final JTextField targetHostRegexField;
    private final JCheckBox loadStaticResourcesCheckBox;
    private final JLabel relayAddressValue;
    private final JLabel mcpAddressValue;
    private final JLabel modeValue;
    private final JLabel statusLabel;
    private final Map<ToolType, JCheckBox> toolCheckBoxes;
    private final Timer autoSaveTimer;
    private volatile boolean loadingConfig;

    public PassRsPanel(MontoyaApi api, ExtensionConfig config, BrowserRequestManager browserRequestManager,
                       LocalRelayServer relayServer, LocalMcpServer mcpServer) {
        this.api = api;
        this.config = config;
        this.browserRequestManager = browserRequestManager;
        this.relayServer = relayServer;
        this.mcpServer = mcpServer;

        root = new JPanel(new BorderLayout(16, 16));
        enabledCheckBox = new JCheckBox("Enable relay hook");
        browserTypeCombo = new JComboBox<>(new String[]{"Edge", "Chrome"});
        scopeModeCombo = new JComboBox<>(new String[]{"All requests", "In-scope only", "Out-of-scope only"});
        browserPathField = new JTextField(34);
        pythonPathField = new JTextField(34);
        timeoutField = new JTextField(10);
        targetHostRegexField = new JTextField(34);
        loadStaticResourcesCheckBox = new JCheckBox("Allow images/media/font resources during browser rendering");
        relayAddressValue = new JLabel();
        mcpAddressValue = new JLabel();
        modeValue = new JLabel();
        statusLabel = new JLabel("Idle");
        toolCheckBoxes = createToolCheckBoxes();
        autoSaveTimer = new Timer(450, e -> autoSaveConfig());
        autoSaveTimer.setRepeats(false);

        initializeUi();
        loadConfig();
    }

    public Component uiComponent() {
        return root;
    }

    public void shutdown() {
        browserRequestManager.cancelCurrentProcess();
        browserRequestManager.close(config.snapshot());
        browserRequestManager.cleanup();
    }

    public void setStatus(String status) {
        String resolved = status == null || status.isBlank() ? "Idle" : status;
        if (SwingUtilities.isEventDispatchThread()) {
            statusLabel.setText(resolved);
            return;
        }
        SwingUtilities.invokeLater(() -> statusLabel.setText(resolved));
    }

    private void initializeUi() {
        root.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        root.add(buildHeaderPanel(), BorderLayout.NORTH);
        root.add(buildBodyPanel(), BorderLayout.CENTER);
        root.add(buildFooterPanel(), BorderLayout.SOUTH);
        installAutoSave();
        api.userInterface().applyThemeToComponent(root);
    }

    private Component buildHeaderPanel() {
        JPanel panel = createCardPanel(new BorderLayout(12, 12));

        JLabel title = new JLabel("PassRS Relay Mode");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 5f));

        JTextArea subtitle = new JTextArea(
                "Selected Burp tool requests are rewritten to a localhost relay. " +
                        "The relay calls Python + DrissionPage, then returns the browser result to Burp. " +
                        "The hook can be limited by scope and by tool module."
        );
        subtitle.setLineWrap(true);
        subtitle.setWrapStyleWord(true);
        subtitle.setEditable(false);
        subtitle.setOpaque(false);
        subtitle.setFont(subtitle.getFont().deriveFont(subtitle.getFont().getSize2D() + 1f));

        JTextArea meta = new JTextArea(
                "Author: " + AUTHOR_NAME + "\n" +
                        "GitHub: " + REPOSITORY_URL
        );
        meta.setEditable(false);
        meta.setOpaque(false);
        meta.setLineWrap(true);
        meta.setWrapStyleWord(true);
        meta.setFont(meta.getFont().deriveFont(meta.getFont().getSize2D() - 0.5f));

        panel.add(title, BorderLayout.NORTH);
        panel.add(subtitle, BorderLayout.CENTER);
        panel.add(meta, BorderLayout.SOUTH);
        return panel;
    }

    private Component buildBodyPanel() {
        JPanel panel = new JPanel(new BorderLayout(16, 16));
        panel.setOpaque(false);
        panel.add(buildSettingsCard(), BorderLayout.CENTER);
        panel.add(buildRuntimeCard(), BorderLayout.EAST);
        return panel;
    }

    private Component buildSettingsCard() {
        JPanel panel = createCardPanel(new GridBagLayout());
        panel.setPreferredSize(new Dimension(820, 420));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.insets = new Insets(6, 6, 6, 10);
        gbc.anchor = GridBagConstraints.WEST;

        addSettingRow(panel, gbc, "Mode", enabledCheckBox, null);
        addSettingRow(panel, gbc, "Scope", scopeModeCombo, null);
        addSettingRow(panel, gbc, "Tools", buildToolSelectionPanel(), null);
        addSettingRow(panel, gbc, "Target Regex", targetHostRegexField, null);
        addSettingRow(panel, gbc, "Static Resources", loadStaticResourcesCheckBox, null);
        addSettingRow(panel, gbc, "Browser", browserTypeCombo, null);
        addSettingRow(panel, gbc, "Browser Path", browserPathField, createBrowseButton(browserPathField, "Select browser executable or app"));
        addSettingRow(panel, gbc, "Python Path", pythonPathField, createBrowseButton(pythonPathField, "Select Python executable or directory"));
        addSettingRow(panel, gbc, "Timeout (ms)", timeoutField, null);

        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttonBar.setOpaque(false);

        JButton saveButton = new JButton("Save");
        saveButton.addActionListener(e -> {
            ExtensionConfig.Snapshot previous = config.snapshot();
            ExtensionConfig.Snapshot snapshot = saveConfig(true);
            if (snapshot != null) {
                refreshRuntime(snapshot);
                if (previous.enabled() && !snapshot.enabled()) {
                    closeBrowserAsync(snapshot, "Relay disabled, closing browser");
                }
            }
        });
        buttonBar.add(saveButton);

        JButton restartRelayButton = new JButton("Restart Relay");
        restartRelayButton.addActionListener(e -> restartRelay());
        buttonBar.add(restartRelayButton);

        JButton closeBrowserButton = new JButton("Close Browser");
        closeBrowserButton.addActionListener(e -> closeBrowser());
        buttonBar.add(closeBrowserButton);

        gbc.gridx = 1;
        gbc.gridy++;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0d;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(buttonBar, gbc);

        return panel;
    }

    private Component buildRuntimeCard() {
        JPanel panel = createCardPanel(new BorderLayout(0, 12));
        panel.setPreferredSize(new Dimension(340, 260));

        JLabel runtimeTitle = new JLabel("Runtime");
        runtimeTitle.setFont(runtimeTitle.getFont().deriveFont(Font.BOLD, runtimeTitle.getFont().getSize2D() + 3f));
        panel.add(runtimeTitle, BorderLayout.NORTH);

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(buildMetric("Hook Mode", modeValue));
        content.add(Box.createVerticalStrut(10));
        content.add(buildMetric("Relay Address", relayAddressValue));
        content.add(Box.createVerticalStrut(10));
        content.add(buildMetric("MCP Address", mcpAddressValue));
        content.add(Box.createVerticalStrut(10));
        content.add(buildMetric("Browser Bridge", new JLabel("Python + DrissionPage")));
        content.add(Box.createVerticalGlue());
        panel.add(content, BorderLayout.CENTER);

        return panel;
    }

    private JPanel buildToolSelectionPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 3, 8, 6));
        panel.setOpaque(false);
        for (ToolType toolType : orderedToolTypes()) {
            JCheckBox checkBox = toolCheckBoxes.get(toolType);
            if (checkBox != null) {
                panel.add(checkBox);
            }
        }
        return panel;
    }

    private Component buildFooterPanel() {
        JPanel panel = createCardPanel(new BorderLayout(8, 0));
        JLabel label = new JLabel("Status");
        label.setHorizontalAlignment(SwingConstants.LEFT);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        panel.add(label, BorderLayout.WEST);
        panel.add(statusLabel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildMetric(String name, JLabel valueLabel) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        JLabel nameLabel = new JLabel(name);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
        valueLabel.setFont(valueLabel.getFont().deriveFont(valueLabel.getFont().getSize2D() + 1f));
        panel.add(nameLabel, BorderLayout.NORTH);
        panel.add(valueLabel, BorderLayout.CENTER);
        return panel;
    }

    private void addSettingRow(JPanel panel, GridBagConstraints gbc, String labelText,
                               JComponent mainComponent, JComponent sideComponent) {
        GridBagConstraints left = (GridBagConstraints) gbc.clone();
        left.gridx = 0;
        left.gridwidth = 1;
        left.weightx = 0;
        left.fill = GridBagConstraints.NONE;
        JLabel label = new JLabel(labelText);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        panel.add(label, left);

        GridBagConstraints center = (GridBagConstraints) gbc.clone();
        center.gridx = 1;
        center.weightx = 1.0d;
        center.fill = GridBagConstraints.HORIZONTAL;
        panel.add(mainComponent, center);

        if (sideComponent != null) {
            GridBagConstraints right = (GridBagConstraints) gbc.clone();
            right.gridx = 2;
            right.weightx = 0;
            right.fill = GridBagConstraints.NONE;
            panel.add(sideComponent, right);
        }
        gbc.gridy++;
    }

    private JPanel createCardPanel(java.awt.LayoutManager layout) {
        Color borderColor = UIManager.getColor("Separator.foreground");
        if (borderColor == null) {
            borderColor = new Color(120, 120, 120);
        }
        JPanel panel = new JPanel(layout);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor),
                BorderFactory.createEmptyBorder(14, 14, 14, 14)
        ));
        panel.setOpaque(false);
        return panel;
    }

    private JButton createBrowseButton(JTextField textField, String dialogTitle) {
        JButton button = new JButton("Browse");
        button.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle(dialogTitle);
            chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            chooser.setFileHidingEnabled(false);
            File currentFile = resolveSelectedPath(textField.getText());
            if (currentFile != null) {
                if (currentFile.isDirectory()) {
                    chooser.setCurrentDirectory(currentFile);
                } else {
                    chooser.setSelectedFile(currentFile);
                    File parent = currentFile.getParentFile();
                    if (parent != null && parent.isDirectory()) {
                        chooser.setCurrentDirectory(parent);
                    }
                }
            }
            if (chooser.showOpenDialog(root) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                textField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        return button;
    }

    private void loadConfig() {
        loadingConfig = true;
        ExtensionConfig.Snapshot snapshot = config.snapshot();
        enabledCheckBox.setSelected(snapshot.enabled());
        scopeModeCombo.setSelectedItem(scopeModeLabel(snapshot.scopeMode()));
        browserTypeCombo.setSelectedItem("chrome".equalsIgnoreCase(snapshot.browserType()) ? "Chrome" : "Edge");
        browserPathField.setText(snapshot.browserPath());
        pythonPathField.setText(snapshot.pythonPath());
        timeoutField.setText(String.valueOf(snapshot.timeoutMs()));
        targetHostRegexField.setText(snapshot.targetHostRegex());
        loadStaticResourcesCheckBox.setSelected(snapshot.loadStaticResources());
        applyToolSelections(snapshot.toolTypes());
        refreshRuntime(snapshot);
        loadingConfig = false;
        setStatus(snapshot.enabled() ? "Relay hook enabled" : "Relay hook disabled");
    }

    private void refreshRuntime(ExtensionConfig.Snapshot snapshot) {
        relayAddressValue.setText(relayServer.relayBaseUrl());
        mcpAddressValue.setText(mcpServer.mcpEndpointUrl());
        modeValue.setText(snapshot.enabled()
                ? "Enabled | " + scopeModeLabel(snapshot.scopeMode()) + " | " + selectedToolSummary(snapshot.toolTypes())
                + regexSummary(snapshot.targetHostRegex())
                + " | Static " + (snapshot.loadStaticResources() ? "on" : "off")
                : "Disabled");
        timeoutField.setText(String.valueOf(snapshot.timeoutMs()));
    }

    private ExtensionConfig.Snapshot saveConfig(boolean showSuccessDialog) {
        return saveConfig(showSuccessDialog, true);
    }

    private ExtensionConfig.Snapshot saveConfig(boolean showSuccessDialog, boolean showErrorDialog) {
        ParsedConfigValues values = parseStrictConfigValues(showErrorDialog);
        if (values == null) {
            return null;
        }
        ExtensionConfig.Snapshot snapshot = persistConfig(values.timeoutMs(), values.targetHostRegex());
        if (showSuccessDialog) {
            JOptionPane.showMessageDialog(root, "Configuration saved.", "PassRS", JOptionPane.INFORMATION_MESSAGE);
        }
        return snapshot;
    }

    private SaveOutcome saveConfigLenient() {
        ExtensionConfig.Snapshot previous = config.snapshot();
        ParsedConfigValues values = parseConfigValues(false, false, previous);
        if (values == null) {
            return new SaveOutcome(null, "Configuration not saved");
        }
        ExtensionConfig.Snapshot snapshot = persistConfig(values.timeoutMs(), values.targetHostRegex());
        return new SaveOutcome(snapshot, values.warningMessage());
    }

    private ParsedConfigValues parseStrictConfigValues(boolean showErrorDialog) {
        long timeoutMs;
        try {
            timeoutMs = Long.parseLong(timeoutField.getText().trim());
        } catch (NumberFormatException e) {
            if (showErrorDialog) {
                showError("Timeout must be a number between 1000 and 300000.");
            } else {
                setStatus("Configuration not saved: invalid timeout");
            }
            return null;
        }
        if (timeoutMs < 1000L || timeoutMs > 300000L) {
            if (showErrorDialog) {
                showError("Timeout must be a number between 1000 and 300000.");
            } else {
                setStatus("Configuration not saved: invalid timeout");
            }
            return null;
        }

        String targetHostRegex = targetHostRegexField.getText() == null ? "" : targetHostRegexField.getText().trim();
        if (!targetHostRegex.isEmpty()) {
            try {
                Pattern.compile(targetHostRegex);
            } catch (PatternSyntaxException e) {
                if (showErrorDialog) {
                    showError("Target Regex is invalid: " + e.getDescription());
                } else {
                    setStatus("Configuration not saved: invalid target regex");
                }
                return null;
            }
        }
        return new ParsedConfigValues(timeoutMs, targetHostRegex, "");
    }

    private ParsedConfigValues parseConfigValues(boolean strictTimeoutValidation, boolean strictRegexValidation,
                                                 ExtensionConfig.Snapshot fallbackSnapshot) {
        long timeoutMs;
        try {
            timeoutMs = Long.parseLong(timeoutField.getText().trim());
        } catch (NumberFormatException e) {
            if (strictTimeoutValidation) {
                showError("Timeout must be a number between 1000 and 300000.");
                return null;
            }
            timeoutMs = fallbackSnapshot.timeoutMs();
        }
        if (timeoutMs < 1000L || timeoutMs > 300000L) {
            if (strictTimeoutValidation) {
                showError("Timeout must be a number between 1000 and 300000.");
                return null;
            }
            timeoutMs = fallbackSnapshot.timeoutMs();
        }

        String targetHostRegex = targetHostRegexField.getText() == null ? "" : targetHostRegexField.getText().trim();
        if (!targetHostRegex.isEmpty()) {
            try {
                Pattern.compile(targetHostRegex);
            } catch (PatternSyntaxException e) {
                if (strictRegexValidation) {
                    showError("Target Regex is invalid: " + e.getDescription());
                    return null;
                }
                targetHostRegex = fallbackSnapshot.targetHostRegex();
            }
        }
        String warningMessage = buildConfigWarningMessage(timeoutMs, targetHostRegex, fallbackSnapshot);
        return new ParsedConfigValues(timeoutMs, targetHostRegex, warningMessage);
    }

    private String buildConfigWarningMessage(long timeoutMs, String targetHostRegex,
                                             ExtensionConfig.Snapshot fallbackSnapshot) {
        boolean timeoutFallback = timeoutMs == fallbackSnapshot.timeoutMs()
                && !timeoutField.getText().trim().equals(String.valueOf(timeoutMs));
        boolean regexFallback = !targetHostRegex.equals(targetHostRegexField.getText() == null
                ? ""
                : targetHostRegexField.getText().trim());
        if (timeoutFallback && regexFallback) {
            return "Applied hook settings; invalid timeout and regex kept previous values";
        }
        if (timeoutFallback) {
            return "Applied hook settings; invalid timeout kept previous value";
        }
        if (regexFallback) {
            return "Applied hook settings; invalid regex kept previous value";
        }
        return "";
    }

    private ExtensionConfig.Snapshot persistConfig(long timeoutMs, String targetHostRegex) {
        return config.save(
                enabledCheckBox.isSelected(),
                "Chrome".equals(browserTypeCombo.getSelectedItem()) ? "chrome" : "edge",
                browserPathField.getText(),
                pythonPathField.getText(),
                timeoutMs,
                selectedScopeMode(),
                selectedToolTypes(),
                loadStaticResourcesCheckBox.isSelected(),
                targetHostRegex
        );
    }

    private void installAutoSave() {
        enabledCheckBox.addActionListener(e -> applyConfigImmediately());
        browserTypeCombo.addActionListener(e -> applyConfigImmediately());
        scopeModeCombo.addActionListener(e -> applyConfigImmediately());
        loadStaticResourcesCheckBox.addActionListener(e -> applyConfigImmediately());
        installAutoSave(browserPathField);
        installAutoSave(pythonPathField);
        installAutoSave(timeoutField);
        installAutoSave(targetHostRegexField);
        for (JCheckBox checkBox : toolCheckBoxes.values()) {
            checkBox.addActionListener(e -> applyConfigImmediately());
        }
    }

    private void installAutoSave(JTextField textField) {
        textField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                scheduleAutoSave();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                scheduleAutoSave();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                scheduleAutoSave();
            }
        });
    }

    private void scheduleAutoSave() {
        if (loadingConfig) {
            return;
        }
        autoSaveTimer.restart();
    }

    private void autoSaveConfig() {
        ExtensionConfig.Snapshot previous = config.snapshot();
        ExtensionConfig.Snapshot snapshot = saveConfig(false, false);
        if (snapshot == null) {
            return;
        }
        refreshRuntime(snapshot);
        setStatus("Configuration auto-saved");
        if (previous.enabled() && !snapshot.enabled()) {
            closeBrowserAsync(snapshot, "Relay disabled, closing browser");
        }
    }

    private void applyConfigImmediately() {
        if (loadingConfig) {
            return;
        }
        autoSaveTimer.stop();
        ExtensionConfig.Snapshot previous = config.snapshot();
        SaveOutcome outcome = saveConfigLenient();
        ExtensionConfig.Snapshot snapshot = outcome.snapshot();
        if (snapshot == null) {
            return;
        }
        refreshRuntime(snapshot);
        if (!outcome.warningMessage().isBlank()) {
            setStatus(outcome.warningMessage());
        } else {
            setStatus(snapshot.enabled() ? "Configuration applied" : "Relay hook disabled");
        }
        if (previous.enabled() && !snapshot.enabled()) {
            closeBrowserAsync(snapshot, "Relay disabled, closing browser");
        }
    }

    private record ParsedConfigValues(long timeoutMs, String targetHostRegex, String warningMessage) {
    }

    private record SaveOutcome(ExtensionConfig.Snapshot snapshot, String warningMessage) {
    }

    private File resolveSelectedPath(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            value = new File(System.getProperty("user.home"), value.substring(2)).getPath();
        } else if (value.equals("~")) {
            value = System.getProperty("user.home");
        }
        File file = new File(value);
        if (file.exists()) {
            return file;
        }
        File parent = file.getParentFile();
        return parent != null && parent.exists() ? parent : null;
    }

    private Map<ToolType, JCheckBox> createToolCheckBoxes() {
        Map<ToolType, JCheckBox> result = new EnumMap<>(ToolType.class);
        for (ToolType toolType : orderedToolTypes()) {
            JCheckBox checkBox = new JCheckBox(toolType.toolName());
            checkBox.setOpaque(false);
            result.put(toolType, checkBox);
        }
        return result;
    }

    private ToolType[] orderedToolTypes() {
        return new ToolType[]{
                ToolType.REPEATER,
                ToolType.INTRUDER,
                ToolType.SCANNER,
                ToolType.TARGET,
                ToolType.LOGGER,
                ToolType.SEQUENCER,
                ToolType.DECODER,
                ToolType.COMPARER,
                ToolType.ORGANIZER,
                ToolType.RECORDED_LOGIN_REPLAYER,
                ToolType.BURP_AI,
                ToolType.SUITE,
                ToolType.PROXY
        };
    }

    private void applyToolSelections(Set<ToolType> selectedTools) {
        for (Map.Entry<ToolType, JCheckBox> entry : toolCheckBoxes.entrySet()) {
            entry.getValue().setSelected(selectedTools != null && selectedTools.contains(entry.getKey()));
        }
    }

    private Set<ToolType> selectedToolTypes() {
        java.util.LinkedHashSet<ToolType> result = new java.util.LinkedHashSet<>();
        for (ToolType toolType : orderedToolTypes()) {
            JCheckBox checkBox = toolCheckBoxes.get(toolType);
            if (checkBox != null && checkBox.isSelected()) {
                result.add(toolType);
            }
        }
        return Set.copyOf(result);
    }

    private ExtensionConfig.ScopeMode selectedScopeMode() {
        Object selected = scopeModeCombo.getSelectedItem();
        if ("In-scope only".equals(selected)) {
            return ExtensionConfig.ScopeMode.IN_SCOPE_ONLY;
        }
        if ("Out-of-scope only".equals(selected)) {
            return ExtensionConfig.ScopeMode.OUT_OF_SCOPE_ONLY;
        }
        return ExtensionConfig.ScopeMode.ALL;
    }

    private String scopeModeLabel(ExtensionConfig.ScopeMode scopeMode) {
        if (scopeMode == ExtensionConfig.ScopeMode.IN_SCOPE_ONLY) {
            return "In-scope only";
        }
        if (scopeMode == ExtensionConfig.ScopeMode.OUT_OF_SCOPE_ONLY) {
            return "Out-of-scope only";
        }
        return "All requests";
    }

    private String selectedToolSummary(Set<ToolType> toolTypes) {
        if (toolTypes == null || toolTypes.isEmpty()) {
            return "No tools";
        }
        if (toolTypes.size() <= 3) {
            return toolTypes.stream().map(ToolType::toolName).reduce((left, right) -> left + ", " + right).orElse("No tools");
        }
        return toolTypes.size() + " tools";
    }

    private String regexSummary(String targetHostRegex) {
        if (targetHostRegex == null || targetHostRegex.isBlank()) {
            return "";
        }
        return " | Regex " + targetHostRegex;
    }

    private void restartRelay() {
        ExtensionConfig.Snapshot previous = config.snapshot();
        ExtensionConfig.Snapshot snapshot = saveConfig(false);
        if (snapshot == null) {
            return;
        }
        if (previous.enabled() && !snapshot.enabled()) {
            closeBrowserAsync(snapshot, "Relay disabled, closing browser");
        }
        setStatus("Restarting relay");
        Thread worker = new Thread(() -> {
            try {
                relayServer.restart();
                SwingUtilities.invokeLater(() -> {
                    refreshRuntime(snapshot);
                    setStatus("Relay restarted: " + relayServer.relayBaseUrl());
                });
            } catch (Exception e) {
                showError("Relay restart failed: " + e.getMessage());
            }
        }, "PassRS-restart-relay");
        worker.setDaemon(true);
        worker.start();
    }

    private void closeBrowser() {
        ExtensionConfig.Snapshot snapshot = saveConfig(false);
        if (snapshot == null) {
            return;
        }
        closeBrowserAsync(snapshot, "Closing browser");
    }

    private void closeBrowserAsync(ExtensionConfig.Snapshot snapshot, String status) {
        setStatus(status);
        Thread worker = new Thread(() -> {
            browserRequestManager.cancelCurrentProcess();
            browserRequestManager.close(snapshot);
            setStatus("Browser closed");
        }, "PassRS-close-browser");
        worker.setDaemon(true);
        worker.start();
    }

    private void showError(String message) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(root, message, "PassRS", JOptionPane.ERROR_MESSAGE));
        setStatus(message);
        api.logging().logToError(message);
    }
}
