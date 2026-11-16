package com.drewdrew0414.global.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** DB/서버 없이 실제 MVC 응답과 준비 상태 전환을 확인합니다. */
class ReadinessControllerTest {
    private final ApplicationAvailability availability=mock(ApplicationAvailability.class);
    private MockMvc mvc;
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.standaloneSetup(new ReadinessController(availability)).build();
    }
    @Test void refusingTrafficReturns503WithoutPublishingInternalState() throws Exception {
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        mvc.perform(get("/api/ready")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.errorCode").value("SERVICE_NOT_READY"))
            .andExpect(jsonPath("$.data").isEmpty())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("REFUSING_TRAFFIC"))));
    }
    @Test void acceptingTrafficReturns200OnlyAfterTheAvailabilityTransition() throws Exception {
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC,
            ReadinessState.ACCEPTING_TRAFFIC,ReadinessState.REFUSING_TRAFFIC);
        mvc.perform(get("/api/ready")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/ready")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.ready").value(true));
        mvc.perform(get("/api/ready")).andExpect(status().isServiceUnavailable());
    }
    @Test void missingReadinessStateFailsClosed() throws Exception {
        when(availability.getReadinessState()).thenReturn(null);
        mvc.perform(get("/api/ready")).andExpect(status().isServiceUnavailable());
    }
}
