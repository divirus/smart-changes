package dev.changestree.smartchanges.commit;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone regression checks; no IDE instance or test framework is required. */
public final class PreparationCallbackTest {
    public static void main(String[] args) throws Exception {
        PanelChangesViewTest.run();
        dev.changestree.smartchanges.model.ModelRegressionTest.run();
        dev.changestree.smartchanges.ui.ProjectorRegressionTest.run();
        runThreading();
    }

    static void runThreading() throws Exception {
        AtomicBoolean onEdt = new AtomicBoolean();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Runnable callback = PreparationCallback.create(SwingUtilities::invokeLater, () -> true,
                () -> onEdt.set(SwingUtilities.isEventDispatchThread()), error::set);
        Thread worker = new Thread(callback, "proxy-refresh-worker");
        worker.start();
        worker.join();
        SwingUtilities.invokeAndWait(() -> { });
        check(onEdt.get(), "Background refresh must prepare the UI on EDT");
        check(error.get() == null, "Successful preparation must not report an error");

        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicBoolean valid = new AtomicBoolean(true);
        AtomicBoolean ran = new AtomicBoolean();
        PreparationCallback.create(queued::set, valid::get, () -> ran.set(true), error::set).run();
        valid.set(false); // Timeout/disposal occurs after refresh but before EDT dispatch.
        SwingUtilities.invokeAndWait(queued.get());
        check(!ran.get(), "An expired preparation must not change inclusion or start a commit");

        RuntimeException expected = new IllegalStateException("Preparation failed");
        AtomicBoolean failureOnEdt = new AtomicBoolean();
        PreparationCallback.create(SwingUtilities::invokeLater, () -> true, () -> { throw expected; }, ex -> {
            error.set(ex);
            failureOnEdt.set(SwingUtilities.isEventDispatchThread());
        }).run();
        SwingUtilities.invokeAndWait(() -> { });
        check(error.get() == expected && failureOnEdt.get(), "Async failures must reach the EDT error handler");
        System.out.println("PASS: EDT dispatch, stale callback rejection, asynchronous failure handling");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
