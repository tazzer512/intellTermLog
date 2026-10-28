package dev.logconsole;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShellOutputParserTest {
    private static final String PWD = ShellOutputParser.PWD_MARKER;
    private static final String DONE = ShellOutputParser.COMPLETION_MARKER;

    /** Records parser events as strings, e.g. "line:foo", "prompt:/tmp", "done:3". */
    private final List<String> events = new ArrayList<>();
    private final ShellOutputParser parser = new ShellOutputParser(new ShellOutputParser.Listener() {
        @Override
        public void line(String text) {
            events.add("line:" + text);
        }

        @Override
        public void prompt(String directory) {
            events.add("prompt:" + directory);
        }

        @Override
        public void completionDone(String id) {
            events.add("done:" + id);
        }
    });

    @Test
    void dropsOutputBeforeTheFirstPrompt() {
        parser.feed("bashrc noise\r\n");
        parser.feed(" stty -echo; PS1=''\r\n");
        parser.feed(PWD + "/home/me\r\n");
        parser.feed("hello\r\n");
        assertEquals(List.of("prompt:/home/me", "line:hello"), events);
    }

    @Test
    void promptMarkerAfterUnterminatedOutputEmitsThatOutput() {
        parser.feed(PWD + "/a\n");
        parser.feed("no newline at end" + PWD + "/b\n");
        assertEquals(List.of("prompt:/a", "line:no newline at end", "prompt:/b"), events);
    }

    @Test
    void completionMarker() {
        parser.feed(PWD + "/a\n");
        parser.feed(DONE + "7\n");
        parser.feed("partial" + DONE + "8 \n");
        assertEquals(List.of("prompt:/a", "done:7", "line:partial", "done:8"), events);
    }

    @Test
    void completionMarkerIsReportedBeforeTheFirstPromptToo() {
        parser.feed(DONE + "1\n");
        assertEquals(List.of("done:1"), events);
    }

    @Test
    void linesSplitAcrossChunks() {
        parser.feed(PWD + "/a\n");
        parser.feed("hel");
        parser.feed("lo\r");
        parser.feed("\nwor");
        parser.feed("ld\n");
        assertEquals(List.of("prompt:/a", "line:hello", "line:world"), events);
    }

    @Test
    void carriageReturnOverwritesTheLine() {
        parser.feed(PWD + "/a\n");
        parser.feed("progress 10%\rprogress 50%\r");
        parser.feed("progress 100%\r\n");
        assertEquals(List.of("prompt:/a", "line:progress 100%"), events);
    }

    @Test
    void stripsAnsiEscapes() {
        parser.feed(PWD + "/a\n");
        parser.feed("\u001B[31mred\u001B[0m \u001B]0;title\u0007plain \u001B(Bnormal\n");
        assertEquals(List.of("prompt:/a", "line:red plain normal"), events);
    }

    @Test
    void veryLongLinesAreSplit() {
        parser.feed(PWD + "/a\n");
        parser.feed("x".repeat(ShellOutputParser.MAX_LINE_LENGTH + 10) + "\n");
        assertEquals(3, events.size());
        assertEquals(ShellOutputParser.MAX_LINE_LENGTH, events.get(1).length() - "line:".length());
        assertEquals("line:" + "x".repeat(10), events.get(2));
    }

    @Test
    void finishEmitsTheLastUnterminatedLine() {
        parser.feed(PWD + "/a\n");
        parser.feed("exit message");
        parser.finish();
        parser.finish();
        assertEquals(List.of("prompt:/a", "line:exit message"), events);
    }

    @Test
    void emptyLinesAreKept() {
        parser.feed(PWD + "/a\n\n\n");
        assertEquals(List.of("prompt:/a", "line:", "line:"), events);
    }
}
