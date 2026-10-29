package dev.logconsole;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellOutputParserTest {
    private static final String NONCE = ShellOutputParser.newNonce();
    private static final String PWD = ShellOutputParser.pwdMarker(NONCE);
    private static final String DONE = ShellOutputParser.completionMarker(NONCE);

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
    }, NONCE);

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

    /** A program printing the marker (e.g. `cat` of a crafted log) must not make the tab think bash is idle. */
    @Test
    void markersWithoutThisShellsNonceAreJustOutput() {
        parser.feed(PWD + "/a\n");
        parser.feed(ShellOutputParser.PWD_MARKER + "/forged\n");
        parser.feed(ShellOutputParser.pwdMarker("guessed") + "/forged\n");
        parser.feed(ShellOutputParser.COMPLETION_MARKER + "7\n");
        assertEquals(List.of("prompt:/a", "line:LCPWD:/forged", "line:LCPWD:guessed:/forged", "line:LCDONE:7"), events);
    }

    @Test
    void noncesAreUnique() {
        assertNotEquals(ShellOutputParser.newNonce(), ShellOutputParser.newNonce());
        assertTrue(ShellOutputParser.newNonce().matches("[0-9a-f]{32}"));
    }

    @Test
    void stripsControlCharactersButKeepsTabs() {
        parser.feed(PWD + "/a\n");
        parser.feed("a\u0007b\bc\u0000d\te\u007Ff\n");
        assertEquals(List.of("prompt:/a", "line:abcd\tef"), events);
    }

    @Test
    void emptyLinesAreKept() {
        parser.feed(PWD + "/a\n\n\n");
        assertEquals(List.of("prompt:/a", "line:", "line:"), events);
    }
}
