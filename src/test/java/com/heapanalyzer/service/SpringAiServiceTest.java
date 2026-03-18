package com.heapanalyzer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SpringAiServiceTest {

    @Mock
    private ConfigService configService;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.PromptSystemSpec promptSystemSpec;

    @Mock
    private ChatClient.PromptUserSpec promptUserSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    private SpringAiService springAiService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        springAiService = new SpringAiService(configService);
    }

    @Test
    void init_shouldReconfigureWhenConfigured() {
        when(configService.isConfigured()).thenReturn(true);
        when(configService.getApiKey()).thenReturn("sk-test");
        when(configService.getBaseUrl()).thenReturn("https://api.example.com");
        when(configService.getModel()).thenReturn("test-model");
        when(configService.getTemperature()).thenReturn(0.5);
        when(configService.isTrustInsecureCerts()).thenReturn(false);

        springAiService.init();

        assertTrue(springAiService.isReady());
    }

    @Test
    void init_shouldNotReconfigureWhenNotConfigured() {
        when(configService.isConfigured()).thenReturn(false);

        springAiService.init();

        assertFalse(springAiService.isReady());
    }

    @Test
    void isReady_shouldReturnTrueWhenChatClientIsSet() {
        ReflectionTestUtils.setField(springAiService, "chatClient", chatClient);
        assertTrue(springAiService.isReady());
    }

    @Test
    void isReady_shouldReturnFalseWhenChatClientIsNull() {
        assertFalse(springAiService.isReady());
    }

    @Test
    void getEffectivePrompt_shouldReturnCustomPromptWhenSet() {
        when(configService.getCustomPrompt(SpringAiService.PROMPT_HEAP_DUMP)).thenReturn("Custom heap prompt");
        assertEquals("Custom heap prompt", springAiService.getEffectivePrompt(SpringAiService.PROMPT_HEAP_DUMP));
    }

    @Test
    void getEffectivePrompt_shouldReturnDefaultPromptWhenCustomIsNull() {
        when(configService.getCustomPrompt(SpringAiService.PROMPT_HEAP_DUMP)).thenReturn(null);
        assertEquals(SpringAiService.DEFAULT_HEAP_DUMP_PROMPT, springAiService.getEffectivePrompt(SpringAiService.PROMPT_HEAP_DUMP));
    }

    @Test
    void getEffectivePrompt_shouldReturnDefaultPromptWhenCustomIsBlank() {
        when(configService.getCustomPrompt(SpringAiService.PROMPT_HEAP_DUMP)).thenReturn("   ");
        assertEquals(SpringAiService.DEFAULT_HEAP_DUMP_PROMPT, springAiService.getEffectivePrompt(SpringAiService.PROMPT_HEAP_DUMP));
    }

    @Test
    void getEffectivePrompt_shouldThrowExceptionForUnknownType() {
        assertThrows(IllegalArgumentException.class, () -> springAiService.getEffectivePrompt("unknown-type"));
    }

    private void setupMockChatClient(String expectedResponse) {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(expectedResponse);
    }

    @Test
    void analyze_shouldReturnResponse() {
        ReflectionTestUtils.setField(springAiService, "chatClient", chatClient);
        setupMockChatClient("Mocked heap dump analysis");

        String result = springAiService.analyze("Heap dump report content");

        assertEquals("Mocked heap dump analysis", result);
    }

    @Test
    void analyzeThreadDump_shouldReturnResponse() {
        ReflectionTestUtils.setField(springAiService, "chatClient", chatClient);
        setupMockChatClient("Mocked thread dump analysis");

        String result = springAiService.analyzeThreadDump("Thread dump report content");

        assertEquals("Mocked thread dump analysis", result);
    }

    @Test
    void analyzeGcLog_shouldReturnResponse() {
        ReflectionTestUtils.setField(springAiService, "chatClient", chatClient);
        setupMockChatClient("Mocked GC log analysis");

        String result = springAiService.analyzeGcLog("GC log report content");

        assertEquals("Mocked GC log analysis", result);
    }

    @Test
    void sendToAi_shouldThrowExceptionWhenChatClientIsNull() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> springAiService.analyze("Some report"));

        assertTrue(exception.getMessage().contains("AI is not configured"));
    }
}
