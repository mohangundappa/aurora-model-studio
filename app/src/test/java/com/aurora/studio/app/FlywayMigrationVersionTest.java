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
    assertThat(versions).isNotEmpty().containsExactly(1, 2, 3, 4, 5);
  }
}
