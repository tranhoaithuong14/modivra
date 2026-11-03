package io.modivra.conversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationNodeRepository extends JpaRepository<ConversationNodeEntity, UUID> {

    List<ConversationNodeEntity> findByConversationIdOrderByCreatedAtAscIdAsc(UUID conversationId);

    Optional<ConversationNodeEntity> findByIdAndConversationId(UUID id, UUID conversationId);

    List<ConversationNodeEntity> findByConversationIdAndStatus(UUID conversationId, String status);

    boolean existsByConversationIdAndStatus(UUID conversationId, String status);

    boolean existsByIdAndConversationId(UUID id, UUID conversationId);
}
