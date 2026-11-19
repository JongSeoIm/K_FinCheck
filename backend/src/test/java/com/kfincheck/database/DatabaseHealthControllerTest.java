package com.kfincheck.database;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DatabaseHealthControllerTest {
    @Test
    void confirmsTargetDatabaseAndRejectsOtherSchema() throws Exception {
        var mapper = mock(DatabaseCheckMapper.class);
        when(mapper.ping()).thenReturn(1);
        when(mapper.currentDatabase()).thenReturn("k_fincheck");
        var mvc = MockMvcBuilders.standaloneSetup(new DatabaseHealthController(mapper)).build();
        mvc.perform(get("/api/health/db")).andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("k_fincheck"))
                .andExpect(jsonPath("$.queryResult").value(1));
        when(mapper.currentDatabase()).thenReturn("hk");
        mvc.perform(get("/api/health/db")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void hidesConnectionExceptionDetails() throws Exception {
        var mapper = mock(DatabaseCheckMapper.class);
        when(mapper.ping()).thenThrow(new IllegalStateException("sensitive-test-detail"));
        var mvc = MockMvcBuilders.standaloneSetup(new DatabaseHealthController(mapper)).build();
        mvc.perform(get("/api/health/db")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive-test-detail"))));
    }
}
