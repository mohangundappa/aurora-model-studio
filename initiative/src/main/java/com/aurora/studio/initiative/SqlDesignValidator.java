package com.aurora.studio.initiative;

import com.aurora.studio.discovery.ModelRequirement;
import com.aurora.studio.knowledge.KnowledgeObject;
import com.aurora.studio.knowledge.KnowledgeRelationship;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.IntervalExpression;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

final class SqlDesignValidator {
  private static final Pattern HORIZON =
      Pattern.compile(
          "(?i)^\\s*(\\d+(?:\\.\\d+)?)\\s*(milliseconds?|ms|seconds?|s|minutes?|m|hours?|h|days?|d)\\s*$");

  private SqlDesignValidator() {}

  static List<ValidatorVerdict> validateCohort(
      String sql, ModelRequirement requirement, List<KnowledgeObject> assets) {
    return validateCohort(sql, requirement, assets, List.of(), List.of());
  }

  static List<ValidatorVerdict> validateCohort(
      String sql,
      ModelRequirement requirement,
      List<KnowledgeObject> assets,
      List<KnowledgeObject> lineageObjects,
      List<KnowledgeRelationship> relationships) {
    List<ValidatorVerdict> results = parse(sql);
    if (results.stream().anyMatch(verdict -> verdict.status().equals("FAIL"))) return results;
    Statement statement = parsed(sql);
    if (statement == null || !(statement instanceof Select select)) {
      return List.of(
          new ValidatorVerdict(
              "parseable-single-read-only", "FAIL", "SQL parse failure at position 0"));
    }
    if (!(select.getSelectBody() instanceof PlainSelect plain)) {
      return List.of(
          new ValidatorVerdict(
              "output-contract", "UNKNOWN", "projection structure is not exposed by the parser"));
    }
    results.addAll(governed(statement, assets));
    String entityColumn = declaredColumn(statement, assets, "primaryKey");
    String eventTimeColumn = declaredColumn(statement, assets, "eventTime");
    results.add(projection(plain, "entity identifier", entityColumn));
    results.add(projection(plain, "as-of timestamp", eventTimeColumn));
    results.add(pointInTime(plain.getWhere(), eventTimeColumn));
    results.addAll(
        targetLeakage(statement, plain, requirement, assets, lineageObjects, relationships));
    return results;
  }

  static List<ValidatorVerdict> validateLabel(
      String sql, ModelRequirement requirement, List<KnowledgeObject> assets) {
    List<ValidatorVerdict> results = parse(sql);
    if (results.stream().anyMatch(verdict -> verdict.status().equals("FAIL"))) return results;
    Statement statement = parsed(sql);
    if (statement == null || !(statement instanceof Select select)) {
      return List.of(
          new ValidatorVerdict(
              "parseable-single-read-only", "FAIL", "SQL parse failure at position 0"));
    }
    if (!(select.getSelectBody() instanceof PlainSelect plain)) {
      return List.of(
          new ValidatorVerdict(
              "label-output-contract",
              "UNKNOWN",
              "label projection structure is not exposed by the parser"));
    }
    results.addAll(governed(statement, assets));
    String entityColumn = declaredColumn(statement, assets, "primaryKey");
    results.add(projection(plain, "entity identifier", entityColumn));
    results.add(labelProjection(plain));
    results.add(horizonAgreement(plain.getWhere(), requirement.outcomeHorizon()));
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
      known.addAll(columnNames(asset));
    }
    for (Column column : columns(statement)) {
      String name = column.getColumnName().toLowerCase(Locale.ROOT);
      if (name.equals("*") || known.contains(name)) continue;
      result.add(
          new ValidatorVerdict("governed-references", "FAIL", "unknown governed column " + name));
      break;
    }
    return result.isEmpty()
        ? List.of(
            new ValidatorVerdict(
                "governed-references", "PASS", "all table and column references are governed"))
        : result;
  }

  private static String declaredColumn(
      Statement statement, List<KnowledgeObject> assets, String field) {
    Set<String> tables =
        new TablesNamesFinder()
            .getTableList(statement).stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
    Set<String> declarations = new HashSet<>();
    for (KnowledgeObject asset : assets) {
      if (!tables.contains(asset.name().toLowerCase(Locale.ROOT))) continue;
      Object value = asset.attributes().get(field);
      if (value != null
          && !String.valueOf(value).isBlank()
          && columnNames(asset).contains(String.valueOf(value).toLowerCase(Locale.ROOT))) {
        declarations.add(String.valueOf(value).toLowerCase(Locale.ROOT));
      }
    }
    return declarations.size() == 1 ? declarations.iterator().next() : "";
  }

  private static ValidatorVerdict projection(
      PlainSelect plain, String label, String expectedColumn) {
    if (expectedColumn.isBlank()) {
      return new ValidatorVerdict(
          "output-contract", "UNKNOWN", label + " is not declared by the governed data asset");
    }
    long count =
        plain.getSelectItems().stream()
            .filter(item -> projectsColumn(item.getExpression(), expectedColumn))
            .count();
    return count == 1
        ? new ValidatorVerdict(
            "output-contract", "PASS", "projects " + label + " " + expectedColumn)
        : new ValidatorVerdict(
            "output-contract",
            "FAIL",
            "missing or ambiguous " + label + " projection " + expectedColumn);
  }

  private static boolean projectsColumn(Expression expression, String expectedColumn) {
    List<Column> columns = new ArrayList<>();
    expression.accept(
        new ExpressionVisitorAdapter() {
          @Override
          public void visit(Column column) {
            columns.add(column);
          }
        });
    return columns.size() == 1
        && columns.getFirst().getColumnName().equalsIgnoreCase(expectedColumn);
  }

  private static ValidatorVerdict labelProjection(PlainSelect plain) {
    return plain.getSelectItems().size() == 2
        ? new ValidatorVerdict(
            "label-output-contract", "PASS", "projects entity and exactly one label")
        : new ValidatorVerdict(
            "label-output-contract",
            "FAIL",
            "label query must project entity and exactly one label");
  }

  private static ValidatorVerdict pointInTime(Expression where, String eventTimeColumn) {
    if (eventTimeColumn.isBlank()) {
      return new ValidatorVerdict(
          "point-in-time-safety", "UNKNOWN", "as-of timestamp column is not declared");
    }
    if (where == null) {
      return new ValidatorVerdict("point-in-time-safety", "FAIL", "unbounded time predicate");
    }
    TimeAnalysis analysis = new TimeAnalysis(eventTimeColumn);
    where.accept(analysis);
    if (analysis.unknownReason != null) {
      return new ValidatorVerdict("point-in-time-safety", "UNKNOWN", analysis.unknownReason);
    }
    if (!analysis.sawEventTime) {
      return new ValidatorVerdict(
          "point-in-time-safety", "FAIL", "unbounded time predicate: " + eventTimeColumn);
    }
    if (analysis.forwardLooking) {
      return new ValidatorVerdict("point-in-time-safety", "FAIL", analysis.forwardReason);
    }
    if (!analysis.upperBounded) {
      return new ValidatorVerdict(
          "point-in-time-safety", "FAIL", "unbounded time predicate: " + eventTimeColumn);
    }
    return new ValidatorVerdict(
        "point-in-time-safety", "PASS", eventTimeColumn + " is bounded by as_of");
  }

  private static ValidatorVerdict horizonAgreement(Expression where, String requirementHorizon) {
    Duration required = duration(requirementHorizon);
    List<IntervalExpression> intervals = new ArrayList<>();
    if (where != null) {
      where.accept(
          new ExpressionVisitorAdapter() {
            @Override
            public void visit(IntervalExpression interval) {
              intervals.add(interval);
              super.visit(interval);
            }
          });
    }
    if (required == null || intervals.size() != 1) {
      return new ValidatorVerdict(
          "label-horizon-agreement",
          "UNKNOWN",
          "label observation window cannot be compared with outcome horizon "
              + String.valueOf(requirementHorizon));
    }
    Duration sqlDuration = duration(intervals.getFirst().getParameter());
    if (sqlDuration == null) {
      return new ValidatorVerdict(
          "label-horizon-agreement",
          "UNKNOWN",
          "SQL interval is not a comparable duration: " + intervals.getFirst());
    }
    return sqlDuration.equals(required)
        ? new ValidatorVerdict(
            "label-horizon-agreement",
            "PASS",
            "label window matches outcome horizon " + requirementHorizon)
        : new ValidatorVerdict(
            "label-horizon-agreement",
            "UNKNOWN",
            "label window does not match outcome horizon " + requirementHorizon);
  }

  private static Duration duration(String value) {
    if (value == null) return null;
    String normalized = value.trim().replace("'", "");
    var matcher = HORIZON.matcher(normalized);
    if (!matcher.matches()) return null;
    try {
      BigDecimal amount = new BigDecimal(matcher.group(1));
      BigDecimal nanosPerUnit =
          switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "ms", "millisecond", "milliseconds" -> BigDecimal.valueOf(1_000_000L);
            case "s", "second", "seconds" -> BigDecimal.valueOf(1_000_000_000L);
            case "m", "minute", "minutes" -> BigDecimal.valueOf(60_000_000_000L);
            case "h", "hour", "hours" -> BigDecimal.valueOf(3_600_000_000_000L);
            case "d", "day", "days" -> BigDecimal.valueOf(86_400_000_000_000L);
            default -> null;
          };
      if (nanosPerUnit == null) return null;
      return Duration.ofNanos(amount.multiply(nanosPerUnit).longValueExact());
    } catch (ArithmeticException exception) {
      return null;
    }
  }

  private static List<ValidatorVerdict> targetLeakage(
      Statement statement,
      PlainSelect plain,
      ModelRequirement requirement,
      List<KnowledgeObject> assets,
      List<KnowledgeObject> lineageObjects,
      List<KnowledgeRelationship> relationships) {
    List<Column> references = columns(statement);
    List<ValidatorVerdict> results = new ArrayList<>();
    for (String target : requirement.requiredObservables()) {
      boolean derived =
          references.stream()
              .anyMatch(
                  column ->
                      derivedFromTarget(column, target, assets, lineageObjects, relationships));
      boolean direct =
          references.stream().anyMatch(column -> column.getColumnName().equalsIgnoreCase(target))
              || targetLiteralComparison(plain, target);
      boolean leaked = direct || derived;
      results.add(
          leaked
              ? new ValidatorVerdict(
                  "target-leakage",
                  "FAIL",
                  direct
                      ? "cohort query references target observable " + target
                      : "cohort query references a governed derivation of target observable "
                          + target)
              : new ValidatorVerdict("target-leakage", "PASS", "target observable is absent"));
    }
    return results;
  }

  private static boolean targetLiteralComparison(PlainSelect plain, String target) {
    if (plain.getWhere() == null) return false;
    final boolean[] found = {false};
    plain
        .getWhere()
        .accept(
            new ExpressionVisitorAdapter() {
              @Override
              public void visit(EqualsTo expression) {
                List<String> literals = stringLiterals(expression);
                List<Column> columns = columns(expression);
                if (literals.stream().anyMatch(value -> value.equalsIgnoreCase(target))
                    && !columns.isEmpty()) {
                  found[0] = true;
                }
                super.visit(expression);
              }
            });
    return found[0];
  }

  private static boolean derivedFromTarget(
      Column reference,
      String target,
      List<KnowledgeObject> assets,
      List<KnowledgeObject> lineageObjects,
      List<KnowledgeRelationship> relationships) {
    for (KnowledgeObject asset : assets) {
      if (!assetMatches(reference, asset)) continue;
      for (Map<String, Object> column : columnMetadata(asset)) {
        if (!String.valueOf(column.get("name")).equalsIgnoreCase(reference.getColumnName()))
          continue;
        if (metadataContainsTarget(column, target)) return true;
      }
      if (metadataContainsTarget(asset.attributes(), target)
          && !asset.attributes().containsKey("columns")) return true;
      if (lineageContainsTarget(asset.id(), target, lineageObjects, relationships)) return true;
    }
    return false;
  }

  private static boolean assetMatches(Column reference, KnowledgeObject asset) {
    String qualifier =
        reference.getTable() == null ? "" : reference.getTable().getName().toLowerCase(Locale.ROOT);
    return qualifier.isBlank() || qualifier.equals(asset.name().toLowerCase(Locale.ROOT));
  }

  private static boolean lineageContainsTarget(
      java.util.UUID assetId,
      String target,
      List<KnowledgeObject> objects,
      List<KnowledgeRelationship> relationships) {
    Map<java.util.UUID, KnowledgeObject> byId = new LinkedHashMap<>();
    for (KnowledgeObject object : objects) byId.put(object.id(), object);
    Map<java.util.UUID, Set<java.util.UUID>> graph = new HashMap<>();
    for (KnowledgeRelationship relationship : relationships) {
      if (!relationship.relationshipType().name().equals("DERIVED_FROM")
          && !relationship.relationshipType().name().equals("IMPLEMENTED_BY")) continue;
      graph
          .computeIfAbsent(relationship.fromObjectId(), ignored -> new HashSet<>())
          .add(relationship.toObjectId());
      graph
          .computeIfAbsent(relationship.toObjectId(), ignored -> new HashSet<>())
          .add(relationship.fromObjectId());
    }
    ArrayDeque<java.util.UUID> pending = new ArrayDeque<>();
    Set<java.util.UUID> visited = new HashSet<>();
    pending.add(assetId);
    while (!pending.isEmpty()) {
      java.util.UUID current = pending.removeFirst();
      if (!visited.add(current)) continue;
      KnowledgeObject object = byId.get(current);
      if (object != null && metadataContainsTarget(object.attributes(), target)) return true;
      for (java.util.UUID related : graph.getOrDefault(current, Set.of())) pending.addLast(related);
    }
    return false;
  }

  private static boolean metadataContainsTarget(Map<?, ?> metadata, String target) {
    for (Map.Entry<?, ?> entry : metadata.entrySet()) {
      String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
      if (!Set.of(
              "derivedfrom",
              "derivedfromobservable",
              "sourceobservables",
              "targetevent",
              "targetobservable",
              "requiredobservables")
          .contains(key)) continue;
      if (containsValue(entry.getValue(), target)) return true;
    }
    return false;
  }

  private static boolean containsValue(Object value, String expected) {
    if (value instanceof Collection<?> values) {
      return values.stream().anyMatch(item -> String.valueOf(item).equalsIgnoreCase(expected));
    }
    return value != null && String.valueOf(value).equalsIgnoreCase(expected);
  }

  private static List<Column> columns(Statement statement) {
    List<Column> result = new ArrayList<>();
    statement.accept(
        new net.sf.jsqlparser.statement.StatementVisitorAdapter() {
          @Override
          public void visit(Select select) {
            if (select.getSelectBody() instanceof PlainSelect plain) {
              for (SelectItem<?> item : plain.getSelectItems())
                addColumns(item.getExpression(), result);
              addColumns(plain.getWhere(), result);
            }
          }
        });
    return result;
  }

  private static List<Column> columns(Expression expression) {
    List<Column> result = new ArrayList<>();
    addColumns(expression, result);
    return result;
  }

  private static void addColumns(Expression expression, List<Column> result) {
    if (expression == null) return;
    expression.accept(
        new ExpressionVisitorAdapter() {
          @Override
          public void visit(Column column) {
            result.add(column);
          }
        });
  }

  private static List<String> stringLiterals(Expression expression) {
    List<String> result = new ArrayList<>();
    expression.accept(
        new ExpressionVisitorAdapter() {
          @Override
          public void visit(StringValue value) {
            result.add(value.getValue());
          }
        });
    return result;
  }

  private static Set<String> columnNames(KnowledgeObject asset) {
    Set<String> result = new HashSet<>();
    for (Map<String, Object> column : columnMetadata(asset)) {
      Object name = column.get("name");
      if (name != null) result.add(String.valueOf(name).toLowerCase(Locale.ROOT));
    }
    return result;
  }

  private static List<Map<String, Object>> columnMetadata(KnowledgeObject asset) {
    Object columns = asset.attributes().get("columns");
    if (!(columns instanceof Collection<?> values)) return List.of();
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object value : values) {
      if (value instanceof Map<?, ?> map) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        map.forEach((key, item) -> normalized.put(String.valueOf(key), item));
        result.add(normalized);
      }
    }
    return result;
  }

  private static String position(Exception exception) {
    String message = exception.getMessage();
    var matcher =
        Pattern.compile("(?i)(?:line|column|position)\\s*[:=]?\\s*\\d+")
            .matcher(String.valueOf(message));
    return matcher.find()
        ? "SQL parse failure at " + matcher.group()
        : "SQL parse failure at position 0";
  }

  private static final class TimeAnalysis extends ExpressionVisitorAdapter {
    private final String eventTimeColumn;
    private boolean sawEventTime;
    private boolean upperBounded;
    private boolean forwardLooking;
    private String forwardReason;
    private String unknownReason;

    private TimeAnalysis(String eventTimeColumn) {
      this.eventTimeColumn = eventTimeColumn;
    }

    @Override
    public void visit(GreaterThan expression) {
      compare(expression, ">");
      super.visit(expression);
    }

    @Override
    public void visit(GreaterThanEquals expression) {
      compare(expression, ">=");
      super.visit(expression);
    }

    @Override
    public void visit(MinorThan expression) {
      compare(expression, "<");
      super.visit(expression);
    }

    @Override
    public void visit(MinorThanEquals expression) {
      compare(expression, "<=");
      super.visit(expression);
    }

    @Override
    public void visit(EqualsTo expression) {
      compare(expression, "=");
      super.visit(expression);
    }

    @Override
    public void visit(Function function) {
      if (!columns(function).stream()
          .noneMatch(column -> column.getColumnName().equalsIgnoreCase(eventTimeColumn))) {
        unknownReason = "unsupported time construct " + function;
      }
      super.visit(function);
    }

    @Override
    public void visit(Between between) {
      if (columns(between).stream()
          .anyMatch(column -> column.getColumnName().equalsIgnoreCase(eventTimeColumn))) {
        unknownReason = "unsupported time construct " + between;
      }
      super.visit(between);
    }

    private void compare(BinaryExpression expression, String operator) {
      Column left = singleColumn(expression.getLeftExpression());
      Column right = singleColumn(expression.getRightExpression());
      boolean leftEvent = isEventTime(left);
      boolean rightEvent = isEventTime(right);
      if (!leftEvent && !rightEvent) return;
      if (leftEvent) sawEventTime = true;
      if (rightEvent) sawEventTime = true;
      if (leftEvent && isDirectAsOf(expression.getRightExpression())) {
        record(operator);
      } else if (leftEvent && isComparableAsOf(expression.getRightExpression())) {
        recordOffset(operator, asOfOffset(expression.getRightExpression()));
      } else if (rightEvent && isDirectAsOf(expression.getLeftExpression())) {
        record(invert(operator));
      } else if (rightEvent && isComparableAsOf(expression.getLeftExpression())) {
        recordOffset(invert(operator), asOfOffset(expression.getLeftExpression()));
      } else if (leftEvent || rightEvent) {
        unknownReason = "time predicate is not comparable with :as_of: " + expression;
      }
    }

    private void record(String operator) {
      if (operator.equals("<") || operator.equals("<=") || operator.equals("=")) {
        upperBounded = true;
      }
      if (operator.equals(">") || operator.equals(">=")) {
        forwardLooking = true;
        forwardReason = "forward-looking predicate " + eventTimeColumn + " " + operator + " as_of";
      }
    }

    private void recordOffset(String operator, int offset) {
      if (offset < 0 && (operator.equals("<") || operator.equals("<="))) {
        upperBounded = true;
      } else if (offset < 0 && (operator.equals(">") || operator.equals(">="))) {
        return;
      } else if (offset > 0 && (operator.equals("<") || operator.equals("<="))) {
        forwardLooking = true;
        forwardReason = "forward-looking predicate " + eventTimeColumn + " " + operator + " as_of";
      } else {
        unknownReason = "time predicate is not a supported as_of offset";
      }
    }

    private String invert(String operator) {
      return switch (operator) {
        case ">" -> "<";
        case ">=" -> "<=";
        case "<" -> ">";
        case "<=" -> ">=";
        default -> operator;
      };
    }

    private boolean isEventTime(Column column) {
      return column != null && column.getColumnName().equalsIgnoreCase(eventTimeColumn);
    }
  }

  private static Column singleColumn(Expression expression) {
    List<Column> columns = columns(expression);
    return columns.size() == 1 ? columns.getFirst() : null;
  }

  private static boolean isDirectAsOf(Expression expression) {
    return expression instanceof JdbcNamedParameter parameter
        && parameter.getName().equalsIgnoreCase("as_of");
  }

  private static boolean isComparableAsOf(Expression expression) {
    final boolean[] found = {false};
    expression.accept(
        new ExpressionVisitorAdapter() {
          @Override
          public void visit(JdbcNamedParameter parameter) {
            if (parameter.getName().equalsIgnoreCase("as_of")) found[0] = true;
          }
        });
    return found[0];
  }

  private static int asOfOffset(Expression expression) {
    if (isDirectAsOf(expression)) return 0;
    if (expression instanceof Addition addition
        && ((isDirectAsOf(addition.getLeftExpression())
                && addition.getRightExpression() instanceof IntervalExpression)
            || (isDirectAsOf(addition.getRightExpression())
                && addition.getLeftExpression() instanceof IntervalExpression))) {
      return 1;
    }
    if (expression instanceof Subtraction subtraction
        && isDirectAsOf(subtraction.getLeftExpression())
        && subtraction.getRightExpression() instanceof IntervalExpression) {
      return -1;
    }
    return 0;
  }
}
