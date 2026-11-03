package io.modivra.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modivra.TestChatModel;
import io.modivra.TestcontainersConfiguration;
import io.modivra.conversation.ConversationDtos.AuthorDto;
import io.modivra.conversation.ConversationDtos.ContentDto;
import io.modivra.conversation.ConversationDtos.ConversationPatchRequest;
import io.modivra.conversation.ConversationDtos.ConversationTurnRequest;
import io.modivra.conversation.ConversationDtos.MessageDto;
import io.modivra.conversation.ConversationTreeService.TurnPreparationResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TreeInvariantsAndConcurrencyTest {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationTreeService treeService;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationNodeRepository nodeRepository;

    @Autowired
    private TestChatModel testChatModel;

    @BeforeEach
    void setUp() {
        testChatModel.reset();
        conversationRepository.deleteAll();
    }

    private static ConversationTurnRequest newTurnRequest(
        String action,
        UUID conversationId,
        UUID parentMessageId,
        String userText
    ) {
        List<MessageDto> messages = userText != null
            ? List.of(new MessageDto(
                null,
                AuthorDto.ofRole("user"),
                null,
                null,
                ContentDto.text(userText),
                null,
                null,
                1.0,
                Map.of(),
                "all"
            ))
            : null;
        return new ConversationTurnRequest(action, conversationId, parentMessageId, "grok-2-latest", messages);
    }

    @Test
    void shouldRejectSecondRootNodeAndEnforceCheckConstraintsAndCascadeDeletes() {
        TurnPreparationResult prep = treeService.prepareTurn(
            newTurnRequest("next", null, null, "Initial turn"),
            ConversationController.DEFAULT_USER_ID
        );
        treeService.completeAssistantTurn(
            prep.conversationId(),
            prep.assistantNodeId(),
            prep.userNodeId(),
            "Initial answer",
            Map.of()
        );

        // 1. Attempt to insert a 2nd root node (parent_id IS NULL) for the same conversation -> violates uq_conversation_nodes_root
        ConversationNodeEntity duplicateRoot = new ConversationNodeEntity();
        duplicateRoot.setId(UUID.randomUUID());
        duplicateRoot.setConversationId(prep.conversationId());
        duplicateRoot.setParent(null);
        duplicateRoot.setRole(null);
        duplicateRoot.setContent(null);
        duplicateRoot.setStatus("finished_successfully");
        duplicateRoot.setCreatedAt(Instant.now());
        duplicateRoot.setUpdatedAt(Instant.now());

        assertThatThrownBy(() -> nodeRepository.saveAndFlush(duplicateRoot))
            .isInstanceOf(DataIntegrityViolationException.class);

        // 2. Attempt to insert a non-root node with role = null -> violates chk_conversation_nodes_root_invariants
        ConversationNodeEntity userNode = nodeRepository.findById(prep.userNodeId()).orElseThrow();
        ConversationNodeEntity invalidNonRoot = new ConversationNodeEntity();
        invalidNonRoot.setId(UUID.randomUUID());
        invalidNonRoot.setConversationId(prep.conversationId());
        invalidNonRoot.setParent(userNode);
        invalidNonRoot.setRole(null);
        invalidNonRoot.setContent(ContentDto.text("Invalid node without role"));
        invalidNonRoot.setStatus("finished_successfully");
        invalidNonRoot.setCreatedAt(Instant.now());
        invalidNonRoot.setUpdatedAt(Instant.now());

        assertThatThrownBy(() -> nodeRepository.saveAndFlush(invalidNonRoot))
            .isInstanceOf(DataIntegrityViolationException.class);

        // 3. Verify ON DELETE CASCADE: deleting userNode cascades to assistantNode instead of creating orphan root
        nodeRepository.deleteById(prep.userNodeId());
        nodeRepository.flush();

        assertThat(nodeRepository.findById(prep.assistantNodeId())).isEmpty();
    }

    @Test
    void shouldRejectConcurrentTurnWith409ConflictWhileTurnIsInProgress() {
        // Turn 1 completes normally to create the conversation
        TurnPreparationResult turn1 = treeService.prepareTurn(
            newTurnRequest("next", null, null, "Turn 1"),
            ConversationController.DEFAULT_USER_ID
        );
        treeService.completeAssistantTurn(
            turn1.conversationId(),
            turn1.assistantNodeId(),
            turn1.userNodeId(),
            "Turn 1 Answer",
            Map.of()
        );

        // Turn 2 starts and leaves an 'in_progress' assistant node during streaming
        TurnPreparationResult turn2 = treeService.prepareTurn(
            newTurnRequest("next", turn1.conversationId(), turn1.assistantNodeId(), "Turn 2"),
            ConversationController.DEFAULT_USER_ID
        );
        assertThat(turn2.assistantNodeId()).isNotNull();

        // Turn 3 arrives concurrently while Turn 2 is still 'in_progress' -> must throw 409 Conflict
        assertThatThrownBy(() ->
            treeService.prepareTurn(
                newTurnRequest("next", turn1.conversationId(), turn1.assistantNodeId(), "Concurrent Turn 3"),
                ConversationController.DEFAULT_USER_ID
            )
        )
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                ResponseStatusException rse = (ResponseStatusException) ex;
                assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            });
    }

    @Test
    void shouldPreserveGeneratedBranchNodeAndRespectConcurrentBranchSwitchInPhase3() throws Exception {
        // 1. Setup a conversation with Turn 1 (Branch B anchor)
        TurnPreparationResult turn1 = treeService.prepareTurn(
            newTurnRequest("next", null, null, "Hello on Branch B"),
            ConversationController.DEFAULT_USER_ID
        );
        treeService.completeAssistantTurn(
            turn1.conversationId(),
            turn1.assistantNodeId(),
            turn1.userNodeId(),
            "Response on Branch B",
            Map.of()
        );
        UUID branchBNodeId = turn1.assistantNodeId();

        // 2. Start streaming a new turn (Branch A) that pauses mid-stream in Phase 2
        CountDownLatch streamStartedLatch = new CountDownLatch(1);
        CountDownLatch allowStreamFinishLatch = new CountDownLatch(1);

        testChatModel.setStreamHandler(prompt ->
            Flux.create(sink -> {
                sink.next(TestChatModel.textChunk("Branch A part 1 "));
                streamStartedLatch.countDown();
                try {
                    allowStreamFinishLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                sink.next(TestChatModel.textChunk("Branch A part 2"));
                sink.complete();
            })
        );

        TurnPreparationResult branchAPrep = treeService.prepareTurn(
            newTurnRequest("next", turn1.conversationId(), branchBNodeId, "Continue on Branch A"),
            ConversationController.DEFAULT_USER_ID
        );

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> streamFuture = CompletableFuture.runAsync(
                () -> conversationService.streamPreparedTurn(branchAPrep, new SseEmitter(180_000L)),
                executor
            );

            assertThat(streamStartedLatch.await(5, TimeUnit.SECONDS)).isTrue();

            // 3. While Branch A is mid-stream in Phase 2, user switches current_node back to branchBNodeId via PATCH
            treeService.patchConversation(
                turn1.conversationId(),
                ConversationController.DEFAULT_USER_ID,
                new ConversationPatchRequest("Switched Branch During Stream", null, null, branchBNodeId)
            );

            // 4. Release Phase 2 stream so Phase 3 executes completeAssistantTurn
            allowStreamFinishLatch.countDown();
            streamFuture.get(5, TimeUnit.SECONDS);
        }

        // 5. Verify Branch A assistant node was persisted with full text and finished_successfully (no rollback!)
        ConversationNodeEntity branchAAssistant = nodeRepository.findById(branchAPrep.assistantNodeId())
            .orElseThrow();
        assertThat(branchAAssistant.getStatus()).isEqualTo("finished_successfully");
        assertThat(branchAAssistant.getContent().extractText())
            .isEqualTo("Branch A part 1 Branch A part 2");

        // 6. Verify conversation's current_node remained branchBNodeId (not overwritten by Phase 3!)
        ConversationEntity updatedConv = conversationRepository.findById(turn1.conversationId())
            .orElseThrow();
        assertThat(updatedConv.getCurrentNode()).isEqualTo(branchBNodeId);
        assertThat(updatedConv.getTitle()).isEqualTo("Switched Branch During Stream");
    }

    @Test
    void shouldAutoRecoverStaleInProgressTurnPastTimeoutAndSkipEmptyAssistantMessages() {
        // 1. Start Turn 1 and leave its assistant node in_progress
        TurnPreparationResult staleTurn = treeService.prepareTurn(
            newTurnRequest("next", null, null, "First prompt before crash"),
            ConversationController.DEFAULT_USER_ID
        );

        // Simulate server crash leaving the assistant node stuck in 'in_progress' for > 180 seconds
        ConversationNodeEntity stuckAssistant = nodeRepository.findById(staleTurn.assistantNodeId())
            .orElseThrow();
        Instant fourMinutesAgo = Instant.now().minus(ConversationTreeService.STALE_TURN_TIMEOUT.plusSeconds(60));
        stuckAssistant.setCreatedAt(fourMinutesAgo);
        stuckAssistant.setUpdatedAt(fourMinutesAgo);
        nodeRepository.saveAndFlush(stuckAssistant);

        // 2. Subsequent turn on the same conversation must recover the stale node to 'interrupted' and succeed
        TurnPreparationResult recoveredTurn = treeService.prepareTurn(
            newTurnRequest("next", staleTurn.conversationId(), staleTurn.assistantNodeId(), "Follow-up after crash recovery"),
            ConversationController.DEFAULT_USER_ID
        );

        ConversationNodeEntity recoveredStaleNode = nodeRepository.findById(staleTurn.assistantNodeId())
            .orElseThrow();
        assertThat(recoveredStaleNode.getStatus()).isEqualTo("interrupted");
        assertThat(recoveredStaleNode.getMetadata().get("end_turn")).isEqualTo(Boolean.FALSE);

        // 3. Verify empty interrupted assistant message ("") was filtered out of promptMessages
        assertThat(recoveredTurn.promptMessages())
            .extracting(org.springframework.ai.chat.messages.Message::getText)
            .containsExactly("First prompt before crash", "Follow-up after crash recovery");
    }

    @Test
    void shouldPreserveManualUserTitleRenameAndUserNodeBranchSwitchDuringFirstTurnStreaming() throws Exception {
        CountDownLatch streamStartedLatch = new CountDownLatch(1);
        CountDownLatch allowStreamFinishLatch = new CountDownLatch(1);

        testChatModel.setStreamHandler(prompt ->
            Flux.create(sink -> {
                sink.next(TestChatModel.textChunk("First turn answer"));
                streamStartedLatch.countDown();
                try {
                    allowStreamFinishLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                sink.complete();
            })
        );
        testChatModel.setCallHandler(prompt -> TestChatModel.textChunk("Auto Generated Title"));

        TurnPreparationResult firstTurnPrep = treeService.prepareTurn(
            newTurnRequest("next", null, null, "Initial user prompt"),
            ConversationController.DEFAULT_USER_ID
        );

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> streamFuture = CompletableFuture.runAsync(
                () -> conversationService.streamPreparedTurn(firstTurnPrep, new SseEmitter(180_000L)),
                executor
            );

            assertThat(streamStartedLatch.await(5, TimeUnit.SECONDS)).isTrue();

            // While Turn 1 is streaming, user manually renames conversation AND switches current_node to userNodeId
            treeService.patchConversation(
                firstTurnPrep.conversationId(),
                ConversationController.DEFAULT_USER_ID,
                new ConversationPatchRequest("Manual Custom Title", null, null, firstTurnPrep.userNodeId())
            );

            allowStreamFinishLatch.countDown();
            streamFuture.get(5, TimeUnit.SECONDS);
        }

        ConversationEntity finalConv = conversationRepository.findById(firstTurnPrep.conversationId())
            .orElseThrow();
        // Neither the user's manual title nor the user's current_node switch to userNodeId should be overwritten!
        assertThat(finalConv.getTitle()).isEqualTo("Manual Custom Title");
        assertThat(finalConv.getCurrentNode()).isEqualTo(firstTurnPrep.userNodeId());
    }
}
