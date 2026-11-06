package io.modivra.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "models")
public class ModelEntity {

    @Id
    @Column(name = "id", nullable = false, length = 192)
    private String id; // Canonical ID: provider_id/model_id (e.g. xai/grok-4.7)

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_id", nullable = false)
    private ModelProviderEntity provider;

    @Column(name = "model_id", nullable = false, length = 128)
    private String modelId; // Raw wire API identifier (e.g. grok-4.7, grok-2-latest)

    @Column(name = "slug", nullable = false, length = 192)
    private String slug; // Short identifier (e.g. grok-4.7)

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description = "";

    @Column(name = "context_window", nullable = false)
    private int contextWindow = 131072;

    @Column(name = "max_output_tokens", nullable = false)
    private int maxOutputTokens = 16384;

    @Column(name = "max_tokens", nullable = false)
    private int maxTokens = 131072;

    @Column(name = "category", nullable = false, length = 64)
    private String category = "general";

    @Column(name = "owned_by", nullable = false, length = 64)
    private String ownedBy = "system";

    @Column(name = "model_version", length = 64)
    private String modelVersion;

    @Column(name = "fingerprint", length = 128)
    private String fingerprint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_modalities", nullable = false, columnDefinition = "jsonb")
    private List<String> inputModalities = new ArrayList<>(List.of("text"));

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_modalities", nullable = false, columnDefinition = "jsonb")
    private List<String> outputModalities = new ArrayList<>(List.of("text"));

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "aliases", nullable = false, columnDefinition = "jsonb")
    private List<String> aliases = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", nullable = false, columnDefinition = "jsonb")
    private List<String> tags = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capabilities", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> capabilities = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "product_features", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> productFeatures = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "enabled_tools", nullable = false, columnDefinition = "jsonb")
    private List<String> enabledTools = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pricing", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> pricing = new LinkedHashMap<>();

    @Column(name = "is_enabled", nullable = false)
    private boolean isEnabled = true;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 100;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "upstream_created_at")
    private Instant upstreamCreatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ModelEntity() {
    }

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public String getProviderId() {
        return provider != null ? provider.getId() : null;
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public ModelProviderEntity getProvider() {
        return provider;
    }

    public void setProvider(ModelProviderEntity provider) {
        this.provider = provider;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(int contextWindow) {
        this.contextWindow = contextWindow;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getOwnedBy() {
        return ownedBy;
    }

    public void setOwnedBy(String ownedBy) {
        this.ownedBy = ownedBy;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public List<String> getInputModalities() {
        return inputModalities;
    }

    public void setInputModalities(List<String> inputModalities) {
        this.inputModalities = inputModalities != null ? new ArrayList<>(inputModalities) : new ArrayList<>();
    }

    public List<String> getOutputModalities() {
        return outputModalities;
    }

    public void setOutputModalities(List<String> outputModalities) {
        this.outputModalities = outputModalities != null ? new ArrayList<>(outputModalities) : new ArrayList<>();
    }

    public List<String> getAliases() {
        return aliases;
    }

    public void setAliases(List<String> aliases) {
        this.aliases = aliases != null ? new ArrayList<>(aliases) : new ArrayList<>();
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
    }

    public Map<String, Object> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(Map<String, Object> capabilities) {
        this.capabilities = capabilities != null ? new LinkedHashMap<>(capabilities) : new LinkedHashMap<>();
    }

    public Map<String, Object> getProductFeatures() {
        return productFeatures;
    }

    public void setProductFeatures(Map<String, Object> productFeatures) {
        this.productFeatures = productFeatures != null ? new LinkedHashMap<>(productFeatures) : new LinkedHashMap<>();
    }

    public List<String> getEnabledTools() {
        return enabledTools;
    }

    public void setEnabledTools(List<String> enabledTools) {
        this.enabledTools = enabledTools != null ? new ArrayList<>(enabledTools) : new ArrayList<>();
    }

    public Map<String, Object> getPricing() {
        return pricing;
    }

    public void setPricing(Map<String, Object> pricing) {
        this.pricing = pricing != null ? new LinkedHashMap<>(pricing) : new LinkedHashMap<>();
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        isDefault = aDefault;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getUpstreamCreatedAt() {
        return upstreamCreatedAt;
    }

    public void setUpstreamCreatedAt(Instant upstreamCreatedAt) {
        this.upstreamCreatedAt = upstreamCreatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
