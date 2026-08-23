package com.aurora.studio.knowledge;

import com.aurora.studio.common.RelationshipType;
import java.util.UUID;

public record KnowledgeRelationship(
    UUID id,
    UUID clientId,
    UUID fromObjectId,
    RelationshipType relationshipType,
    UUID toObjectId,
    UUID evidenceId) {}
