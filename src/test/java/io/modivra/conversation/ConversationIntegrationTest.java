package io.modivra.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.modivra.TestChatModel;
import io.modivra.TestcontainersConfiguration;
import io.modivra.conversation.ConversationDtos.ConversationDetailResponse;
import io.modivra.conversation.ConversationDtos.NodeMappingDto;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ConversationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestChatModel testChatModel;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationNodeRepository nodeRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        testChatModel.reset();
        conversationRepository.deleteAll();
    }

    public record SseFrame(String event, String data) {
    }

    private static List<SseFrame> parseSseFrames(String rawBody) {
        List<SseFrame> frames = new ArrayList<>();
        String[] blocks = rawBody.split("\\r?\\n\\r?\\n");
        for (String block : blocks) {
            if (block.isBlank()) {
                continue;
            }
            String event = null;
            StringBuilder dataBuilder = new StringBuilder();
            for (String line : block.split("\\r?\\n")) {
                if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (!dataBuilder.isEmpty()) {
                        dataBuilder.append("\n");
                    }
                    dataBuilder.append(line.substring("data:".length()).trim());
                }
            }
            if (!dataBuilder.isEmpty() || event != null) {
                frames.add(new SseFrame(event, dataBuilder.toString()));
            }
        }
        return frames;
    }

    @Test
    void shouldStreamSseDeltaEncodingV1AndBuildValidFourNodeTree() throws Exception {
        String requestPayload = """
            {
              "action": "next",
              "model": "grok-2-latest",
              "messages": [
                {
                  "author": { "role": "user" },
                  "content": { "content_type": "text", "parts": ["Explain Virtual Threads in Java 25"] }
                }
              ]
            }
            """;

        MvcResult initialResult = mockMvc.perform(
                post("/api/v1/conversation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .content(requestPayload)
            )
            .andExpect(request().asyncStarted())
            .andReturn();

        MvcResult finalResult = mockMvc.perform(asyncDispatch(initialResult))
            .andExpect(status().isOk())
            .andReturn();

        String rawSse = finalResult.getResponse().getContentAsString();
        List<SseFrame> frames = parseSseFrames(rawSse);

        // 1. Verify exact SSE Delta Encoding v1 sequence
        assertThat(frames).hasSizeGreaterThanOrEqualTo(11);

        // Frame 0: event: delta_encoding / data: "v1"
        assertThat(frames.get(0).event()).isEqualTo("delta_encoding");
        assertThat(frames.get(0).data()).isEqualTo("\"v1\"");

        // Frame 1: Initial state delta patch (p="", o="add", parts=[""])
        assertThat(frames.get(1).event()).isEqualTo("delta");
        JsonNode initPatch = objectMapper.readTree(frames.get(1).data());
        assertThat(initPatch.get("p").asString()).isEmpty();
        assertThat(initPatch.get("o").asString()).isEqualTo("add");
        String conversationIdStr = initPatch.path("v").path("conversation_id").asString();
        assertThat(conversationIdStr).isNotBlank();
        UUID conversationId = UUID.fromString(conversationIdStr);
        assertThat(initPatch.path("v").path("message").path("status").asString()).isEqualTo("in_progress");
        assertThat(initPatch.path("v").path("message").path("content").path("parts").get(0).asString()).isEmpty();

        // Frames 2..5: Token append chunks ("Hello", " from", " Grok", "!")
        assertThat(frames.get(2).event()).isEqualTo("delta");
        assertThat(objectMapper.readTree(frames.get(2).data()).get("p").asString())
            .isEqualTo("/message/content/parts/0");
        assertThat(objectMapper.readTree(frames.get(2).data()).get("o").asString())
            .isEqualTo("append");
        assertThat(objectMapper.readTree(frames.get(2).data()).get("v").asString())
            .isEqualTo("Hello");

        assertThat(objectMapper.readTree(frames.get(3).data()).get("v").asString()).isEqualTo(" from");
        assertThat(objectMapper.readTree(frames.get(4).data()).get("v").asString()).isEqualTo(" Grok");
        assertThat(objectMapper.readTree(frames.get(5).data()).get("v").asString()).isEqualTo("!");

        // Frame 6: Terminal status patch
        assertThat(frames.get(6).event()).isEqualTo("delta");
        JsonNode statusPatch = objectMapper.readTree(frames.get(6).data());
        assertThat(statusPatch.get("p").asString()).isEqualTo("/message/status");
        assertThat(statusPatch.get("o").asString()).isEqualTo("replace");
        assertThat(statusPatch.get("v").asString()).isEqualTo("finished_successfully");

        // Frame 7: Terminal end_turn patch
        assertThat(frames.get(7).event()).isEqualTo("delta");
        JsonNode endTurnPatch = objectMapper.readTree(frames.get(7).data());
        assertThat(endTurnPatch.get("p").asString()).isEqualTo("/message/end_turn");
        assertThat(endTurnPatch.get("o").asString()).isEqualTo("replace");
        assertThat(endTurnPatch.get("v").asBoolean()).isTrue();

        // Frame 8: finish_details patch
        assertThat(frames.get(8).event()).isEqualTo("delta");
        JsonNode finishPatch = objectMapper.readTree(frames.get(8).data());
        assertThat(finishPatch.get("p").asString()).isEqualTo("/message/metadata/finish_details");
        assertThat(finishPatch.get("o").asString()).isEqualTo("add");

        // Frame 9: title_generation data frame
        assertThat(frames.get(9).event()).isNull();
        JsonNode titleFrame = objectMapper.readTree(frames.get(9).data());
        assertThat(titleFrame.get("type").asString()).isEqualTo("title_generation");
        assertThat(titleFrame.get("title").asString()).isEqualTo("Virtual Threads Overview");
        assertThat(titleFrame.get("conversation_id").asString()).isEqualTo(conversationIdStr);

        // Frame 10: message_stream_complete data frame
        assertThat(frames.get(10).event()).isNull();
        JsonNode completeFrame = objectMapper.readTree(frames.get(10).data());
        assertThat(completeFrame.get("type").asString()).isEqualTo("message_stream_complete");
        assertThat(completeFrame.get("conversation_id").asString()).isEqualTo(conversationIdStr);

        // Frame 11: [DONE]
        assertThat(frames.get(11).event()).isNull();
        assertThat(frames.get(11).data()).isEqualTo("[DONE]");

        // Verify empty system message was filtered out before calling LLM
        assertThat(testChatModel.getRecordedStreamPrompts()).hasSize(1);
        Prompt sentPrompt = testChatModel.getRecordedStreamPrompts().getFirst();
        assertThat(sentPrompt.getInstructions()).hasSize(1);
        assertThat(sentPrompt.getInstructions().getFirst().getText())
            .isEqualTo("Explain Virtual Threads in Java 25");

        // 2. Verify GET /api/v1/conversation/{id} tree mapping and invariants
        String detailJson = mockMvc.perform(get("/api/v1/conversation/{id}", conversationId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.conversation_id").value(conversationIdStr))
            .andExpect(jsonPath("$.title").value("Virtual Threads Overview"))
            .andExpect(jsonPath("$.create_time").isNumber())
            .andExpect(jsonPath("$.update_time").isNumber())
            .andReturn()
            .getResponse()
            .getContentAsString();

        ConversationDetailResponse detail = objectMapper.readValue(
            detailJson,
            ConversationDetailResponse.class
        );
        assertThat(detail.mapping()).hasSize(4);

        // Locate Root Node (parent == null, message == null)
        NodeMappingDto rootNode = detail.mapping().values().stream()
            .filter(n -> n.parent() == null)
            .findFirst()
            .orElseThrow();
        assertThat(rootNode.message()).isNull();
        assertThat(rootNode.children()).hasSize(1);

        // Locate Hidden System Node
        String sysNodeId = rootNode.children().getFirst();
        NodeMappingDto sysNode = detail.mapping().get(sysNodeId);
        assertThat(sysNode).isNotNull();
        assertThat(sysNode.parent()).isEqualTo(rootNode.id());
        assertThat(sysNode.message().author().role()).isEqualTo("system");
        assertThat(sysNode.message().metadata().get("is_visually_hidden_from_conversation"))
            .isEqualTo(Boolean.TRUE);
        assertThat(sysNode.children()).hasSize(1);

        // Locate User Node
        String userNodeId = sysNode.children().getFirst();
        NodeMappingDto userNode = detail.mapping().get(userNodeId);
        assertThat(userNode).isNotNull();
        assertThat(userNode.parent()).isEqualTo(sysNodeId);
        assertThat(userNode.message().author().role()).isEqualTo("user");
        assertThat(userNode.message().content().parts())
            .containsExactly("Explain Virtual Threads in Java 25");
        assertThat(userNode.children()).hasSize(1);

        // Locate Assistant Leaf Node
        String assistantNodeId = userNode.children().getFirst();
        NodeMappingDto assistantNode = detail.mapping().get(assistantNodeId);
        assertThat(assistantNode).isNotNull();
        assertThat(assistantNode.parent()).isEqualTo(userNodeId);
        assertThat(assistantNode.message().author().role()).isEqualTo("assistant");
        assertThat(assistantNode.message().content().parts()).containsExactly("Hello from Grok!");
        assertThat(assistantNode.message().status()).isEqualTo("finished_successfully");
        assertThat(assistantNode.message().endTurn()).isTrue();
        assertThat(assistantNode.message().createTime()).isNotNull().isPositive();
        assertThat(assistantNode.message().updateTime()).isNotNull().isPositive();
        // Leaf node children MUST be empty array [], never null
        assertThat(assistantNode.children()).isNotNull().isEmpty();
        assertThat(detail.currentNode().toString()).isEqualTo(assistantNodeId);
    }

    @Test
    void shouldSupportVariantRegenerationAndIsolateBranchContext() throws Exception {
        // Turn 1: Create initial conversation (Branch A response: "Branch A Answer")
        testChatModel.setStreamHandler(p -> Flux.just(TestChatModel.textChunk("Branch A Answer")));

        MvcResult turn1Async = mockMvc.perform(
                post("/api/v1/conversation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "action": "next",
                          "messages": [
                            {
                              "author": { "role": "user" },
                              "content": { "content_type": "text", "parts": ["What is 2 + 2?"] }
                            }
                          ]
                        }
                        """)
            )
            .andExpect(request().asyncStarted())
            .andReturn();
        mockMvc.perform(asyncDispatch(turn1Async)).andExpect(status().isOk());

        ConversationEntity conv = conversationRepository.findAll().getFirst();
        ConversationDetailResponse afterTurn1 = objectMapper.readValue(
            mockMvc.perform(get("/api/v1/conversation/{id}", conv.getId()))
                .andReturn().getResponse().getContentAsString(),
            ConversationDetailResponse.class
        );

        NodeMappingDto user1Node = afterTurn1.mapping().values().stream()
            .filter(n -> n.message() != null && "user".equals(n.message().author().role()))
            .findFirst()
            .orElseThrow();
        String assistant1Id = user1Node.children().getFirst();

        // Turn 2: Regenerate variant on user1Node with messages = null (Branch B response: "Branch B Answer")
        testChatModel.setStreamHandler(p -> Flux.just(TestChatModel.textChunk("Branch B Answer")));

        MvcResult variantAsync = mockMvc.perform(
                post("/api/v1/conversation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "action": "variant",
                          "conversation_id": "%s",
                          "parent_message_id": "%s"
                        }
                        """.formatted(conv.getId(), user1Node.id()))
            )
            .andExpect(request().asyncStarted())
            .andReturn();
        mockMvc.perform(asyncDispatch(variantAsync)).andExpect(status().isOk());

        ConversationDetailResponse afterVariant = objectMapper.readValue(
            mockMvc.perform(get("/api/v1/conversation/{id}", conv.getId()))
                .andReturn().getResponse().getContentAsString(),
            ConversationDetailResponse.class
        );

        NodeMappingDto updatedUser1Node = afterVariant.mapping().get(user1Node.id());
        assertThat(updatedUser1Node.children()).hasSize(2);
        assertThat(updatedUser1Node.children().get(0)).isEqualTo(assistant1Id);
        String assistant2Id = updatedUser1Node.children().get(1);
        assertThat(afterVariant.currentNode().toString()).isEqualTo(assistant2Id);

        // Turn 3: Follow-up on Branch A (parent_message_id = assistant1Id)
        testChatModel.setStreamHandler(p -> Flux.just(TestChatModel.textChunk("Follow-up on Branch A")));

        MvcResult turn3Async = mockMvc.perform(
                post("/api/v1/conversation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "action": "next",
                          "conversation_id": "%s",
                          "parent_message_id": "%s",
                          "messages": [
                            {
                              "author": { "role": "user" },
                              "content": { "content_type": "text", "parts": ["Multiply that by 3"] }
                            }
                          ]
                        }
                        """.formatted(conv.getId(), assistant1Id))
            )
            .andExpect(request().asyncStarted())
            .andReturn();
        mockMvc.perform(asyncDispatch(turn3Async)).andExpect(status().isOk());

        // Verify prompt sent to LLM in Turn 3 ONLY contains Branch A ("Branch A Answer") and NOT "Branch B Answer"
        Prompt turn3Prompt = testChatModel.getRecordedStreamPrompts().getLast();
        List<String> promptTexts = turn3Prompt.getInstructions().stream()
            .map(Message::getText)
            .toList();
        assertThat(promptTexts).containsExactly(
            "What is 2 + 2?",
            "Branch A Answer",
            "Multiply that by 3"
        );
    }

    @Test
    void shouldManageSidebarPatchAndTitleGenerationWithTimeoutFallback() throws Exception {
        // Create a conversation
        MvcResult turnAsync = mockMvc.perform(
                post("/api/v1/conversation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "action": "next",
                          "messages": [
                            {
                              "author": { "role": "user" },
                              "content": {
                                "content_type": "text",
                                "parts": ["Design a high concurrency PostgreSQL schema for stateful trees"]
                              }
                            }
                          ]
                        }
                        """)
            )
            .andExpect(request().asyncStarted())
            .andReturn();
        mockMvc.perform(asyncDispatch(turnAsync)).andExpect(status().isOk());

        ConversationEntity conv = conversationRepository.findAll().getFirst();
        List<ConversationNodeEntity> nodes = nodeRepository
            .findByConversationIdOrderByCreatedAtAscIdAsc(conv.getId());
        UUID userNodeId = nodes.stream()
            .filter(n -> "user".equals(n.getRole()))
            .findFirst()
            .orElseThrow()
            .getId();

        // 1. Sidebar list includes active conversation
        mockMvc.perform(get("/api/v1/conversations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].id").value(conv.getId().toString()))
            .andExpect(jsonPath("$.items[0].title").value("Virtual Threads Overview"));

        // 2. PATCH title and current_node
        mockMvc.perform(
                patch("/api/v1/conversation/{id}", conv.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "title": "PostgreSQL Tree Architecture",
                          "current_node": "%s"
                        }
                        """.formatted(userNodeId))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.title").value("PostgreSQL Tree Architecture"))
            .andExpect(jsonPath("$.current_node").value(userNodeId.toString()));

        // 3. PATCH with invalid current_node returns 400 Bad Request
        mockMvc.perform(
                patch("/api/v1/conversation/{id}", conv.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "current_node": "%s"
                        }
                        """.formatted(UUID.randomUUID()))
            )
            .andExpect(status().isBadRequest());

        // 4. POST /api/v1/conversation/gen_title/{id} with timeout fallback when LLM hangs > 3s
        testChatModel.setCallHandler(p -> {
            try {
                Thread.sleep(3_500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return TestChatModel.textChunk("Too Late Title");
        });

        mockMvc.perform(
                post("/api/v1/conversation/gen_title/{id}", conv.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "message_id": "%s"
                        }
                        """.formatted(userNodeId))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.conversation_id").value(conv.getId().toString()))
            .andExpect(jsonPath("$.title").value("Design a high concurrency Post..."));

        // 5. Archive conversation (is_archived: true)
        mockMvc.perform(
                patch("/api/v1/conversation/{id}", conv.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        { "is_archived": true }
                        """)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.is_archived").value(true));

        // Default sidebar query (is_archived=false) should now return 0 items, while is_archived=true returns 1
        mockMvc.perform(get("/api/v1/conversations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get("/api/v1/conversations").param("is_archived", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));

        // 6. Soft delete (is_visible: false) hides conversation from both active and archived sidebar
        mockMvc.perform(
                patch("/api/v1/conversation/{id}", conv.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        { "is_visible": false }
                        """)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.is_visible").value(false));

        mockMvc.perform(get("/api/v1/conversations").param("is_archived", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));

        // 7. Soft-deleted conversation (is_visible: false) must return 404 Not Found on GET, POST turn, gen_title, and non-restore PATCH
        mockMvc.perform(get("/api/v1/conversation/{id}", conv.getId()))
            .andExpect(status().isNotFound());

        mockMvc.perform(
                post("/api/v1/conversation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "action": "next",
                          "conversation_id": "%s",
                          "messages": [
                            {
                              "author": { "role": "user" },
                              "content": { "content_type": "text", "parts": ["Should fail on deleted"] }
                            }
                          ]
                        }
                        """.formatted(conv.getId()))
            )
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/conversation/gen_title/{id}", conv.getId()))
            .andExpect(status().isNotFound());

        mockMvc.perform(
                patch("/api/v1/conversation/{id}", conv.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        { "title": "Should not rename deleted conversation" }
                        """)
            )
            .andExpect(status().isNotFound());
    }

    @Test
    void shouldEnforceValidationErrorPathsAndNonAlignedOffsetPagination() throws Exception {
        // 1. Blank user message on action=next -> 400 Bad Request
        mockMvc.perform(
                post("/api/v1/conversation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "action": "next",
                          "messages": [
                            {
                              "author": { "role": "user" },
                              "content": { "content_type": "text", "parts": ["   "] }
                            }
                          ]
                        }
                        """)
            )
            .andExpect(status().isBadRequest());

        // 2. Unsupported action -> 400 Bad Request
        mockMvc.perform(
                post("/api/v1/conversation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "action": "unsupported_action",
                          "messages": []
                        }
                        """)
            )
            .andExpect(status().isBadRequest());

        // 3. Variant without conversation_id -> 400 Bad Request
        mockMvc.perform(
                post("/api/v1/conversation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "action": "variant"
                        }
                        """)
            )
            .andExpect(status().isBadRequest());

        // 4. Non-existent conversation_id -> 404 Not Found across endpoints
        UUID missingId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/conversation/{id}", missingId))
            .andExpect(status().isNotFound());
        mockMvc.perform(
                patch("/api/v1/conversation/{id}", missingId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        { "title": "Valid Title" }
                        """)
            )
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/conversation/gen_title/{id}", missingId))
            .andExpect(status().isNotFound());

        // 5. Create 3 conversations and test non-page-aligned offset pagination (offset=1, limit=2)
        for (int i = 1; i <= 3; i++) {
            final String title = "Chat " + i;
            testChatModel.setCallHandler(p -> TestChatModel.textChunk(title));
            MvcResult asyncRes = mockMvc.perform(
                    post("/api/v1/conversation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "action": "next",
                              "messages": [
                                {
                                  "author": { "role": "user" },
                                  "content": { "content_type": "text", "parts": ["Prompt %d"] }
                                }
                              ]
                            }
                            """.formatted(i))
                )
                .andExpect(request().asyncStarted())
                .andReturn();
            mockMvc.perform(asyncDispatch(asyncRes)).andExpect(status().isOk());
        }

        // Order is Chat 3, Chat 2, Chat 1 (updated_at DESC). With offset=1, limit=2 -> [Chat 2, Chat 1]
        mockMvc.perform(
                get("/api/v1/conversations")
                    .param("offset", "1")
                    .param("limit", "2")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.offset").value(1))
            .andExpect(jsonPath("$.limit").value(2))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].title").value("Chat 2"))
            .andExpect(jsonPath("$.items[1].title").value("Chat 1"));
    }

    @Test
    void shouldIsolateConversationsBetweenDifferentUserPrincipalsAndPreserveRootSystemHierarchy() throws Exception {
        UUID clientSuppliedMsgId = UUID.randomUUID();

        // 1. Alice creates a conversation with a specific client-supplied message ID
        MvcResult aliceTurn = mockMvc.perform(
                post("/api/v1/conversation")
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("user-alice"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "action": "next",
                          "messages": [
                            {
                              "id": "%s",
                              "author": { "role": "user" },
                              "content": { "content_type": "text", "parts": ["Alice secret prompt"] }
                            }
                          ]
                        }
                        """.formatted(clientSuppliedMsgId))
            )
            .andExpect(request().asyncStarted())
            .andReturn();
        mockMvc.perform(asyncDispatch(aliceTurn)).andExpect(status().isOk());

        ConversationEntity aliceConv = conversationRepository.findAll().getFirst();
        assertThat(aliceConv.getUserId()).isEqualTo("user-alice");

        // 2. Bob cannot view, patch, append to, or generate a title for Alice's conversation (404 Not Found)
        mockMvc.perform(
                get("/api/v1/conversation/{id}", aliceConv.getId())
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("user-bob"))
            )
            .andExpect(status().isNotFound());

        mockMvc.perform(
                patch("/api/v1/conversation/{id}", aliceConv.getId())
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("user-bob"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        { "title": "Bob Hijack" }
                        """)
            )
            .andExpect(status().isNotFound());

        mockMvc.perform(
                post("/api/v1/conversation/gen_title/{id}", aliceConv.getId())
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("user-bob"))
            )
            .andExpect(status().isNotFound());

        mockMvc.perform(
                get("/api/v1/conversations")
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("user-bob"))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));

        // 3. Bob creates a conversation reusing the exact same clientSuppliedMsgId -> must not fail with 500 PK collision
        MvcResult bobTurn = mockMvc.perform(
                post("/api/v1/conversation")
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("user-bob"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "action": "next",
                          "messages": [
                            {
                              "id": "%s",
                              "author": { "role": "user" },
                              "content": { "content_type": "text", "parts": ["Bob prompt with colliding ID"] }
                            }
                          ]
                        }
                        """.formatted(clientSuppliedMsgId))
            )
            .andExpect(request().asyncStarted())
            .andReturn();
        mockMvc.perform(asyncDispatch(bobTurn)).andExpect(status().isOk());

        // 4. Alice branches from the Hidden Root Node ID -> must attach to Hidden System Node instead of bypassing it
        ConversationDetailResponse aliceDetail = objectMapper.readValue(
            mockMvc.perform(
                    get("/api/v1/conversation/{id}", aliceConv.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("user-alice"))
                )
                .andReturn().getResponse().getContentAsString(),
            ConversationDetailResponse.class
        );
        NodeMappingDto rootNode = aliceDetail.mapping().values().stream()
            .filter(n -> n.parent() == null)
            .findFirst()
            .orElseThrow();
        String sysNodeId = rootNode.children().getFirst();

        MvcResult aliceBranchFromRoot = mockMvc.perform(
                post("/api/v1/conversation")
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("user-alice"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "action": "next",
                          "conversation_id": "%s",
                          "parent_message_id": "%s",
                          "messages": [
                            {
                              "author": { "role": "user" },
                              "content": { "content_type": "text", "parts": ["Branched first turn"] }
                            }
                          ]
                        }
                        """.formatted(aliceConv.getId(), rootNode.id()))
            )
            .andExpect(request().asyncStarted())
            .andReturn();
        mockMvc.perform(asyncDispatch(aliceBranchFromRoot)).andExpect(status().isOk());

        ConversationDetailResponse aliceAfterBranch = objectMapper.readValue(
            mockMvc.perform(
                    get("/api/v1/conversation/{id}", aliceConv.getId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("user-alice"))
                )
                .andReturn().getResponse().getContentAsString(),
            ConversationDetailResponse.class
        );
        // Root node still has only 1 child (System Node), and System Node now has 2 User Node children
        assertThat(aliceAfterBranch.mapping().get(rootNode.id()).children()).containsExactly(sysNodeId);
        assertThat(aliceAfterBranch.mapping().get(sysNodeId).children()).hasSize(2);
    }
}
