# Log Console – IntelliJ IDEA plugin

A **Log Console** tool window: a bash session where you type or paste commands (e.g. `sh run-all.sh`
that starts stubs + apps), with the output colored and filterable. Linux and macOS only: it needs bash, and
Stop/Kill find processes via `/proc` (Linux) — on Windows the shell does not start.

- **Command box** at the bottom (drag the divider to resize it): Enter runs, Shift+Enter adds a line,
  pasted multi-line text runs line by line, Tab completes, ↑/↓ history, Ctrl+C interrupts, Ctrl+L clears.
  Commands typed with a leading space are not saved in the history (like bash's `ignorespace`).
  It's one persistent shell per tab, so `cd`, `export` and aliases carry over. While a command runs,
  what you type goes to that program's input.

- **Red** error lines, **yellow** warning lines, **green** debug lines, everything else default (white in a
  dark theme). The ⚙ button in the left toolbar opens the settings to change the color per level.
  Stack traces (`at …`, `Caused by:`, `... 12 more`) keep the color of the log line above them.
  The level token appearing *first* in a line wins, so `INFO … got ERROR from stub` stays white.
- **Live filter** on keyword or regex, case-sensitive or not, *include* or *exclude* matching lines;
  matches are highlighted. Level toggles: Errors / Warnings / Debug (DEBUG, TRACE) / Other (INFO, rest).
  Each toggle shows its count, e.g. *Errors (12)*, and has ▲/▼ buttons to jump to the previous/next
  error, warning or debug entry, which gets selected and marked with a yellow box, wrapping around. While
  stepping, the toggle shows where you are, e.g. *Errors (3/12)*. Ctrl+click (⌘+click) a toggle to show only
  that level; Ctrl+click it again to restore the levels you had. Counts and jumps are per entry: a stack trace
  (and an exception printed right below its ERROR line) belongs to its entry, and back-to-back errors are
  separate entries. A jump pauses scrolling. Keyboard: F2 / Shift+F2 for next/previous error (your keymap's
  *Next/Previous Highlighted Error*) while the focus is in the tab.
- Next to the filter field: the line counter (*Lines: 1,234*, or *Lines: 120 of 1,234* while filtering) and,
  for an include filter, the match counter (*3/17*) with ▲/▼ to step through the matches like the IDE's
  Find bar. Enter / Shift+Enter in the filter field go to the next / previous match; F3 / Shift+F3 anywhere in the tab.
- **Clickable stack traces**: `at com.foo.Bar.baz(Bar.java:42)` links to the source, like in the Run console
  (the same console filters; links to classes in the open project).
- Ctrl+double-click (⌘+double-click) a line to select all of it rather than one word.
- The ▲/▼ arrows in the left toolbar scroll up/down one page (and pause scrolling).
- **Pause Scrolling** toggle keeps the view still while output arrives; *Scroll to the Top* shows the first
  line (and pauses), *Scroll to the End* shows the last line and follows new output again. Soft-wrap, clear.
- **Bookmarks**: right-click a line → *Add Bookmark* / *Add Bookmark with Note…* (or click the gutter,
  F11 / Shift+F11). The **Bookmarks** box on the right lists them; click one to jump to it, double-click
  to jump even when the filter hides it (the filter is cleared). Buttons: add at caret, remove, edit note,
  previous/next, copy bookmarked lines, remove all. The bookmark icon in the toolbar shows/hides the box.
- **Save Output…** (toolbar) writes the output to a file; with a filter active it asks: all lines or the shown ones.
- Multiple tabs (`+` in the tool window title); one tab per script.
- **Stop** ends everything started from the tab but keeps the shell (and your `cd`/exports): the running
  command, background jobs and apps a script left running, even when it ended without `wait` or an app
  detached with `setsid` (each tab's shell gets a `LOG_CONSOLE_SESSION` variable that its processes inherit). SIGTERM first so Spring shutdown hooks run; anything alive after 10s gets SIGKILL.
  **Kill shell** does the same and ends bash itself with SIGHUP (anything still alive after 5s gets SIGKILL), then
  opens a fresh shell.
  Ctrl+click (⌘+click) *Kill shell* to also SIGTERM every Java process, like `pkill -f java` (after a confirmation;
  the IDE itself and the processes it runs under are spared). Ctrl+C in the command box interrupts the running command.
  Closing a tab kills too. Not reachable: processes owned by another daemon, e.g. apps run by the
  Gradle daemon (`gradle bootRun` without `--no-daemon`) or `docker run -d` / `docker compose up -d`.
- Right-click a `.sh` file → **Run 'run-all.sh' in Log Console** (project view, editor, editor tab).
- Settings | Tools | Log Console (or ⚙ in the toolbar): level regexes, a color per level (error, warning,
  debug, and optionally other), max lines per tab.

## Build & install

```bash
./gradlew buildPlugin
```

Then in IntelliJ: *Settings → Plugins → ⚙ → Install Plugin from Disk…* →
`build/distributions/log-console-1.0.0.zip`. Requires IntelliJ IDEA 2026.1 or newer.

To try it in a sandbox IDE without touching your own: `./gradlew runIde`, then right-click
`samples/demo.sh` → *Run 'demo.sh' in Log Console*.

Run the tests with `./gradlew test`.

## Tips for your scripts

- End `run-all.sh` with `wait`, so the tab shows it as running until all apps have exited.
- Prefix lines per app if you want to filter by app, e.g. `java -jar stub.jar 2>&1 | sed 's/^/[stub] /' &`,
  then filter on `[stub]`.
- ANSI color codes in the output are stripped; the plugin applies its own colors.
