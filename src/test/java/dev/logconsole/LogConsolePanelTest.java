package dev.logconsole;

import com.intellij.execution.filters.ConsoleFilterProvider;
import com.intellij.execution.filters.Filter;
import com.intellij.execution.impl.EditorHyperlinkSupport;
import com.intellij.ide.IdeEventQueue;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Drives a real panel (and its editor, and for some tests its bash) the way the toolbar buttons do. */
public class LogConsolePanelTest extends BasePlatformTestCase {
    private static final String HISTORY_KEY = "logConsole.history";

    private LogConsolePanel panel;
    private final List<Process> started = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        panel = newPanel();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            started.forEach(Process::destroyForcibly);
        } finally {
            super.tearDown();
        }
    }

    private LogConsolePanel newPanel() {
        LogConsolePanel newPanel = new LogConsolePanel(getProject());
        Disposer.register(getTestRootDisposable(), newPanel);
        return newPanel;
    }

    // ---------------------------------------------------------------- helpers

    private void output(String... texts) {
        for (String text : texts) panel.enqueue(new LogLine(text, LogLine.Kind.OUTPUT));
        panel.flush();
    }

    /** The program output lines in the document (no commands or system messages). */
    private List<String> shownOutput() {
        return panel.visibleLines.stream().filter(l -> l.kind == LogLine.Kind.OUTPUT).map(l -> l.text).toList();
    }

    private String documentText() {
        return panel.editor.getDocument().getText();
    }

    private String selected() {
        return panel.editor.getSelectionModel().getSelectedText();
    }

    private int caretLine() {
        return panel.editor.getCaretModel().getLogicalPosition().line;
    }

    private void filter(String text) {
        panel.filterField.setText(text);
        panel.applyFilter();
    }

    private void assertMarked(int docLine) {
        assertTrue(panel.jumpHighlighter.isValid());
        assertEquals(docLine, panel.editor.getDocument().getLineNumber(panel.jumpHighlighter.getStartOffset()));
    }

    /** Pumps the event queue (the panel's output and shell callbacks arrive on it) until the condition holds. */
    private void waitUntil(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for " + what + "; output:\n" + panel.outputText(true));
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** Runs a command in the tab's shell and waits until bash is idle again. */
    private void run(String command) {
        panel.runCommand(command);
        waitForIdle();
    }

    /** Idle again, with the command's output shown (the prompt can arrive before the 50 ms output flush). */
    private void waitForIdle() {
        waitUntil("the shell to be idle", () -> panel.shellReady && !panel.busy);
        panel.flush();
    }

    private long shellPid() {
        return panel.shell.getProcess().pid();
    }

    /** Waits for a `sleep` started from the shell, so Stop/Ctrl+C have something to end. */
    private void waitForSleep() {
        long shell = shellPid();
        waitUntil("sleep to start", () -> ProcessHandle.of(shell).map(p -> p.descendants()
                .anyMatch(d -> d.info().command().map(c -> c.endsWith("/sleep")).orElse(false))).orElse(false));
    }

    /** A mouse click dispatched through the event queue, so listeners see it as the current event. */
    private static void click(JComponent component, int x, int y, int clickCount, int modifiers, int... ids) {
        for (int id : ids) {
            int buttonMask = id == MouseEvent.MOUSE_PRESSED ? InputEvent.BUTTON1_DOWN_MASK : 0;
            IdeEventQueue.getInstance().dispatchEvent(new MouseEvent(component, id, System.currentTimeMillis(),
                    modifiers | buttonMask, x, y, clickCount, false, MouseEvent.BUTTON1));
        }
    }

    private static void clickCheckBox(JComponent box, int modifiers) {
        box.setSize(100, 20);
        click(box, 5, 5, 1, modifiers, MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED);
    }

    /** Foreground colors of the highlighters that cover that offset. */
    private List<Color> foregroundsAt(int offset) {
        return Arrays.stream(panel.editor.getMarkupModel().getAllHighlighters())
                .filter(h -> h.getStartOffset() <= offset && h.getEndOffset() > offset)
                .map(h -> h.getTextAttributes(null))
                .filter(a -> a != null && a.getForegroundColor() != null)
                .map(TextAttributes::getForegroundColor)
                .toList();
    }

    private List<RangeHighlighter> bookmarkMarks() {
        return Arrays.stream(panel.editor.getMarkupModel().getAllHighlighters())
                .filter(h -> h.getGutterIconRenderer() != null).toList();
    }

    private List<String> toolbarActionTexts() {
        List<String> texts = new ArrayList<>();
        ActionGroup group = ((ActionToolbar) panel.getToolbar()).getActionGroup();
        for (AnAction action : ((DefaultActionGroup) group).getChildActionsOrStubs()) {
            if (action.getTemplateText() != null) texts.add(action.getTemplateText());
        }
        return texts;
    }

    // ---------------------------------------------------------------- level jumps

    public void testNextAndPreviousErrorVisitEveryErrorBlock() {
        output("info 1", "ERROR first", "\tat a.B(B.java:1)", "info 2", "ERROR second", "info 3", "ERROR third", "info 4");

        panel.jumpTo(LogLevel.ERROR, true);
        assertEquals("ERROR first", selected());
        assertMarked(1);
        assertFalse("a jump stops following the output", panel.scrollToEnd);
        panel.jumpTo(LogLevel.ERROR, true);
        assertEquals("the stack trace belongs to the first error", "ERROR second", selected());
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

    public void testWarningAndDebugJumpsAndHiddenLevelsHaveNoStops() {
        output("12:00 INFO a", "12:00 WARN careful", "12:00 DEBUG detail", "12:00 WARN again");
        panel.jumpTo(LogLevel.WARN, true);
        assertEquals("12:00 WARN careful", selected());
        assertEquals("Warnings (1/2)", panel.warnBox.getText());
        panel.jumpTo(LogLevel.DEBUG, true);
        assertEquals("12:00 DEBUG detail", selected());
        assertEquals("the other level's position is forgotten", "Warnings (2)", panel.warnBox.getText());
        assertEquals("Debug (1/1)", panel.debugBox.getText());

        panel.warnBox.setSelected(false);
        panel.editor.getSelectionModel().removeSelection();
        panel.jumpTo(LogLevel.WARN, true);
        assertNull("hidden warnings are not jumped to", selected());
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

    public void testJumpPositionSurvivesNewOutput() {
        output("ERROR first", "info", "ERROR second", "info");
        panel.jumpTo(LogLevel.ERROR, true);
        assertEquals("ERROR first", selected());
        output("info more", "ERROR third");
        panel.jumpTo(LogLevel.ERROR, true);
        assertEquals("ERROR second", selected());
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

    public void testJumpsWhileTheDemoIsRunning() {
        String demo = Path.of("samples/demo.sh").toAbsolutePath().toString();
        panel.runCommand("bash " + demo);
        waitUntil("4 errors from the demo",
                () -> panel.visibleLines.stream().filter(l -> l.level == LogLevel.ERROR && l.text.contains(" ERROR ")).count() >= 4);
        int previous = -1;
        for (int press = 0; press < 6; press++) {
            panel.jumpTo(LogLevel.ERROR, true);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            int line = caretLine();
            assertEquals(LogLevel.ERROR, panel.visibleLines.get(line).level);
            assertTrue(line != previous);
            previous = line;
        }
    }

    public void testAdjacentIndex() {
        var list = new IntArrayList(new int[]{10, 20, 30});
        assertEquals(0, LogConsolePanel.adjacentIndex(list, 5, true));
        assertEquals(1, LogConsolePanel.adjacentIndex(list, 10, true));
        assertEquals(0, LogConsolePanel.adjacentIndex(list, 30, true));
        assertEquals(2, LogConsolePanel.adjacentIndex(list, 5, false));
        assertEquals(0, LogConsolePanel.adjacentIndex(list, 20, false));
        assertEquals(1, LogConsolePanel.adjacentIndex(list, 25, false));
    }

    // ---------------------------------------------------------------- scrolling

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

    public void testScrollPageMovesTheViewAndPausesFollowing() {
        String[] lines = new String[100];
        Arrays.fill(lines, "line");
        output(lines);
        panel.scrollToTop();
        assertEquals(0, panel.editor.getScrollingModel().getVerticalScrollOffset());
        panel.scrollToEnd = true;
        panel.scrollPage(true);
        assertFalse(panel.scrollToEnd);
        int down = panel.editor.getScrollingModel().getVerticalScrollOffset();
        assertTrue(String.valueOf(down), down > 0);
        panel.scrollPage(false);
        assertEquals(0, panel.editor.getScrollingModel().getVerticalScrollOffset());
        panel.scrollPage(false);
        assertEquals("no scrolling above the top", 0, panel.editor.getScrollingModel().getVerticalScrollOffset());
    }

    public void testScrollingForgetsTheJumpPosition() {
        output("ERROR one", "two");
        panel.jumpTo(LogLevel.ERROR, true);
        assertEquals("Errors (1/1)", panel.errorBox.getText());
        panel.scrollPage(true);
        assertEquals("Errors (1)", panel.errorBox.getText());
    }

    // ---------------------------------------------------------------- filter

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
        assertEquals(documentText().lastIndexOf("alpha"), panel.editor.getSelectionModel().getSelectionStart());
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
        assertFalse(panel.editor.getSelectionModel().hasSelection());
        filter("");
        assertEquals("", panel.matchLabel.getText());
        assertEquals(5, shownOutput().size());
    }

    public void testFilterIsLiteralAndCaseInsensitiveUnlessAsked() {
        output("Alpha 1", "alpha 2", "beta 3", "a.b 4");
        filter("alpha");
        assertEquals(List.of("Alpha 1", "alpha 2"), shownOutput());
        panel.caseBox.setSelected(true);
        assertEquals(List.of("alpha 2"), shownOutput());
        panel.caseBox.setSelected(false);

        filter("a.b");
        assertEquals("without Regex the dot is literal", List.of("a.b 4"), shownOutput());
        panel.regexBox.setSelected(true);
        filter("^a.*2$");
        assertEquals(List.of("alpha 2"), shownOutput());
        filter("alpha|beta");
        assertEquals(List.of("Alpha 1", "alpha 2", "beta 3"), shownOutput());
    }

    public void testInvalidRegexShowsTheErrorAndAllLines() {
        output("one", "two");
        panel.regexBox.setSelected(true);
        filter("([");
        assertEquals(List.of("one", "two"), shownOutput());
        assertTrue(panel.statusLabel.getText(), panel.statusLabel.getText().contains("Invalid regex"));
        filter("o");
        assertFalse(panel.statusLabel.getText().contains("Invalid regex"));
    }

    public void testExcludeModeHidesMatchesAndHasNoMatchNavigation() {
        output("alpha", "beta");
        panel.excludeBox.setSelected(true);
        filter("alpha");
        assertEquals(List.of("beta"), shownOutput());
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

    public void testHidingALevelHidesItsStackTracesButKeepsTheCount() {
        output("10:00 ERROR boom", "\tat a.B(B.java:1)", "\t... 3 more", "10:00 INFO ok");
        panel.errorBox.setSelected(false);
        assertEquals(List.of("10:00 INFO ok"), shownOutput());
        assertEquals("Errors (1)", panel.errorBox.getText());
        panel.errorBox.setSelected(true);
        assertEquals(4, shownOutput().size());
    }

    public void testCtrlClickSolosALevelAndRestoresThePreviousLevels() {
        output("ERROR e", "WARN w", "DEBUG d", "INFO i");
        panel.debugBox.setSelected(false);
        assertEquals("Lines: 3 of 4", panel.lineCountLabel.getText());

        panel.toggleSoloLevel(panel.warnBox);
        assertEquals(List.of("WARN w"), shownOutput());
        panel.toggleSoloLevel(panel.errorBox); // another solo keeps the levels from before the first one
        assertEquals(List.of("ERROR e"), shownOutput());
        panel.toggleSoloLevel(panel.errorBox);
        assertEquals(List.of("ERROR e", "WARN w", "INFO i"), shownOutput());
        assertFalse(panel.debugBox.isSelected());

        panel.toggleSoloLevel(panel.otherBox);
        panel.warnBox.setSelected(true); // a manual change ends the solo: no restore
        panel.toggleSoloLevel(panel.otherBox);
        assertEquals(List.of("INFO i"), shownOutput());
    }

    public void testCtrlClickOnALevelCheckbox() {
        output("ERROR e", "WARN w");
        clickCheckBox(panel.warnBox, InputEvent.CTRL_DOWN_MASK);
        assertEquals(List.of("WARN w"), shownOutput());
        assertTrue("the clicked box stays on", panel.warnBox.isSelected());
        clickCheckBox(panel.warnBox, InputEvent.CTRL_DOWN_MASK);
        assertEquals(List.of("ERROR e", "WARN w"), shownOutput());
        clickCheckBox(panel.warnBox, 0);
        assertFalse(panel.warnBox.isSelected());
        assertEquals(List.of("ERROR e"), shownOutput());
    }

    public void testCtrlOrCmdModifierCounts() {
        JComponent source = panel.warnBox;
        assertTrue(LogConsolePanel.isCtrlClick(new MouseEvent(source, MouseEvent.MOUSE_RELEASED, 0, InputEvent.CTRL_DOWN_MASK, 0, 0, 1, false)));
        assertTrue(LogConsolePanel.isCtrlClick(new MouseEvent(source, MouseEvent.MOUSE_RELEASED, 0, InputEvent.META_DOWN_MASK, 0, 0, 1, false)));
        assertFalse(LogConsolePanel.isCtrlClick(new MouseEvent(source, MouseEvent.MOUSE_RELEASED, 0, InputEvent.SHIFT_DOWN_MASK, 0, 0, 1, false)));
        assertFalse(LogConsolePanel.isCtrlClick(null));
    }

    // ---------------------------------------------------------------- editor

    public void testCtrlDoubleClickSelectsTheWholeLine() {
        output("first line", "ERROR the second line");
        JComponent text = panel.editor.getContentComponent();
        text.setSize(800, 200);
        Point at = panel.editor.logicalPositionToXY(new LogicalPosition(1, 8));
        int[] doubleClick = {MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED};
        click(text, at.x + 1, at.y + 1, 2, 0, doubleClick);
        assertEquals("a plain double-click selects the word", "the", selected());
        click(text, at.x + 1, at.y + 1, 2, InputEvent.CTRL_DOWN_MASK, doubleClick);
        assertEquals("ERROR the second line", selected());
    }

    public void testLevelColors() {
        output("10:00:00 INFO start", "10:00:01 DEBUG details", "10:00:02 ERROR bad", "10:00:03 WARN hmm");
        LogConsoleSettings settings = LogConsoleSettings.getInstance();
        var doc = panel.editor.getDocument();
        assertEquals(List.of(), foregroundsAt(doc.getLineStartOffset(0))); // the editor's own text color
        assertEquals(List.of(settings.debugColor()), foregroundsAt(doc.getLineStartOffset(1)));
        assertEquals(List.of(settings.errorColor()), foregroundsAt(doc.getLineStartOffset(2)));
        assertEquals(List.of(settings.warnColor()), foregroundsAt(doc.getLineStartOffset(3)));

        LogConsoleSettings.State saved = settings.getState();
        try {
            LogConsoleSettings.State custom = new LogConsoleSettings.State();
            custom.otherColor = "445566";
            settings.update(custom);
            assertEquals(List.of(new Color(0x445566)), foregroundsAt(panel.editor.getDocument().getLineStartOffset(0)));
        } finally {
            settings.update(saved);
        }
    }

    /** A console filter like the Java stack-trace one: links every "(Foo.java:12)". */
    private static final Filter SOURCE_LINKS = (line, entireLength) -> {
        Matcher m = Pattern.compile("\\(\\w+\\.java:\\d+\\)").matcher(line);
        if (!m.find()) return null;
        int lineStart = entireLength - line.length();
        return new Filter.Result(lineStart + m.start() + 1, lineStart + m.end() - 1, project -> { });
    };

    private List<String> linkTexts() {
        // Internal API, so only here: in the plugin it would fail the Marketplace verifier.
        EditorHyperlinkSupport.get(panel.editor).waitForPendingFilters(5_000);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        String text = documentText();
        return EditorHyperlinkSupport.get(panel.editor).getAllHyperlinks(0, text.length()).stream()
                .map(h -> text.substring(h.getStartOffset(), h.getEndOffset())).sorted().toList();
    }

    public void testStackTraceLinksFromConsoleFilters() {
        ConsoleFilterProvider.FILTER_PROVIDERS.getPoint().registerExtension(project -> new Filter[]{SOURCE_LINKS}, getTestRootDisposable());
        panel.linkFilter = null;
        output("10:00:00 ERROR boom", "java.lang.IllegalStateException: bad",
                "\tat dev.App.run(App.java:12)", "\tat dev.Main.main(Main.java:3)", "10:00:01 INFO ok");
        assertEquals(List.of("App.java:12", "Main.java:3"), linkTexts());

        // The filter rebuilds the document; links are found again at the new offsets.
        filter("Main");
        assertEquals(List.of("Main.java:3"), linkTexts());
        filter("");
        assertEquals(List.of("App.java:12", "Main.java:3"), linkTexts());
        panel.clear();
        assertEquals(List.of(), linkTexts());
    }

    // ---------------------------------------------------------------- output buffer

    public void testOutputTextIsAllLinesOrTheShownLines() {
        output("10:00:00 INFO one", "10:00:01 ERROR two", "10:00:02 INFO three");
        assertEquals("10:00:00 INFO one\n10:00:01 ERROR two\n10:00:02 INFO three\n", panel.outputText(true));
        filter("ERROR");
        assertEquals("10:00:01 ERROR two\n", panel.outputText(false));
        assertEquals(3, panel.outputText(true).split("\n").length);
    }

    public void testClearDropsOutputCountsBookmarksAndJumpPosition() {
        output("10:00 ERROR one", "10:00 WARN two", "10:00 INFO three");
        panel.toggleBookmark(panel.visibleLines.get(0));
        panel.jumpTo(LogLevel.ERROR, true);
        panel.clear();
        assertEquals("", documentText());
        assertEquals("", panel.outputText(true));
        assertEquals("Errors (0)", panel.errorBox.getText());
        assertEquals("Warnings (0)", panel.warnBox.getText());
        assertEquals("Lines: 0", panel.lineCountLabel.getText());
        assertTrue(panel.bookmarks.isEmpty());
        assertEquals(List.of(), bookmarkMarks());

        output("10:01 ERROR after clear");
        assertEquals("Errors (1)", panel.errorBox.getText());
    }

    public void testOldestLinesAreDroppedBeyondMaxLines() {
        LogConsoleSettings settings = LogConsoleSettings.getInstance();
        LogConsoleSettings.State saved = settings.getState();
        try {
            LogConsoleSettings.State small = new LogConsoleSettings.State();
            small.maxLines = 1_000; // the minimum
            settings.update(small);
            output("10:00 ERROR first");
            panel.toggleBookmark(panel.visibleLines.get(0));
            String[] more = new String[1_000];
            for (int i = 0; i < more.length; i++) more[i] = "line " + i;
            output(more);

            // 1,001 lines > 1,000: the oldest 10% beyond the limit go at once, so trimming is rare.
            List<String> shown = shownOutput();
            assertEquals(900, shown.size());
            assertEquals("line 100", shown.get(0));
            assertEquals("line 999", shown.get(899));
            assertEquals("Errors (0)", panel.errorBox.getText());
            assertEquals("Lines: 900", panel.lineCountLabel.getText());
            assertTrue("the bookmark went with its line", panel.bookmarks.isEmpty());
        } finally {
            settings.update(saved);
        }
    }

    public void testOutputArrivingFasterThanShownIsCapped() {
        LogConsoleSettings settings = LogConsoleSettings.getInstance();
        LogConsoleSettings.State saved = settings.getState();
        try {
            LogConsoleSettings.State large = new LogConsoleSettings.State();
            large.maxLines = 1_000_000;
            settings.update(large);
            for (int i = 0; i < 200_005; i++) panel.enqueue(new LogLine("line " + i, LogLine.Kind.OUTPUT));
            panel.flush();
            LogLine first = panel.visibleLines.get(0);
            assertEquals(LogLine.Kind.SYSTEM, first.kind);
            assertTrue(first.text, first.text.startsWith("… 5 lines dropped"));
            assertEquals("line 5", panel.visibleLines.get(1).text);
            assertEquals(200_000, shownOutput().size());
        } finally {
            settings.update(saved);
        }
    }

    // ---------------------------------------------------------------- bookmarks

    public void testBookmarksAreInOutputOrderAndSurviveFilters() {
        output("10:00 INFO a", "10:00 ERROR b", "10:00 INFO c");
        LogLine a = panel.visibleLines.get(0);
        LogLine c = panel.visibleLines.get(2);
        panel.toggleBookmark(c);
        panel.addBookmark(a, "start");
        assertEquals(List.of(a, c), panel.bookmarks.getItems());
        assertEquals(2, bookmarkMarks().size());

        filter("ERROR");
        assertEquals("a filtered-out line keeps its bookmark", List.of(a, c), panel.bookmarks.getItems());
        assertEquals(List.of(), bookmarkMarks());
        panel.goTo(a, false);
        assertEquals("ERROR", panel.filterField.getText());
        panel.goTo(a, true);
        assertEquals("revealing a hidden bookmark clears the filter", "", panel.filterField.getText());
        assertEquals(0, caretLine());
        assertEquals(2, bookmarkMarks().size());

        panel.toggleBookmark(c);
        assertEquals(List.of(a), panel.bookmarks.getItems());
        assertNull(c.bookmarkNote);
        assertEquals("start", a.bookmarkNote);
        panel.removeAllBookmarks();
        assertTrue(panel.bookmarks.isEmpty());
        assertNull(a.bookmarkNote);
        assertEquals(List.of(), bookmarkMarks());
    }

    public void testNextAndPreviousBookmarkWrapAround() {
        output("zero", "one", "two", "three", "four");
        panel.toggleBookmark(panel.visibleLines.get(1));
        panel.toggleBookmark(panel.visibleLines.get(3));
        panel.scrollToTop();
        panel.jumpToBookmark(true);
        assertEquals(1, caretLine());
        assertEquals("one", panel.bookmarkList.getSelectedValue().text);
        panel.jumpToBookmark(true);
        assertEquals(3, caretLine());
        panel.jumpToBookmark(true);
        assertEquals(1, caretLine());
        panel.jumpToBookmark(false);
        assertEquals(3, caretLine());
    }

    public void testCopyBookmarksPutsNotesAboveTheirLines() throws Exception {
        output("10:00 INFO a", "10:00 INFO b", "10:00 INFO c");
        panel.addBookmark(panel.visibleLines.get(0), "why");
        panel.toggleBookmark(panel.visibleLines.get(2));
        panel.copyBookmarks();
        assertEquals("# why\n10:00 INFO a\n10:00 INFO c\n", CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor));

        panel.bookmarkList.setSelectedValue(panel.visibleLines.get(2), false);
        panel.copyBookmarks();
        assertEquals("only the selected bookmarks", "10:00 INFO c\n", CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor));
    }

    // ---------------------------------------------------------------- settings, toolbar, shortcuts

    public void testSettingsPageRoundTripsColors() throws Exception {
        LogConsoleSettings.State saved = LogConsoleSettings.getInstance().getState();
        LogConsoleConfigurable page = new LogConsoleConfigurable();
        try {
            LogConsoleSettings.State custom = new LogConsoleSettings.State();
            custom.debugColor = "112233";
            custom.otherColor = "445566";
            LogConsoleSettings.getInstance().update(custom);
            assertNotNull(page.createComponent());
            assertFalse(page.isModified());
            page.apply();
            assertEquals("112233", LogConsoleSettings.getInstance().getState().debugColor);
            assertEquals("445566", LogConsoleSettings.getInstance().getState().otherColor);

            // Restore Defaults: debug back to the theme green, no custom color for other lines.
            LogConsoleSettings.getInstance().update(new LogConsoleSettings.State());
            page.reset();
            assertFalse(page.isModified());
            page.apply();
            assertNull(LogConsoleSettings.getInstance().getState().debugColor);
            assertNull(LogConsoleSettings.getInstance().getState().otherColor);
        } finally {
            page.disposeUIResources();
            LogConsoleSettings.getInstance().update(saved);
        }
    }

    public void testErrorAndMatchNavigationUseTheIdeShortcuts() {
        Map<String, String> shortcuts = new HashMap<>();
        for (AnAction action : ActionUtil.getActions(panel)) {
            shortcuts.put(action.getTemplateText(), KeymapUtil.getFirstKeyboardShortcutText(action.getShortcutSet()));
        }
        assertEquals("F2", shortcuts.get("Next Error"));
        assertEquals("Shift+F2", shortcuts.get("Previous Error"));
        assertEquals("F3", shortcuts.get("Next Match"));
        assertEquals("Shift+F3", shortcuts.get("Previous Match"));
    }

    public void testToolbarHasKillButNoInterruptButton() {
        List<String> texts = toolbarActionTexts();
        assertTrue(texts.toString(), texts.contains("Kill Shell and All Processes"));
        assertTrue(texts.toString(), texts.contains("Java Processes"));
        assertFalse("Ctrl+C in the command box interrupts instead", texts.stream().anyMatch(t -> t.startsWith("Interrupt")));
    }

    // ---------------------------------------------------------------- the shell

    public void testCommandsShareOnePersistentShell() throws Exception {
        Path dir = Files.createTempDirectory("log-console-test");
        try {
            run("cd " + ShellText.shellQuote(dir.toString()));
            String real = dir.toRealPath().toString();
            assertEquals(real, panel.cwd);
            run("export LC_TEST_VALUE=kept");
            run("echo \"value=$LC_TEST_VALUE pwd=$(pwd)\"");
            assertEquals(List.of("value=kept pwd=" + real), shownOutput());
        } finally {
            FileUtil.delete(dir.toFile());
        }
    }

    public void testMultiLineInputRunsLineByLine() {
        panel.input.setText("echo one\necho two");
        panel.submitInput();
        assertEquals("", panel.input.getText());
        waitUntil("both lines to run", () -> shownOutput().size() == 2 && !panel.busy);
        assertEquals(List.of("one", "two"), shownOutput());
        assertEquals("each line is echoed as a command", 2,
                panel.visibleLines.stream().filter(l -> l.kind == LogLine.Kind.COMMAND).count());
    }

    public void testHistorySkipsSpacePrefixedCommandsAndKeepsTheDraft() {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        List<String> saved = properties.getList(HISTORY_KEY);
        try {
            properties.setList(HISTORY_KEY, null);
            panel = newPanel(); // starts with an empty history
            for (String command : List.of("echo first", " echo secret", "echo second", "echo first")) {
                panel.input.setText(command);
                panel.submitInput();
            }
            assertEquals("re-running moves a command to the end; a leading space keeps it out",
                    List.of("echo second", "echo first"), properties.getList(HISTORY_KEY));

            panel.input.setText("draft");
            panel.historyMove(-1);
            assertEquals("echo first", panel.input.getText());
            panel.historyMove(-1);
            assertEquals("echo second", panel.input.getText());
            panel.historyMove(-1);
            assertEquals("the oldest entry stays", "echo second", panel.input.getText());
            panel.historyMove(1);
            assertEquals("echo first", panel.input.getText());
            panel.historyMove(1);
            assertEquals("draft", panel.input.getText());
            waitForIdle();
        } finally {
            properties.setList(HISTORY_KEY, saved);
        }
    }

    public void testTabCompletesFromTheLiveShell() {
        run("lc_test_function_xyz() { :; }");
        panel.input.setText("lc_test_func");
        panel.input.setCaretPosition(panel.input.getText().length());
        panel.complete();
        waitUntil("the completion", () -> panel.input.getText().equals("lc_test_function_xyz "));
    }

    public void testTabCompletesFileNamesWhileACommandRuns() throws Exception {
        Path dir = Files.createTempDirectory("log-console-test");
        try {
            Files.createFile(dir.resolve("unique-file.txt"));
            run("cd " + ShellText.shellQuote(dir.toString()));
            panel.runCommand("sleep 300");
            waitForSleep();
            panel.input.setText("cat uniq");
            panel.input.setCaretPosition(panel.input.getText().length());
            panel.complete();
            assertEquals("cat unique-file.txt ", panel.input.getText());
            panel.stopAll();
            waitForIdle();
        } finally {
            FileUtil.delete(dir.toFile());
        }
    }

    public void testCtrlCInterruptsTheRunningCommand() {
        run("true");
        panel.runCommand("sleep 300");
        waitForSleep();
        assertTrue(panel.busy);
        panel.interrupt();
        waitForIdle();
    }

    public void testStopEndsTheRunningCommandButKeepsTheShell() {
        run("export LC_TEST_VALUE=kept");
        long shell = shellPid();
        panel.runCommand("sleep 300 & sleep 301; wait");
        waitForSleep();
        panel.stopAll();
        waitForIdle();
        assertEquals(shell, shellPid());
        run("echo \"value=$LC_TEST_VALUE\"");
        assertTrue(shownOutput().toString(), shownOutput().contains("value=kept"));
    }

    public void testKillStartsAFreshShell() {
        run("export LC_TEST_VALUE=kept");
        long shell = shellPid();
        long start = System.currentTimeMillis();
        panel.kill(false);
        waitUntil("a new shell", () -> panel.shell != null && shellPid() != shell && panel.shellReady && !panel.busy);
        assertFalse(ProcessHandle.of(shell).map(ProcessHandle::isAlive).orElse(false));
        long took = System.currentTimeMillis() - start;
        assertTrue("SIGHUP ends bash at once, without the 5s SIGKILL fallback; took " + took + " ms", took < 3_000);
        run("echo \"value=[$LC_TEST_VALUE]\"");
        assertTrue(shownOutput().toString(), shownOutput().contains("value=[]"));
    }

    public void testCtrlClickKillCancelledLeavesEverythingRunning() throws Exception {
        Process javaApp = new ProcessBuilder("bash", "-c", "sleep 410; : java").start();
        started.add(javaApp);
        run("true");
        long shell = shellPid();
        List<List<ProcessHandle>> asked = new ArrayList<>();
        panel.javaProcesses = () -> List.of(javaApp.toHandle());
        panel.confirmKillJava = processes -> {
            asked.add(processes);
            return false;
        };
        panel.kill(true);
        waitUntil("the confirmation", () -> !asked.isEmpty());
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals(List.of(List.of(javaApp.toHandle())), asked);
        assertTrue(javaApp.isAlive());
        assertEquals("cancel also skips killing the shell", shell, shellPid());
    }

    public void testCtrlClickKillStopsJavaProcessesAndTheShell() throws Exception {
        Process javaApp = new ProcessBuilder("bash", "-c", "sleep 411; : java").start();
        started.add(javaApp);
        run("true");
        long shell = shellPid();
        panel.javaProcesses = () -> List.of(javaApp.toHandle());
        panel.confirmKillJava = processes -> true;
        panel.kill(true);
        waitUntil("the Java process to end", () -> !javaApp.isAlive());
        waitUntil("a new shell", () -> panel.shell != null && shellPid() != shell && panel.shellReady);
        assertTrue(panel.outputText(true), panel.outputText(true).contains("like pkill -f java"));
    }

    public void testJavaButtonShowsTheCount() throws Exception {
        Process javaApp = new ProcessBuilder("bash", "-c", "sleep 412; : java").start();
        started.add(javaApp);
        panel.javaProcesses = () -> List.of(javaApp.toHandle());
        panel.refreshJavaCount();
        assertEquals(1, panel.javaProcessCount);
        AnAction button = Arrays.stream(((DefaultActionGroup) ((ActionToolbar) panel.getToolbar()).getActionGroup())
                .getChildActionsOrStubs()).filter(a -> "Java Processes".equals(a.getTemplateText())).findFirst().orElseThrow();
        AnActionEvent event = TestActionEvent.createTestEvent(button);
        button.update(event);
        assertEquals("Java: 1", event.getPresentation().getText());
    }

    public void testKillJavaNowKillsAtOnce() throws Exception {
        Process javaApp = new ProcessBuilder("bash", "-c", "trap '' TERM; sleep 413; : java").start();
        started.add(javaApp);
        panel.javaProcesses = List::of;
        panel.killJavaNow(List.of(javaApp.toHandle()));
        assertTrue("SIGKILL, so even a process ignoring SIGTERM ends", javaApp.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
        panel.flush();
        assertTrue(panel.outputText(true), panel.outputText(true).contains("(SIGKILL)"));
        waitUntil("the count to refresh", () -> panel.javaProcessCount == 0);
    }

    public void testRealJavaProcessListNeverIncludesThisIde() {
        List<Long> pids = ShellProcesses.javaProcesses().stream().map(ProcessHandle::pid).toList();
        assertFalse(pids.contains(ProcessHandle.current().pid()));
    }
    // ---------------------------------------------------------------- review findings

    /** #1: output that prints the marker must not end the command early or change the working directory. */
    public void testProgramOutputCannotForgeTheIdleMarker() {
        run("true");
        String cwd = panel.cwd;
        panel.runCommand("printf 'before\\n\\036LCPWD:/forged\\n'; sleep 300");
        waitForSleep();
        waitUntil("the output", () -> {
            panel.flush();
            return shownOutput().contains("before");
        });
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertTrue("still running", panel.busy);
        assertEquals(cwd, panel.cwd);
        panel.stopAll();
        waitForIdle();
    }

    /** #2: a log line is shown as text in the bookmark tooltip, never rendered as HTML (which can load URLs). */
    public void testBookmarkTooltipDoesNotRenderLogLinesAsHtml() {
        output("<html><img src='http://example.invalid/x.png'>");
        LogLine line = panel.visibleLines.get(0);
        panel.toggleBookmark(line);
        JComponent cell = (JComponent) panel.bookmarkList.getCellRenderer()
                .getListCellRendererComponent(panel.bookmarkList, line, 0, false, false);
        String tip = cell.getToolTipText();
        assertNotNull(tip);
        assertFalse(tip, tip.contains("<img"));
        assertTrue(tip, tip.contains("&lt;img"));
    }

    /** #3: a file name with a newline would put a second command line into the command box. */
    public void testCompletionSkipsNamesWithControlCharacters() throws Exception {
        Path dir = Files.createTempDirectory("log-console-test");
        try {
            Files.createFile(dir.resolve("evil\nreboot"));
            run("cd " + ShellText.shellQuote(dir.toString()));
            panel.runCommand("sleep 300");
            waitForSleep();
            panel.input.setText("cat ev");
            panel.input.setCaretPosition(panel.input.getText().length());
            panel.complete();
            assertEquals("cat ev", panel.input.getText());
            panel.stopAll();
            waitForIdle();
        } finally {
            FileUtil.delete(dir.toFile());
        }
    }

    /** #4: the confirmation names each process (pid and command line), not just "java, java". */
    public void testKillJavaConfirmationListsTheCommandLines() throws Exception {
        Process app = new ProcessBuilder("bash", "-c", "sleep 412; : java -jar my-app.jar").start();
        started.add(app);
        String message = LogConsolePanel.killJavaMessage(List.of(app.toHandle()));
        assertTrue(message, message.contains(String.valueOf(app.pid())));
        assertTrue(message, message.contains("java -jar my-app.jar"));

        List<ProcessHandle> many = java.util.Collections.nCopies(30, app.toHandle());
        String capped = LogConsolePanel.killJavaMessage(many);
        assertTrue(capped, capped.contains("… and 10 more"));
    }

    /** #5: Run while bash waits in a builtin (nothing to SIGTERM) must still run the new command. */
    public void testRunWhileAShellBuiltinWaitsStillRuns() {
        run("true");
        panel.runCommand("read lc_test_answer");
        waitUntil("read to start", () -> panel.busy);
        panel.runCommand("echo after-read");
        waitUntil("the new command", () -> {
            panel.flush();
            return shownOutput().contains("after-read");
        });
    }

    /** #6: Ctrl+L also drops output that arrived but was not shown yet. */
    public void testClearDropsQueuedOutput() {
        output("shown");
        panel.enqueue(new LogLine("queued before the clear", LogLine.Kind.OUTPUT));
        panel.clear();
        panel.flush();
        assertEquals(List.of(), shownOutput());
        assertEquals("Lines: 0", panel.lineCountLabel.getText());
    }

    /** #9: a filter matching everywhere must not create a highlighter per match; counting and stepping still see all. */
    public void testManyMatchesAreCountedButOnlySomeArePainted() {
        String[] lines = new String[30_000];
        Arrays.fill(lines, "e e e e e");
        output(lines);
        filter("e");
        assertEquals("150,000 matches", panel.matchLabel.getText());
        long highlighters = panel.editor.getMarkupModel().getAllHighlighters().length;
        assertTrue(highlighters + " highlighters", highlighters <= LogConsolePanel.MAX_MATCH_HIGHLIGHTS + 1_000);

        panel.scrollToEndAndFollow();
        panel.jumpToMatch(false);
        assertEquals("150,000/150,000", panel.matchLabel.getText());
        assertEquals("e", selected());
    }

    /** #10: listing every process's command line from /proc must not block the UI thread. */
    public void testCtrlClickKillScansProcessesOffTheUiThread() {
        run("true");
        List<Boolean> onUiThread = new ArrayList<>();
        panel.javaProcesses = () -> {
            onUiThread.add(com.intellij.openapi.application.ApplicationManager.getApplication().isDispatchThread());
            return List.of();
        };
        panel.kill(true);
        waitUntil("the scan", () -> !onUiThread.isEmpty());
        assertEquals(List.of(false), onUiThread);
        waitUntil("a new shell", () -> panel.shell != null && panel.shellReady);
    }
}
