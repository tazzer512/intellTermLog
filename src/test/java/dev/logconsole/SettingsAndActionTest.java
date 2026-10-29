package dev.logconsole;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SettingsAndActionTest {
    @Test
    void settingsDefaults() {
        LogConsoleSettings.State s = new LogConsoleSettings.State();
        assertEquals(LogClassifier.DEFAULT_ERROR, s.errorPattern);
        assertEquals(LogClassifier.DEFAULT_WARN, s.warnPattern);
        assertEquals(LogClassifier.DEFAULT_DEBUG, s.debugPattern);
        assertEquals(LogClassifier.DEFAULT_OTHER, s.otherPattern);
        assertEquals(LogClassifier.DEFAULT_CONTINUATION, s.continuationPattern);
        assertEquals(100_000, s.maxLines);
        assertNull(s.errorColor);
        assertNull(s.debugColor);
        assertNull(s.otherColor);
    }

    @Test
    void settingsEquality() {
        LogConsoleSettings.State a = new LogConsoleSettings.State();
        LogConsoleSettings.State b = new LogConsoleSettings.State();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        b.debugPattern = "x";
        assertNotEquals(a, b);
    }

    @Test
    void colorsFallBackForMissingOrInvalidHex() {
        assertEquals(new Color(0x112233), LogConsoleSettings.parseColor("112233", Color.RED));
        assertEquals(Color.RED, LogConsoleSettings.parseColor(null, Color.RED));
        assertEquals(Color.RED, LogConsoleSettings.parseColor("  ", Color.RED));
        assertEquals(Color.RED, LogConsoleSettings.parseColor("not a color", Color.RED));
        assertNull(LogConsoleSettings.parseColor(null, null));
    }

    @Test
    void settingsEqualityIncludesColors() {
        LogConsoleSettings.State a = new LogConsoleSettings.State();
        LogConsoleSettings.State b = new LogConsoleSettings.State();
        b.otherColor = "445566";
        assertNotEquals(a, b);
        b.otherColor = null;
        b.debugColor = "112233";
        assertNotEquals(a, b);
    }

    @Test
    void runActionUsesTheScriptsShell() {
        assertEquals("bash run-all.sh", RunInLogConsoleAction.commandFor("run-all.sh"));
        assertEquals("bash 'my script.bash'", RunInLogConsoleAction.commandFor("my script.bash"));
        assertEquals("zsh setup.zsh", RunInLogConsoleAction.commandFor("setup.zsh"));
        assertEquals("ksh OLD.KSH", RunInLogConsoleAction.commandFor("OLD.KSH"));
        assertEquals("bash ./-x.sh", RunInLogConsoleAction.commandFor("-x.sh"), "a leading dash is not an option");
    }

    @Test
    void runActionQuotesOnlyWhenNeeded() {
        assertEquals("/home/me/run-all.sh", RunInLogConsoleAction.quote("/home/me/run-all.sh"));
        assertEquals("'/home/me/my scripts'", RunInLogConsoleAction.quote("/home/me/my scripts"));
        assertEquals("'it'\\''s.sh'", RunInLogConsoleAction.quote("it's.sh"));
        assertEquals("'$(rm -rf x).sh'", RunInLogConsoleAction.quote("$(rm -rf x).sh"));
    }
}
