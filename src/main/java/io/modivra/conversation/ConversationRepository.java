package io.modivra.conversation;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<ConversationEntity, UUID> {

    Optional<ConversationEntity> findByIdAndUserId(UUID id, String userId);

    Optional<ConversationEntity> findByIdAndUserIdAndVisibleTrue(UUID id, String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ConversationEntity c WHERE c.id = :id AND c.userId = :userId AND c.visible = true")
    Optional<ConversationEntity> findByIdForUpdate(@Param("id") UUID id, @Param("userId") String userId);

    Page<ConversationEntity> findByUserIdAndVisibleTrueAndArchivedOrderByUpdatedAtDesc(
        String userId,
        boolean archived,
        Pageable pageable
    );

    long countByUserIdAndVisibleTrueAndArchived(String userId, boolean archived);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ConversationEntity c
        SET c.currentNode = :assistantNodeId,
            c.updatedAt = :updatedAt,
            c.version = c.version + 1
        WHERE c.id = :conversationId
          AND c.currentNode = :assistantNodeId
        """)
    int updateCurrentNodeIfOnActiveBranch(
        @Param("conversationId") UUID conversationId,
        @Param("assistantNodeId") UUID assistantNodeId,
        @Param("updatedAt") Instant updatedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ConversationEntity c
        SET c.title = :title,
            c.updatedAt = :updatedAt,
            c.version = c.version + 1
        WHERE c.id = :conversationId
        """)
    int updateConversationTitle(
        @Param("conversationId") UUID conversationId,
        @Param("title") String title,
        @Param("updatedAt") Instant updatedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ConversationEntity c
        SET c.title = :title,
            c.updatedAt = :updatedAt,
            c.version = c.version + 1
        WHERE c.id = :conversationId
          AND c.title = 'New chat'
        """)
    int updateConversationTitleIfDefault(
        @Param("conversationId") UUID conversationId,
        @Param("title") String title,
        @Param("updatedAt") Instant updatedAt
    );
}
