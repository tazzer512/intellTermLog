package dev.logconsole;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.Objects;

@Service(Service.Level.APP)
@State(name = "LogConsoleSettings", storages = @Storage("logConsole.xml"))
public final class LogConsoleSettings implements PersistentStateComponent<LogConsoleSettings.State> {

    public interface Listener {
        void settingsChanged();
    }

    public static final Topic<Listener> TOPIC = Topic.create("Log Console settings", Listener.class);

    static final JBColor DEFAULT_ERROR_COLOR = new JBColor(new Color(0xCC2B2B), new Color(0xFF6B68));
    static final JBColor DEFAULT_WARN_COLOR = new JBColor(new Color(0xA66F00), new Color(0xE8C547));
    static final JBColor DEFAULT_DEBUG_COLOR = new JBColor(new Color(0x2E7D32), new Color(0x6FBF73));

    public static final class State {
        public String errorPattern = LogClassifier.DEFAULT_ERROR;
        public String warnPattern = LogClassifier.DEFAULT_WARN;
        public String debugPattern = LogClassifier.DEFAULT_DEBUG;
        public String otherPattern = LogClassifier.DEFAULT_OTHER;
        public String continuationPattern = LogClassifier.DEFAULT_CONTINUATION;
        public boolean inheritUntagged = false;
        public int maxLines = 100_000;
        /** Hex color, or null for the theme-aware default. */
        public String errorColor;
        public String warnColor;
        public String debugColor;
        /** Hex color, or null for the editor's normal text color. */
        public String otherColor;

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof State s)) return false;
            return Objects.equals(errorPattern, s.errorPattern)
                    && Objects.equals(warnPattern, s.warnPattern)
                    && Objects.equals(debugPattern, s.debugPattern)
                    && Objects.equals(otherPattern, s.otherPattern)
                    && Objects.equals(continuationPattern, s.continuationPattern)
                    && inheritUntagged == s.inheritUntagged
                    && maxLines == s.maxLines
                    && Objects.equals(errorColor, s.errorColor)
                    && Objects.equals(warnColor, s.warnColor)
                    && Objects.equals(debugColor, s.debugColor)
                    && Objects.equals(otherColor, s.otherColor);
        }

        @Override
        public int hashCode() {
            return Objects.hash(errorPattern, warnPattern, debugPattern, otherPattern, continuationPattern,
                    inheritUntagged, maxLines, errorColor, warnColor, debugColor, otherColor);
        }
    }

    private State state = new State();

    public static LogConsoleSettings getInstance() {
        return ApplicationManager.getApplication().getService(LogConsoleSettings.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    void update(State newState) {
        this.state = newState;
        ApplicationManager.getApplication().getMessageBus().syncPublisher(TOPIC).settingsChanged();
    }

    Color errorColor() {
        return parseColor(state.errorColor, DEFAULT_ERROR_COLOR);
    }

    Color warnColor() {
        return parseColor(state.warnColor, DEFAULT_WARN_COLOR);
    }

    Color debugColor() {
        return parseColor(state.debugColor, DEFAULT_DEBUG_COLOR);
    }

    /** Null: other lines use the editor's normal text color. */
    Color otherColor() {
        return parseColor(state.otherColor, null);
    }

    static Color parseColor(String hex, Color fallback) {
        if (hex == null || hex.isBlank()) return fallback;
        try {
            return ColorUtil.fromHex(hex);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
