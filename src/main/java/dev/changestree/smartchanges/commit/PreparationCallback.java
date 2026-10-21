package dev.changestree.smartchanges.commit;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Checks validity on the destination thread, after dispatch rather than before it. */
final class PreparationCallback {
    private PreparationCallback() { }

    static Runnable create(Consumer<Runnable> dispatch, BooleanSupplier valid,
                           Runnable action, Consumer<RuntimeException> failure) {
        return () -> dispatch.accept(() -> {
            if (!valid.getAsBoolean()) {
                return;
            }
            try {
                action.run();
            } catch (RuntimeException exception) {
                failure.accept(exception);
            }
        });
    }
}
