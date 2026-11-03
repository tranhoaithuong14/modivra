package io.modivra.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies the real Spring AI 2.0.1 {@link OpenAiChatModel} and {@code openai-java-core} 4.49.0 HTTP stack
 * against a real local HTTP server mimicking xAI's {@code /v1/chat/completions} endpoint.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class OpenAiSdkWireIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static HttpServer mockXaiServer;
    private static final List<String> recordedRequestPaths = new CopyOnWriteArrayList<>();
    private static final List<String> recordedAuthHeaders = new CopyOnWriteArrayList<>();
    private static final List<String> recordedRequestBodies = new CopyOnWriteArrayList<>();

    static {
        try {
            mockXaiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            mockXaiServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            mockXaiServer.createContext("/v1/chat/completions", exchange -> {
                recordedRequestPaths.add(exchange.getRequestURI().getPath());
                recordedAuthHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
                String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                recordedRequestBodies.add(reqBody);

                if (reqBody.contains("\"stream\":true") || reqBody.contains("\"stream\": true")) {
                    String ssePayload = """
                        data: {"id":"chatcmpl-xai-1","object":"chat.completion.chunk","created":1711928372,"model":"grok-2-latest","choices":[{"index":0,"delta":{"role":"assistant","content":"Live "},"finish_reason":null}]}

                        data: {"id":"chatcmpl-xai-1","object":"chat.completion.chunk","created":1711928372,"model":"grok-2-latest","choices":[{"index":0,"delta":{"content":"xAI "},"finish_reason":null}]}

                        data: {"id":"chatcmpl-xai-1","object":"chat.completion.chunk","created":1711928372,"model":"grok-2-latest","choices":[{"index":0,"delta":{"content":"wire!"},"finish_reason":"stop"}],"usage":{"prompt_tokens":11,"completion_tokens":3,"total_tokens":14}}

                        data: [DONE]

                        """;
                    byte[] bytes = ssePayload.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                        os.flush();
                    }
                } else {
                    String jsonPayload = """
                        {
                          "id": "chatcmpl-xai-title",
                          "object": "chat.completion",
                          "created": 1711928373,
                          "model": "grok-2-latest",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "Live xAI Wire Test"
                              },
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 15,
                            "completion_tokens": 4,
                            "total_tokens": 19
                          }
                        }
                        """;
                    byte[] bytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                        os.flush();
                    }
                }
            });
            mockXaiServer.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void configureXaiMockServer(DynamicPropertyRegistry registry) {
        int port = mockXaiServer.getAddress().getPort();
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + port + "/v1");
        registry.add("spring.ai.openai.api-key", () -> "test-xai-live-key");
        registry.add("spring.ai.openai.chat.options.model", () -> "grok-2-latest");
    }

    @AfterAll
    static void stopServer() {
        if (mockXaiServer != null) {
            mockXaiServer.stop(0);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationNodeRepository nodeRepository;

    @BeforeEach
    void setUp() {
        recordedRequestPaths.clear();
        recordedAuthHeaders.clear();
        recordedRequestBodies.clear();
        conversationRepository.deleteAll();
    }

    @Test
    void shouldExecuteRealOpenAiChatModelOverHttpToV1ChatCompletionsAndPersistFullTurn() throws Exception {
        // Confirm we are testing the real auto-configured Spring AI OpenAiChatModel, not TestChatModel
        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);

        MvcResult initialResult = mockMvc.perform(
                post("/api/v1/conversation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .content("""
                        {
                          "action": "next",
                          "model": "grok-2-latest",
                          "messages": [
                            {
                              "author": { "role": "user" },
                              "content": { "content_type": "text", "parts": ["Verify real OpenAiChatModel HTTP wire call"] }
                            }
                          ]
                        }
                        """)
            )
            .andExpect(request().asyncStarted())
            .andReturn();

        MvcResult finalResult = mockMvc.perform(asyncDispatch(initialResult))
            .andExpect(status().isOk())
            .andReturn();

        String rawSse = finalResult.getResponse().getContentAsString();
        assertThat(rawSse).contains("event:delta_encoding");
        assertThat(rawSse).contains("Live ");
        assertThat(rawSse).contains("xAI ");
        assertThat(rawSse).contains("wire!");
        assertThat(rawSse).contains("Live xAI Wire Test");
        assertThat(rawSse).contains("[DONE]");

        // Verify both outbound HTTP calls (1 streaming + 1 title call) hit exact path /v1/chat/completions
        assertThat(recordedRequestPaths).containsExactly("/v1/chat/completions", "/v1/chat/completions");
        assertThat(recordedAuthHeaders).containsOnly("Bearer test-xai-live-key");
        assertThat(recordedRequestBodies.getFirst()).contains("\"model\":\"grok-2-latest\"");

        ConversationEntity savedConv = conversationRepository.findAll().getFirst();
        assertThat(savedConv.getTitle()).isEqualTo("Live xAI Wire Test");

        mockMvc.perform(get("/api/v1/conversation/{id}", savedConv.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Live xAI Wire Test"));

        ConversationNodeEntity assistantNode = nodeRepository
            .findByConversationIdOrderByCreatedAtAscIdAsc(savedConv.getId())
            .stream()
            .filter(n -> "assistant".equals(n.getRole()))
            .findFirst()
            .orElseThrow();

        assertThat(assistantNode.getStatus()).isEqualTo("finished_successfully");
        assertThat(assistantNode.getContent().extractText()).isEqualTo("Live xAI wire!");
    }
}
