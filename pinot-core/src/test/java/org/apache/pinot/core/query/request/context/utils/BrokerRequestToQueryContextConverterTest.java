/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.core.query.request.context.utils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.apache.pinot.core.query.request.context.ExpressionContext;
import org.apache.pinot.core.query.request.context.FilterContext;
import org.apache.pinot.core.query.request.context.FunctionContext;
import org.apache.pinot.core.query.request.context.OrderByExpressionContext;
import org.apache.pinot.core.query.request.context.QueryContext;
import org.apache.pinot.core.query.request.context.predicate.InPredicate;
import org.apache.pinot.core.query.request.context.predicate.RangePredicate;
import org.apache.pinot.core.query.request.context.predicate.TextMatchPredicate;
import org.apache.pinot.pql.parsers.Pql2Compiler;
import org.apache.pinot.sql.parsers.CalciteSqlCompiler;
import org.testng.annotations.Test;

import static org.testng.Assert.*;


public class BrokerRequestToQueryContextConverterTest {
  private static final Pql2Compiler PQL_COMPILER = new Pql2Compiler();
  private static final CalciteSqlCompiler SQL_COMPILER = new CalciteSqlCompiler();

  @Test
  public void testHardcodedQueries() {
    // Select *
    {
      String query = "SELECT * FROM testTable";
      QueryContext[] queryContexts = getQueryContexts(query, query);
      for (QueryContext queryContext : queryContexts) {
        List<ExpressionContext> selectExpressions = queryContext.getSelectExpressions();
        assertEquals(selectExpressions.size(), 1);
        assertEquals(selectExpressions.get(0), ExpressionContext.forIdentifier("*"));
        assertEquals(selectExpressions.get(0).toString(), "*");
        assertNull(queryContext.getFilter());
        assertNull(queryContext.getGroupByExpressions());
        assertNull(queryContext.getOrderByExpressions());
        assertNull(queryContext.getHavingFilter());
        assertEquals(queryContext.getLimit(), 10);
        assertEquals(queryContext.getOffset(), 0);
        assertTrue(QueryContextUtils.getAllColumns(queryContext).isEmpty());
        assertFalse(QueryContextUtils.isAggregationQuery(queryContext));
      }
    }

    // Select COUNT(*)
    {
      String query = "SELECT COUNT(*) FROM testTable";
      QueryContext[] queryContexts = getQueryContexts(query, query);
      for (QueryContext queryContext : queryContexts) {
        List<ExpressionContext> selectExpressions = queryContext.getSelectExpressions();
        assertEquals(selectExpressions.size(), 1);
        assertEquals(selectExpressions.get(0), ExpressionContext.forFunction(
            new FunctionContext(FunctionContext.Type.AGGREGATION, "count",
                Collections.singletonList(ExpressionContext.forIdentifier("*")))));
        assertEquals(selectExpressions.get(0).toString(), "count(*)");
        assertNull(queryContext.getFilter());
        assertNull(queryContext.getGroupByExpressions());
        assertNull(queryContext.getOrderByExpressions());
        assertNull(queryContext.getHavingFilter());
        assertEquals(queryContext.getLimit(), 10);
        assertEquals(queryContext.getOffset(), 0);
        assertTrue(QueryContextUtils.getAllColumns(queryContext).isEmpty());
        assertTrue(QueryContextUtils.isAggregationQuery(queryContext));
      }
    }

    // Order-by
    {
      String query = "SELECT foo, bar FROM testTable ORDER BY bar ASC, foo DESC LIMIT 50, 100";
      QueryContext[] queryContexts = getQueryContexts(query, query);
      for (QueryContext queryContext : queryContexts) {
        List<ExpressionContext> selectExpressions = queryContext.getSelectExpressions();
        assertEquals(selectExpressions.size(), 2);
        assertEquals(selectExpressions.get(0), ExpressionContext.forIdentifier("foo"));
        assertEquals(selectExpressions.get(0).toString(), "foo");
        assertEquals(selectExpressions.get(1), ExpressionContext.forIdentifier("bar"));
        assertEquals(selectExpressions.get(1).toString(), "bar");
        assertNull(queryContext.getFilter());
        List<OrderByExpressionContext> orderByExpressions = queryContext.getOrderByExpressions();
        assertNotNull(orderByExpressions);
        assertEquals(orderByExpressions.size(), 2);
        assertEquals(orderByExpressions.get(0),
            new OrderByExpressionContext(ExpressionContext.forIdentifier("bar"), true));
        assertEquals(orderByExpressions.get(0).toString(), "bar ASC");
        assertEquals(orderByExpressions.get(1).toString(), "foo DESC");
        assertNull(queryContext.getHavingFilter());
        assertEquals(queryContext.getLimit(), 100);
        assertEquals(queryContext.getOffset(), 50);
        assertEquals(QueryContextUtils.getAllColumns(queryContext), new HashSet<>(Arrays.asList("foo", "bar")));
        assertFalse(QueryContextUtils.isAggregationQuery(queryContext));
      }
    }

    // Distinct with order-by
    {
      String pqlQuery = "SELECT DISTINCT(foo, bar, foobar) FROM testTable ORDER BY bar DESC, foo LIMIT 15";
      String sqlQuery = "SELECT DISTINCT foo, bar, foobar FROM testTable ORDER BY bar DESC, foo LIMIT 15";
      QueryContext[] queryContexts = getQueryContexts(pqlQuery, sqlQuery);
      for (QueryContext queryContext : queryContexts) {
        List<ExpressionContext> selectExpressions = queryContext.getSelectExpressions();
        assertEquals(selectExpressions.size(), 1);
        assertEquals(selectExpressions.get(0), ExpressionContext.forFunction(
            new FunctionContext(FunctionContext.Type.AGGREGATION, "distinct", Arrays
                .asList(ExpressionContext.forIdentifier("foo"), ExpressionContext.forIdentifier("bar"),
                    ExpressionContext.forIdentifier("foobar")))));
        assertEquals(selectExpressions.get(0).toString(), "distinct(foo,bar,foobar)");
        assertNull(queryContext.getFilter());
        assertNull(queryContext.getGroupByExpressions());
        List<OrderByExpressionContext> orderByExpressions = queryContext.getOrderByExpressions();
        assertNotNull(orderByExpressions);
        assertEquals(orderByExpressions.size(), 2);
        assertEquals(orderByExpressions.get(0),
            new OrderByExpressionContext(ExpressionContext.forIdentifier("bar"), false));
        assertEquals(orderByExpressions.get(0).toString(), "bar DESC");
        assertEquals(orderByExpressions.get(1),
            new OrderByExpressionContext(ExpressionContext.forIdentifier("foo"), true));
        assertEquals(orderByExpressions.get(1).toString(), "foo ASC");
        assertNull(queryContext.getHavingFilter());
        assertEquals(queryContext.getLimit(), 15);
        assertEquals(queryContext.getOffset(), 0);
        assertEquals(QueryContextUtils.getAllColumns(queryContext),
            new HashSet<>(Arrays.asList("foo", "bar", "foobar")));
        assertTrue(QueryContextUtils.isAggregationQuery(queryContext));
      }
    }

    // Transform with order-by
    {
      String query =
          "SELECT ADD(foo, ADD(bar, 123)), SUB('456', foobar) FROM testTable ORDER BY SUB(456, foobar) LIMIT 30, 20";
      QueryContext[] queryContexts = getQueryContexts(query, query);
      for (QueryContext queryContext : queryContexts) {
        List<ExpressionContext> selectExpressions = queryContext.getSelectExpressions();
        assertEquals(selectExpressions.size(), 2);
        assertEquals(selectExpressions.get(0), ExpressionContext.forFunction(
            new FunctionContext(FunctionContext.Type.TRANSFORM, "add", Arrays
                .asList(ExpressionContext.forIdentifier("foo"), ExpressionContext.forFunction(
                    new FunctionContext(FunctionContext.Type.TRANSFORM, "add", Arrays
                        .asList(ExpressionContext.forIdentifier("bar"), ExpressionContext.forLiteral("123"))))))));
        assertEquals(selectExpressions.get(0).toString(), "add(foo,add(bar,'123'))");
        assertEquals(selectExpressions.get(1), ExpressionContext.forFunction(
            new FunctionContext(FunctionContext.Type.TRANSFORM, "sub",
                Arrays.asList(ExpressionContext.forLiteral("456"), ExpressionContext.forIdentifier("foobar")))));
        assertEquals(selectExpressions.get(1).toString(), "sub('456',foobar)");
        assertNull(queryContext.getFilter());
        assertNull(queryContext.getGroupByExpressions());
        List<OrderByExpressionContext> orderByExpressions = queryContext.getOrderByExpressions();
        assertNotNull(orderByExpressions);
        assertEquals(orderByExpressions.size(), 1);
        assertEquals(orderByExpressions.get(0), new OrderByExpressionContext(ExpressionContext.forFunction(
            new FunctionContext(FunctionContext.Type.TRANSFORM, "sub",
                Arrays.asList(ExpressionContext.forLiteral("456"), ExpressionContext.forIdentifier("foobar")))), true));
        assertEquals(orderByExpressions.get(0).toString(), "sub('456',foobar) ASC");
        assertNull(queryContext.getHavingFilter());
        assertEquals(queryContext.getLimit(), 20);
        assertEquals(queryContext.getOffset(), 30);
        assertEquals(QueryContextUtils.getAllColumns(queryContext),
            new HashSet<>(Arrays.asList("foo", "bar", "foobar")));
        assertFalse(QueryContextUtils.isAggregationQuery(queryContext));
      }
    }

    // Aggregation group-by with transform, order-by
    {
      String pqlQuery =
          "SELECT SUM(ADD(foo, bar)) FROM testTable GROUP BY SUB(foo, bar), bar ORDER BY SUM(ADD(foo, bar)), SUB(foo, bar) DESC LIMIT 20";
      String sqlQuery =
          "SELECT SUB(foo, bar), bar, SUM(ADD(foo, bar)) FROM testTable GROUP BY SUB(foo, bar), bar ORDER BY SUM(ADD(foo, bar)), SUB(foo, bar) DESC LIMIT 20";
      QueryContext[] queryContexts = getQueryContexts(pqlQuery, sqlQuery);
      for (QueryContext queryContext : queryContexts) {
        List<ExpressionContext> selectExpressions = queryContext.getSelectExpressions();
        assertEquals(selectExpressions.size(), 1);
        assertEquals(selectExpressions.get(0), ExpressionContext.forFunction(
            new FunctionContext(FunctionContext.Type.AGGREGATION, "sum", Collections.singletonList(ExpressionContext
                .forFunction(new FunctionContext(FunctionContext.Type.TRANSFORM, "add",
                    Arrays.asList(ExpressionContext.forIdentifier("foo"), ExpressionContext.forIdentifier("bar"))))))));
        assertEquals(selectExpressions.get(0).toString(), "sum(add(foo,bar))");
        assertNull(queryContext.getFilter());
        List<ExpressionContext> groupByExpressions = queryContext.getGroupByExpressions();
        assertNotNull(groupByExpressions);
        assertEquals(groupByExpressions.size(), 2);
        assertEquals(groupByExpressions.get(0), ExpressionContext.forFunction(
            new FunctionContext(FunctionContext.Type.TRANSFORM, "sub",
                Arrays.asList(ExpressionContext.forIdentifier("foo"), ExpressionContext.forIdentifier("bar")))));
        assertEquals(groupByExpressions.get(0).toString(), "sub(foo,bar)");
        assertEquals(groupByExpressions.get(1), ExpressionContext.forIdentifier("bar"));
        assertEquals(groupByExpressions.get(1).toString(), "bar");
        List<OrderByExpressionContext> orderByExpressions = queryContext.getOrderByExpressions();
        assertNotNull(orderByExpressions);
        assertEquals(orderByExpressions.size(), 2);
        assertEquals(orderByExpressions.get(0), new OrderByExpressionContext(ExpressionContext.forFunction(
            new FunctionContext(FunctionContext.Type.AGGREGATION, "sum", Collections.singletonList(ExpressionContext
                .forFunction(new FunctionContext(FunctionContext.Type.TRANSFORM, "add",
                    Arrays.asList(ExpressionContext.forIdentifier("foo"), ExpressionContext.forIdentifier("bar"))))))),
            true));
        assertEquals(orderByExpressions.get(0).toString(), "sum(add(foo,bar)) ASC");
        assertEquals(orderByExpressions.get(1), new OrderByExpressionContext(ExpressionContext.forFunction(
            new FunctionContext(FunctionContext.Type.TRANSFORM, "sub",
                Arrays.asList(ExpressionContext.forIdentifier("foo"), ExpressionContext.forIdentifier("bar")))),
            false));
        assertEquals(orderByExpressions.get(1).toString(), "sub(foo,bar) DESC");
        assertNull(queryContext.getHavingFilter());
        assertEquals(queryContext.getLimit(), 20);
        assertEquals(queryContext.getOffset(), 0);
        assertEquals(QueryContextUtils.getAllColumns(queryContext), new HashSet<>(Arrays.asList("foo", "bar")));
        assertTrue(QueryContextUtils.isAggregationQuery(queryContext));
      }
    }

    // Filter with transform
    {
      String query =
          "SELECT * FROM testTable WHERE foo > 15 AND (DIV(bar, foo) BETWEEN 10 AND 20 OR TEXT_MATCH(foobar, 'potato'))";
      QueryContext[] queryContexts = getQueryContexts(query, query);
      for (QueryContext queryContext : queryContexts) {
        List<ExpressionContext> selectExpressions = queryContext.getSelectExpressions();
        assertEquals(selectExpressions.size(), 1);
        assertEquals(selectExpressions.get(0), ExpressionContext.forIdentifier("*"));
        assertEquals(selectExpressions.get(0).toString(), "*");
        FilterContext filter = queryContext.getFilter();
        assertNotNull(filter);
        assertEquals(filter.getType(), FilterContext.Type.AND);
        List<FilterContext> children = filter.getChildren();
        assertEquals(children.size(), 2);
        assertEquals(children.get(0), new FilterContext(FilterContext.Type.PREDICATE, null,
            new RangePredicate(ExpressionContext.forIdentifier("foo"), false, "15", false, "*")));
        FilterContext orFilter = children.get(1);
        assertEquals(orFilter.getType(), FilterContext.Type.OR);
        assertEquals(orFilter.getChildren().size(), 2);
        assertEquals(orFilter.getChildren().get(0), new FilterContext(FilterContext.Type.PREDICATE, null,
            new RangePredicate(ExpressionContext.forFunction(new FunctionContext(FunctionContext.Type.TRANSFORM, "div",
                Arrays.asList(ExpressionContext.forIdentifier("bar"), ExpressionContext.forIdentifier("foo")))), true,
                "10", true, "20")));
        assertEquals(orFilter.getChildren().get(1), new FilterContext(FilterContext.Type.PREDICATE, null,
            new TextMatchPredicate(ExpressionContext.forIdentifier("foobar"), "potato")));
        assertEquals(filter.toString(),
            "(foo IN RANGE (15,*) AND (div(bar,foo) IN RANGE [10,20] OR foobar TEXT_MATCH 'potato'))");
        assertNull(queryContext.getGroupByExpressions());
        assertNull(queryContext.getOrderByExpressions());
        assertNull(queryContext.getHavingFilter());
        assertEquals(queryContext.getLimit(), 10);
        assertEquals(queryContext.getOffset(), 0);
        assertEquals(QueryContextUtils.getAllColumns(queryContext),
            new HashSet<>(Arrays.asList("foo", "bar", "foobar")));
        assertFalse(QueryContextUtils.isAggregationQuery(queryContext));
      }
    }

    // Alias (only supported in SQL format)
    // NOTE: All the references to the alias should already be converted to the original expressions.
    {
      String sqlQuery =
          "SELECT SUM(foo) AS a, bar AS b FROM testTable WHERE b IN (5, 10, 15) GROUP BY b ORDER BY a DESC";
      QueryContext queryContext =
          BrokerRequestToQueryContextConverter.convert(SQL_COMPILER.compileToBrokerRequest(sqlQuery));
      List<ExpressionContext> selectExpressions = queryContext.getSelectExpressions();
      assertEquals(selectExpressions.size(), 1);
      assertEquals(selectExpressions.get(0), ExpressionContext.forFunction(
          new FunctionContext(FunctionContext.Type.AGGREGATION, "sum",
              Collections.singletonList(ExpressionContext.forIdentifier("foo")))));
      assertEquals(selectExpressions.get(0).toString(), "sum(foo)");
      assertEquals(queryContext.getAlias(ExpressionContext.forFunction(
          new FunctionContext(FunctionContext.Type.AGGREGATION, "sum",
              Collections.singletonList(ExpressionContext.forIdentifier("foo"))))), "a");
      assertEquals(queryContext.getAlias(ExpressionContext.forIdentifier("bar")), "b");
      FilterContext filter = queryContext.getFilter();
      assertNotNull(filter);
      assertEquals(filter, new FilterContext(FilterContext.Type.PREDICATE, null,
          new InPredicate(ExpressionContext.forIdentifier("bar"), Arrays.asList("5", "10", "15"))));
      assertEquals(filter.toString(), "bar IN ('5','10','15')");
      List<ExpressionContext> groupByExpressions = queryContext.getGroupByExpressions();
      assertNotNull(groupByExpressions);
      assertEquals(groupByExpressions.size(), 1);
      assertEquals(groupByExpressions.get(0), ExpressionContext.forIdentifier("bar"));
      assertEquals(groupByExpressions.get(0).toString(), "bar");
      List<OrderByExpressionContext> orderByExpressions = queryContext.getOrderByExpressions();
      assertNotNull(orderByExpressions);
      assertEquals(orderByExpressions.size(), 1);
      assertEquals(orderByExpressions.get(0), new OrderByExpressionContext(ExpressionContext.forFunction(
          new FunctionContext(FunctionContext.Type.AGGREGATION, "sum",
              Collections.singletonList(ExpressionContext.forIdentifier("foo")))), false));
      assertEquals(orderByExpressions.get(0).toString(), "sum(foo) DESC");
      assertNull(queryContext.getHavingFilter());
      assertEquals(queryContext.getLimit(), 10);
      assertEquals(queryContext.getOffset(), 0);
      assertEquals(QueryContextUtils.getAllColumns(queryContext), new HashSet<>(Arrays.asList("foo", "bar")));
      assertTrue(QueryContextUtils.isAggregationQuery(queryContext));
    }

    // TODO: Uncomment the following part after CalciteSqlParser supports Having clause
    // Having (only supported in SQL format)
//    {
//      String sqlQuery = "SELECT SUM(foo), bar FROM testTable GROUP BY bar HAVING SUM(foo) IN (5, 10, 15)";
//      QueryContext queryContext =
//          BrokerRequestToQueryContextConverter.convertToQueryContext(SQL_COMPILER.compileToBrokerRequest(sqlQuery));
//      List<ExpressionContext> selectExpressions = queryContext.getSelectExpressions();
//      assertEquals(selectExpressions.size(), 1);
//      assertEquals(selectExpressions.get(0), ExpressionContext.forFunction(
//          new FunctionContext(FunctionContext.Type.AGGREGATION, "sum",
//              Collections.singletonList(ExpressionContext.forIdentifier("foo")))));
//      assertEquals(selectExpressions.get(0).toString(), "sum(foo)");
//      assertNull(queryContext.getFilter());
//      List<ExpressionContext> groupByExpressions = queryContext.getGroupByExpressions();
//      assertNotNull(groupByExpressions);
//      assertEquals(groupByExpressions.size(), 1);
//      assertEquals(groupByExpressions.get(0), ExpressionContext.forIdentifier("bar"));
//      assertEquals(groupByExpressions.get(0).toString(), "bar");
//      assertNull(queryContext.getOrderByExpressions());
//      FilterContext havingFilter = queryContext.getHavingFilter();
//      assertNotNull(havingFilter);
//      assertEquals(havingFilter, new FilterContext(FilterContext.Type.PREDICATE, null, new InPredicate(ExpressionContext
//          .forFunction(new FunctionContext(FunctionContext.Type.AGGREGATION, "sum",
//              Collections.singletonList(ExpressionContext.forIdentifier("foo")))), Arrays.asList("5", "10", "15"))));
//      assertEquals(havingFilter.toString(), "sum(foo) IN ('5','10','15')");
//      assertEquals(queryContext.getLimit(), 10);
//      assertEquals(queryContext.getOffset(), 0);
//      assertEquals(QueryContextUtils.getAllColumns(queryContext), new HashSet<>(Arrays.asList("foo", "bar")));
//      assertTrue(QueryContextUtils.isAggregationQuery(queryContext));
//    }

    // DistinctCountThetaSketch (string literal and escape quote)
    {
      String query =
          "SELECT DISTINCTCOUNTTHETASKETCH(foo, 'nominalEntries=1000', 'bar=''a''', 'bar=''b''', 'bar=''a'' AND bar=''b''') FROM testTable WHERE bar IN ('a', 'b')";
      QueryContext[] queryContexts = getQueryContexts(query, query);
      for (QueryContext queryContext : queryContexts) {
        FunctionContext function = queryContext.getSelectExpressions().get(0).getFunction();
        assertEquals(function.getType(), FunctionContext.Type.AGGREGATION);
        assertEquals(function.getFunctionName(), "distinctcountthetasketch");
        List<ExpressionContext> arguments = function.getArguments();
        assertEquals(arguments.get(0), ExpressionContext.forIdentifier("foo"));
        assertEquals(arguments.get(1), ExpressionContext.forLiteral("nominalEntries=1000"));
        assertEquals(arguments.get(2), ExpressionContext.forLiteral("bar='a'"));
        assertEquals(arguments.get(3), ExpressionContext.forLiteral("bar='b'"));
        assertEquals(arguments.get(4), ExpressionContext.forLiteral("bar='a' AND bar='b'"));
      }
    }

    // Legacy PQL behaviors
    // Aggregation group-by with only TOP
    {
      String query = "SELECT COUNT(*) FROM testTable GROUP BY foo TOP 50";
      QueryContext queryContext =
          BrokerRequestToQueryContextConverter.convert(PQL_COMPILER.compileToBrokerRequest(query));
      assertEquals(queryContext.getLimit(), 50);
    }
    // Aggregation group-by with both LIMIT and TOP
    {
      String query = "SELECT COUNT(*) FROM testTable GROUP BY foo LIMIT 0 TOP 50";
      QueryContext queryContext =
          BrokerRequestToQueryContextConverter.convert(PQL_COMPILER.compileToBrokerRequest(query));
      assertEquals(queryContext.getLimit(), 50);
    }
    // Mixed column, aggregation and transform in select expressions
    {
      String query = "SELECT foo, ADD(foo, bar), MAX(foo), SUM(bar) FROM testTable";
      QueryContext[] queryContexts = getQueryContexts(query, query);
      for (QueryContext queryContext : queryContexts) {
        List<ExpressionContext> selectExpressions = queryContext.getSelectExpressions();
        assertEquals(selectExpressions.size(), 2);
        assertEquals(selectExpressions.get(0), ExpressionContext.forFunction(
            new FunctionContext(FunctionContext.Type.AGGREGATION, "max",
                Collections.singletonList(ExpressionContext.forIdentifier("foo")))));
        assertEquals(selectExpressions.get(0).toString(), "max(foo)");
        assertEquals(selectExpressions.get(1), ExpressionContext.forFunction(
            new FunctionContext(FunctionContext.Type.AGGREGATION, "sum",
                Collections.singletonList(ExpressionContext.forIdentifier("bar")))));
        assertEquals(selectExpressions.get(1).toString(), "sum(bar)");
        assertTrue(QueryContextUtils.isAggregationQuery(queryContext));
      }
    }
    // Use string literal as identifier for aggregation
    {
      String query = "SELECT SUM('foo') FROM testTable";
      QueryContext queryContext =
          BrokerRequestToQueryContextConverter.convert(PQL_COMPILER.compileToBrokerRequest(query));
      assertEquals(queryContext.getSelectExpressions().get(0), ExpressionContext.forFunction(
          new FunctionContext(FunctionContext.Type.AGGREGATION, "sum",
              Collections.singletonList(ExpressionContext.forIdentifier("foo")))));
      assertEquals(queryContext.getSelectExpressions().get(0).toString(), "sum(foo)");
      assertTrue(QueryContextUtils.isAggregationQuery(queryContext));
    }
  }

  private QueryContext[] getQueryContexts(String pqlQuery, String sqlQuery) {
    return new QueryContext[]{BrokerRequestToQueryContextConverter.convert(
        PQL_COMPILER.compileToBrokerRequest(pqlQuery)), BrokerRequestToQueryContextConverter.convert(
        SQL_COMPILER.compileToBrokerRequest(sqlQuery))};
  }

  @Test
  public void testPqlAndSqlCompatible()
      throws Exception {
    ClassLoader classLoader = getClass().getClassLoader();
    InputStream pqlInputStream = classLoader.getResourceAsStream("pql_queries.list");
    assertNotNull(pqlInputStream);
    InputStream sqlInputStream = classLoader.getResourceAsStream("sql_queries.list");
    assertNotNull(sqlInputStream);
    try (BufferedReader pqlReader = new BufferedReader(new InputStreamReader(pqlInputStream));
        BufferedReader sqlReader = new BufferedReader(new InputStreamReader(sqlInputStream))) {
      String pqlQuery;
      while ((pqlQuery = pqlReader.readLine()) != null) {
        String sqlQuery = sqlReader.readLine();
        assertNotNull(sqlQuery);
        QueryContext pqlQueryContext =
            BrokerRequestToQueryContextConverter.convert(PQL_COMPILER.compileToBrokerRequest(pqlQuery));
        QueryContext sqlQueryContext =
            BrokerRequestToQueryContextConverter.convert(SQL_COMPILER.compileToBrokerRequest(sqlQuery));
        // NOTE: Do not compare alias and HAVING clause because they are not supported in PQL.
        // NOTE: Do not compare filter (WHERE clause) because:
        //       1. It is always generated from the BrokerRequest so there is no compatibility issue.
        //       2. PQL and SQL compiler have different behavior on AND/OR handling, and require BrokerRequestOptimizer
        //          to fix the discrepancy. Check PqlAndCalciteSqlCompatibilityTest for more details.
        assertEquals(pqlQueryContext.getSelectExpressions(), sqlQueryContext.getSelectExpressions());
        assertEquals(pqlQueryContext.getGroupByExpressions(), sqlQueryContext.getGroupByExpressions());
        assertEquals(pqlQueryContext.getOrderByExpressions(), sqlQueryContext.getOrderByExpressions());
        assertEquals(pqlQueryContext.getLimit(), sqlQueryContext.getLimit());
        assertEquals(pqlQueryContext.getOffset(), sqlQueryContext.getOffset());
      }
      assertNull(sqlReader.readLine());
    }
  }
}
