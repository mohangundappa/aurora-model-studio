package com.aurora.studio.app;

import com.aurora.studio.common.ClientContext;
import com.aurora.studio.extraction.ExtractionService;
import com.aurora.studio.importer.AuroraBackfillImporter;
import java.nio.file.Path;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class ImporterCommand implements CommandLineRunner {
  private final AuroraBackfillImporter importer;
  private final ExtractionService extraction;

  public ImporterCommand(AuroraBackfillImporter importer, ExtractionService extraction) {
    this.importer = importer;
    this.extraction = extraction;
  }

  @Override
  public void run(String... args) throws Exception {
    String repository = null;
    boolean importRequested = false;
    boolean extractionRequested = false;
    boolean syntheticRequested = false;
    for (int index = 0; index < args.length; index++) {
      if (args[index].equals("--import")) importRequested = true;
      if (args[index].equals("--extract")) extractionRequested = true;
      if (args[index].equals("--extract-synthetic")) syntheticRequested = true;
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
  }
}
