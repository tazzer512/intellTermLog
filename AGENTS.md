# Log Console – notes for coding agents

IntelliJ IDEA plugin (Java 21, IntelliJ Platform Gradle Plugin 2.x, target IDEA 2026.1+): a tool window
with a persistent bash session per tab whose output is colored by log level, filterable, and bookmarkable.
User-facing behavior is described in `README.md`; keep it in sync when behavior changes.

## Commands

```bash
./gradlew test          # JUnit 5 unit tests + LogConsolePanelTest (BasePlatformTestCase via the vintage engine);
                        # some run real bash/processes, Linux (and macOS) only
./gradlew buildPlugin   # -> build/distributions/log-console-<version>.zip
./gradlew runIde        # sandbox IDE with the plugin, opens samples/ (right-click demo.sh -> Run in Log Console)
```

Compiler runs with `-Xlint:deprecation -Xlint:removal`; keep the build free of warnings.

Restarting a running sandbox IDE: find it with `pgrep -f "[i]dea-2026.1.5/jbr/bin/java"` and kill those
pids. Do **not** use `pkill -f "idea-2026.1.5/jbr/bin/java"`: the pattern matches your own shell command
and kills it. The sandbox log is `.intellijPlatform/sandbox/log-console/IU-*/log_runIde/idea.log`; check it
for `Loaded custom plugins: Log Console` and for ` ERROR ` lines. The sandbox runs on Wayland, so it can't be
screenshotted. Test UI behavior in `LogConsolePanelTest` instead: it creates a real panel and editor (with a real
shell), feeds lines with `enqueue` + `flush`, and calls the same methods the toolbar buttons call. Fields and
methods it needs are package-private.

## Layout (`src/main/java/dev/logconsole`)

| File | Role |
|------|------|
| `LogConsolePanel` | One tab: UI, bash session lifecycle, output pipeline, filter, completion, bookmarks, history. EDT-only state unless `volatile`. |
| `ShellOutputParser` | Splits PTY output into lines, strips ANSI, handles `\r` overwrites, recognizes the prompt/completion markers. Pure, tested. |
| `ShellText` | Quoting/escaping, word boundaries, file completion, tab titles. Pure, tested. |
| `ShellProcesses` | Finds processes started from a shell (tree + session + env tag via `/proc`) for Stop/Kill. Tested. |
| `LogClassifier` | Level of a line: the earliest-matching pattern wins; stack-trace continuation lines inherit. `continuesEntry` decides `LogLine.entryStart`, which the level counters and jumps use. Tested. |
| `LogConsoleSettings` / `LogConsoleConfigurable` | App-level settings (patterns, colors, max lines), Settings \| Tools \| Log Console. |
| `LogConsoleToolWindowFactory` | Tool window + tabs; `run(project, command)` reuses or opens a tab. |
| `RunInLogConsoleAction` | "Run 'x.sh' in Log Console" on script files. |
| `resources/dev/logconsole/complete.bash` | `__lc_complete` bash function used by Tab completion. Tested via `CompleteBashTest`. |

## How the shell session works (don't break these invariants)

- bash runs on a PTY (`PtyCommandLine`, `bash --noediting -i`, `TERM=dumb`) with echo off and empty
  `PS1`/`PS2`. `PROMPT_COMMAND` prints `\036LCPWD:<cwd>`: that marker means "idle again" and updates the cwd.
  Output before the first marker (bashrc noise) is dropped.
- Commands we inject (init line, completion requests) start with a space so they stay out of bash history.
- Tab completion while idle writes ` __lc_complete '<line>' >'<file>'; printf '\036LCDONE:<id>\n'` to the
  shell and reads the file when the marker arrives; while busy it falls back to `ShellText.fileCompletions`.
  The helper script and result files live in `PathManager.getSystemPath()/log-console`, never in `/tmp`
  (the shell `source`s the script).
- Each shell gets `LOG_CONSOLE_SESSION=<uuid>` in its environment. Stop = SIGTERM to everything from
  `ShellProcesses.startedFrom` (shell excluded), SIGKILL after 10s; Kill also includes the shell (5s).
  pty4j's `Process` does not support `toHandle()`; use `ProcessHandle.of(process.pid())`.
- Output is queued off-EDT (`pending`, capped at `MAX_PENDING`) and flushed in 50 ms batches on the EDT.
  `visibleLines.get(i)` is document line `i`. Bookmarks reference `LogLine` objects (identity), not line numbers.
- Level highlighters are coalesced per run of same-level lines; a filter/level change rebuilds the document.
- `scrollToEnd` = follow new output (the Pause Scrolling toggle is its inverse). Jumps and Scroll to the Top
  clear it. The caret normally sits on the empty line after the output, so jumps treat that line specially.
- Filter matches are recorded as document offsets (`matchStarts`/`matchEnds`) while highlighting, so match
  navigation and the 3/17 counter need no rescans; `rebuild()` resets them.
- Links (stack traces etc.) come from the platform's `consoleFilterProvider` extensions (the Java plugin's
  `DefaultConsoleFiltersProvider` gives `ExceptionFilter`), so there is no hard dependency on the Java plugin.
  `EditorHyperlinkSupport.highlightHyperlinksLater` runs them per appended batch; `rebuild()`/`clear()` bump
  `linkGeneration` so searches over the old text expire.
- `samples/demo.sh` must run under bash (`$RANDOM`); it re-execs itself with bash when started via `sh`.

## Conventions

- Plain Java, no Kotlin. Match the existing style: small static helpers in package-private classes,
  comments that explain *why*, `DumbAwareAction`s with `ActionUpdateThread.EDT` when they read UI state.
- Put new pure logic in a testable class (like `ShellText`) and add tests in `src/test/java/dev/logconsole`.
- Run `./gradlew test buildPlugin` before handing work back.
