package dev.logconsole;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Decides the level of a log line. The pattern that matches <em>earliest</em> in the line wins,
 * so {@code "12:00 INFO  Retrying after ERROR"} is INFO, not ERROR.
 */
final class LogClassifier {
    static final String DEFAULT_ERROR =
            "\\b(ERROR|FATAL|SEVERE|CRITICAL)\\b|^Exception in thread |^[\\w$.]*(Exception|Error)(:|$)";
    static final String DEFAULT_WARN = "\\b(WARN|WARNING)\\b";
    static final String DEFAULT_DEBUG = "\\b(DEBUG|TRACE|FINE|FINER|FINEST)\\b";
    static final String DEFAULT_OTHER = "\\b(INFO|CONFIG|NOTICE)\\b";
    static final String DEFAULT_CONTINUATION =
            "^\\s+at |^\\s*\\.\\.\\. \\d+ (more|common frames omitted)|^Caused by: |^\\s+Suppressed: |^\\s*Wrapped by: ";

    private final Pattern error;
    private final Pattern warn;
    private final Pattern debug;
    private final Pattern other;
    private final Pattern continuation;
    private final boolean inheritUntagged;

    LogClassifier(String error, String warn, String debug, String other, String continuation, boolean inheritUntagged) {
        this.error = compile(error, DEFAULT_ERROR);
        this.warn = compile(warn, DEFAULT_WARN);
        this.debug = compile(debug, DEFAULT_DEBUG);
        this.other = compile(other, DEFAULT_OTHER);
        this.continuation = compile(continuation, DEFAULT_CONTINUATION);
        this.inheritUntagged = inheritUntagged;
    }

    static LogClassifier fromSettings() {
        LogConsoleSettings.State s = LogConsoleSettings.getInstance().getState();
        return new LogClassifier(s.errorPattern, s.warnPattern, s.debugPattern, s.otherPattern, s.continuationPattern,
                s.inheritUntagged);
    }

    /**
     * @param previous level of the previous output line, or null
     */
    LogLevel classify(String text, LogLevel previous) {
        if (continuation != null && continuation.matcher(text).find()) {
            return previous == LogLevel.ERROR || previous == LogLevel.WARN || previous == LogLevel.DEBUG
                    ? previous : LogLevel.OTHER;
        }

        int best = Integer.MAX_VALUE;
        LogLevel level = null;
        int e = start(error, text);
        if (e >= 0) {
            best = e;
            level = LogLevel.ERROR;
        }
        int w = start(warn, text);
        if (w >= 0 && w < best) {
            best = w;
            level = LogLevel.WARN;
        }
        int d = start(debug, text);
        if (d >= 0 && d < best) {
            best = d;
            level = LogLevel.DEBUG;
        }
        int o = start(other, text);
        if (o >= 0 && o < best) {
            level = LogLevel.OTHER;
        }
        if (level != null) return level;

        return inheritUntagged && previous != null ? previous : LogLevel.OTHER;
    }

    private static int start(Pattern p, String text) {
        if (p == null) return -1;
        Matcher m = p.matcher(text);
        return m.find() ? m.start() : -1;
    }

    private static Pattern compile(String regex, String fallback) {
        if (regex == null || regex.isBlank()) return null;
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException ex) {
            return Pattern.compile(fallback);
        }
    }
}
