package dev.changestree.smartchanges.ui;

import com.intellij.openapi.vcs.changes.ui.ChangesViewContentProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

public final class SmartChangesContentProvider implements ChangesViewContentProvider {
    private final Project project;
    private SmartChangesPanel panel;

    public SmartChangesContentProvider(Project project) {
        this.project = project;
    }

    @Override
    public @Nullable JComponent initContent() {
        if (panel == null) {
            panel = new SmartChangesPanel(project);
            Disposer.register(project, panel);
        }
        return panel;
    }

    @Override
    public void initTabContent(Content content) {
        JComponent component = initContent();
        content.setComponent(component);
    }

    @Override
    public void disposeContent() {
        if (panel != null) {
            Disposer.dispose(panel);
            panel = null;
        }
    }
}
