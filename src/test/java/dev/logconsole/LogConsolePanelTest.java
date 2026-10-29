package dev.logconsole;

import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;

/** Drives a real panel (and its editor) the way the toolbar buttons do. */
public class LogConsolePanelTest extends BasePlatformTestCase {
    private LogConsolePanel panel;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        panel = new LogConsolePanel(getProject());
        Disposer.register(getTestRootDisposable(), panel);
    }

    private void output(String... texts) {
        for (String text : texts) panel.enqueue(new LogLine(text, LogLine.Kind.OUTPUT));
        panel.flush();
    }

    private String selected() {
        return panel.editor.getSelectionModel().getSelectedText();
    }

    public void testNextAndPreviousErrorVisitEveryErrorBlock() {
        output("info 1", "ERROR first", "\tat a.B(B.java:1)", "info 2", "ERROR second", "info 3", "ERROR third", "info 4");

        panel.jumpTo(LogLevel.ERROR, true);
        assertEquals("ERROR first", selected());
        assertMarked(1);
        assertFalse("a jump stops following the output", panel.scrollToEnd);
        panel.jumpTo(LogLevel.ERROR, true);
        assertEquals("ERROR second", selected());
        assertMarked(4);
        panel.jumpTo(LogLevel.ERROR, true);
        assertEquals("ERROR third", selected());
        panel.jumpTo(LogLevel.ERROR, true);
        assertEquals("ERROR first", selected());
        panel.jumpTo(LogLevel.ERROR, false);
        assertEquals("ERROR third", selected());
        panel.jumpTo(LogLevel.ERROR, false);
        assertEquals("ERROR second", selected());
    }

    private void assertMarked(int docLine) {
        assertTrue(panel.jumpHighlighter.isValid());
        assertEquals(docLine, panel.editor.getDocument().getLineNumber(panel.jumpHighlighter.getStartOffset()));
    }

    public void testFirstLineIsReachableFromTheEnd() {
        output("ERROR at the top", "info", "ERROR below");
        panel.jumpTo(LogLevel.ERROR, true);
        assertEquals("ERROR at the top", selected());
        output("more");
        panel.scrollToEndAndFollow();
        panel.jumpTo(LogLevel.ERROR, false);
        assertEquals("ERROR below", selected());
    }

    public void testScrollToTopPausesFollowing() {
        output("first", "second", "third");
        panel.scrollToTop();
        assertEquals(0, panel.editor.getCaretModel().getOffset());
        assertFalse(panel.scrollToEnd);
        output("fourth");
        assertEquals("new output does not move the view while paused", 0, panel.editor.getCaretModel().getOffset());
        panel.scrollToEndAndFollow();
        output("fifth");
        assertEquals(panel.editor.getDocument().getTextLength(), panel.editor.getCaretModel().getOffset());
    }

    public void testJumpPositionSurvivesNewOutput() {
        output("ERROR first", "info", "ERROR second", "info");
        panel.jumpTo(LogLevel.ERROR, true);
        assertEquals("ERROR first", selected());
        output("info more", "ERROR third");
        panel.jumpTo(LogLevel.ERROR, true);
        assertEquals("ERROR second", selected());
    }

    public void testJumpsWhileTheDemoIsRunning() {
        String demo = Path.of("samples/demo.sh").toAbsolutePath().toString();
        panel.runCommand("bash " + demo);
        long deadline = System.currentTimeMillis() + 30_000;
        while (panel.visibleLines.stream().filter(l -> l.level == LogLevel.ERROR && l.text.contains(" ERROR ")).count() < 4) {
            if (System.currentTimeMillis() > deadline) fail("no errors; shown: " + panel.visibleLines.stream().map(l -> l.text).toList());
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        }
        int previous = -1;
        for (int press = 0; press < 6; press++) {
            panel.jumpTo(LogLevel.ERROR, true);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            int line = panel.editor.getCaretModel().getLogicalPosition().line;
            assertEquals(LogLevel.ERROR, panel.visibleLines.get(line).level);
            assertTrue(line != previous);
            previous = line;
        }
    }

    private void filter(String text) {
        panel.filterField.setText(text);
        panel.applyFilter();
    }

    public void testNextAndPreviousMatchSelectEachMatchAndWrap() {
        output("alpha one", "beta", "alpha two alpha three", "gamma");
        filter("alpha");
        assertEquals("3 matches", panel.matchLabel.getText());

        panel.jumpToMatch(true);
        assertEquals("1/3", panel.matchLabel.getText());
        assertEquals("alpha", selected());
        assertMarked(0);
        panel.jumpToMatch(true);
        assertEquals("2/3", panel.matchLabel.getText());
        assertMarked(1);
        panel.jumpToMatch(true);
        assertEquals("3/3", panel.matchLabel.getText());
        assertEquals(panel.editor.getDocument().getText().lastIndexOf("alpha"), panel.editor.getSelectionModel().getSelectionStart());
        panel.jumpToMatch(true);
        assertEquals("1/3", panel.matchLabel.getText());
        panel.jumpToMatch(false);
        assertEquals("3/3", panel.matchLabel.getText());
        panel.jumpToMatch(false);
        assertEquals("2/3", panel.matchLabel.getText());

        output("alpha four");
        assertEquals("2/4", panel.matchLabel.getText());
        filter("zzz");
        assertEquals("No matches", panel.matchLabel.getText());
        panel.jumpToMatch(true);
        filter("");
        assertEquals("", panel.matchLabel.getText());
    }

    public void testExcludeModeHasNoMatchNavigation() {
        output("alpha", "beta");
        panel.excludeBox.setSelected(true);
        filter("alpha");
        assertEquals("", panel.matchLabel.getText());
        panel.jumpToMatch(true);
        assertFalse(panel.editor.getSelectionModel().hasSelection());
    }

    public void testLineCounterIsAlwaysShown() {
        output("one", "ERROR two", "three");
        assertEquals("Lines: 3", panel.lineCountLabel.getText());
        filter("t");
        assertEquals("Lines: 2 of 3", panel.lineCountLabel.getText());
        filter("");
        panel.errorBox.setSelected(false);
        assertEquals("Lines: 2 of 3", panel.lineCountLabel.getText());
    }

    public void testAdjacentIndex() {
        var list = new it.unimi.dsi.fastutil.ints.IntArrayList(new int[]{10, 20, 30});
        assertEquals(0, LogConsolePanel.adjacentIndex(list, 5, true));
        assertEquals(1, LogConsolePanel.adjacentIndex(list, 10, true));
        assertEquals(0, LogConsolePanel.adjacentIndex(list, 30, true));
        assertEquals(2, LogConsolePanel.adjacentIndex(list, 5, false));
        assertEquals(0, LogConsolePanel.adjacentIndex(list, 20, false));
        assertEquals(1, LogConsolePanel.adjacentIndex(list, 25, false));
    }

    /** What `sh demo.sh` printed under dash: nothing but back-to-back errors with stack traces. */
    public void testBackToBackErrorsAreSeparateStopsAndShowTheirPosition() {
        for (int i = 1; i <= 3; i++) {
            output("12:00 ERROR [app] Failed to process order " + i, "java.lang.IllegalStateException: order " + i + " is locked",
                    "\tat com.example.OrderService.process(OrderService.java:42)", "Caused by: java.sql.SQLException: lock timeout",
                    "\t... 12 more");
        }
        assertEquals("Errors (3)", panel.errorBox.getText());
        panel.jumpTo(LogLevel.ERROR, true);
        assertEquals("12:00 ERROR [app] Failed to process order 1", selected());
        assertEquals("Errors (1/3)", panel.errorBox.getText());
        panel.jumpTo(LogLevel.ERROR, true);
        assertEquals("12:00 ERROR [app] Failed to process order 2", selected());
        assertEquals("Errors (2/3)", panel.errorBox.getText());
        panel.jumpTo(LogLevel.ERROR, true);
        assertEquals("Errors (3/3)", panel.errorBox.getText());
        panel.jumpTo(LogLevel.ERROR, true);
        assertEquals("Errors (1/3)", panel.errorBox.getText());
        panel.jumpTo(LogLevel.ERROR, false);
        assertEquals("12:00 ERROR [app] Failed to process order 3", selected());
        assertEquals("Errors (3/3)", panel.errorBox.getText());

        output("12:01 ERROR [app] another");
        assertEquals("Errors (3/4)", panel.errorBox.getText());
        panel.scrollToEndAndFollow();
        assertEquals("Errors (4)", panel.errorBox.getText());
    }

    public void testScrollPagePausesFollowing() {
        output("ERROR one", "two");
        panel.jumpTo(LogLevel.ERROR, true);
        panel.scrollPage(true);
        assertFalse(panel.scrollToEnd);
        assertEquals("Errors (1)", panel.errorBox.getText());
        panel.scrollPage(false);
        assertTrue(panel.editor.getScrollingModel().getVerticalScrollOffset() >= 0);
    }

    public void testDebugLinesAreColoredWithTheDebugColor() {
        output("10:00:00 INFO start", "10:00:01 DEBUG details", "10:00:02 INFO done");
        int start = panel.editor.getDocument().getLineStartOffset(1);
        java.awt.Color debug = LogConsoleSettings.getInstance().debugColor();
        boolean colored = java.util.Arrays.stream(panel.editor.getMarkupModel().getAllHighlighters())
                .anyMatch(h -> h.getStartOffset() <= start && h.getEndOffset() > start
                        && h.getTextAttributes(null) != null
                        && debug.equals(h.getTextAttributes(null).getForegroundColor()));
        assertTrue(colored);
        int info = panel.editor.getDocument().getLineStartOffset(0);
        // Other lines keep the editor's text color unless a custom color is set.
        assertFalse(java.util.Arrays.stream(panel.editor.getMarkupModel().getAllHighlighters())
                .anyMatch(h -> h.getStartOffset() <= info && h.getEndOffset() > info
                        && h.getTextAttributes(null) != null && h.getTextAttributes(null).getForegroundColor() != null));
    }
}
