package com.aurora.studio.initiative;

import com.aurora.studio.common.RelationshipType;
import com.aurora.studio.knowledge.KnowledgeConflict;
import com.aurora.studio.knowledge.KnowledgeObject;
import com.aurora.studio.knowledge.KnowledgePackage;
import com.aurora.studio.knowledge.KnowledgeRelationship;
import com.aurora.studio.knowledge.KnowledgeService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class InitiativeKnowledge {
  private final KnowledgeService knowledge;

  InitiativeKnowledge(KnowledgeService knowledge) {
    this.knowledge = knowledge;
  }

  Set<KnowledgeObject> resolveDataAssets(
      KnowledgeObject artifact, Map<UUID, KnowledgeObject> visibleById, boolean includeCandidates) {
    Set<KnowledgeObject> assets = new java.util.LinkedHashSet<>();
    java.util.ArrayDeque<UUID> pending = new java.util.ArrayDeque<>();
    Set<UUID> visited = new java.util.HashSet<>();
    pending.add(artifact.id());
    while (!pending.isEmpty()) {
      UUID current = pending.removeFirst();
      if (!visited.add(current)) continue;
      KnowledgeObject currentObject = visibleById.get(current);
      if (currentObject != null && currentObject.knowledgeType().name().equals("DATA_ASSET")) {
        assets.add(currentObject);
        continue;
      }
      KnowledgePackage pack = knowledge.get(current, includeCandidates);
      for (KnowledgeRelationship relationship : pack.relationships()) {
        if (!relationship.fromObjectId().equals(current)) continue;
        UUID relatedId = relationship.toObjectId();
        KnowledgeObject related = visibleById.get(relatedId);
        if (related == null) continue;
        if (relationship.relationshipType() == RelationshipType.DERIVED_FROM
            && related.knowledgeType().name().equals("DATA_ASSET")) {
          assets.add(related);
        } else if (relationship.relationshipType() == RelationshipType.IMPLEMENTED_BY) {
          pending.addLast(relatedId);
        }
      }
    }
    return assets;
  }

  boolean hasOpenBlockingConflict(KnowledgeObject object, boolean includeCandidates) {
    return hasOpenBlockingConflict(object.id(), includeCandidates, new java.util.HashSet<>());
  }

  private boolean hasOpenBlockingConflict(
      UUID objectId, boolean includeCandidates, Set<UUID> visited) {
    if (!visited.add(objectId)) return false;
    KnowledgePackage packageData = knowledge.get(objectId, includeCandidates);
    if (hasOpenBlockingConflict(packageData.conflicts())) return true;
    return packageData.relationships().stream()
        .filter(relationship -> relationship.relationshipType() == RelationshipType.GOVERNED_BY)
        .map(
            relationship ->
                relationship.fromObjectId().equals(objectId)
                    ? relationship.toObjectId()
                    : relationship.fromObjectId())
        .anyMatch(id -> hasOpenBlockingConflict(id, includeCandidates, visited));
  }

  private boolean hasOpenBlockingConflict(List<KnowledgeConflict> conflicts) {
    return conflicts.stream()
        .anyMatch(conflict -> conflict.status().name().equals("OPEN") && conflict.blocking());
  }

  KnowledgeObject findObservable(String observable, List<KnowledgeObject> visible) {
    String expected = observable.toLowerCase();
    return visible.stream()
        .filter(
            object ->
                object.name().equalsIgnoreCase(observable)
                    || contains(object.attributes(), expected))
        .findFirst()
        .orElse(null);
  }

  private boolean contains(Object value, String expected) {
    if (value == null) return false;
    if (value instanceof Map<?, ?> map) {
      return map.entrySet().stream()
          .anyMatch(
              entry -> contains(entry.getKey(), expected) || contains(entry.getValue(), expected));
    }
    if (value instanceof Collection<?> values) {
      return values.stream().anyMatch(item -> contains(item, expected));
    }
    String text = String.valueOf(value).toLowerCase();
    return text.equals(expected) || text.contains(expected);
  }
}
