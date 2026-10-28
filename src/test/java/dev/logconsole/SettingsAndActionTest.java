package dev.logconsole;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
        assertEquals(null, s.errorColor);
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
    void runActionQuotesOnlyWhenNeeded() {
        assertEquals("/home/me/run-all.sh", RunInLogConsoleAction.quote("/home/me/run-all.sh"));
        assertEquals("'/home/me/my scripts'", RunInLogConsoleAction.quote("/home/me/my scripts"));
        assertEquals("'it'\\''s.sh'", RunInLogConsoleAction.quote("it's.sh"));
        assertEquals("'$(rm -rf x).sh'", RunInLogConsoleAction.quote("$(rm -rf x).sh"));
    }
}
