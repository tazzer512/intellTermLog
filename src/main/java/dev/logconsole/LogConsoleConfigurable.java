package dev.logconsole;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.ui.ColorPanel;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Color;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class LogConsoleConfigurable implements Configurable {
    private final JBTextField errorField = new JBTextField();
    private final JBTextField warnField = new JBTextField();
    private final JBTextField debugField = new JBTextField();
    private final JBTextField otherField = new JBTextField();
    private final JBTextField continuationField = new JBTextField();
    private final JBCheckBox inheritBox =
            new JBCheckBox("Lines without a level keep the level of the previous line (multi-line log entries)");
    private final ColorPanel errorColor = new ColorPanel();
    private final ColorPanel warnColor = new ColorPanel();
    private final JBIntSpinner maxLines = new JBIntSpinner(100_000, 1_000, 5_000_000, 10_000);

    @Override
    public @Nls String getDisplayName() {
        return "Log Console";
    }

    @Override
    public @Nullable JComponent createComponent() {
        JButton defaults = new JButton("Restore Defaults");
        defaults.addActionListener(e -> load(new LogConsoleSettings.State()));
        JPanel panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Error pattern:", errorField)
                .addLabeledComponent("Warning pattern:", warnField)
                .addLabeledComponent("Debug pattern:", debugField)
                .addLabeledComponent("Other levels pattern:", otherField)
                .addTooltip("Java regexes. The pattern matching earliest in a line decides its level, "
                        + "so 'INFO ... got ERROR from stub' stays INFO.")
                .addLabeledComponent("Continuation pattern:", continuationField)
                .addTooltip("Lines matching this (stack trace frames, 'Caused by:') keep the color of the line above.")
                .addComponent(inheritBox)
                .addSeparator()
                .addLabeledComponent("Error color:", errorColor)
                .addLabeledComponent("Warning color:", warnColor)
                .addLabeledComponent("Max lines per tab:", maxLines)
                .addComponent(defaults)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        return !fromUi().equals(LogConsoleSettings.getInstance().getState());
    }

    @Override
    public void apply() throws ConfigurationException {
        LogConsoleSettings.State s = fromUi();
        validate("Error pattern", s.errorPattern);
        validate("Warning pattern", s.warnPattern);
        validate("Debug pattern", s.debugPattern);
        validate("Other levels pattern", s.otherPattern);
        validate("Continuation pattern", s.continuationPattern);
        LogConsoleSettings.getInstance().update(s);
    }

    @Override
    public void reset() {
        load(LogConsoleSettings.getInstance().getState());
    }

    private void load(LogConsoleSettings.State s) {
        errorField.setText(s.errorPattern);
        warnField.setText(s.warnPattern);
        debugField.setText(s.debugPattern);
        otherField.setText(s.otherPattern);
        continuationField.setText(s.continuationPattern);
        inheritBox.setSelected(s.inheritUntagged);
        errorColor.setSelectedColor(LogConsoleSettings.parseColor(s.errorColor, LogConsoleSettings.DEFAULT_ERROR_COLOR));
        warnColor.setSelectedColor(LogConsoleSettings.parseColor(s.warnColor, LogConsoleSettings.DEFAULT_WARN_COLOR));
        maxLines.setNumber(s.maxLines);
    }

    private LogConsoleSettings.State fromUi() {
        LogConsoleSettings.State s = new LogConsoleSettings.State();
        s.errorPattern = errorField.getText();
        s.warnPattern = warnField.getText();
        s.debugPattern = debugField.getText();
        s.otherPattern = otherField.getText();
        s.continuationPattern = continuationField.getText();
        s.inheritUntagged = inheritBox.isSelected();
        s.errorColor = toHex(errorColor.getSelectedColor(), LogConsoleSettings.DEFAULT_ERROR_COLOR);
        s.warnColor = toHex(warnColor.getSelectedColor(), LogConsoleSettings.DEFAULT_WARN_COLOR);
        s.maxLines = maxLines.getNumber();
        return s;
    }

    /** Colors equal to the theme default are stored as null so they keep following the theme. */
    private static String toHex(Color c, Color themeDefault) {
        return c == null || c.getRGB() == themeDefault.getRGB() ? null : ColorUtil.toHex(c);
    }

    private static void validate(String name, String regex) throws ConfigurationException {
        if (regex == null || regex.isBlank()) return;
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new ConfigurationException(name + " is not a valid regex: " + e.getDescription());
        }
    }
}
