package com.example.searchengine.infrastructure.sync;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mutual-exclusion guard shared by the scheduled and the manual sync triggers.
 *
 * <p>Holds a single {@link AtomicBoolean} flag and offers a non-blocking
 * {@link #tryRun(Runnable)} primitive. The first caller atomically flips the
 * flag from {@code false} to {@code true}, runs the supplied {@link Runnable}
 * <strong>on the calling thread</strong>, and resets the flag in a
 * {@code finally} block. Any concurrent caller that observes the flag set
 * receives {@code false} and the runnable is skipped.</p>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #tryRun(Runnable)} runs the body synchronously on the calling
 *       thread. Callers that want asynchronous dispatch (e.g. the admin
 *       manual-sync endpoint) wrap the call in a
 *       {@link org.springframework.core.task.TaskExecutor#execute task
 *       executor}, where the submitted task simply invokes
 *       {@code coordinator.tryRun(...)}.</li>
 *   <li>Returns {@code true} when the runnable executed (or threw) on this
 *       thread; returns {@code false} when a run was already in progress and
 *       this invocation was skipped.</li>
 *   <li>Exceptions raised by the runnable propagate to the caller; the running
 *       flag is reset regardless via {@code try / finally}.</li>
 * </ul>
 *
 * <p>Validates Requirement 3.2 (manual sync mutual exclusion) and design
 * Property 4 (SyncCoordinator mutual exclusion).</p>
 */
@Component
public class SyncCoordinator {

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Atomically attempts to acquire the running guard, runs the supplied
     * body on the calling thread, and releases the guard.
     *
     * @param body the runnable to invoke when the guard was acquired; must
     *             not be {@code null}
     * @return {@code true} when the body was executed (even if it threw),
     *         {@code false} when a run was already in progress
     * @throws NullPointerException if {@code body} is {@code null}
     */
    public boolean tryRun(Runnable body) {
        Objects.requireNonNull(body, "body");
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            body.run();
        } finally {
            running.set(false);
        }
        return true;
    }

    /**
     * @return {@code true} while a {@link #tryRun(Runnable)} body is currently
     *         executing on any thread; {@code false} otherwise.
     */
    public boolean isRunning() {
        return running.get();
    }
}
