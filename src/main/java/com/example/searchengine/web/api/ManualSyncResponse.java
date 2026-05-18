package com.example.searchengine.web.api;

/**
 * JSON response envelope for the manual sync trigger endpoint
 * ({@code POST /api/v1/admin/sync}).
 *
 * <p>Validates: Requirements 3.1, 3.2 (operability quick-wins).</p>
 *
 * @param triggered      {@code true} when a new sync run was dispatched;
 *                       {@code false} when a run was already in progress
 * @param alreadyRunning {@code true} when a sync was already in progress and
 *                       the request was skipped; {@code false} otherwise
 */
public record ManualSyncResponse(boolean triggered, boolean alreadyRunning) {

    /** Factory for the "sync dispatched" case. */
    public static ManualSyncResponse syncTriggered() {
        return new ManualSyncResponse(true, false);
    }

    /** Factory for the "already running" case. */
    public static ManualSyncResponse syncAlreadyRunning() {
        return new ManualSyncResponse(false, true);
    }
}
