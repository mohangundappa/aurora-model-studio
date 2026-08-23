package com.aurora.studio.app;

import com.aurora.studio.common.ClientContext;
import com.aurora.studio.discovery.DiscoveryService;
import com.aurora.studio.extraction.ExtractionService;
import com.aurora.studio.importer.AuroraBackfillImporter;
import com.aurora.studio.initiative.InitiativeService;
import com.aurora.studio.knowledge.KnowledgeObject;
import com.aurora.studio.knowledge.KnowledgeService;
import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class ImporterCommand implements CommandLineRunner {
  private final AuroraBackfillImporter importer;
  private final ExtractionService extraction;
  private final KnowledgeService knowledge;
  private final DiscoveryService discovery;
  private final InitiativeService initiatives;

  public ImporterCommand(
      AuroraBackfillImporter importer,
      ExtractionService extraction,
      KnowledgeService knowledge,
      DiscoveryService discovery,
      InitiativeService initiatives) {
    this.importer = importer;
    this.extraction = extraction;
    this.knowledge = knowledge;
    this.discovery = discovery;
    this.initiatives = initiatives;
  }

  @Override
  public void run(String... args) throws Exception {
    String repository = null;
    boolean importRequested = false;
    boolean extractionRequested = false;
    boolean syntheticRequested = false;
    boolean backfillEmbeddingsRequested = false;
    boolean seedInitiativesRequested = false;
    String approvalList = null;
    for (int index = 0; index < args.length; index++) {
      if (args[index].equals("--import")) importRequested = true;
      if (args[index].equals("--extract")) extractionRequested = true;
      if (args[index].equals("--extract-synthetic")) syntheticRequested = true;
      if (args[index].equals("--backfill-embeddings")) backfillEmbeddingsRequested = true;
      if (args[index].equals("--seed-initiatives")) seedInitiativesRequested = true;
      if (args[index].equals("--approve-curated") && index + 1 < args.length)
        approvalList = args[++index];
      if (args[index].equals("--aurora-repo") && index + 1 < args.length)
        repository = args[++index];
    }
    if (importRequested || repository != null) {
      if (repository == null) repository = "/home/ubuntu/repos/aurora-intelligence";
      var result = importer.importRepository(Path.of(repository));
      System.out.println("Imported commit " + result.commit() + ": " + result.counts());
    }
    if (extractionRequested || syntheticRequested) {
      ClientContext.set(AuroraBackfillImporter.IMPORT_CLIENT);
      try {
        if (extractionRequested) {
          if (repository == null) repository = "/home/ubuntu/repos/aurora-intelligence";
          var result = extraction.extract(Path.of(repository), false);
          System.out.println(
              "Extracted Aurora estate: synthetic=false candidates="
                  + result.candidateIds().size()
                  + " skipped="
                  + result.skippedArtifacts()
                  + " unchanged="
                  + result.unchangedArtifacts()
                  + " counts="
                  + result.counts());
        }
        if (syntheticRequested) {
          var result = extraction.extractSyntheticEstate();
          System.out.println(
              "Extracted synthetic estate: synthetic=true candidates="
                  + result.candidateIds().size()
                  + " skipped="
                  + result.skippedArtifacts()
                  + " unchanged="
                  + result.unchangedArtifacts()
                  + " counts="
                  + result.counts());
        }
      } finally {
        ClientContext.clear();
      }
    }
    if (approvalList != null) {
      if (approvalList.isBlank())
        throw new IllegalArgumentException("--approve-curated requires keys");
      ClientContext.set(AuroraBackfillImporter.IMPORT_CLIENT);
      try {
        for (String key : Arrays.stream(approvalList.split(",")).map(String::trim).toList()) {
          KnowledgeObject object =
              knowledge.search(null, null, null, "EXTRACTED", null, null, true).stream()
                  .filter(candidate -> candidate.knowledgeKey().equals(key))
                  .findFirst()
                  .orElseThrow(() -> new IllegalArgumentException("Unknown curated key: " + key));
          knowledge.submitForReview(
              object.id(), "local-curated-approval-unverified", "Declared curated pass");
          knowledge.approve(
              object.id(), "local-curated-approval-unverified", "Declared curated pass");
          System.out.println("Approved curated knowledge: " + key);
        }
      } finally {
        ClientContext.clear();
      }
    }
    if (backfillEmbeddingsRequested) {
      ClientContext.set(AuroraBackfillImporter.IMPORT_CLIENT);
      try {
        System.out.println(
            "Backfilled embeddings: " + discovery.backfillEmbeddings(true) + " objects");
      } finally {
        ClientContext.clear();
      }
    }
    if (seedInitiativesRequested) {
      ClientContext.set(AuroraBackfillImporter.IMPORT_CLIENT);
      try {
        initiatives
            .seedDemo()
            .forEach(initiative -> System.out.println("Seeded initiative: " + initiative.id()));
      } finally {
        ClientContext.clear();
      }
    }
    if (!importRequested
        && !extractionRequested
        && !syntheticRequested
        && !backfillEmbeddingsRequested
        && approvalList == null
        && !seedInitiativesRequested) {
      ClientContext.set(AuroraBackfillImporter.IMPORT_CLIENT);
      try {
        initiatives
            .seedDemo()
            .forEach(initiative -> System.out.println("Seeded initiative: " + initiative.id()));
      } finally {
        ClientContext.clear();
      }
    }
  }
}
