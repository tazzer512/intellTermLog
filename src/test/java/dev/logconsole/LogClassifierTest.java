package dev.logconsole;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogClassifierTest {
    private final LogClassifier classifier = defaults(false);

    private static LogClassifier defaults(boolean inheritUntagged) {
        return new LogClassifier(LogClassifier.DEFAULT_ERROR, LogClassifier.DEFAULT_WARN, LogClassifier.DEFAULT_DEBUG,
                LogClassifier.DEFAULT_OTHER, LogClassifier.DEFAULT_CONTINUATION, inheritUntagged);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // Spring Boot / Logback
            "2026-10-28T12:00:01.123Z ERROR 4242 --- [main] o.s.boot.SpringApplication : Application run failed | ERROR",
            "2026-10-28T12:00:01.123Z  WARN 4242 --- [main] o.h.engine.jdbc : HHH000342 | WARN",
            "2026-10-28T12:00:01.123Z  INFO 4242 --- [main] o.s.b.w.e.tomcat.TomcatWebServer : started | OTHER",
            "2026-10-28T12:00:01.123Z DEBUG 4242 --- [main] o.s.Foo : wiring | DEBUG",
            "12:00:01.123 [main] TRACE c.x.Bar - enter | DEBUG",
            "12:00:01 FATAL disk full | ERROR",
            "12:00:01 [WARNING] deprecated API | WARN",
            // java.util.logging
            "SEVERE: Servlet failed | ERROR",
            "WARNING: low memory | WARN",
            "FINE: details | DEBUG",
            "FINEST: more details | DEBUG",
            "CONFIG: setting | OTHER",
            // uncaught exceptions
            "Exception in thread \"main\" java.lang.IllegalStateException: boom | ERROR",
            "java.lang.NullPointerException: null | ERROR",
            "java.lang.OutOfMemoryError | ERROR",
            // the level token that comes first wins
            "12:00 INFO  Retrying after ERROR from stub | OTHER",
            "12:00 ERROR stub said WARN | ERROR",
            "12:00 DEBUG got ERROR response, retrying | DEBUG",
            // words containing a level are not a level
            "ERRORS: 0, WARNINGS: 0 | OTHER",
            "plain line | OTHER",
    })
    void classifiesByEarliestLevelToken(String line, LogLevel expected) {
        assertEquals(expected, classifier.classify(line, null));
    }

    @Test
    void stackTraceLinesKeepTheLevelOfTheLineAbove() {
        for (LogLevel level : new LogLevel[]{LogLevel.ERROR, LogLevel.WARN, LogLevel.DEBUG}) {
            assertEquals(level, classifier.classify("\tat com.example.Foo.bar(Foo.java:12)", level));
            assertEquals(level, classifier.classify("Caused by: java.io.IOException: closed", level));
            assertEquals(level, classifier.classify("\t... 42 more", level));
            assertEquals(level, classifier.classify("\t... 12 common frames omitted", level));
            assertEquals(level, classifier.classify("\tSuppressed: java.lang.Exception", level));
        }
        assertEquals(LogLevel.OTHER, classifier.classify("\tat com.example.Foo.bar(Foo.java:12)", LogLevel.OTHER));
        assertEquals(LogLevel.OTHER, classifier.classify("\tat com.example.Foo.bar(Foo.java:12)", null));
    }

    @Test
    void untaggedLinesInheritOnlyWhenEnabled() {
        assertEquals(LogLevel.OTHER, classifier.classify("  continuation of a message", LogLevel.ERROR));
        LogClassifier inheriting = defaults(true);
        assertEquals(LogLevel.ERROR, inheriting.classify("  continuation of a message", LogLevel.ERROR));
        assertEquals(LogLevel.OTHER, inheriting.classify("  continuation of a message", null));
        assertEquals(LogLevel.OTHER, inheriting.classify("12:00 INFO tagged", LogLevel.ERROR));
    }

    @Test
    void blankPatternsDisableThatLevel() {
        LogClassifier noDebug = new LogClassifier(LogClassifier.DEFAULT_ERROR, LogClassifier.DEFAULT_WARN, "",
                LogClassifier.DEFAULT_OTHER, LogClassifier.DEFAULT_CONTINUATION, false);
        assertEquals(LogLevel.OTHER, noDebug.classify("12:00 DEBUG x", null));
    }

    @Test
    void invalidPatternFallsBackToTheDefault() {
        LogClassifier broken = new LogClassifier("([unclosed", LogClassifier.DEFAULT_WARN, LogClassifier.DEFAULT_DEBUG,
                LogClassifier.DEFAULT_OTHER, LogClassifier.DEFAULT_CONTINUATION, false);
        assertEquals(LogLevel.ERROR, broken.classify("12:00 ERROR x", null));
    }

    @Test
    void customPatterns() {
        LogClassifier custom = new LogClassifier("\\bE/", "\\bW/", "\\bD/", "\\bI/", "", false);
        assertEquals(LogLevel.ERROR, custom.classify("12:00 E/Tag: failed", null));
        assertEquals(LogLevel.WARN, custom.classify("12:00 W/Tag: careful", null));
        assertEquals(LogLevel.DEBUG, custom.classify("12:00 D/Tag: detail", null));
        assertEquals(LogLevel.OTHER, custom.classify("12:00 ERROR not matched by the custom pattern", null));
    }
}
