package com.aurora.studio.app;

import com.aurora.studio.importer.AuroraBackfillImporter;
import java.nio.file.Path;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class ImporterCommand implements CommandLineRunner {
  private final AuroraBackfillImporter importer;

  public ImporterCommand(AuroraBackfillImporter importer) {
    this.importer = importer;
  }

  @Override
  public void run(String... args) throws Exception {
    String repository = null;
    boolean importRequested = false;
    for (int index = 0; index < args.length; index++) {
      if (args[index].equals("--import")) importRequested = true;
      if (args[index].equals("--aurora-repo") && index + 1 < args.length)
        repository = args[++index];
    }
    if (importRequested || repository != null) {
      if (repository == null) repository = "/home/ubuntu/repos/aurora-intelligence";
      var result = importer.importRepository(Path.of(repository));
      System.out.println("Imported commit " + result.commit() + ": " + result.counts());
    }
  }
}
