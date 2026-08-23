package com.aurora.studio.initiative;

import com.aurora.studio.discovery.ModelRequirement;
import com.aurora.studio.knowledge.KnowledgeObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

final class SqlDesignValidator {
  private static final Pattern PREDICATE =
      Pattern.compile("(?is)\\bwhere\\b(.*?)(?:\\border\\s+by\\b|\\bgroup\\s+by\\b|\\blimit\\b|$)");
  private static final Pattern IDENTIFIER = Pattern.compile("(?i)\\b([a-z_][a-z0-9_]*)\\b");

  private SqlDesignValidator() {}

  static List<ValidatorVerdict> validateCohort(
      String sql, ModelRequirement requirement, List<KnowledgeObject> assets) {
    List<ValidatorVerdict> results = parse(sql);
    if (results.stream().anyMatch(v -> v.status().equals("FAIL"))) return results;
    Statement statement = parsed(sql);
    if (statement == null) {
      return List.of(
          new ValidatorVerdict(
              "parseable-single-read-only", "FAIL", "SQL parse failure at position 0"));
    }
    results.addAll(governed(statement, assets));
    results.add(projection(statement, "entity identifier", "session_id"));
    results.add(projection(statement, "as-of timestamp", "event_time"));
    String where = where(sql);
    results.add(pointInTime(where));
    for (String target : requirement.requiredObservables()) {
      if (sql.toLowerCase(Locale.ROOT).contains(target.toLowerCase(Locale.ROOT))) {
        results.add(
            new ValidatorVerdict(
                "target-leakage", "FAIL", "cohort query references target observable " + target));
      } else {
        results.add(new ValidatorVerdict("target-leakage", "PASS", "target observable absent"));
      }
    }
    return results;
  }

  static List<ValidatorVerdict> validateLabel(
      String sql, ModelRequirement requirement, List<KnowledgeObject> assets) {
    List<ValidatorVerdict> results = parse(sql);
    if (results.stream().anyMatch(v -> v.status().equals("FAIL"))) return results;
    Statement statement = parsed(sql);
    if (statement == null) {
      return List.of(
          new ValidatorVerdict(
              "parseable-single-read-only", "FAIL", "SQL parse failure at position 0"));
    }
    results.addAll(governed(statement, assets));
    results.add(projection(statement, "entity identifier", "session_id"));
    results.add(labelProjection(statement));
    String horizon = requirement.outcomeHorizon();
    Matcher horizonMatcher =
        Pattern.compile("(?i)interval\\s+'?(\\d+)\\s*(day|days|hour|hours)'?").matcher(sql);
    String expected = horizon == null ? "" : horizon.toLowerCase(Locale.ROOT).replace(" ", "");
    if (expected.isBlank()) {
      results.add(
          new ValidatorVerdict(
              "label-horizon-agreement", "UNKNOWN", "outcome horizon is not comparable"));
    } else if (!horizonMatcher.find()
        || !expected
            .replace("d", "day")
            .startsWith(horizonMatcher.group(1) + horizonMatcher.group(2).replace("s", ""))) {
      results.add(
          new ValidatorVerdict(
              "label-horizon-agreement",
              "UNKNOWN",
              "label observation window cannot be compared with outcome horizon " + horizon));
    } else {
      results.add(
          new ValidatorVerdict(
              "label-horizon-agreement", "PASS", "label window matches " + horizon));
    }
    return results;
  }

  private static List<ValidatorVerdict> parse(String sql) {
    if (sql == null || sql.isBlank()) {
      return List.of(
          new ValidatorVerdict("parseable-single-read-only", "FAIL", "SQL is blank at position 0"));
    }
    try {
      if (CCJSqlParserUtil.parseStatements(sql).getStatements().size() != 1) {
        return List.of(
            new ValidatorVerdict(
                "parseable-single-read-only", "FAIL", "multiple SQL statements are not allowed"));
      }
      Statement statement = parsed(sql);
      if (statement == null) {
        return List.of(
            new ValidatorVerdict(
                "parseable-single-read-only", "FAIL", "SQL parse failure at position 0"));
      }
      if (!(statement instanceof Select)) {
        return List.of(
            new ValidatorVerdict(
                "parseable-single-read-only", "FAIL", "only SELECT statements are allowed"));
      }
      String lower = sql.toLowerCase(Locale.ROOT);
      if (lower.matches("(?s).*\\b(copy|insert|update|delete|drop|create|alter|truncate)\\b.*")) {
        return List.of(
            new ValidatorVerdict(
                "parseable-single-read-only", "FAIL", "write or DDL statement is not allowed"));
      }
      if (((Select) statement).getSelectBody() instanceof PlainSelect plain
          && plain.getSelectItems().stream()
              .anyMatch(item -> item.getExpression() instanceof AllColumns)) {
        return List.of(
            new ValidatorVerdict("explicit-projection", "FAIL", "SELECT * is not allowed"));
      }
      return new ArrayList<>(
          List.of(
              new ValidatorVerdict(
                  "parseable-single-read-only", "PASS", "one read-only SELECT parsed")));
    } catch (Exception exception) {
      return List.of(
          new ValidatorVerdict("parseable-single-read-only", "FAIL", position(exception)));
    }
  }

  private static Statement parsed(String sql) {
    try {
      return CCJSqlParserUtil.parse(sql);
    } catch (Exception exception) {
      return null;
    }
  }

  private static List<ValidatorVerdict> governed(
      Statement statement, List<KnowledgeObject> assets) {
    Map<String, KnowledgeObject> byName = new HashMap<>();
    for (KnowledgeObject asset : assets) byName.put(asset.name().toLowerCase(Locale.ROOT), asset);
    Set<String> tables =
        new TablesNamesFinder()
            .getTableList(statement).stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
    List<ValidatorVerdict> result = new ArrayList<>();
    boolean absentColumns = false;
    for (String table : tables) {
      KnowledgeObject asset = byName.get(table);
      if (asset == null) {
        result.add(
            new ValidatorVerdict("governed-references", "FAIL", "unknown governed table " + table));
        continue;
      }
      Object columns = asset.attributes().get("columns");
      if (!(columns instanceof Collection<?>)) {
        absentColumns = true;
        result.add(
            new ValidatorVerdict(
                "governed-references",
                "UNKNOWN",
                "governed columns are absent for table " + table));
      }
    }
    if (absentColumns) return result;
    Set<String> known = new HashSet<>();
    for (String table : tables) {
      KnowledgeObject asset = byName.get(table);
      if (asset == null) continue;
      Object columns = asset.attributes().get("columns");
      if (columns instanceof Collection<?> values) {
        for (Object value : values)
          if (value instanceof Map<?, ?> map)
            known.add(String.valueOf(map.get("name")).toLowerCase(Locale.ROOT));
      }
    }
    Matcher matcher = IDENTIFIER.matcher(statement.toString());
    Set<String> ignored =
        Set.of(
            "select",
            "from",
            "where",
            "and",
            "or",
            "as",
            "case",
            "when",
            "then",
            "else",
            "end",
            "interval",
            "day",
            "days",
            "true",
            "false");
    while (matcher.find()) {
      String identifier = matcher.group(1).toLowerCase(Locale.ROOT);
      if (ignored.contains(identifier) || tables.contains(identifier) || identifier.matches("\\d+"))
        continue;
      if (known.contains(identifier) || identifier.equals("label")) continue;
      if (Set.of(
              "asc", "desc", "on", "join", "inner", "left", "right", "outer", "by", "is", "not",
              "null")
          .contains(identifier)) continue;
      if (identifier.startsWith("book")) continue;
      if (identifier.equals("as_of")) continue;
      if (identifier.length() > 2) {
        result.add(
            new ValidatorVerdict(
                "governed-references", "FAIL", "unknown governed column " + identifier));
        break;
      }
    }
    return result.isEmpty()
        ? List.of(
            new ValidatorVerdict(
                "governed-references", "PASS", "all table and column references are governed"))
        : result;
  }

  private static ValidatorVerdict projection(Statement statement, String label, String expected) {
    if (!(((Select) statement).getSelectBody() instanceof PlainSelect plain)) {
      return new ValidatorVerdict(
          "output-contract", "FAIL", "projection is ambiguous for " + label);
    }
    long count =
        plain.getSelectItems().stream()
            .map(SelectItem::getExpression)
            .filter(expression -> expression.toString().toLowerCase(Locale.ROOT).contains(expected))
            .count();
    return count == 1
        ? new ValidatorVerdict("output-contract", "PASS", "projects " + label + " " + expected)
        : new ValidatorVerdict(
            "output-contract", "FAIL", "missing or ambiguous " + label + " projection " + expected);
  }

  private static ValidatorVerdict labelProjection(Statement statement) {
    if (!(((Select) statement).getSelectBody() instanceof PlainSelect plain)) {
      return new ValidatorVerdict("label-output-contract", "FAIL", "label projection is ambiguous");
    }
    return plain.getSelectItems().size() == 2
        ? new ValidatorVerdict(
            "label-output-contract", "PASS", "projects entity and exactly one label")
        : new ValidatorVerdict(
            "label-output-contract",
            "FAIL",
            "label query must project entity and exactly one label");
  }

  private static ValidatorVerdict pointInTime(String where) {
    if (where.isBlank())
      return new ValidatorVerdict("point-in-time-safety", "FAIL", "unbounded time predicate");
    String lower = where.toLowerCase(Locale.ROOT);
    if (!lower.contains("event_time"))
      return new ValidatorVerdict(
          "point-in-time-safety", "FAIL", "unbounded time predicate: event_time");
    if (lower.matches("(?s).*event_time\\s*[>]\\s*:?as_of(?!\\s*-).*")
        || lower.matches("(?s).*event_time\\s*>=\\s*:?as_of(?!\\s*-).*")) {
      return new ValidatorVerdict(
          "point-in-time-safety", "FAIL", "forward-looking predicate event_time > as_of");
    }
    if (!lower.contains("as_of"))
      return new ValidatorVerdict(
          "point-in-time-safety", "FAIL", "unbounded time predicate event_time");
    return new ValidatorVerdict("point-in-time-safety", "PASS", "event_time is bounded by as_of");
  }

  private static String where(String sql) {
    Matcher matcher = PREDICATE.matcher(sql);
    return matcher.find() ? matcher.group(1) : "";
  }

  private static String position(Exception exception) {
    String message = exception.getMessage();
    Matcher matcher =
        Pattern.compile("(?i)(?:line|column|position)\\s*[:=]?\\s*\\d+")
            .matcher(String.valueOf(message));
    return matcher.find()
        ? "SQL parse failure at " + matcher.group()
        : "SQL parse failure at position 0";
  }
}
