package dev.logconsole;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class LogConsoleToolWindowFactory implements ToolWindowFactory, DumbAware {
    public static final String TOOL_WINDOW_ID = "Log Console";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        newTab(project, toolWindow);
        toolWindow.setTitleActions(List.of(new DumbAwareAction("New Tab", "Open another Log Console tab", AllIcons.General.Add) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                newTab(project, toolWindow);
            }
        }));
    }

    static LogConsolePanel newTab(Project project, ToolWindow toolWindow) {
        LogConsolePanel panel = new LogConsolePanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "Shell", false);
        content.setCloseable(true);
        content.setDisposer(panel);
        content.setPreferredFocusableComponent(panel.getPreferredFocus());
        panel.bindTab(content);
        ContentManager contentManager = toolWindow.getContentManager();
        contentManager.addContent(content);
        contentManager.setSelectedContent(content);
        return panel;
    }

    /** Runs the command in a tab that ran it before (restarting it), an idle tab, or a new tab. */
    static void run(Project project, String command) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null) return;
        toolWindow.activate(() -> {
            ContentManager contentManager = toolWindow.getContentManager();
            for (Content content : contentManager.getContents()) {
                if (content.getComponent() instanceof LogConsolePanel panel && panel.canReuseFor(command)) {
                    contentManager.setSelectedContent(content);
                    panel.runCommand(command);
                    return;
                }
            }
            newTab(project, toolWindow).runCommand(command);
        });
    }
}
