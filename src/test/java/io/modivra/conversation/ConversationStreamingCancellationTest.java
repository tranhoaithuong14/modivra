package io.modivra.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import io.modivra.TestChatModel;
import io.modivra.TestcontainersConfiguration;
import io.modivra.conversation.ConversationDtos.AuthorDto;
import io.modivra.conversation.ConversationDtos.ContentDto;
import io.modivra.conversation.ConversationDtos.ConversationTurnRequest;
import io.modivra.conversation.ConversationDtos.MessageDto;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ConversationStreamingCancellationTest {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private TestChatModel testChatModel;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationNodeRepository nodeRepository;

    @BeforeEach
    void setUp() {
        testChatModel.reset();
        conversationRepository.deleteAll();
    }

    @Test
    void shouldCancelUpstreamFluxAndPersistInterruptedNodeWhenClientDisconnectsAfterTwoTokens() {
        AtomicInteger upstreamTokensEmitted = new AtomicInteger(0);
        AtomicBoolean upstreamSubscriptionCancelled = new AtomicBoolean(false);

        List<String> allTokens = List.of("First ", "Second", "Third ", "Fourth");

        testChatModel.setStreamHandler(prompt ->
            Flux.fromIterable(allTokens)
                .delayElements(Duration.ofMillis(30))
                .map(TestChatModel::textChunk)
                .doOnNext(chunk -> upstreamTokensEmitted.incrementAndGet())
                .doOnCancel(() -> upstreamSubscriptionCancelled.set(true))
        );

        // Custom SseEmitter that simulates ClientAbortException (IOException) on the 2nd token append delta
        AtomicInteger appendDeltaCount = new AtomicInteger(0);
        SseEmitter disconnectingEmitter = new SseEmitter(180_000L) {
            @Override
            public synchronized void send(SseEventBuilder builder) throws IOException {
                Set<ResponseBodyEmitter.DataWithMediaType> parts = builder.build();
                StringBuilder combined = new StringBuilder();
                for (ResponseBodyEmitter.DataWithMediaType part : parts) {
                    combined.append(part.getData());
                }
                String payload = combined.toString();
                if (payload.contains("\"/message/content/parts/0\"")) {
                    int current = appendDeltaCount.incrementAndGet();
                    if (current == 2) {
                        throw new IOException("ClientAbortException: Broken pipe (simulated browser tab close)");
                    }
                }
            }
        };

        ConversationTurnRequest request = new ConversationTurnRequest(
            "next",
            null,
            null,
            "grok-2-latest",
            List.of(new MessageDto(
                null,
                AuthorDto.ofRole("user"),
                null,
                null,
                ContentDto.text("Stream a long response"),
                null,
                null,
                1.0,
                Map.of(),
                "all"
            ))
        );

        conversationService.streamConversationTurn(
            request,
            ConversationController.DEFAULT_USER_ID,
            disconnectingEmitter
        );

        // 1. Verify upstream Subscription.cancel() was invoked immediately and 3rd token was NEVER emitted
        assertThat(upstreamSubscriptionCancelled.get())
            .as("Upstream Reactor Flux subscription must be cancelled immediately on IOException")
            .isTrue();
        assertThat(upstreamTokensEmitted.get())
            .as("Upstream must stop after the 2nd token and never emit the 3rd token")
            .isEqualTo(2);

        // 2. Verify Assistant Node in PostgreSQL has status = 'interrupted' and preserves the 2 partial tokens
        ConversationEntity conv = conversationRepository.findAll().getFirst();
        List<ConversationNodeEntity> nodes = nodeRepository
            .findByConversationIdOrderByCreatedAtAscIdAsc(conv.getId());

        ConversationNodeEntity assistantNode = nodes.stream()
            .filter(n -> "assistant".equals(n.getRole()))
            .findFirst()
            .orElseThrow();

        assertThat(assistantNode.getStatus()).isEqualTo("interrupted");
        assertThat(assistantNode.getContent().extractText()).isEqualTo("First Second");
        assertThat(assistantNode.getMetadata().get("end_turn")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void shouldMarkAssistantNodeFailedAndPreservePartialContentWhenUpstreamThrowsMidStream() {
        testChatModel.setStreamHandler(prompt ->
            Flux.concat(
                Flux.just(TestChatModel.textChunk("Partial response before crash")),
                Flux.error(new RuntimeException("503 Service Unavailable: xAI upstream overloaded"))
            )
        );

        ConversationTurnRequest request = new ConversationTurnRequest(
            "next",
            null,
            null,
            "grok-2-latest",
            List.of(new MessageDto(
                null,
                AuthorDto.ofRole("user"),
                null,
                null,
                ContentDto.text("Trigger upstream crash"),
                null,
                null,
                1.0,
                Map.of(),
                "all"
            ))
        );

        conversationService.streamConversationTurn(
            request,
            ConversationController.DEFAULT_USER_ID,
            new SseEmitter(180_000L)
        );

        ConversationEntity conv = conversationRepository.findAll().getFirst();
        List<ConversationNodeEntity> nodes = nodeRepository
            .findByConversationIdOrderByCreatedAtAscIdAsc(conv.getId());

        ConversationNodeEntity assistantNode = nodes.stream()
            .filter(n -> "assistant".equals(n.getRole()))
            .findFirst()
            .orElseThrow();

        assertThat(assistantNode.getStatus()).isEqualTo("failed");
        assertThat(assistantNode.getContent().extractText())
            .isEqualTo("Partial response before crash");
        assertThat(assistantNode.getMetadata().get("error").toString())
            .contains("503 Service Unavailable");
    }

    @Test
    void shouldUnblockIteratorHasNextAndCancelUpstreamWhenEmitterTimesOutWhileWaitingForSlowToken() throws Exception {
        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        java.util.concurrent.CountDownLatch firstTokenSent = new java.util.concurrent.CountDownLatch(1);

        // Upstream emits 1 token and then stalls for 60 seconds without emitting a 2nd token
        testChatModel.setStreamHandler(prompt ->
            Flux.concat(
                Flux.just(TestChatModel.textChunk("First token before stall")),
                Flux.never()
            ).doOnCancel(() -> upstreamCancelled.set(true))
        );

        java.util.concurrent.atomic.AtomicReference<Runnable> registeredTimeoutCallback =
            new java.util.concurrent.atomic.AtomicReference<>();
        SseEmitter controllableEmitter = new SseEmitter(180_000L) {
            @Override
            public synchronized void onTimeout(Runnable callback) {
                super.onTimeout(callback);
                registeredTimeoutCallback.set(callback);
            }

            @Override
            public synchronized void send(SseEventBuilder builder) throws IOException {
                Set<ResponseBodyEmitter.DataWithMediaType> parts = builder.build();
                StringBuilder combined = new StringBuilder();
                for (ResponseBodyEmitter.DataWithMediaType part : parts) {
                    combined.append(part.getData());
                }
                if (combined.toString().contains("\"/message/content/parts/0\"")) {
                    firstTokenSent.countDown();
                }
            }
        };

        ConversationTurnRequest request = new ConversationTurnRequest(
            "next",
            null,
            null,
            "grok-2-latest",
            List.of(new MessageDto(
                null,
                AuthorDto.ofRole("user"),
                null,
                null,
                ContentDto.text("Test stalled upstream"),
                null,
                null,
                1.0,
                Map.of(),
                "all"
            ))
        );

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.CompletableFuture<Void> streamFuture = java.util.concurrent.CompletableFuture.runAsync(
                () -> conversationService.streamConversationTurn(
                    request,
                    ConversationController.DEFAULT_USER_ID,
                    controllableEmitter
                ),
                executor
            );

            assertThat(firstTokenSent.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // Give the virtual thread a brief moment to enter blocking iterator.hasNext() on Flux.never()
            Thread.sleep(50);

            // Trigger emitter onTimeout while the virtual thread is blocked inside iterator.hasNext()
            assertThat(registeredTimeoutCallback.get()).isNotNull();
            registeredTimeoutCallback.get().run();

            // Must unblock immediately (< 1s) instead of hanging forever on Flux.never()
            streamFuture.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }

        assertThat(upstreamCancelled.get()).isTrue();
        ConversationEntity conv = conversationRepository.findAll().getFirst();
        ConversationNodeEntity assistantNode = nodeRepository
            .findByConversationIdOrderByCreatedAtAscIdAsc(conv.getId())
            .stream()
            .filter(n -> "assistant".equals(n.getRole()))
            .findFirst()
            .orElseThrow();
        assertThat(assistantNode.getStatus()).isEqualTo("interrupted");
        assertThat(assistantNode.getContent().extractText()).isEqualTo("First token before stall");
    }
}
