package com.example.searchengine.web.api;

import com.example.searchengine.application.ingest.ContentAggregator;
import com.example.searchengine.infrastructure.sync.SyncCoordinator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit test for {@link AdminSyncController}.
 *
 * <p>Verifies the two main scenarios (REQ 3.1, 3.2):
 * <ul>
 *   <li>First call returns 202 with triggered=true, alreadyRunning=false.</li>
 *   <li>When coordinator reports already running, returns 202 with
 *       triggered=false, alreadyRunning=true.</li>
 * </ul>
 */
class AdminSyncControllerTest {

    private MockMvc mockMvc;
    private SyncCoordinator coordinator;
    private ContentAggregator contentAggregator;
    private TaskExecutor adminSyncExecutor;

    @BeforeEach
    void setUp() {
        coordinator = mock(SyncCoordinator.class);
        contentAggregator = mock(ContentAggregator.class);
        // Use a synchronous executor for testing — runs the task immediately on the calling thread
        adminSyncExecutor = Runnable::run;

        AdminSyncController controller = new AdminSyncController(
                coordinator, contentAggregator, adminSyncExecutor);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST /api/v1/admin/sync returns 202 with triggered=true when no sync is running")
    void triggerSync_noRunInProgress_returns202Triggered() throws Exception {
        when(coordinator.isRunning()).thenReturn(false);
        when(coordinator.tryRun(any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/admin/sync"))
                .andExpect(status().isAccepted())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.triggered").value(true))
                .andExpect(jsonPath("$.alreadyRunning").value(false));

        // Verify the executor dispatched the task which calls coordinator.tryRun
        verify(coordinator).tryRun(any());
    }

    @Test
    @DisplayName("POST /api/v1/admin/sync returns 202 with alreadyRunning=true when sync is in progress")
    void triggerSync_alreadyRunning_returns202AlreadyRunning() throws Exception {
        when(coordinator.isRunning()).thenReturn(true);

        mockMvc.perform(post("/api/v1/admin/sync"))
                .andExpect(status().isAccepted())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.triggered").value(false))
                .andExpect(jsonPath("$.alreadyRunning").value(true));

        // Verify the executor was NOT called — no task submitted
        verify(coordinator, never()).tryRun(any());
    }
}
