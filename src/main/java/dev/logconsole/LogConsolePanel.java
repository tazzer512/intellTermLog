package dev.logconsole;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.PtyCommandLine;
import com.intellij.execution.filters.CompositeFilter;
import com.intellij.execution.filters.ConsoleFilterProvider;
import com.intellij.execution.filters.Filter;
import com.intellij.execution.impl.ConsoleViewUtil;
import com.intellij.execution.impl.EditorHyperlinkSupport;
import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputType;
import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.command.undo.UndoUtil;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseEventArea;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.impl.ContextMenuPopupHandler;
import com.intellij.openapi.editor.impl.DocumentImpl;
import com.intellij.openapi.editor.markup.EffectType;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.content.Content;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.concurrency.SequentialTaskExecutor;
import com.intellij.util.io.BaseOutputReader;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.Point;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One Log Console tab: a persistent bash session whose output is colored by level and filtered live.
 * <p>
 * bash runs on a PTY (so Ctrl+C, job control, cd, exports and aliases behave like in a terminal) with
 * echo and prompt switched off; a PROMPT_COMMAND prints an invisible marker carrying the working directory,
 * which tells us the shell is idle again. Output arrives on a reader thread, is queued in {@link #pending}
 * and appended to the editor in batches on the EDT. All other state is EDT-only unless marked volatile.
 */
public final class LogConsolePanel extends SimpleToolWindowPanel implements Disposable {
    private static final int MAX_COMPLETIONS = 1_000;
    /**
     * Lines waiting for the EDT. A flood of output (e.g. 'yes') must not fill the heap while the EDT is busy;
     * more than this and the oldest waiting lines are dropped, which trimming would do anyway.
     */
    private static final int MAX_PENDING = 200_000;
    /** Filter matches painted at most; more are still counted and stepped through (each highlighter costs the EDT). */
    static final int MAX_MATCH_HIGHLIGHTS = 10_000;
    private static volatile Path completionScript;
    private static final String HOME = System.getProperty("user.home");
    private static final String HISTORY_KEY = "logConsole.history";
    private static final int HISTORY_SIZE = 200;
    private static final int STOP_TIMEOUT_SECONDS = 10;
    private static final int KILL_TIMEOUT_SECONDS = 5;
    private static final String SHOW_BOOKMARKS_KEY = "logConsole.showBookmarks";

    private final Project project;
    private Content tab;

    final SearchTextField filterField = new SearchTextField("logConsole.filterHistory");
    final JBCheckBox regexBox = new JBCheckBox("Regex");
    final JBCheckBox caseBox = new JBCheckBox("Match case");
    final JBCheckBox excludeBox = new JBCheckBox("Exclude");
    final JBCheckBox errorBox = new JBCheckBox("Errors", true);
    final JBCheckBox warnBox = new JBCheckBox("Warnings", true);
    final JBCheckBox debugBox = new JBCheckBox("Debug", true);
    final JBCheckBox otherBox = new JBCheckBox("Other", true);
    final JBLabel statusLabel = new JBLabel();
    final JBLabel matchLabel = new JBLabel();
    final JBLabel lineCountLabel = new JBLabel();
    private final JBLabel promptLabel = new JBLabel();
    final JBTextArea input = new JBTextArea(3, 80);
    private final Timer filterTimer = new Timer(250, e -> applyFilter());

    private final DocumentEx document = new DocumentImpl("", true);
    final EditorEx editor;

    private final ConcurrentLinkedQueue<LogLine> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    /** Size of {@link #pending}; beyond {@link #MAX_PENDING} the oldest queued lines are dropped. */
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final AtomicInteger droppedCount = new AtomicInteger();

    private final List<LogLine> lines = new ArrayList<>();
    /** Lines currently in the document; index == document line number. */
    final List<LogLine> visibleLines = new ArrayList<>();
    private LogLevel previousLevel;
    private LogClassifier classifier = LogClassifier.fromSettings();
    private TextAttributes errorAttrs;
    private TextAttributes warnAttrs;
    private TextAttributes debugAttrs;
    /** Null unless a custom color for other lines is set. */
    private TextAttributes otherAttrs;
    private TextAttributes systemAttrs;
    private TextAttributes commandAttrs;
    private Pattern filterPattern;
    private String filterError;
    /** Document ranges of the filter matches, in document order (only filled in include mode). */
    private final IntArrayList matchStarts = new IntArrayList();
    private final IntArrayList matchEnds = new IntArrayList();
    /** The painted matches, oldest first; at most {@link #MAX_MATCH_HIGHLIGHTS}. */
    private final java.util.ArrayDeque<RangeHighlighter> matchHighlighters = new java.util.ArrayDeque<>();
    /** 1-based index of the match the last match jump selected; 0 when none. */
    private int currentMatch;
    /** Set while several filter controls change at once, so they rebuild the output only once. */
    private boolean bulkFilterChange;
    /** The level checkboxes' states before a Ctrl+click soloed one level (error, warn, debug, other); null when none. */
    private boolean[] levelsBeforeSolo;
    private int errorCount;
    /** The level the last level jump went to, and the number of its entry (shown as "Errors (3/12)"); null when none. */
    private LogLevel navLevel;
    private int navIndex;
    private int warnCount;
    private int debugCount;
    boolean scrollToEnd = true;
    private int lineCounter;
    /** OUTPUT lines in {@link #lines} and in {@link #visibleLines}, kept up to date for the status bar. */
    private int outputTotal;
    private int outputShown;

    // bookmarks: the bookmarked LogLines, in output order; a line keeps its bookmark while filtered out
    final CollectionListModel<LogLine> bookmarks = new CollectionListModel<>();
    final JBList<LogLine> bookmarkList = new JBList<>(bookmarks);
    private final JBLabel bookmarkTitle = new JBLabel();
    private JComponent bookmarkPanel;
    /** Highlighters of the bookmarked lines currently in the document. */
    private final Map<LogLine, RangeHighlighter> bookmarkHighlighters = new IdentityHashMap<>();
    private TextAttributes bookmarkAttrs;
    private TextAttributes jumpAttrs;
    /** Marks the line the last jump landed on; gone (invalid) after a rebuild. */
    RangeHighlighter jumpHighlighter;
    /** Document line the editor context menu was opened on. */
    private int popupLine = -1;

    // shell session
    private final ExecutorService inputWriter = SequentialTaskExecutor.createSequentialApplicationPoolExecutor("Log Console input");
    volatile KillableProcessHandler shell;
    /** Value of {@link ShellProcesses#TAG_VARIABLE} in the current shell's environment. */
    private volatile String shellTag;
    /** The secret in the current shell's prompt/completion markers, see {@link ShellOutputParser}. */
    private String shellNonce;
    boolean shellReady;
    private long shellStartedAt;
    private boolean restartShellOnExit;
    private final List<String> queuedInput = new ArrayList<>();
    String cwd;
    boolean busy;
    private String lastCommand;
    private String commandAfterIdle;
    private volatile boolean disposed;

    /** Stack-trace (and other) links, found by the same console filters as the IDE's Run console. */
    private EditorHyperlinkSupport hyperlinks;
    /** Built on first use; null again makes the next batch pick up newly installed filter providers. */
    Filter linkFilter;
    /** Bumped when the document is rebuilt, so link searches still running for the old text are dropped. */
    private int linkGeneration;

    private int completionId;
    private Completion pendingCompletion;

    private final List<String> history = new ArrayList<>();
    private int historyIndex = -1;
    private String historyDraft = "";

    public LogConsolePanel(@NotNull Project project) {
        super(false, true);
        this.project = project;

        UndoUtil.disableUndoFor(document);
        editor = (EditorEx) EditorFactory.getInstance().createViewer(document, project, EditorKind.CONSOLE);
        ConsoleViewUtil.setupConsoleEditor(editor, false, true);
        editor.getSettings().setUseSoftWraps(false);
        hyperlinks = EditorHyperlinkSupport.get(editor);
        refreshAttributes();

        List<String> saved = PropertiesComponent.getInstance().getList(HISTORY_KEY);
        if (saved != null) history.addAll(saved);
        setUpInput();
        setUpFilter();
        setUpBookmarks();

        setToolbar(createToolbar().getComponent());
        setContent(createContent());

        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(LogConsoleSettings.TOPIC, this::onSettingsChanged);
        cwd = defaultDir();
        updatePrompt();
        updateStatus();
        startShell();
    }

    // ---------------------------------------------------------------- UI

    private void setUpFilter() {
        filterTimer.setRepeats(false);
        filterField.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                if (!bulkFilterChange) filterTimer.restart();
            }
        });
        // Enter applies the filter, or (once applied) moves to the next match like the IDE's Find; Shift+Enter goes back.
        filterField.getTextEditor().addActionListener(e -> {
            filterField.addCurrentTextToHistory();
            boolean applied = !filterTimer.isRunning();
            filterTimer.stop();
            if (!applied) applyFilter();
            jumpToMatch((e.getModifiers() & ActionEvent.SHIFT_MASK) == 0);
        });
        filterField.getTextEditor().getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), javax.swing.JTextField.notifyAction);
        for (JBCheckBox box : List.of(regexBox, caseBox, excludeBox)) {
            box.addItemListener(e -> applyFilter());
        }
        for (JBCheckBox box : List.of(errorBox, warnBox, debugBox, otherBox)) {
            box.addItemListener(e -> {
                if (bulkFilterChange) return;
                if (isCtrlClick(EventQueue.getCurrentEvent())) {
                    // The click already toggled the box; undo that and solo the level instead.
                    bulkFilterChange = true;
                    try {
                        box.setSelected(!box.isSelected());
                    } finally {
                        bulkFilterChange = false;
                    }
                    toggleSoloLevel(box);
                    return;
                }
                levelsBeforeSolo = null; // a manual change ends the solo
                rebuild();
                updateStatus();
            });
        }
        regexBox.setToolTipText("Interpret the filter as a Java regular expression");
        String solo = "<br>Ctrl+click: show only this level; Ctrl+click again to restore the previous levels";
        errorBox.setToolTipText("<html>Error lines and their stack traces" + solo);
        warnBox.setToolTipText("<html>Warning lines" + solo);
        debugBox.setToolTipText("<html>DEBUG / TRACE lines (and FINE…FINEST); patterns in Settings | Tools | Log Console" + solo);
        otherBox.setToolTipText("<html>INFO and all lines without a recognized level" + solo);
        excludeBox.setToolTipText("Hide matching lines instead of showing only matching lines");
    }

    private void setUpInput() {
        input.setFont(editor.getColorsScheme().getFont(EditorFontType.CONSOLE_PLAIN));
        input.setLineWrap(true);
        input.setWrapStyleWord(false);
        input.getEmptyText().setText("Type or paste a command, e.g. sh run-all.sh   ·   Enter runs   ·   "
                + "Tab completes   ·   Shift+Enter new line   ·   Ctrl+C interrupts   ·   ↑/↓ history");

        InputMap keys = input.getInputMap(JComponent.WHEN_FOCUSED);
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "logConsole.submit");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), DefaultEditorKit.insertBreakAction);
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "logConsole.historyUp");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "logConsole.historyDown");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "logConsole.complete");
        input.getActionMap().put("logConsole.complete", swingAction(e -> complete()));
        Action caretUp = input.getActionMap().get(DefaultEditorKit.upAction);
        Action caretDown = input.getActionMap().get(DefaultEditorKit.downAction);
        input.getActionMap().put("logConsole.submit", swingAction(e -> submitInput()));
        input.getActionMap().put("logConsole.historyUp", swingAction(e -> {
            if (caretLine() == 0) historyMove(-1);
            else if (caretUp != null) caretUp.actionPerformed(e);
        }));
        input.getActionMap().put("logConsole.historyDown", swingAction(e -> {
            if (caretLine() == input.getLineCount() - 1) historyMove(1);
            else if (caretDown != null) caretDown.actionPerformed(e);
        }));

        // Registered as IDE actions on the component so they win over the keymap's Copy / other bindings.
        new DumbAwareAction() {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (input.getSelectedText() != null) input.copy();
                else interrupt();
            }
        }.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK)), input, this);
        new DumbAwareAction() {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                clear();
            }
        }.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK)), input, this);
    }

    private static Action swingAction(java.util.function.Consumer<ActionEvent> body) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                body.accept(e);
            }
        };
    }

    private int caretLine() {
        try {
            return input.getLineOfOffset(input.getCaretPosition());
        } catch (BadLocationException e) {
            return 0;
        }
    }

    private JComponent createContent() {
        JPanel filterRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        filterRow.setBorder(JBUI.Borders.empty(4, 6));
        filterRow.add(new JBLabel("Filter:"), BorderLayout.WEST);
        filterRow.add(filterField, BorderLayout.CENTER);
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        lineCountLabel.setToolTipText("Output lines shown / in this tab (the oldest are dropped beyond the maximum in Settings)");
        options.add(lineCountLabel);
        matchLabel.setForeground(JBColor.GRAY);
        options.add(matchLabel);
        options.add(navigationButtons("Match", () -> jumpToMatch(false), () -> jumpToMatch(true),
                () -> !matchStarts.isEmpty(), IdeActions.ACTION_FIND_PREVIOUS, IdeActions.ACTION_FIND_NEXT));
        for (JComponent c : List.of(regexBox, caseBox, excludeBox, new JBLabel("|"))) {
            options.add(c);
        }
        options.add(withNavigation(errorBox, LogLevel.ERROR, "Error", () -> errorCount,
                "GotoPreviousError", "GotoNextError"));
        options.add(withNavigation(warnBox, LogLevel.WARN, "Warning", () -> warnCount, null, null));
        options.add(withNavigation(debugBox, LogLevel.DEBUG, "Debug Line", () -> debugCount, null, null));
        options.add(otherBox);
        filterRow.add(options, BorderLayout.EAST);

        promptLabel.setBorder(JBUI.Borders.empty(3, 6, 2, 6));
        promptLabel.putClientProperty("html.disable", Boolean.TRUE); // shows the directory and command as typed
        promptLabel.setFont(input.getFont().deriveFont(Font.BOLD));
        JBScrollPane inputScroll = new JBScrollPane(input);
        inputScroll.setBorder(JBUI.Borders.empty());
        inputScroll.setMinimumSize(new Dimension(0, JBUI.scale(20)));
        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.add(promptLabel, BorderLayout.NORTH);
        inputPanel.add(inputScroll, BorderLayout.CENTER);
        inputPanel.setMinimumSize(new Dimension(0, JBUI.scale(40)));

        // Drag the divider to resize the command area; the position is remembered.
        OnePixelSplitter outputSplitter = new OnePixelSplitter(false, "logConsole.bookmarksProportion", 0.8f);
        outputSplitter.setFirstComponent(editor.getComponent());
        bookmarkPanel = createBookmarkPanel();
        bookmarkPanel.setVisible(PropertiesComponent.getInstance().getBoolean(SHOW_BOOKMARKS_KEY, true));
        outputSplitter.setSecondComponent(bookmarkPanel);

        OnePixelSplitter splitter = new OnePixelSplitter(true, "logConsole.inputProportion", 0.82f);
        splitter.setFirstComponent(outputSplitter);
        splitter.setSecondComponent(inputPanel);

        statusLabel.setBorder(JBUI.Borders.empty(2, 6));
        statusLabel.setForeground(JBColor.GRAY);

        JPanel content = new JPanel(new BorderLayout());
        content.add(filterRow, BorderLayout.NORTH);
        content.add(splitter, BorderLayout.CENTER);
        content.add(statusLabel, BorderLayout.SOUTH);
        return content;
    }

    /** A level checkbox followed by previous/next buttons that jump between that level's lines. */
    private JComponent withNavigation(JBCheckBox box, LogLevel level, String name, java.util.function.IntSupplier count,
                                      String previousShortcut, String nextShortcut) {
        JComponent buttons = navigationButtons(name, () -> jumpTo(level, false), () -> jumpTo(level, true),
                () -> count.getAsInt() > 0 && box.isSelected(), previousShortcut, nextShortcut);
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.add(box, BorderLayout.WEST);
        panel.add(buttons, BorderLayout.EAST);
        return panel;
    }

    /**
     * Compact previous/next buttons, as in the IDE's Find bar. The shortcut ids name IDE actions (e.g. Next Highlighted
     * Error, F2) whose keymap shortcuts also trigger the buttons while the focus is anywhere in this tab.
     */
    private JComponent navigationButtons(String name, Runnable previous, Runnable next, BooleanSupplier enabled,
                                         String previousShortcut, String nextShortcut) {
        DefaultActionGroup group = new DefaultActionGroup();
        AnAction previousAction = action("Previous " + name, "Jump to the previous " + name.toLowerCase(),
                AllIcons.Actions.PreviousOccurence, previous, enabled);
        AnAction nextAction = action("Next " + name, "Jump to the next " + name.toLowerCase(),
                AllIcons.Actions.NextOccurence, next, enabled);
        if (previousShortcut != null) previousAction.registerCustomShortcutSet(KeymapUtil.getActiveKeymapShortcuts(previousShortcut), this);
        if (nextShortcut != null) nextAction.registerCustomShortcutSet(KeymapUtil.getActiveKeymapShortcuts(nextShortcut), this);
        group.add(previousAction);
        group.add(nextAction);
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("LogConsoleNavigation", group, true);
        toolbar.setTargetComponent(this);
        toolbar.setReservePlaceAutoPopupIcon(false);
        JComponent buttons = toolbar.getComponent();
        buttons.setBorder(JBUI.Borders.empty());
        buttons.setOpaque(false);
        return buttons;
    }

    private ActionToolbar createToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(action("Rerun Last Command", "Stop the running command if needed and run the last command again",
                AllIcons.Actions.Restart, () -> runCommand(lastCommand), () -> lastCommand != null));
        group.add(action("Stop All Processes (Keep Shell)",
                "SIGTERM the running command and everything started from this tab (also background apps); SIGKILL after "
                        + STOP_TIMEOUT_SECONDS + "s; the shell keeps running", AllIcons.Actions.Suspend, this::stopAll, () -> shell != null));
        group.add(new DumbAwareAction("Kill Shell and All Processes",
                "Kill the shell and everything started from it, then open a fresh shell. Ctrl+click: also SIGTERM all your "
                        + "Java processes (like pkill -f java), except this IDE", AllIcons.Debugger.KillProcess) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                kill(isCtrlClick(e.getInputEvent()));
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(shell != null);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        group.addSeparator();
        group.add(action("Scroll Up", "Scroll up one page and pause scrolling", AllIcons.Actions.PreviousOccurence,
                () -> scrollPage(false), () -> !visibleLines.isEmpty()));
        group.add(action("Scroll Down", "Scroll down one page and pause scrolling", AllIcons.Actions.NextOccurence,
                () -> scrollPage(true), () -> !visibleLines.isEmpty()));
        group.add(action("Scroll to the Top", "Show the first line and pause scrolling", AllIcons.Actions.MoveUp,
                this::scrollToTop, () -> !visibleLines.isEmpty()));
        group.add(action("Scroll to the End", "Show the last line and follow new output again",
                AllIcons.RunConfigurations.Scroll_down, this::scrollToEndAndFollow, () -> true));
        group.add(new DumbAwareToggleAction("Pause Scrolling", "Keep the view where it is while new output arrives",
                AllIcons.Actions.Pause) {
            @Override
            public boolean isSelected(@NotNull AnActionEvent e) {
                return !scrollToEnd;
            }

            @Override
            public void setSelected(@NotNull AnActionEvent e, boolean state) {
                scrollToEnd = !state;
                if (!state) scrollToEnd();
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        group.add(new DumbAwareToggleAction("Soft-Wrap", null, AllIcons.Actions.ToggleSoftWrap) {
            @Override
            public boolean isSelected(@NotNull AnActionEvent e) {
                return editor.getSettings().isUseSoftWraps();
            }

            @Override
            public void setSelected(@NotNull AnActionEvent e, boolean state) {
                editor.getSettings().setUseSoftWraps(state);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        group.add(new DumbAwareToggleAction("Show Bookmarks", "Show the bookmarks list next to the output",
                AllIcons.Nodes.Bookmark) {
            @Override
            public boolean isSelected(@NotNull AnActionEvent e) {
                return bookmarkPanel.isVisible();
            }

            @Override
            public void setSelected(@NotNull AnActionEvent e, boolean state) {
                bookmarkPanel.setVisible(state);
                PropertiesComponent.getInstance().setValue(SHOW_BOOKMARKS_KEY, state, true);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        group.add(action("Save Output…", "Save the output to a file (all lines, or only the lines the filter shows)",
                AllIcons.Actions.MenuSaveall, this::saveOutput, () -> !lines.isEmpty()));
        group.add(action("Clear (Ctrl+L)", "Clear all output", AllIcons.Actions.GC, this::clear, () -> !lines.isEmpty()));
        group.addSeparator();
        group.add(action("Log Console Settings…", "Configure the colors and patterns per log level",
                AllIcons.General.Settings,
                () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, LogConsoleConfigurable.class),
                () -> true));

        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("LogConsole", group, false);
        toolbar.setTargetComponent(this);
        return toolbar;
    }

    private static AnAction action(String text, String description, Icon icon, Runnable run, BooleanSupplier enabled) {
        return new DumbAwareAction(text, description, icon) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                run.run();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(enabled.getAsBoolean());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
    }

    void bindTab(Content tab) {
        this.tab = tab;
        tab.putUserData(ToolWindow.SHOW_CONTENT_ICON, Boolean.TRUE);
        updateTab();
    }

    JComponent getPreferredFocus() {
        return input;
    }

    private void updateTab() {
        if (tab == null) return;
        tab.setDisplayName(lastCommand == null ? "Shell" : ShellText.tabTitle(lastCommand));
        tab.setIcon(busy ? AllIcons.Toolwindows.ToolWindowRun : null);
    }

    private void updatePrompt() {
        String text = ShellText.shortPath(cwd, HOME) + " $";
        if (busy && lastCommand != null) {
            text += "    running: " + StringUtil.shortenTextWithEllipsis(lastCommand.replaceAll("\\s+", " "), 60, 0)
                    + "  (input goes to it; Ctrl+C interrupts)";
        } else if (!shellReady) {
            text += "    starting shell…";
        }
        promptLabel.setText(text);
    }

    private void refreshAttributes() {
        LogConsoleSettings settings = LogConsoleSettings.getInstance();
        errorAttrs = new TextAttributes(settings.errorColor(), null, null, null, Font.PLAIN);
        warnAttrs = new TextAttributes(settings.warnColor(), null, null, null, Font.PLAIN);
        debugAttrs = new TextAttributes(settings.debugColor(), null, null, null, Font.PLAIN);
        Color other = settings.otherColor();
        otherAttrs = other == null ? null : new TextAttributes(other, null, null, null, Font.PLAIN);
        systemAttrs = new TextAttributes(JBColor.GRAY, null, null, null, Font.ITALIC);
        commandAttrs = new TextAttributes(new JBColor(0x1A5FB4, 0x6CB6FF), null, null, null, Font.BOLD);
        bookmarkAttrs = new TextAttributes(null, new JBColor(0xDCE8FA, 0x2D3B4E), null, null, Font.PLAIN);
        jumpAttrs = new TextAttributes(null, new JBColor(0xFFF0A0, 0x5C5124), new JBColor(0xE0B000, 0xC9A227),
                EffectType.BOXED, Font.PLAIN);
        errorBox.setForeground(settings.errorColor());
        warnBox.setForeground(settings.warnColor());
        debugBox.setForeground(settings.debugColor());
        if (other != null) otherBox.setForeground(other);
        else otherBox.setForeground(UIUtil.getLabelForeground());
    }

    private void onSettingsChanged() {
        classifier = LogClassifier.fromSettings();
        refreshAttributes();
        previousLevel = null;
        navLevel = null;
        lines.forEach(this::classify);
        trimToMaxLines();
        recount();
        rebuild();
        updateStatus();
    }

    // ---------------------------------------------------------------- shell session

    /** Whether the Run-in-Log-Console action may use this tab for the given command. */
    boolean canReuseFor(String command) {
        return !busy || command.equals(lastCommand);
    }

    /** Runs a command as if typed; if something is running it is stopped first. */
    void runCommand(String command) {
        if (command == null) return;
        if (busy) {
            commandAfterIdle = command;
            stopAll();
        } else {
            send(command);
        }
    }

    void submitInput() {
        String text = input.getText();
        input.setText("");
        historyIndex = -1;
        // As with bash's HISTCONTROL=ignorespace: a leading space keeps a command (e.g. one with a password)
        // out of the history, which is stored in the IDE's options.
        if (!text.isBlank() && !text.startsWith(" ")) addToHistory(text.strip());
        send(text);
    }

    private void send(String text) {
        boolean newCommand = !busy && !text.isBlank();
        String prefix = busy ? "> " : ShellText.shortPath(cwd, HOME) + " $ ";
        for (String line : text.split("\n", -1)) {
            if (!line.isBlank()) enqueue(new LogLine(prefix + line, LogLine.Kind.COMMAND));
        }
        if (newCommand) {
            lastCommand = text.strip();
            busy = true;
            scrollToEnd = true;
            updateTab();
            updatePrompt();
        }
        write(text.endsWith("\n") ? text : text + "\n");
    }

    private void write(String text) {
        if (shell == null || shell.isProcessTerminated()) startShell();
        if (!shellReady) {
            queuedInput.add(text);
            return;
        }
        writeRaw(shell, text);
    }

    private void writeRaw(KillableProcessHandler handler, String text) {
        if (handler == null) return;
        inputWriter.execute(() -> {
            try {
                OutputStream out = handler.getProcessInput();
                if (out == null) return;
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                addSystemLine("Could not write to the shell: " + e.getMessage());
            }
        });
    }

    private void startShell() {
        if (disposed) return;
        String dir = cwd != null && new File(cwd).isDirectory() ? cwd : defaultDir();
        PtyCommandLine commandLine = new PtyCommandLine(List.of("bash", "--noediting", "-i"));
        commandLine.withInitialColumns(300);
        commandLine.withConsoleMode(false);
        commandLine.withWorkDirectory(dir);
        commandLine.withCharset(StandardCharsets.UTF_8);
        commandLine.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);
        commandLine.withEnvironment("TERM", "dumb");
        commandLine.withEnvironment("PAGER", "cat");
        commandLine.withEnvironment("GIT_PAGER", "cat");
        String tag = ShellProcesses.newTag();
        commandLine.withEnvironment(ShellProcesses.TAG_VARIABLE, tag);
        String nonce = ShellOutputParser.newNonce();
        try {
            KillableProcessHandler handler = new KillableProcessHandler(commandLine) {
                @Override
                protected BaseOutputReader.@NotNull Options readerOptions() {
                    return BaseOutputReader.Options.forMostlySilentProcess();
                }
            };
            handler.addProcessListener(new ShellListener(handler, nonce));
            shell = handler;
            shellTag = tag;
            shellNonce = nonce;
            shellReady = false;
            busy = false;
            shellStartedAt = System.currentTimeMillis();
            cwd = dir;
            handler.startNotify();
            writeRaw(handler, shellInit(nonce));
        } catch (ExecutionException ex) {
            addSystemLine("Could not start bash: " + ex.getMessage());
        }
        updatePrompt();
        updateTab();
    }

    /**
     * Switches off echo and the prompt and sets the PROMPT_COMMAND that prints the idle marker with this shell's
     * nonce. A leading space keeps it out of the bash history (HISTCONTROL=ignorespace/ignoreboth).
     */
    private static String shellInit(String nonce) {
        String init = " stty -echo 2>/dev/null; PS1=''; PS2=''; unset PROMPT_COMMAND; "
                + "PROMPT_COMMAND='printf \"\\036LCPWD:" + nonce + ":%s\\n\" \"$PWD\"'\n";
        try {
            return " source " + ShellText.shellQuote(completionScript().toString()) + " 2>/dev/null;" + init;
        } catch (IOException e) {
            return init;
        }
    }

    /**
     * The completion helper the shell sources. It lives in the IDE's own system directory rather than /tmp,
     * where another user could pre-create or swap the file and get code run in this shell.
     */
    private static Path completionScript() throws IOException {
        Path script = completionScript;
        if (script == null || !Files.isRegularFile(script)) {
            script = privateDir().resolve("complete.bash");
            Path partial = Files.createTempFile(privateDir(), "complete", ".tmp");
            try (InputStream in = LogConsolePanel.class.getResourceAsStream("complete.bash")) {
                if (in == null) throw new IOException("complete.bash not found");
                Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(partial, script, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            completionScript = script;
        }
        return script;
    }

    /** A directory only this user can write to: the IDE's system directory, not the shared /tmp. */
    private static Path privateDir() throws IOException {
        return Files.createDirectories(Path.of(PathManager.getSystemPath(), "log-console"));
    }

    /** Called when bash shows its (invisible) prompt: the previous command finished. */
    private void onPrompt(KillableProcessHandler handler, String directory) {
        if (handler != shell || disposed) return;
        cwd = directory;
        busy = false;
        if (!shellReady) {
            shellReady = true;
            for (String text : queuedInput) {
                writeRaw(handler, text);
                if (!text.isBlank()) busy = true;
            }
            queuedInput.clear();
        }
        if (!busy && commandAfterIdle != null) {
            String command = commandAfterIdle;
            commandAfterIdle = null;
            send(command);
        }
        updatePrompt();
        updateTab();
        updateStatus();
    }

    private void onShellTerminated(KillableProcessHandler handler) {
        if (handler != shell) return;
        shell = null;
        shellReady = false;
        busy = false;
        queuedInput.clear();
        boolean restart = restartShellOnExit || System.currentTimeMillis() - shellStartedAt > 2_000;
        restartShellOnExit = false;
        if (!disposed && restart) startShell();
        updatePrompt();
        updateTab();
        updateStatus();
    }

    void interrupt() {
        if (shellReady) writeRaw(shell, "\u0003");
    }

    /**
     * Stops everything started from this tab but keeps the shell: the running command, background jobs and
     * apps a script left running. SIGTERM first so shutdown hooks run, SIGKILL for whatever is left after a timeout.
     */
    void stopAll() {
        KillableProcessHandler handler = shell;
        if (handler == null) return;
        ProcessHandle shellProcess = processHandle(handler);
        if (shellProcess == null) return;
        String tag = shellTag;
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            List<ProcessHandle> processes = ShellProcesses.startedFrom(shellProcess, tag);
            if (processes.isEmpty()) {
                // bash itself may be busy, e.g. in `read`: no process to signal, but Ctrl+C ends it (and lets a
                // command waiting in commandAfterIdle run).
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (busy && shell == handler) {
                        addSystemLine("Nothing to stop: interrupting the shell (Ctrl+C).");
                        interrupt();
                    } else {
                        addSystemLine("Nothing is running.");
                    }
                }, ModalityState.any());
                return;
            }
            addSystemLine("Stopping " + ShellProcesses.describe(processes) + " (SIGTERM)…");
            processes.forEach(ProcessHandle::destroy);
            AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
                List<ProcessHandle> left = processes.stream().filter(ProcessHandle::isAlive).toList();
                if (left.isEmpty()) return;
                addSystemLine("Still running after " + STOP_TIMEOUT_SECONDS + "s: " + ShellProcesses.describe(left) + ", killing (SIGKILL).");
                left.forEach(ProcessHandle::destroyForcibly);
            }, STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        });
    }

    /**
     * Ctrl+click on Kill: SIGTERM to all of this user's Java processes like {@code pkill -f java}, after a confirmation
     * since that also hits apps outside this tab (other IDEs and Gradle daemons, too). Returns false when cancelled.
     */
    boolean killJavaProcesses(List<ProcessHandle> processes) {
        if (processes.isEmpty()) {
            addSystemLine("No Java processes of yours running (this IDE excluded).");
            return true;
        }
        if (!confirmKillJava.test(processes)) return false;
        addSystemLine("Stopping " + ShellProcesses.describe(processes) + " (SIGTERM, like pkill -f java)…");
        processes.forEach(ProcessHandle::destroy);
        return true;
    }

    /** The processes {@link #killJavaProcesses} signals; tests replace it so they never touch real Java apps. */
    java.util.function.Supplier<List<ProcessHandle>> javaProcesses = ShellProcesses::javaProcesses;

    /** Asks before {@link #killJavaProcesses} signals anything; tests replace it. */
    java.util.function.Predicate<List<ProcessHandle>> confirmKillJava = this::askToKillJava;

    private boolean askToKillJava(List<ProcessHandle> processes) {
        return Messages.showYesNoDialog(project, killJavaMessage(processes),
                "Kill All Java Processes", "Kill", "Cancel", Messages.getWarningIcon()) == Messages.YES;
    }

    /** Names every process (pid and command line, up to 20) so it is clear which apps get the signal. */
    static String killJavaMessage(List<ProcessHandle> processes) {
        StringBuilder s = new StringBuilder("Send SIGTERM to ").append(processes.size())
                .append(processes.size() == 1 ? " process" : " processes")
                .append("? This includes your Java apps not started from this tab, such as other IDEs and Gradle daemons "
                        + "(this IDE is excluded).\n");
        processes.stream().limit(20).forEach(p -> s.append('\n').append(p.pid()).append("  ")
                .append(StringUtil.shortenTextWithEllipsis(ShellProcesses.commandLine(p), 120, 0)));
        if (processes.size() > 20) s.append("\n… and ").append(processes.size() - 20).append(" more");
        return s.toString();
    }

    /**
     * The Kill button; {@code alsoJava} (Ctrl+click) first stops every Java process, unless that is cancelled.
     * Listing them reads every process's command line from /proc, so that happens off the EDT.
     */
    void kill(boolean alsoJava) {
        if (!alsoJava) {
            killShell(true);
            return;
        }
        java.util.function.Supplier<List<ProcessHandle>> source = javaProcesses;
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            List<ProcessHandle> processes = source.get();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!disposed && killJavaProcesses(processes)) killShell(true);
            }, ModalityState.defaultModalityState());
        });
    }

    /** Kills the whole session: the shell, the running command and anything left running in the background. */
    private void killShell(boolean restart) {
        KillableProcessHandler handler = shell;
        if (handler == null) return;
        restartShellOnExit = restart;
        commandAfterIdle = null;
        ProcessHandle shellProcess = processHandle(handler);
        if (shellProcess == null) {
            handler.killProcess();
            return;
        }
        if (restart) addSystemLine("Killing the shell and everything started from it…");
        String tag = shellTag;
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            List<ProcessHandle> processes = new ArrayList<>(ShellProcesses.startedFrom(shellProcess, tag));
            processes.forEach(ProcessHandle::destroy);
            // An interactive bash ignores SIGTERM, so it would only go at the SIGKILL below; SIGHUP ends it now.
            ShellProcesses.hangUp(shellProcess);
            processes.add(shellProcess);
            AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
                // processes started while we were waiting are included too
                processes.addAll(ShellProcesses.startedFrom(shellProcess, tag));
                processes.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                if (!handler.isProcessTerminated()) handler.killProcess();
            }, KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        });
    }

    /** pty4j's process doesn't implement {@link Process#toHandle()}, but it does know its pid. */
    private static ProcessHandle processHandle(KillableProcessHandler handler) {
        return ProcessHandle.of(handler.getProcess().pid()).orElse(null);
    }

    private final class ShellListener implements ProcessListener {
        private final KillableProcessHandler handler;
        private final ShellOutputParser parser;

        ShellListener(KillableProcessHandler handler, String nonce) {
            this.handler = handler;
            this.parser = new ShellOutputParser(new ShellOutputParser.Listener() {
                @Override
                public void line(String text) {
                    enqueue(new LogLine(text, LogLine.Kind.OUTPUT));
                }

                @Override
                public void prompt(String directory) {
                    ApplicationManager.getApplication().invokeLater(() -> onPrompt(handler, directory), ModalityState.any());
                }

                @Override
                public void completionDone(String id) {
                    ApplicationManager.getApplication().invokeLater(() -> onCompletionDone(id), ModalityState.any());
                }
            }, nonce);
        }

        @Override
        public synchronized void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
            if (ProcessOutputType.isStdout(outputType) || ProcessOutputType.isStderr(outputType)) parser.feed(event.getText());
        }

        @Override
        public synchronized void processTerminated(@NotNull ProcessEvent event) {
            parser.finish();
            addSystemLine("Shell exited (code " + event.getExitCode() + ")");
            ApplicationManager.getApplication().invokeLater(() -> onShellTerminated(handler), ModalityState.any());
        }
    }

    // ---------------------------------------------------------------- completion

    /** A Tab press waiting for candidates; applied only if the input has not changed meanwhile. */
    private record Completion(int id, String text, int caret, int wordStart, Path out) {
    }

    void complete() {
        String text = input.getText();
        int caret = input.getCaretPosition();
        int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
        String line = text.substring(lineStart, caret);
        if (line.isBlank()) return;
        int wordStart = ShellText.wordStart(text, lineStart, caret);

        // The live shell knows aliases, functions, variables and git/mvn/... completions, but it can only
        // answer while idle; while a command runs, fall back to file names relative to its directory.
        if (!shellReady || busy) {
            applyCompletion(text, caret, wordStart, ShellText.fileCompletions(text.substring(wordStart, caret), cwd, HOME));
            return;
        }
        try {
            Path out = Files.createTempFile(privateDir(), "completion", ".txt");
            Completion completion = new Completion(++completionId, text, caret, wordStart, out);
            discard(pendingCompletion);
            pendingCompletion = completion;
            writeRaw(shell, " __lc_complete " + ShellText.shellQuote(line) + " >" + ShellText.shellQuote(out.toString())
                    + " 2>/dev/null; printf '\\036LCDONE:" + shellNonce + ":%s\\n' " + completion.id() + "\n");
        } catch (IOException e) {
            applyCompletion(text, caret, wordStart, ShellText.fileCompletions(text.substring(wordStart, caret), cwd, HOME));
        }
    }

    private void onCompletionDone(String id) {
        Completion completion = pendingCompletion;
        if (completion == null || !String.valueOf(completion.id()).equals(id)) return;
        pendingCompletion = null;
        List<String> candidates;
        try {
            candidates = Files.readAllLines(completion.out(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            candidates = List.of();
        } finally {
            discard(completion);
        }
        applyCompletion(completion.text(), completion.caret(), completion.wordStart(), candidates);
    }

    private static void discard(Completion completion) {
        if (completion != null) FileUtil.delete(completion.out().toFile());
    }

    private void applyCompletion(String text, int caret, int wordStart, List<String> raw) {
        if (!input.getText().equals(text) || input.getCaretPosition() != caret) return;
        // A name with a newline (e.g. a crafted file "x\nreboot") would put a second command line into the box.
        List<String> candidates = raw.stream().map(String::strip).filter(c -> !c.isEmpty())
                .filter(c -> c.chars().noneMatch(Character::isISOControl))
                .distinct().sorted().limit(MAX_COMPLETIONS).toList();
        if (candidates.isEmpty()) return;
        String word = text.substring(wordStart, caret);
        if (candidates.size() == 1) {
            String only = candidates.get(0);
            replaceWord(wordStart, caret, ShellText.completionText(only));
            return;
        }
        String escaped = ShellText.escape(ShellText.commonPrefix(candidates));
        if (escaped.length() > word.length() && escaped.startsWith(word)) {
            replaceWord(wordStart, caret, escaped);
            return;
        }
        showCompletionPopup(candidates, wordStart, caret);
    }

    private void showCompletionPopup(List<String> candidates, int wordStart, int caret) {
        JBPopup popup = JBPopupFactory.getInstance().createPopupChooserBuilder(candidates)
                .setNamerForFiltering(c -> c)
                .setVisibleRowCount(12)
                .setItemChosenCallback(chosen -> {
                    if (input.getCaretPosition() == caret) {
                        replaceWord(wordStart, caret, ShellText.completionText(chosen));
                    }
                    input.requestFocusInWindow();
                })
                .createPopup();
        Point point = new Point(0, input.getHeight());
        try {
            Rectangle2D r = input.modelToView2D(caret);
            if (r != null) point = new Point((int) r.getX(), (int) r.getMaxY());
        } catch (BadLocationException ignored) {
            // show below the input instead
        }
        popup.show(new RelativePoint(input, point));
    }

    private void replaceWord(int start, int end, String replacement) {
        input.replaceRange(replacement, start, end);
        input.setCaretPosition(start + replacement.length());
    }

    // ---------------------------------------------------------------- history

    private void addToHistory(String command) {
        history.remove(command);
        history.add(command);
        while (history.size() > HISTORY_SIZE) history.remove(0);
        PropertiesComponent.getInstance().setList(HISTORY_KEY, history);
    }

    void historyMove(int delta) {
        if (history.isEmpty()) return;
        if (historyIndex == -1) {
            if (delta > 0) return;
            historyDraft = input.getText();
            historyIndex = history.size();
        }
        int index = historyIndex + delta;
        if (index < 0) return;
        if (index >= history.size()) {
            historyIndex = -1;
            input.setText(historyDraft);
        } else {
            historyIndex = index;
            input.setText(history.get(index));
        }
    }

    // ---------------------------------------------------------------- output

    private void addSystemLine(String text) {
        enqueue(new LogLine(text, LogLine.Kind.SYSTEM));
    }

    void enqueue(LogLine line) {
        pending.add(line);
        if (pendingCount.incrementAndGet() > MAX_PENDING && pending.poll() != null) {
            pendingCount.decrementAndGet();
            droppedCount.incrementAndGet();
        }
        if (flushScheduled.compareAndSet(false, true)) {
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                    () -> ApplicationManager.getApplication().invokeLater(this::flush, ModalityState.any()),
                    50, TimeUnit.MILLISECONDS);
        }
    }

    void flush() {
        flushScheduled.set(false);
        if (disposed) return;
        List<LogLine> batch = new ArrayList<>();
        int dropped = droppedCount.getAndSet(0);
        if (dropped > 0) {
            batch.add(new LogLine(String.format("… %,d lines dropped: output came in faster than it could be shown", dropped),
                    LogLine.Kind.SYSTEM));
        }
        for (LogLine line; (line = pending.poll()) != null; ) {
            pendingCount.decrementAndGet();
            batch.add(line);
        }
        for (LogLine line : batch) {
            line.number = ++lineCounter;
            classify(line);
            count(line, 1);
        }
        if (batch.isEmpty()) return;
        lines.addAll(batch);
        if (trimToMaxLines()) {
            rebuild();
        } else {
            append(batch.stream().filter(this::isVisible).toList());
        }
        updateStatus();
    }

    private void classify(LogLine line) {
        if (line.kind != LogLine.Kind.OUTPUT) return;
        line.level = classifier.classify(line.text, previousLevel);
        line.entryStart = !classifier.continuesEntry(line.text, previousLevel);
        previousLevel = line.level;
    }

    /** Errors/warnings/debug are counted per entry (a stack trace is part of its error), like the jumps stop. */
    private void count(LogLine line, int delta) {
        if (line.kind == LogLine.Kind.OUTPUT) outputTotal += delta;
        if (!line.entryStart) return;
        if (line.level == LogLevel.ERROR) errorCount += delta;
        else if (line.level == LogLevel.WARN) warnCount += delta;
        else if (line.level == LogLevel.DEBUG) debugCount += delta;
    }

    private void recount() {
        errorCount = 0;
        warnCount = 0;
        debugCount = 0;
        outputTotal = 0;
        lines.forEach(l -> count(l, 1));
    }

    /** Drops the oldest 10% once the limit is exceeded, so trimming (and the rebuild it needs) is rare. */
    private boolean trimToMaxLines() {
        int max = Math.max(1_000, LogConsoleSettings.getInstance().getState().maxLines);
        if (lines.size() <= max) return false;
        List<LogLine> dropped = lines.subList(0, lines.size() - max + max / 10);
        for (LogLine line : dropped) {
            count(line, -1);
            if (line.bookmarkNote != null) bookmarks.remove(line);
        }
        dropped.clear();
        navLevel = null; // entry numbers shift
        return true;
    }

    private boolean isVisible(LogLine line) {
        boolean levelShown = switch (line.level) {
            case SYSTEM, COMMAND -> true;
            case ERROR -> errorBox.isSelected();
            case WARN -> warnBox.isSelected();
            case DEBUG -> debugBox.isSelected();
            case OTHER -> otherBox.isSelected();
        };
        if (!levelShown) return false;
        if (filterPattern == null || line.kind != LogLine.Kind.OUTPUT) return true;
        return filterPattern.matcher(line.text).find() != excludeBox.isSelected();
    }

    private void append(List<LogLine> toShow) {
        if (toShow.isEmpty()) return;
        int base = document.getTextLength();
        StringBuilder text = new StringBuilder();
        for (LogLine line : toShow) text.append(line.text).append('\n');
        int firstLine = visibleLines.size();
        document.insertString(base, text);
        visibleLines.addAll(toShow);
        for (LogLine line : toShow) {
            if (line.kind == LogLine.Kind.OUTPUT) outputShown++;
        }
        highlight(toShow, base, firstLine);
        findLinks(firstLine, visibleLines.size() - 1);
        if (scrollToEnd) scrollToEnd();
    }

    /** Runs the console filters (in the background, like the Run console) over the given document lines. */
    private void findLinks(int startLine, int endLine) {
        if (linkFilter == null) {
            List<Filter> filters = new ArrayList<>();
            for (ConsoleFilterProvider provider : ConsoleFilterProvider.FILTER_PROVIDERS.getExtensionList()) {
                filters.addAll(List.of(provider.getDefaultFilters(project)));
            }
            linkFilter = new CompositeFilter(project, filters);
        }
        int generation = linkGeneration;
        hyperlinks.highlightHyperlinksLater(linkFilter, startLine, endLine,
                () -> disposed || generation != linkGeneration);
    }

    void waitForLinks() {
        hyperlinks.waitForPendingFilters(5_000);
    }

    private void highlight(List<LogLine> shown, int offset, int docLine) {
        MarkupModel markup = editor.getMarkupModel();
        int firstNewMatch = matchStarts.size();
        boolean markMatches = filterPattern != null && !excludeBox.isSelected();
        TextAttributes matchAttrs = markMatches
                ? editor.getColorsScheme().getAttributes(EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES)
                : null;
        // Consecutive lines of the same level (an error and its stack trace) share one highlighter:
        // far fewer markup objects than one per line.
        TextAttributes runAttrs = null;
        int runStart = 0;
        int runEnd = 0;
        for (LogLine line : shown) {
            int end = offset + line.text.length();
            TextAttributes attrs = switch (line.level) {
                case ERROR -> errorAttrs;
                case WARN -> warnAttrs;
                case SYSTEM -> systemAttrs;
                case COMMAND -> commandAttrs;
                case DEBUG -> debugAttrs;
                case OTHER -> otherAttrs;
            };
            if (attrs != runAttrs) {
                if (runAttrs != null && runEnd > runStart) {
                    markup.addRangeHighlighter(runStart, runEnd, HighlighterLayer.SYNTAX, runAttrs, HighlighterTargetArea.EXACT_RANGE);
                }
                runAttrs = attrs;
                runStart = offset;
            }
            runEnd = end;
            if (markMatches && line.kind == LogLine.Kind.OUTPUT) {
                Matcher m = filterPattern.matcher(line.text);
                while (m.find()) {
                    if (m.end() > m.start()) {
                        matchStarts.add(offset + m.start());
                        matchEnds.add(offset + m.end());
                    }
                }
            }
            if (line.bookmarkNote != null) addBookmarkHighlighter(line, docLine);
            offset = end + 1;
            docLine++;
        }
        if (runAttrs != null && runEnd > runStart) {
            markup.addRangeHighlighter(runStart, runEnd, HighlighterLayer.SYNTAX, runAttrs, HighlighterTargetArea.EXACT_RANGE);
        }
        if (markMatches) paintMatches(firstNewMatch, matchAttrs);
    }

    /**
     * Paints the matches from {@code from} on, but keeps at most {@link #MAX_MATCH_HIGHLIGHTS} painted: the newest,
     * since the view usually follows the end. A filter like "e" on a big log would otherwise add hundreds of
     * thousands of highlighters on the EDT. Unpainted matches are still counted and selected by the match jumps.
     */
    private void paintMatches(int from, TextAttributes attrs) {
        MarkupModel markup = editor.getMarkupModel();
        for (int i = Math.max(from, matchStarts.size() - MAX_MATCH_HIGHLIGHTS); i < matchStarts.size(); i++) {
            matchHighlighters.add(markup.addRangeHighlighter(matchStarts.getInt(i), matchEnds.getInt(i),
                    HighlighterLayer.SELECTION - 1, attrs, HighlighterTargetArea.EXACT_RANGE));
        }
        while (matchHighlighters.size() > MAX_MATCH_HIGHLIGHTS) markup.removeHighlighter(matchHighlighters.pollFirst());
    }

    private void rebuild() {
        linkGeneration++;
        hyperlinks.clearHyperlinks();
        editor.getMarkupModel().removeAllHighlighters();
        clearMatches();
        bookmarkHighlighters.clear();
        visibleLines.clear();
        outputShown = 0;
        document.setText("");
        append(lines.stream().filter(this::isVisible).toList());
        bookmarkList.repaint();
    }

    void applyFilter() {
        String text = filterField.getText();
        filterError = null;
        if (text.isEmpty()) {
            filterPattern = null;
        } else {
            int flags = caseBox.isSelected() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            try {
                filterPattern = Pattern.compile(regexBox.isSelected() ? text : Pattern.quote(text), flags);
            } catch (PatternSyntaxException e) {
                filterPattern = null;
                filterError = e.getDescription();
            }
        }
        rebuild();
        updateStatus();
    }

    void clear() {
        // Output that arrived before the clear but was not shown yet goes too.
        while (pending.poll() != null) pendingCount.decrementAndGet();
        droppedCount.set(0);
        lines.clear();
        visibleLines.clear();
        previousLevel = null;
        errorCount = 0;
        warnCount = 0;
        debugCount = 0;
        outputTotal = 0;
        outputShown = 0;
        bookmarks.removeAll();
        bookmarkHighlighters.clear();
        linkGeneration++;
        hyperlinks.clearHyperlinks();
        editor.getMarkupModel().removeAllHighlighters();
        clearMatches();
        navLevel = null;
        document.setText("");
        updateStatus();
    }

    private void saveOutput() {
        boolean all = true;
        if (visibleLines.size() != lines.size()) {
            int choice = Messages.showDialog(project,
                    String.format("Save all %,d lines, or only the %,d lines the filter shows?", lines.size(), visibleLines.size()),
                    "Save Output", new String[]{"All Lines", "Shown Lines", "Cancel"}, 1, Messages.getQuestionIcon());
            if (choice == 2 || choice < 0) return;
            all = choice == 0;
        }
        String name = (lastCommand == null ? "shell" : ShellText.tabTitle(lastCommand).replaceAll("[^\\w.-]+", "_"))
                + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")) + ".log";
        VirtualFileWrapper target = FileChooserFactory.getInstance()
                .createSaveFileDialog(new FileSaverDescriptor("Save Output", "Save the Log Console output"), project)
                .save(LocalFileSystem.getInstance().findFileByPath(cwd), name);
        if (target == null) return;
        String text = outputText(all);
        Path file = target.getFile().toPath();
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                Files.writeString(file, text, StandardCharsets.UTF_8);
            } catch (IOException e) {
                ApplicationManager.getApplication().invokeLater(() ->
                        Messages.showErrorDialog(project, "Could not save " + file + ": " + e.getMessage(), "Save Output"));
            }
        });
    }

    /** The output as text, one line per log line: every line in this tab, or only those the filter shows. */
    String outputText(boolean all) {
        StringBuilder text = new StringBuilder();
        for (LogLine line : all ? lines : visibleLines) text.append(line.text).append('\n');
        return text.toString();
    }

    private void scrollToEnd() {
        editor.getCaretModel().moveToOffset(document.getTextLength());
        editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
    }

    void scrollToEndAndFollow() {
        navLevel = null;
        scrollToEnd = true;
        scrollToEnd();
        updateStatus();
    }

    /** Scrolls a page (keeping one line of overlap), like Page Up/Down; pauses following the end. */
    void scrollPage(boolean down) {
        navLevel = null;
        scrollToEnd = false;
        java.awt.Rectangle area = editor.getScrollingModel().getVisibleArea();
        int step = Math.max(editor.getLineHeight(), area.height - editor.getLineHeight());
        editor.getScrollingModel().scrollVertically(Math.max(0, area.y + (down ? step : -step)));
        updateStatus();
    }

    void scrollToTop() {
        navLevel = null;
        scrollToEnd = false;
        editor.getCaretModel().moveToOffset(0);
        editor.getScrollingModel().scrollTo(new LogicalPosition(0, 0), ScrollType.MAKE_VISIBLE);
        updateStatus();
    }

    /** 1-based number of the entry of that line's level that contains the line, counted like the level counters. */
    private int entryNumber(LogLine target) {
        int n = 0;
        for (LogLine line : lines) {
            if (line.level == target.level && line.entryStart) n++;
            if (line == target) break;
        }
        return Math.max(n, 1);
    }

    /** Moves to the first line of the next/previous block of that level (e.g. an error plus its stack trace), wrapping around. */
    void jumpTo(LogLevel level, boolean forward) {
        int size = visibleLines.size();
        if (size == 0) return;
        int current = editor.getCaretModel().getLogicalPosition().line;
        // The caret usually sits on the empty line after the output (following the end): start from the
        // first line going forward, from the last one going back.
        if (current >= size) current = forward ? -1 : size;
        for (int n = 1; n <= size; n++) {
            int i = Math.floorMod(current + (forward ? n : -n), size);
            LogLine line = visibleLines.get(i);
            // an entry start, or the first visible line of an entry whose start the filter hides
            boolean blockStart = line.level == level
                    && (line.entryStart || i == 0 || visibleLines.get(i - 1).level != level);
            if (blockStart) {
                showLine(i);
                navLevel = level;
                navIndex = entryNumber(line);
                updateStatus();
                return;
            }
        }
    }

    // ---------------------------------------------------------------- bookmarks

    private void setUpBookmarks() {
        DumbAwareAction toggle = new DumbAwareAction("Toggle Bookmark", "Bookmark this line, or remove its bookmark",
                AllIcons.Nodes.Bookmark) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                LogLine line = targetLine(e);
                if (line != null) toggleBookmark(line);
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                LogLine line = targetLine(e);
                e.getPresentation().setEnabled(line != null);
                e.getPresentation().setText(line != null && line.bookmarkNote != null ? "Remove Bookmark" : "Add Bookmark");
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
        toggle.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0)),
                editor.getContentComponent(), this);
        DumbAwareAction note = new DumbAwareAction("Add Bookmark with Note…", "Bookmark this line with a note", AllIcons.Actions.Edit) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                LogLine line = targetLine(e);
                if (line != null) editNote(line);
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                LogLine line = targetLine(e);
                e.getPresentation().setEnabled(line != null);
                e.getPresentation().setText(line != null && line.bookmarkNote != null ? "Edit Bookmark Note…" : "Add Bookmark with Note…");
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
        note.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_F11, InputEvent.SHIFT_DOWN_MASK)),
                editor.getContentComponent(), this);

        DefaultActionGroup menu = new DefaultActionGroup();
        menu.add(toggle);
        menu.add(note);
        menu.addSeparator();
        AnAction copy = ActionManager.getInstance().getAction(IdeActions.ACTION_COPY);
        if (copy != null) menu.add(copy);
        menu.add(action("Clear All Output", "Clear all output and its bookmarks", AllIcons.Actions.GC, this::clear,
                () -> !lines.isEmpty()));
        editor.installPopupHandler(new ContextMenuPopupHandler() {
            @Override
            public ActionGroup getActionGroup(@NotNull EditorMouseEvent event) {
                popupLine = event.getLogicalPosition().line;
                return menu;
            }
        });
        // A click in the gutter toggles the bookmark of that line, as in the IDE's editors.
        // Ctrl+double-click in the text selects the whole line instead of the word under the mouse.
        editor.addEditorMouseListener(new EditorMouseListener() {
            @Override
            public void mouseClicked(@NotNull EditorMouseEvent event) {
                MouseEvent mouse = event.getMouseEvent();
                if (event.getArea() == EditorMouseEventArea.EDITING_AREA && mouse.getButton() == MouseEvent.BUTTON1
                        && mouse.getClickCount() == 2 && isCtrlClick(mouse)) {
                    selectLine(event.getLogicalPosition().line);
                    event.consume();
                    return;
                }
                if (event.getArea() != EditorMouseEventArea.LINE_MARKERS_AREA
                        || event.getMouseEvent().getButton() != MouseEvent.BUTTON1) return;
                LogLine line = lineAt(event.getLogicalPosition().line);
                if (line != null) {
                    toggleBookmark(line);
                    event.consume();
                }
            }
        }, this);
    }

    /** Selects document line {@code line} without its line break (the word selection of the double click is replaced). */
    void selectLine(int line) {
        if (line >= document.getLineCount()) return;
        int start = document.getLineStartOffset(line);
        int end = document.getLineEndOffset(line);
        editor.getSelectionModel().setSelection(start, end);
        editor.getCaretModel().moveToOffset(end);
    }

        private JComponent createBookmarkPanel() {
        bookmarkList.setCellRenderer(new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends LogLine> list, LogLine line, int index,
                                                 boolean selected, boolean hasFocus) {
                setIcon(AllIcons.Nodes.Bookmark);
                append(line.number + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                if (!line.bookmarkNote.isEmpty()) append(line.bookmarkNote + "  ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                append(StringUtil.shortenTextWithEllipsis(line.text.strip(), 200, 0), listAttributes(line.level));
                if (!bookmarkHighlighters.containsKey(line)) {
                    append("  (hidden by filter)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                }
                // Plain text: a log line starting with <html> must not be rendered (it could load remote images).
                setToolTipText("<html>" + StringUtil.escapeXmlEntities(line.text) + "</html>");
            }
        });
        bookmarkList.getEmptyText().setText("No bookmarks")
                .appendLine("Right-click a line → Add Bookmark,")
                .appendLine("click the gutter, or press F11");
        bookmarkList.addListSelectionListener(e -> {
            LogLine line = bookmarkList.getSelectedValue();
            if (!e.getValueIsAdjusting() && line != null && bookmarkList.getSelectedIndices().length == 1) goTo(line, false);
        });
        new DoubleClickListener() {
            @Override
            protected boolean onDoubleClick(@NotNull MouseEvent event) {
                LogLine line = bookmarkList.getSelectedValue();
                if (line != null) goTo(line, true);
                return line != null;
            }
        }.installOn(bookmarkList);
        bookmarkList.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "logConsole.goToBookmark");
        bookmarkList.getActionMap().put("logConsole.goToBookmark", swingAction(e -> {
            LogLine line = bookmarkList.getSelectedValue();
            if (line != null) goTo(line, true);
        }));
        bookmarks.addListDataListener(new ListDataListener() {
            @Override
            public void intervalAdded(ListDataEvent e) {
                updateBookmarkTitle();
            }

            @Override
            public void intervalRemoved(ListDataEvent e) {
                updateBookmarkTitle();
            }

            @Override
            public void contentsChanged(ListDataEvent e) {
                updateBookmarkTitle();
            }
        });

        JPanel listPanel = ToolbarDecorator.createDecorator(bookmarkList)
                .setAddAction(b -> {
                    LogLine line = lineAt(caretDocLine());
                    if (line != null && line.bookmarkNote == null) addBookmark(line, "");
                })
                .setAddActionName("Bookmark the Line at the Caret (F11)")
                .setEditAction(b -> {
                    LogLine line = bookmarkList.getSelectedValue();
                    if (line != null) editNote(line);
                })
                .setEditActionName("Edit Note (Shift+F11)")
                .setRemoveAction(b -> List.copyOf(bookmarkList.getSelectedValuesList()).forEach(this::removeBookmark))
                .setRemoveActionName("Remove Bookmark")
                .disableUpDownActions()
                .addExtraActions(
                        action("Previous Bookmark", "Jump to the previous bookmarked line", AllIcons.Actions.PreviousOccurence,
                                () -> jumpToBookmark(false), () -> !bookmarks.isEmpty()),
                        action("Next Bookmark", "Jump to the next bookmarked line", AllIcons.Actions.NextOccurence,
                                () -> jumpToBookmark(true), () -> !bookmarks.isEmpty()),
                        action("Copy Bookmarked Lines", "Copy the selected bookmarked lines (all if none selected)",
                                AllIcons.Actions.Copy, this::copyBookmarks, () -> !bookmarks.isEmpty()),
                        action("Remove All Bookmarks", "Remove all bookmarks of this tab", AllIcons.Actions.ClearCash,
                                this::removeAllBookmarks, () -> !bookmarks.isEmpty()))
                .createPanel();

        bookmarkTitle.setBorder(JBUI.Borders.empty(4, 8));
        bookmarkTitle.setFont(bookmarkTitle.getFont().deriveFont(Font.BOLD));
        updateBookmarkTitle();
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(bookmarkTitle, BorderLayout.NORTH);
        panel.add(listPanel, BorderLayout.CENTER);
        panel.setMinimumSize(new Dimension(JBUI.scale(120), 0));
        return panel;
    }

    private void updateBookmarkTitle() {
        bookmarkTitle.setText(bookmarks.isEmpty() ? "Bookmarks" : "Bookmarks (" + bookmarks.getSize() + ")");
    }

    private SimpleTextAttributes listAttributes(LogLevel level) {
        return switch (level) {
            case ERROR -> new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, errorAttrs.getForegroundColor());
            case WARN -> new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, warnAttrs.getForegroundColor());
            case COMMAND -> new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, commandAttrs.getForegroundColor());
            case DEBUG -> new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, debugAttrs.getForegroundColor());
            case SYSTEM -> SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES;
            case OTHER -> otherAttrs == null ? SimpleTextAttributes.REGULAR_ATTRIBUTES
                    : new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, otherAttrs.getForegroundColor());
        };
    }

    /** The line a bookmark action applies to: the right-clicked line in the context menu, else the caret line. */
    private LogLine targetLine(AnActionEvent e) {
        return lineAt(e.isFromContextMenu() ? popupLine : caretDocLine());
    }

    private LogLine lineAt(int docLine) {
        return docLine >= 0 && docLine < visibleLines.size() ? visibleLines.get(docLine) : null;
    }

    /** The caret line; the empty line after the last output line counts as the last output line. */
    private int caretDocLine() {
        return Math.min(editor.getCaretModel().getLogicalPosition().line, visibleLines.size() - 1);
    }

    void toggleBookmark(LogLine line) {
        if (line.bookmarkNote == null) addBookmark(line, "");
        else removeBookmark(line);
    }

    void addBookmark(LogLine line, String note) {
        line.bookmarkNote = note;
        int index = 0;
        while (index < bookmarks.getSize() && bookmarks.getElementAt(index).number < line.number) index++;
        bookmarks.add(index, line);
        int docLine = visibleLines.indexOf(line);
        if (docLine >= 0) addBookmarkHighlighter(line, docLine);
        bookmarkPanel.setVisible(true);
    }

    void removeBookmark(LogLine line) {
        line.bookmarkNote = null;
        bookmarks.remove(line);
        RangeHighlighter highlighter = bookmarkHighlighters.remove(line);
        if (highlighter != null) editor.getMarkupModel().removeHighlighter(highlighter);
    }

    void removeAllBookmarks() {
        List.copyOf(bookmarks.getItems()).forEach(this::removeBookmark);
    }

    private void editNote(LogLine line) {
        String note = Messages.showInputDialog(project, "Note for line " + line.number + ":", "Bookmark Note",
                AllIcons.Nodes.Bookmark, line.bookmarkNote == null ? "" : line.bookmarkNote, null);
        if (note == null) return;
        if (line.bookmarkNote == null) {
            addBookmark(line, note.strip());
        } else {
            line.bookmarkNote = note.strip();
            bookmarks.contentsChanged(line);
        }
    }

    private void addBookmarkHighlighter(LogLine line, int docLine) {
        RangeHighlighter highlighter = editor.getMarkupModel().addLineHighlighter(docLine, HighlighterLayer.CARET_ROW + 1, bookmarkAttrs);
        highlighter.setGutterIconRenderer(new BookmarkIcon(line));
        bookmarkHighlighters.put(line, highlighter);
    }

    /** Scrolls to a bookmarked line; with {@code reveal}, clears the filter first if it hides the line. */
    void goTo(LogLine line, boolean reveal) {
        navLevel = null;
        int docLine = visibleLines.indexOf(line);
        if (docLine < 0) {
            if (!reveal) return;
            showAllLines();
            docLine = visibleLines.indexOf(line);
            if (docLine < 0) return;
        }
        showLine(docLine);
    }

    /** Forgets the matches; their highlighters are removed with all others by the caller. */
    private void clearMatches() {
        matchStarts.clear();
        matchEnds.clear();
        matchHighlighters.clear();
        currentMatch = 0;
    }

    /** Selects the next/previous filter match from the caret, wrapping around, like the IDE's Find. */
    void jumpToMatch(boolean forward) {
        if (matchStarts.isEmpty()) return;
        int index;
        if (editor.getCaretModel().getLogicalPosition().line >= visibleLines.size()) {
            // following the end: start at the first match going forward, at the last going back
            index = forward ? 0 : matchStarts.size() - 1;
        } else {
            boolean selected = editor.getSelectionModel().hasSelection();
            int from = selected ? editor.getSelectionModel().getSelectionStart() : editor.getCaretModel().getOffset();
            index = adjacentIndex(matchStarts, forward && !selected ? from - 1 : from, forward);
        }
        currentMatch = index + 1;
        navLevel = null;
        int start = matchStarts.getInt(index);
        showRange(document.getLineNumber(start), start, matchEnds.getInt(index));
        updateStatus();
    }

    /**
     * Index of the first value greater than {@code anchor} (forward) or the last value less than it (backward)
     * in an ascending list, wrapping around.
     */
    static int adjacentIndex(IntArrayList sorted, int anchor, boolean forward) {
        int low = 0;
        int high = sorted.size();
        while (low < high) { // first index whose value is > anchor (forward) or >= anchor (backward)
            int mid = (low + high) >>> 1;
            int value = sorted.getInt(mid);
            if (forward ? value <= anchor : value < anchor) low = mid + 1;
            else high = mid;
        }
        if (forward) return low < sorted.size() ? low : 0;
        return low > 0 ? low - 1 : sorted.size() - 1;
    }

    /**
     * Centers a document line and selects it: the console viewer shows no caret, so without the selection
     * you could not tell which line a jump landed on. Stops following the end of the output.
     */
    private void showLine(int docLine) {
        showRange(docLine, document.getLineStartOffset(docLine), document.getLineEndOffset(docLine));
    }

    private void showRange(int docLine, int start, int end) {
        scrollToEnd = false;
        editor.getCaretModel().moveToOffset(start);
        editor.getSelectionModel().setSelection(start, end);
        // The selection is faint while the input field has the focus; this full-width mark is not.
        if (jumpHighlighter != null && jumpHighlighter.isValid()) editor.getMarkupModel().removeHighlighter(jumpHighlighter);
        jumpHighlighter = editor.getMarkupModel().addLineHighlighter(docLine, HighlighterLayer.SELECTION - 1, jumpAttrs);
        editor.getScrollingModel().scrollTo(new LogicalPosition(docLine, 0), ScrollType.CENTER);
    }

    /** Next/previous visible bookmark from the caret, wrapping around. */
    void jumpToBookmark(boolean forward) {
        int size = visibleLines.size();
        if (size == 0) return;
        int current = caretDocLine();
        for (int n = 1; n <= size; n++) {
            int i = Math.floorMod(current + (forward ? n : -n), size);
            LogLine line = visibleLines.get(i);
            if (line.bookmarkNote != null) {
                goTo(line, false);
                bookmarkList.setSelectedValue(line, true);
                return;
            }
        }
    }

    void copyBookmarks() {
        List<LogLine> selected = bookmarkList.getSelectedValuesList();
        List<LogLine> toCopy = selected.isEmpty() ? bookmarks.getItems() : selected;
        StringBuilder text = new StringBuilder();
        for (LogLine line : toCopy) {
            if (!line.bookmarkNote.isEmpty()) text.append("# ").append(line.bookmarkNote).append('\n');
            text.append(line.text).append('\n');
        }
        CopyPasteManager.getInstance().setContents(new StringSelection(text.toString()));
    }

    static boolean isCtrlClick(AWTEvent event) {
        // Cmd on macOS too, where Ctrl+click usually means right-click.
        return event instanceof InputEvent input
                && (input.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0;
    }

    /**
     * Ctrl+click on a level checkbox: show only that level. Ctrl+clicking the soloed level again restores the levels
     * shown before the first solo (soloing another level in between keeps them).
     */
    void toggleSoloLevel(JBCheckBox solo) {
        List<JBCheckBox> boxes = List.of(errorBox, warnBox, debugBox, otherBox);
        boolean soloed = boxes.stream().allMatch(box -> box.isSelected() == (box == solo));
        boolean[] target = new boolean[boxes.size()];
        if (soloed && levelsBeforeSolo != null) {
            target = levelsBeforeSolo;
            levelsBeforeSolo = null;
        } else {
            if (levelsBeforeSolo == null) {
                levelsBeforeSolo = new boolean[boxes.size()];
                for (int i = 0; i < boxes.size(); i++) levelsBeforeSolo[i] = boxes.get(i).isSelected();
            }
            target[boxes.indexOf(solo)] = true;
        }
        bulkFilterChange = true;
        try {
            for (int i = 0; i < boxes.size(); i++) boxes.get(i).setSelected(target[i]);
        } finally {
            bulkFilterChange = false;
        }
        rebuild();
        updateStatus();
    }

    /** Clears the text filter and shows all levels, rebuilding the output once. */
    private void showAllLines() {
        levelsBeforeSolo = null;
        bulkFilterChange = true;
        try {
            filterField.setText("");
            for (JBCheckBox box : List.of(errorBox, warnBox, debugBox, otherBox)) box.setSelected(true);
        } finally {
            bulkFilterChange = false;
        }
        filterTimer.stop();
        applyFilter();
    }

    private static final class BookmarkIcon extends GutterIconRenderer {
        private final LogLine line;

        BookmarkIcon(LogLine line) {
            this.line = line;
        }

        @Override
        public @NotNull Icon getIcon() {
            return AllIcons.Nodes.Bookmark;
        }

        @Override
        public String getTooltipText() {
            return line.bookmarkNote == null || line.bookmarkNote.isEmpty()
                    ? "Bookmark (click to remove)"
                    : "Bookmark: " + line.bookmarkNote + " (click to remove)";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof BookmarkIcon other && other.line == line;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(line);
        }
    }

    // ---------------------------------------------------------------- status

    private void updateStatus() {
        int shown = outputShown;
        int total = outputTotal;
        StringBuilder s = new StringBuilder();
        s.append(String.format("Showing %,d of %,d lines   ·   %,d errors   ·   %,d warnings   ·   %,d debug",
                shown, total, errorCount, warnCount, debugCount));
        if (filterError != null) s.append("   ·   Invalid regex: ").append(filterError);
        s.append("   ·   ").append(busy ? "Running" : shellReady ? "Idle" : "Starting shell");
        statusLabel.setText(s.toString());
        lineCountLabel.setText(shown == total ? String.format("Lines: %,d", total) : String.format("Lines: %,d of %,d", shown, total));
        if (filterPattern == null || excludeBox.isSelected()) matchLabel.setText("");
        else if (matchStarts.isEmpty()) matchLabel.setText("No matches");
        else if (currentMatch > 0) matchLabel.setText(String.format("%,d/%,d", currentMatch, matchStarts.size()));
        else matchLabel.setText(String.format("%,d matches", matchStarts.size()));
        errorBox.setText(levelText("Errors", LogLevel.ERROR, errorCount));
        warnBox.setText(levelText("Warnings", LogLevel.WARN, warnCount));
        debugBox.setText(levelText("Debug", LogLevel.DEBUG, debugCount));
    }

    private String levelText(String name, LogLevel level, int count) {
        return navLevel == level ? String.format("%s (%,d/%,d)", name, navIndex, count) : String.format("%s (%,d)", name, count);
    }

    private String defaultDir() {
        String base = project.getBasePath();
        // bash can't start in a directory that doesn't exist (e.g. a moved project, or a light test project)
        return base != null && new java.io.File(base).isDirectory() ? base : System.getProperty("user.home");
    }

    @Override
    public void dispose() {
        disposed = true;
        filterTimer.stop();
        discard(pendingCompletion);
        pendingCompletion = null;
        killShell(false);
        EditorFactory.getInstance().releaseEditor(editor);
    }
}
