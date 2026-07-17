package com.example.agenticanalytics.web;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvcTest slices don't load ChatClientConfig (which needs a real Anthropic
 * API key and a reachable MCP gateway) — @MockitoBean supplies a fake
 * ChatClient instead. See ChatControllerTest's earlier version of this
 * comment (now QuestionControllerTest) for why the fluent chain is stubbed
 * by hand rather than with a deep-stub mock.
 */
@WebMvcTest(QuestionController.class)
class QuestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatClient chatClient;

    @Test
    @SuppressWarnings("unchecked")
    void askEndpointReturnsQuestionAndAnswer() throws Exception {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("There are 8 employees in the system.");

        mockMvc.perform(post("/api/questions")
                        .contentType("application/json")
                        .content("""
                                {"question": "How many employees are in the system?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value("How many employees are in the system?"))
                .andExpect(jsonPath("$.answer").value("There are 8 employees in the system."));
    }

    @Test
    void askEndpointReturnsReadableErrorWhenProviderCallFails() throws Exception {
        // Simulates what an auth failure, rate limit, or network error from
        // any provider client looks like at this layer: prompt() itself
        // doesn't throw, something further down the fluent chain does.
        when(chatClient.prompt()).thenThrow(new RuntimeException("invalid x-api-key"));

        mockMvc.perform(post("/api/questions")
                        .contentType("application/json")
                        .content("""
                                {"question": "How many employees are in the system?"}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("The model provider request failed."))
                .andExpect(jsonPath("$.detail").value("invalid x-api-key"));
    }
}
