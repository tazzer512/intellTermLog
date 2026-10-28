package dev.logconsole;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/** "Run 'run-all.sh' in Log Console" in the project view and editor context menus. */
public final class RunInLogConsoleAction extends DumbAwareAction {
    private static final Set<String> SCRIPT_EXTENSIONS = Set.of("sh", "bash", "zsh", "ksh");

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean enabled = e.getProject() != null && file != null && isScript(file);
        e.getPresentation().setEnabledAndVisible(enabled);
        if (enabled) e.getPresentation().setText("Run '" + file.getName() + "' in Log Console");
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || file == null || file.getParent() == null) return;
        String command = "cd " + quote(file.getParent().getPath()) + " && " + commandFor(file);
        LogConsoleToolWindowFactory.run(project, command);
    }

    private static boolean isScript(VirtualFile file) {
        String ext = file.getExtension();
        return !file.isDirectory() && file.isInLocalFileSystem() && ext != null && SCRIPT_EXTENSIONS.contains(ext.toLowerCase());
    }

    private static String commandFor(VirtualFile file) {
        String name = file.getName();
        String ext = file.getExtension() == null ? "" : file.getExtension().toLowerCase();
        String shell = ext.equals("zsh") ? "zsh" : ext.equals("ksh") ? "ksh" : "bash";
        return shell + " " + quote(name);
    }

    static String quote(String s) {
        return s.matches("[\\w.,/@%+=:-]+") ? s : "'" + s.replace("'", "'\\''") + "'";
    }
}
