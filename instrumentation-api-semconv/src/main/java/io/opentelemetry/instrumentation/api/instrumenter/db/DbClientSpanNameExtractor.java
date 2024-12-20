/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.instrumenter.db;

import io.opentelemetry.instrumentation.api.db.SqlStatementInfo;
import io.opentelemetry.instrumentation.api.db.SqlStatementSanitizer;
import io.opentelemetry.instrumentation.api.instrumenter.SpanNameExtractor;

public abstract class DbClientSpanNameExtractor<REQUEST> implements SpanNameExtractor<REQUEST> {

  /**
   * Returns a {@link SpanNameExtractor} that constructs the span name according to DB semantic
   * conventions: {@code <db.operation> <db.name>}.
   *
   * @see DbClientAttributesGetter#getOperation(Object) used to extract {@code <db.operation>}.
   * @see DbClientAttributesGetter#getName(Object) used to extract {@code <db.name>}.
   */
  public static <REQUEST> SpanNameExtractor<REQUEST> create(
      DbClientAttributesGetter<REQUEST> getter) {
    return new GenericDbClientSpanNameExtractor<>(getter);
  }

  /**
   * Returns a {@link SpanNameExtractor} that constructs the span name according to DB semantic
   * conventions: {@code <db.operation> <db.name>.<identifier>}.
   *
   * @see SqlStatementInfo#getOperation() used to extract {@code <db.operation>}.
   * @see DbClientAttributesGetter#getName(Object) used to extract {@code <db.name>}.
   * @see SqlStatementInfo#getMainIdentifier() used to extract {@code <db.table>} or stored
   *     procedure name.
   */
  public static <REQUEST> SpanNameExtractor<REQUEST> create(
      SqlClientAttributesGetter<REQUEST> getter) {
    return new SqlClientSpanNameExtractor<>(getter);
  }

  private static final String DEFAULT_SPAN_NAME = "DB Query";

  private DbClientSpanNameExtractor() {}

  protected String computeSpanName(String dbName, String operation, String mainIdentifier) {
    if (operation == null) {
      // 如果operation和dbName都为空则返回DB Query作为SpanName，若operation为空，dbName不为空则返回dbName作为SpanName
      return dbName == null ? DEFAULT_SPAN_NAME : dbName;
    }

    StringBuilder name = new StringBuilder(operation);
    if (dbName != null || mainIdentifier != null) {
      name.append(' ');
    }
    // skip db name if identifier already has a db name prefixed to it
    if (dbName != null && (mainIdentifier == null || mainIdentifier.indexOf('.') == -1)) {
      // 若operation和dbName都不为空，则SpanName="operation dbName"
      name.append(dbName);
      if (mainIdentifier != null) {
        name.append('.');
      }
    }
    if (mainIdentifier != null) {
      name.append(mainIdentifier);
    }
    // 若operation不为空，dbName为空，则SpanName为operation
    return name.toString();
  }

  private static final class GenericDbClientSpanNameExtractor<REQUEST> extends DbClientSpanNameExtractor<REQUEST> {

    private final DbClientAttributesGetter<REQUEST> getter;

    private GenericDbClientSpanNameExtractor(DbClientAttributesGetter<REQUEST> getter) {
      this.getter = getter;
    }

    @Override
    public String extract(REQUEST request) {
      String dbName = getter.getName(request);
      String operation = getter.getOperation(request);
      return computeSpanName(dbName, operation, null);
    }
  }

  private static final class SqlClientSpanNameExtractor<REQUEST>
      extends DbClientSpanNameExtractor<REQUEST> {

    // a dedicated sanitizer just for extracting the operation and identifier name
    private static final SqlStatementSanitizer sanitizer = SqlStatementSanitizer.create(true);

    private final SqlClientAttributesGetter<REQUEST> getter;

    private SqlClientSpanNameExtractor(SqlClientAttributesGetter<REQUEST> getter) {
      this.getter = getter;
    }

    @Override
    public String extract(REQUEST request) {
      String dbName = getter.getName(request);
      SqlStatementInfo sanitizedStatement = sanitizer.sanitize(getter.getRawStatement(request));
      return computeSpanName(dbName, sanitizedStatement.getOperation(), sanitizedStatement.getMainIdentifier());
    }
  }
}
