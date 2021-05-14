/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.qp.strategy.optimizer;

import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.exception.query.LogicalOptimizeException;
import org.apache.iotdb.db.exception.query.PathNumOverLimitException;
import org.apache.iotdb.db.exception.runtime.SQLParserException;
import org.apache.iotdb.db.metadata.PartialPath;
import org.apache.iotdb.db.qp.constant.SQLConstant;
import org.apache.iotdb.db.qp.logical.Operator;
import org.apache.iotdb.db.qp.logical.crud.BasicFunctionOperator;
import org.apache.iotdb.db.qp.logical.crud.FilterOperator;
import org.apache.iotdb.db.qp.logical.crud.FromOperator;
import org.apache.iotdb.db.qp.logical.crud.FunctionOperator;
import org.apache.iotdb.db.qp.logical.crud.QueryOperator;
import org.apache.iotdb.db.qp.logical.crud.SelectOperator;
import org.apache.iotdb.db.query.control.QueryResourceManager;
import org.apache.iotdb.db.query.expression.ResultColumn;
import org.apache.iotdb.db.service.IoTDB;
import org.apache.iotdb.tsfile.utils.Pair;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** concat paths in select and from clause. */
public class ConcatPathOptimizer implements ILogicalOptimizer {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConcatPathOptimizer.class);

  private static final String WARNING_NO_SUFFIX_PATHS =
      "failed to concat series paths because the given query operator didn't have suffix paths";
  private static final String WARNING_NO_PREFIX_PATHS =
      "failed to concat series paths because the given query operator didn't have prefix paths";

  @Override
  public Operator transform(Operator operator, int fetchSize)
      throws LogicalOptimizeException, PathNumOverLimitException {
    QueryOperator queryOperator = (QueryOperator) operator;
    if (!optimizable(queryOperator)) {
      return queryOperator;
    }
    concatSelect(queryOperator);
    removeWildcardsInSelectPaths(queryOperator);
    concatFilter(queryOperator);
    return queryOperator;
  }

  private static boolean optimizable(QueryOperator queryOperator) {
    FromOperator from = queryOperator.getFromOperator();
    if (from == null || from.getPrefixPaths().isEmpty()) {
      LOGGER.warn(WARNING_NO_PREFIX_PATHS);
      return false;
    }

    SelectOperator select = queryOperator.getSelectOperator();
    if (select == null || select.getResultColumns().isEmpty()) {
      LOGGER.warn(WARNING_NO_SUFFIX_PATHS);
      return false;
    }

    return true;
  }

  private void concatSelect(QueryOperator queryOperator) {
    if (queryOperator.isAlignByDevice()) {
      return;
    }

    List<PartialPath> prefixPaths = queryOperator.getFromOperator().getPrefixPaths();
    List<ResultColumn> resultColumns = new ArrayList<>();
    for (ResultColumn suffixColumn : queryOperator.getSelectOperator().getResultColumns()) {
      suffixColumn.concat(prefixPaths, resultColumns);
    }
    queryOperator.getSelectOperator().setResultColumns(resultColumns);
  }

  private void removeWildcardsInSelectPaths(
      List<PartialPath> afterConcatPaths,
      List<String> afterConcatAggregations,
      List<UDFContext> afterConcatUdfList,
      SelectOperator selectOperator,
      int finalLimit,
      int finalOffset,
      int maxDeduplicatedPathNum)
      throws LogicalOptimizeException, PathNumOverLimitException {

    int maxDeduplicatedPathNum =
        QueryResourceManager.getInstance().getMaxDeduplicatedPathNum(fetchSize);
    if (queryOperator.isLastQuery()) {
      // Dataset of last query actually has only three columns, so we shouldn't limit the path num
      // while constructing logical plan
      // To avoid overflowing because logicalOptimize function may do maxDeduplicatedPathNum + 1, we
      // set it to Integer.MAX_VALUE - 1
      maxDeduplicatedPathNum = Integer.MAX_VALUE - 1;
    }

    int seriesLimit = queryOperator.getSeriesLimit();
    int seriesOffset = queryOperator.getSeriesOffset();
    boolean needRemoveStar = queryOperator.getIndexType() == null;

    int offset = finalOffset;
    int limit =
        finalLimit == 0 || maxDeduplicatedPathNum < finalLimit
            ? maxDeduplicatedPathNum + 1
            : finalLimit;
    int consumed = 0;

    List<PartialPath> newSuffixPathList = new ArrayList<>();
    List<String> newAggregations = new ArrayList<>();
    List<UDFContext> newUdfList = new ArrayList<>();

    for (int i = 0; i < afterConcatPaths.size(); i++) {
      try {
        PartialPath afterConcatPath = afterConcatPaths.get(i);

        if (afterConcatPath == null) { // udf
          UDFContext originUdf = afterConcatUdfList.get(i);
          List<PartialPath> originPaths = originUdf.getPaths();
          List<List<PartialPath>> extendedPaths = new ArrayList<>();

          boolean atLeastOneSeriesNotExisted = false;
          for (PartialPath originPath : originPaths) {
            List<PartialPath> actualPaths = removeWildcard(originPath, 0, 0).left;
            if (actualPaths.isEmpty()) {
              atLeastOneSeriesNotExisted = true;
              break;
            }
            checkAndSetTsAlias(actualPaths, originPath);
            extendedPaths.add(actualPaths);
          }
          if (atLeastOneSeriesNotExisted) {
            continue;
          }

          List<List<PartialPath>> actualPaths = new ArrayList<>();
          cartesianProduct(extendedPaths, actualPaths, 0, new ArrayList<>());

          for (List<PartialPath> actualPath : actualPaths) {
            if (offset != 0) {
              --offset;
              continue;
            } else if (limit != 0) {
              --limit;
            } else {
              break;
            }

            newSuffixPathList.add(null);
            extendListSafely(afterConcatAggregations, i, newAggregations);

            newUdfList.add(
                new UDFContext(originUdf.getName(), originUdf.getAttributes(), actualPath));
          }
        } else { // non-udf
          Pair<List<PartialPath>, Integer> pair = removeWildcard(afterConcatPath, limit, offset);
          List<PartialPath> actualPaths = pair.left;
          checkAndSetTsAlias(actualPaths, afterConcatPath);

          for (PartialPath actualPath : actualPaths) {
            newSuffixPathList.add(actualPath);
            extendListSafely(afterConcatAggregations, i, newAggregations);

            newUdfList.add(null);
          }

          consumed += pair.right;
          if (offset != 0) {
            int delta = offset - pair.right;
            offset = Math.max(delta, 0);
            if (delta < 0) {
              limit += delta;
            }
          } else {
            limit -= pair.right;
          }
        }

        if (limit == 0) {
          if (maxDeduplicatedPathNum < newSuffixPathList.size()) {
            throw new PathNumOverLimitException(maxDeduplicatedPathNum);
          }
          break;
        }
      } catch (MetadataException e) {
        throw new LogicalOptimizeException("error when remove star: " + e.getMessage());
      }
    }

    if (consumed == 0 ? finalOffset != 0 : newSuffixPathList.isEmpty()) {
      throw new LogicalOptimizeException(
          String.format(
              "The value of SOFFSET (%d) is equal to or exceeds the number of sequences (%d) that can actually be returned.",
              finalOffset, consumed));
    }
    selectOperator.setSuffixPathList(newSuffixPathList);
    selectOperator.setAggregations(newAggregations);
    selectOperator.setUdfList(newUdfList);
  }

  private void checkAndSetTsAlias(List<PartialPath> actualPaths, PartialPath originPath)
      throws LogicalOptimizeException {
    if (originPath.isTsAliasExists()) {
      if (actualPaths.size() == 1) {
        actualPaths.get(0).setTsAlias(originPath.getTsAlias());
      } else if (actualPaths.size() >= 2) {
        throw new LogicalOptimizeException(
            "alias '" + originPath.getTsAlias() + "' can only be matched with one time series");
      }
    }
  }

  private void removeWildcardsInSelectPaths(QueryOperator queryOperator) {
    if (queryOperator.getIndexType() != null) {
      return;
    }
  }

  private void concatFilter(QueryOperator queryOperator) throws LogicalOptimizeException {
    FilterOperator filter = queryOperator.getFilterOperator();
    if (filter == null) {
      return;
    }

    if (queryOperator.isAlignByDevice()) {
      // GROUP_BY_DEVICE leaves the concatFilter to PhysicalGenerator to optimize filter without
      // prefix first
      queryOperator.getFilterOperator().setPathSet(new HashSet<>());
    } else {
      queryOperator.setFilterOperator(
          concatFilter(queryOperator.getFromOperator().getPrefixPaths(), filter, new HashSet<>()));
    }
  }

  private FilterOperator concatFilter(
      List<PartialPath> fromPaths, FilterOperator operator, Set<PartialPath> filterPaths)
      throws LogicalOptimizeException {
    if (!operator.isLeaf()) {
      List<FilterOperator> newFilterList = new ArrayList<>();
      for (FilterOperator child : operator.getChildren()) {
        newFilterList.add(concatFilter(fromPaths, child, filterPaths));
      }
      operator.setChildren(newFilterList);
      return operator;
    }
    FunctionOperator functionOperator = (FunctionOperator) operator;
    PartialPath filterPath = functionOperator.getSinglePath();
    // do nothing in the cases of "where time > 5" or "where root.d1.s1 > 5"
    if (SQLConstant.isReservedPath(filterPath)
        || filterPath.getFirstNode().startsWith(SQLConstant.ROOT)) {
      filterPaths.add(filterPath);
      return operator;
    }
    List<PartialPath> concatPaths = new ArrayList<>();
    fromPaths.forEach(fromPath -> concatPaths.add(fromPath.concatPath(filterPath)));
    List<PartialPath> noStarPaths = removeWildcardsInConcatPaths(concatPaths);
    filterPaths.addAll(noStarPaths);
    if (noStarPaths.size() == 1) {
      // Transform "select s1 from root.car.* where s1 > 10" to
      // "select s1 from root.car.* where root.car.*.s1 > 10"
      functionOperator.setSinglePath(noStarPaths.get(0));
      return operator;
    } else {
      // Transform "select s1 from root.car.d1, root.car.d2 where s1 > 10" to
      // "select s1 from root.car.d1, root.car.d2 where root.car.d1.s1 > 10 and root.car.d2.s1 > 10"
      // Note that,
      // two fork tree has to be maintained while removing stars in paths for DnfFilterOptimizer
      // requirement.
      return constructBinaryFilterTreeWithAnd(noStarPaths, operator);
    }
  }

  private FilterOperator constructBinaryFilterTreeWithAnd(
      List<PartialPath> noStarPaths, FilterOperator operator) throws LogicalOptimizeException {
    FilterOperator filterBinaryTree = new FilterOperator(SQLConstant.KW_AND);
    FilterOperator currentNode = filterBinaryTree;
    for (int i = 0; i < noStarPaths.size(); i++) {
      if (i > 0 && i < noStarPaths.size() - 1) {
        FilterOperator newInnerNode = new FilterOperator(SQLConstant.KW_AND);
        currentNode.addChildOperator(newInnerNode);
        currentNode = newInnerNode;
      }
      try {
        currentNode.addChildOperator(
            new BasicFunctionOperator(
                operator.getTokenIntType(),
                noStarPaths.get(i),
                ((BasicFunctionOperator) operator).getValue()));
      } catch (SQLParserException e) {
        throw new LogicalOptimizeException(e.getMessage());
      }
    }
    return filterBinaryTree;
  }

  private List<PartialPath> removeWildcardsInConcatPaths(List<PartialPath> paths)
      throws LogicalOptimizeException {
    List<PartialPath> retPaths = new ArrayList<>();
    HashSet<PartialPath> pathSet = new HashSet<>();
    try {
      for (PartialPath path : paths) {
        List<PartialPath> all = removeWildcard(path, 0, 0).left;
        if (all.size() == 0) {
          throw new LogicalOptimizeException(
              String.format("Unknown time series %s in `where clause`", path));
        }
        for (PartialPath subPath : all) {
          if (!pathSet.contains(subPath)) {
            pathSet.add(subPath);
            retPaths.add(subPath);
          }
        }
      }
    } catch (MetadataException e) {
      throw new LogicalOptimizeException("error when remove star: " + e.getMessage());
    }
    return retPaths;
  }

  protected Pair<List<PartialPath>, Integer> removeWildcard(PartialPath path, int limit, int offset)
      throws MetadataException {
    return IoTDB.metaManager.getAllTimeseriesPathWithAlias(path, limit, offset);
  }

  public static <T> void cartesianProduct(
      List<List<T>> dimensionValue, List<List<T>> resultList, int layer, List<T> currentList) {
    if (layer < dimensionValue.size() - 1) {
      if (dimensionValue.get(layer).isEmpty()) {
        cartesianProduct(dimensionValue, resultList, layer + 1, currentList);
      } else {
        for (int i = 0; i < dimensionValue.get(layer).size(); i++) {
          List<T> list = new ArrayList<>(currentList);
          list.add(dimensionValue.get(layer).get(i));
          cartesianProduct(dimensionValue, resultList, layer + 1, list);
        }
      }
    } else if (layer == dimensionValue.size() - 1) {
      if (dimensionValue.get(layer).isEmpty()) {
        resultList.add(currentList);
      } else {
        for (int i = 0; i < dimensionValue.get(layer).size(); i++) {
          List<T> list = new ArrayList<>(currentList);
          list.add(dimensionValue.get(layer).get(i));
          resultList.add(list);
        }
      }
    }
  }
}
