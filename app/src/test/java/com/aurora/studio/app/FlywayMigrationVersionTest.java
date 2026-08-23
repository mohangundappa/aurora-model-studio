package com.aurora.studio.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class FlywayMigrationVersionTest {
  @Test
  void migrationsArePresentAndContiguousFromOne() throws Exception {
    var resolver = new PathMatchingResourcePatternResolver();
    var pattern = Pattern.compile("V(\\d+)__.*\\.sql");
    List<Integer> versions =
        java.util.Arrays.stream(resolver.getResources("classpath*:db/migration/*.sql"))
            .map(resource -> resource.getFilename())
            .map(pattern::matcher)
            .filter(java.util.regex.Matcher::matches)
            .map(matcher -> Integer.parseInt(matcher.group(1)))
            .sorted(Comparator.naturalOrder())
            .toList();
    assertThat(versions).isNotEmpty().containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
  }

  @Test
  void structuralCleanupOnlyMatchesExactPlaceholderFields() throws Exception {
    var resolver = new PathMatchingResourcePatternResolver();
    for (String migration :
        List.of(
            "V8__classify_knowledge_conflicts.sql",
            "V9__resolve_superseded_structural_conflicts.sql")) {
      String sql =
          new String(
              resolver
                  .getResource("classpath:db/migration/" + migration)
                  .getInputStream()
                  .readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8);
      assertThat(sql).doesNotContain("values::text");
      assertThat(sql).doesNotContain("%guest%");
      assertThat(sql).contains("#>> '{current,value}'");
      assertThat(sql).contains("#>> '{other,value}'");
    }
  }
}
