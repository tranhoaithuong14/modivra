package io.modivra.conversation;

import io.modivra.conversation.ConversationDtos.ConversationTurnRequest;
import io.modivra.conversation.ConversationDtos.GenerateTitleRequest;
import io.modivra.conversation.ConversationDtos.GenerateTitleResponse;
import io.modivra.conversation.ConversationDtos.SseDeltaPatch;
import io.modivra.conversation.ConversationDtos.SseErrorEvent;
import io.modivra.conversation.ConversationDtos.SseInitialStateValue;
import io.modivra.conversation.ConversationDtos.SseStreamCompleteEvent;
import io.modivra.conversation.ConversationDtos.SseTitleGenerationEvent;
import io.modivra.conversation.ConversationTreeService.TurnPreparationResult;
import io.modivra.model.routing.MultiProviderChatClientRegistry;
import io.modivra.model.routing.ResolvedChatModel;
import io.modivra.model.service.ModelRegistryService;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;

/**
 * Coordinates the 3-phase conversation streaming lifecycle on Java 25 Virtual Threads.
 * Intentionally NOT annotated with @Transactional so zero JDBC connections are held during SSE streaming.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final long TITLE_GENERATION_TIMEOUT_MS = 3_000L;

    private final ConversationTreeService treeService;
    private final MultiProviderChatClientRegistry chatClientRegistry;
    private final ModelRegistryService modelRegistryService;
    private final ChatClient defaultChatClient;
    private final ObjectMapper jsonMapper;
    private final String defaultModelSlug;

    public ConversationService(
        ConversationTreeService treeService,
        MultiProviderChatClientRegistry chatClientRegistry,
        ModelRegistryService modelRegistryService,
        ChatClient defaultChatClient,
        ObjectMapper jsonMapper,
        @Value("${spring.ai.openai.chat.options.model:grok-4.7}") String defaultModelSlug
    ) {
        this.treeService = treeService;
        this.chatClientRegistry = chatClientRegistry;
        this.modelRegistryService = modelRegistryService;
        this.defaultChatClient = defaultChatClient;
        this.jsonMapper = jsonMapper;
        this.defaultModelSlug = defaultModelSlug;
    }

    /**
     * Full 3-phase execution on the calling (Virtual) thread.
     */
    public void streamConversationTurn(
        ConversationTurnRequest request,
        String userId,
        SseEmitter emitter
    ) {
        TurnPreparationResult prep = treeService.prepareTurn(request, userId);
        streamPreparedTurn(prep, emitter);
    }

    /**
     * Executes Phase 2 (non-transactional SSE streaming) and Phase 3 (short DB finalization)
     * for a turn already prepared in Phase 1.
     */
    public void streamPreparedTurn(TurnPreparationResult prep, SseEmitter emitter) {
        AtomicBoolean aborted = new AtomicBoolean(false);
        Sinks.Empty<Void> abortSink = Sinks.empty();
        AtomicReference<Iterator<ChatResponse>> iteratorRef = new AtomicReference<>();
        emitter.onTimeout(() -> {
            aborted.set(true);
            abortSink.tryEmitEmpty();
            cancelIteratorUpstream(iteratorRef.get());
        });
        emitter.onError(ex -> {
            aborted.set(true);
            abortSink.tryEmitEmpty();
            cancelIteratorUpstream(iteratorRef.get());
        });

        StringBuilder fullContent = new StringBuilder();
        Map<String, Object> usageMetadata = new LinkedHashMap<>();

        // 1. Emit initial SSE Delta Encoding v1 frames
        try {
            emitter.send(SseEmitter.event().name("delta_encoding").data("\"v1\""));

            SseInitialStateValue initialValue = new SseInitialStateValue(
                prep.conversationId().toString(),
                prep.initialAssistantMessage()
            );
            SseDeltaPatch initialPatch = new SseDeltaPatch("", "add", initialValue);
            emitter.send(
                SseEmitter.event()
                    .name("delta")
                    .data(jsonMapper.writeValueAsString(initialPatch))
            );
        } catch (IOException | IllegalStateException initSendEx) {
            aborted.set(true);
            abortSink.tryEmitEmpty();
            treeService.interruptAssistantTurn(prep.conversationId(), prep.assistantNodeId(), "");
            completeEmitterQuietly(emitter);
            return;
        }

        // 2. Phase 2: Stream tokens from LLM without holding any DB connection
        Iterator<ChatResponse> iterator = null;
        try {
            ChatClient client = (prep.resolvedModel() != null && prep.resolvedModel().providerEntity() != null)
                ? chatClientRegistry.getChatClient(prep.resolvedModel().providerEntity())
                : defaultChatClient;

            String upstreamModel = (prep.resolvedModel() != null && prep.resolvedModel().upstreamModelId() != null)
                ? prep.resolvedModel().upstreamModelId()
                : prep.modelSlug();

            Flux<ChatResponse> stream = client.prompt()
                .messages(prep.promptMessages())
                .options(ChatOptions.builder().model(upstreamModel))
                .stream()
                .chatResponse()
                .takeWhile(chunk -> !aborted.get())
                .takeUntilOther(abortSink.asMono());

            iterator = stream.toIterable(1).iterator();
            iteratorRef.set(iterator);
            if (aborted.get()) {
                abortSink.tryEmitEmpty();
                cancelIteratorUpstream(iterator);
            }

            while (!aborted.get() && iterator.hasNext()) {
                ChatResponse chatResponse = iterator.next();
                if (chatResponse == null) {
                    continue;
                }
                extractUsageIfPresent(chatResponse, usageMetadata);

                Generation generation = chatResponse.getResult();
                String chunkText = (generation != null && generation.getOutput() != null)
                    ? generation.getOutput().getText()
                    : null;

                if (chunkText != null && !chunkText.isEmpty()) {
                    fullContent.append(chunkText);
                    SseDeltaPatch appendPatch = new SseDeltaPatch(
                        "/message/content/parts/0",
                        "append",
                        chunkText
                    );
                    try {
                        emitter.send(
                            SseEmitter.event()
                                .name("delta")
                                .data(jsonMapper.writeValueAsString(appendPatch))
                        );
                    } catch (IOException | IllegalStateException ioEx) {
                        aborted.set(true);
                        abortSink.tryEmitEmpty();
                        cancelIteratorUpstream(iterator);
                        break;
                    }
                }
            }
        } catch (Exception streamEx) {
            abortSink.tryEmitEmpty();
            cancelIteratorUpstream(iterator);
            if (aborted.get() || isClientDisconnectException(streamEx)) {
                aborted.set(true);
            } else {
                log.warn("Upstream LLM streaming failed for conversation {}: {}",
                    prep.conversationId(), streamEx.getMessage());
                treeService.failAssistantTurn(
                    prep.conversationId(),
                    prep.assistantNodeId(),
                    fullContent.toString(),
                    streamEx.getMessage()
                );
                sendErrorFrameAndComplete(emitter, streamEx);
                return;
            }
        }

        // 3. Phase 3: Finalize Turn in Short DB Transaction
        if (aborted.get()) {
            treeService.interruptAssistantTurn(
                prep.conversationId(),
                prep.assistantNodeId(),
                fullContent.toString()
            );
            completeEmitterQuietly(emitter);
            return;
        }

        // Persist completed assistant turn before emitting final completion frames
        treeService.completeAssistantTurn(
            prep.conversationId(),
            prep.assistantNodeId(),
            prep.userNodeId(),
            fullContent.toString(),
            usageMetadata
        );

        try {
            // Terminal status & end_turn patches
            SseDeltaPatch statusPatch = new SseDeltaPatch(
                "/message/status",
                "replace",
                "finished_successfully"
            );
            emitter.send(
                SseEmitter.event()
                    .name("delta")
                    .data(jsonMapper.writeValueAsString(statusPatch))
            );

            SseDeltaPatch endTurnPatch = new SseDeltaPatch(
                "/message/end_turn",
                "replace",
                true
            );
            emitter.send(
                SseEmitter.event()
                    .name("delta")
                    .data(jsonMapper.writeValueAsString(endTurnPatch))
            );

            SseDeltaPatch finishDetailsPatch = new SseDeltaPatch(
                "/message/metadata/finish_details",
                "add",
                Map.of("type", "stop")
            );
            emitter.send(
                SseEmitter.event()
                    .name("delta")
                    .data(jsonMapper.writeValueAsString(finishDetailsPatch))
            );

            // Phase 4: Safe Title Generation on First Turn
            if (prep.firstTurn()) {
                String generatedTitle = generateTitleSafely(prep.firstUserPromptText(), prep.resolvedModel());
                String effectiveTitle = treeService.updateTitleIfDefault(
                    prep.conversationId(),
                    generatedTitle
                );

                SseTitleGenerationEvent titleEvent = SseTitleGenerationEvent.of(
                    effectiveTitle,
                    prep.conversationId()
                );
                emitter.send(
                    SseEmitter.event()
                        .data(jsonMapper.writeValueAsString(titleEvent))
                );
            }

            SseStreamCompleteEvent completeEvent = SseStreamCompleteEvent.of(prep.conversationId());
            emitter.send(
                SseEmitter.event()
                    .data(jsonMapper.writeValueAsString(completeEvent))
            );

            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (IOException | IllegalStateException postSendEx) {
            log.debug("Client disconnected during terminal SSE frames for conversation {}",
                prep.conversationId());
            completeEmitterQuietly(emitter);
        }
    }

    public GenerateTitleResponse generateTitleForConversation(
        UUID conversationId,
        String userId,
        GenerateTitleRequest request
    ) {
        UUID messageId = request != null ? request.messageId() : null;
        String promptText = treeService.resolvePromptForTitleGeneration(conversationId, userId, messageId);
        String convModel = treeService.resolveConversationModelSlug(conversationId, userId);
        ResolvedChatModel resolvedModel = null;
        try {
            resolvedModel = modelRegistryService.resolveAndValidateChatModel(convModel);
        } catch (Exception ex) {
            log.debug("Model resolution for title generation failed, using fallback: {}", ex.getMessage());
        }
        String title = generateTitleSafely(promptText, resolvedModel);
        treeService.updateTitle(conversationId, title);
        return new GenerateTitleResponse(title, conversationId);
    }

    public String generateTitleSafely(String userPrompt, ResolvedChatModel resolvedModel) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return "New chat";
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                ChatClient client = (resolvedModel != null && resolvedModel.providerEntity() != null)
                    ? chatClientRegistry.getChatClient(resolvedModel.providerEntity())
                    : defaultChatClient;

                String modelOption = (resolvedModel != null && resolvedModel.upstreamModelId() != null)
                    ? resolvedModel.upstreamModelId()
                    : defaultModelSlug;

                String raw = client.prompt()
                    .system("Generate a concise 3 to 5 word title for this conversation. Return only the title text without quotes.")
                    .user(userPrompt)
                    .options(ChatOptions.builder().model(modelOption))
                    .call()
                    .content();
                return sanitizeGeneratedTitle(raw, userPrompt);
            }, executor);

            return future.get(TITLE_GENERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            log.debug("Title generation timed out or failed, using deterministic fallback: {}", ex.getMessage());
            return fallbackTitle(userPrompt);
        }
    }

    public String generateTitleSafely(String userPrompt, String modelSlug) {
        ResolvedChatModel resolved = null;
        if (modelSlug != null && !modelSlug.isBlank()) {
            try {
                resolved = modelRegistryService.resolveAndValidateChatModel(modelSlug);
            } catch (Exception ignored) {
            }
        }
        return generateTitleSafely(userPrompt, resolved);
    }

    static String sanitizeGeneratedTitle(String rawTitle, String fallbackPrompt) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return fallbackTitle(fallbackPrompt);
        }
        String cleaned = rawTitle.replaceAll("[\\r\\n\"'`]+", " ").trim();
        if (cleaned.isBlank()) {
            return fallbackTitle(fallbackPrompt);
        }
        return cleaned.length() > 80 ? cleaned.substring(0, 80).trim() : cleaned;
    }

    static String fallbackTitle(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "New chat";
        }
        String clean = prompt.replaceAll("[\\r\\n]+", " ").trim();
        return clean.length() > 30 ? clean.substring(0, 30) + "..." : clean;
    }

    private static void cancelIteratorUpstream(Iterator<?> iterator) {
        if (iterator == null) {
            return;
        }
        if (iterator instanceof Disposable disposable) {
            disposable.dispose();
        } else if (iterator instanceof Runnable runnable) {
            runnable.run();
        } else if (iterator instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void extractUsageIfPresent(ChatResponse response, Map<String, Object> target) {
        ChatResponseMetadata metadata = response.getMetadata();
        if (metadata == null || metadata.getUsage() == null) {
            return;
        }
        Usage usage = metadata.getUsage();
        if (usage.getPromptTokens() != null && usage.getPromptTokens() > 0) {
            target.put("prompt_tokens", usage.getPromptTokens());
        }
        if (usage.getCompletionTokens() != null && usage.getCompletionTokens() > 0) {
            target.put("completion_tokens", usage.getCompletionTokens());
        }
        if (usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
            target.put("total_tokens", usage.getTotalTokens());
        }
    }

    private static boolean isClientDisconnectException(Throwable ex) {
        Throwable cursor = ex;
        while (cursor != null) {
            if (cursor instanceof IOException) {
                return true;
            }
            String name = cursor.getClass().getSimpleName();
            if (name.contains("ClientAbort") || name.contains("AsyncRequestNotUsable")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static void completeEmitterQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }

    private void sendErrorFrameAndComplete(SseEmitter emitter, Exception ex) {
        try {
            SseErrorEvent errorEvent = SseErrorEvent.of(
                "Upstream model generation failed. Partial response preserved."
            );
            emitter.send(SseEmitter.event().data(jsonMapper.writeValueAsString(errorEvent)));
        } catch (Exception ignored) {
        }
        completeEmitterQuietly(emitter);
    }
}
