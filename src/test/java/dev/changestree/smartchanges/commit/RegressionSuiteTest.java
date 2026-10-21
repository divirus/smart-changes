package dev.changestree.smartchanges.commit;

import org.junit.Test;

public final class RegressionSuiteTest {
    @Test public void inclusionWithoutNativeTree() { PanelChangesViewTest.run(); }
    @Test public void modelsAndWorkspaceSettings() { dev.changestree.smartchanges.model.ModelRegressionTest.run(); }
    @Test public void tableProjection() { dev.changestree.smartchanges.ui.ProjectorRegressionTest.run(); }
    @Test public void callbackThreading() throws Exception { PreparationCallbackTest.runThreading(); }
}
