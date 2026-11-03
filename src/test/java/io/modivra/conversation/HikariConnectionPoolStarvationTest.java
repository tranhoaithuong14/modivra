package io.modivra.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.modivra.TestChatModel;
import io.modivra.TestcontainersConfiguration;
import io.modivra.conversation.ConversationDtos.AuthorDto;
import io.modivra.conversation.ConversationDtos.ContentDto;
import io.modivra.conversation.ConversationDtos.ConversationTurnRequest;
import io.modivra.conversation.ConversationDtos.MessageDto;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
    "spring.datasource.hikari.maximum-pool-size=2",
    "spring.datasource.hikari.connection-timeout=1000"
})
@AutoConfigureMockMvc
class HikariConnectionPoolStarvationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private TestChatModel testChatModel;

    @BeforeEach
    void setUp() {
        testChatModel.reset();
        conversationRepository.deleteAll();
    }

    @Test
    void shouldNotHoldJdbcConnectionsDuringPhase2SseStreamingWithPoolSizeTwo() throws Exception {
        CountDownLatch bothStreamsInPhase2 = new CountDownLatch(2);
        CountDownLatch releaseStreams = new CountDownLatch(1);

        testChatModel.setStreamHandler(prompt ->
            Flux.create(sink -> {
                sink.next(TestChatModel.textChunk("Streaming token 1..."));
                bothStreamsInPhase2.countDown();
                try {
                    releaseStreams.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                sink.next(TestChatModel.textChunk(" done!"));
                sink.complete();
            })
        );

        ConversationTurnRequest req1 = new ConversationTurnRequest(
            "next",
            null,
            null,
            "grok-2-latest",
            List.of(new MessageDto(
                null,
                AuthorDto.ofRole("user"),
                null,
                null,
                ContentDto.text("Long stream 1"),
                null,
                null,
                1.0,
                Map.of(),
                "all"
            ))
        );

        ConversationTurnRequest req2 = new ConversationTurnRequest(
            "next",
            null,
            null,
            "grok-2-latest",
            List.of(new MessageDto(
                null,
                AuthorDto.ofRole("user"),
                null,
                null,
                ContentDto.text("Long stream 2"),
                null,
                null,
                1.0,
                Map.of(),
                "all"
            ))
        );

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> stream1 = CompletableFuture.runAsync(
                () -> conversationService.streamConversationTurn(
                    req1,
                    ConversationController.DEFAULT_USER_ID,
                    new SseEmitter(180_000L)
                ),
                executor
            );
            CompletableFuture<Void> stream2 = CompletableFuture.runAsync(
                () -> conversationService.streamConversationTurn(
                    req2,
                    ConversationController.DEFAULT_USER_ID,
                    new SseEmitter(180_000L)
                ),
                executor
            );

            // Wait until both turns have completed Phase 1 (DB prep) and are actively in Phase 2 (LLM streaming)
            assertThat(bothStreamsInPhase2.await(5, TimeUnit.SECONDS))
                .as("Both streams must enter Phase 2 simultaneously")
                .isTrue();

            // With maximum-pool-size=2, if Phase 2 held JDBC connections, the pool would have 0 available
            // connections and GET /api/v1/conversations would block and fail with ConnectionTimeoutException.
            long startNanos = System.nanoTime();
            mockMvc.perform(get("/api/v1/conversations"))
                .andExpect(status().isOk());
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertThat(elapsedMs)
                .as("GET /api/v1/conversations must execute immediately without waiting on streaming threads")
                .isLessThan(200L);

            releaseStreams.countDown();
            stream1.get(5, TimeUnit.SECONDS);
            stream2.get(5, TimeUnit.SECONDS);
        }
    }
}
