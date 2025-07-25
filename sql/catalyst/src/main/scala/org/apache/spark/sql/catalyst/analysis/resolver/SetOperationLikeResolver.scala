/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.analysis.resolver

import java.util.HashSet

import org.apache.spark.SparkException
import org.apache.spark.sql.catalyst.analysis.{AnsiTypeCoercion, TypeCoercion, TypeCoercionBase}
import org.apache.spark.sql.catalyst.expressions.{Attribute, Cast, ExprId}
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.errors.QueryCompilationErrors
import org.apache.spark.sql.types.{DataType, MetadataBuilder}

/**
 * The [[SetOperationLikeResolver]] performs [[Union]], [[Intersect]] or [[Except]] operator
 * resolution. These operators have 2+ children. Resolution involves checking and normalizing child
 * output attributes (data types and nullability).
 */
class SetOperationLikeResolver(resolver: Resolver, expressionResolver: ExpressionResolver)
    extends TreeNodeResolver[LogicalPlan, LogicalPlan] {
  private val expressionIdAssigner = expressionResolver.getExpressionIdAssigner
  private val autoGeneratedAliasProvider = new AutoGeneratedAliasProvider(expressionIdAssigner)
  private val scopes = resolver.getNameScopes
  private val cteRegistry = resolver.getCteRegistry

  /**
   * Resolve the [[Union]], [[Intersect]] or [[Except]] operators:
   *  - Resolve each child in the context of a) New [[NameScope]] b) New [[ExpressionIdAssigner]]
   *    mapping c) CTE scope. Collect child outputs to coerce them later.
   *  - Create a new mapping in [[ExpressionIdAssigner]] for the current operator. We only need the
   *    left child mapping, because that's the only child whose expression IDs get propagated
   *    upwards for [[Union]], [[Intersect]] or [[Except]]. This is an optimization.
   *  - Compute widened data types for child output attributes using
   *    [[getTypeCoercion.findWiderTypeForTwo]] or throw "INCOMPATIBLE_COLUMN_TYPE" if coercion
   *    fails.
   *  - Perform individual output deduplication to handle the distinct union case described in
   *    [[performIndividualOutputExpressionIdDeduplication]] scaladoc.
   *  - Validate that child outputs have same length or throw "NUM_COLUMNS_MISMATCH" otherwise.
   *  - Add [[Project]] with [[Cast]] on children needing attribute data type widening.
   *  - Assert that coerced outputs don't have conflicting expression IDs.
   *  - Merge transformed outputs using a separate logic for each operator type.
   *  - Store merged output in current [[NameScope]].
   *  - Validate that the operator doesn't have unsupported data types in the output
   *  - Create a new mapping in [[ExpressionIdAssigner]] using the coerced and validated outputs.
   *  - Return the resolved operator with new children optionally wrapped in [[WithCTE]]. See
   *    [[CteScope]] scaladoc for more info.
   */
  override def resolve(unresolvedOperator: LogicalPlan): LogicalPlan = {
    val (resolvedChildren, childScopes) = resolveChildren(unresolvedOperator)

    expressionIdAssigner.createMappingFromChildMappings(
      newOutputIds = childScopes.head.getOutputIds
    )

    val childOutputs = childScopes.map(_.output)

    val (coercedChildren, coercedChildOutputs) =
      if (needToCoerceChildOutputs(childOutputs, unresolvedOperator)) {
        coerceChildOutputs(
          resolvedChildren,
          childOutputs,
          validateAndDeduceTypes(unresolvedOperator, childOutputs)
        )
      } else {
        (resolvedChildren, childOutputs)
      }

    val (newChildren, newChildOutputs) =
      performIndividualOutputExpressionIdDeduplication(
        coercedChildren,
        coercedChildOutputs,
        unresolvedOperator
      )

    ExpressionIdAssigner.assertOutputsHaveNoConflictingExpressionIds(newChildOutputs)

    val output = mergeChildOutputs(unresolvedOperator, newChildOutputs)
    scopes.overwriteCurrent(output = Some(output), hiddenOutput = Some(output))

    OperatorWithUncomparableTypeValidator.validate(unresolvedOperator, output)

    val resolvedOperator = unresolvedOperator.withNewChildren(newChildren)

    cteRegistry.currentScope.tryPutWithCTE(
      unresolvedOperator = unresolvedOperator,
      resolvedOperator = resolvedOperator
    )
  }

  /**
   * Resolve `unresolvedSetOperationLike`'s children in the context of new [[NameScope]],
   * [[ExpressionIdAssigner]] mapping and [[CteScope]].
   *
   * [[ExpressionIdAssigner]] child mapping is collected just or the left child, because that's
   * the only child whose expression IDs get propagated upwards through [[Union]], [[Intersect]] or
   * [[Except]]. This is an optimization to avoid fast-growing expression ID mappings.
   */
  private def resolveChildren(
      unresolvedOperator: LogicalPlan): (Seq[LogicalPlan], Seq[NameScope]) = {
    unresolvedOperator.children.zipWithIndex.map {
      case (unresolvedChild, childIndex) =>
        expressionIdAssigner.pushMapping()
        scopes.pushScope()
        cteRegistry.pushScopeForMultiChildOperator(
          unresolvedOperator = unresolvedOperator,
          unresolvedChild = unresolvedChild
        )

        try {
          val resolvedChild = resolver.resolve(unresolvedChild)
          (resolvedChild, scopes.current)
        } finally {
          cteRegistry.popScope()
          scopes.popScope()
          expressionIdAssigner.popMapping(collectChildMapping = childIndex == 0)
        }
    }.unzip
  }

  /**
   * Deduplicate expression IDs at the scope of each individual child output. This is necessary to
   * handle the following case:
   *
   * {{{
   * -- The correct answer is (1, 1), (1, 2). Without deduplication it would be (1, 1), because
   * -- aggregation would be done only based on the first column.
   * SELECT
   *   a, a
   * FROM
   *   VALUES (1, 1), (1, 2) AS t1 (a, b)
   * UNION
   * SELECT
   *  a, b
   * FROM
   *   VALUES (1, 1), (1, 2) AS t2 (a, b)
   * }}}
   *
   * Putting [[Alias]] introduces a new expression ID for the attribute duplicates in the output. We
   * also add `__is_duplicate` metadata so that [[AttributeSeq.getCandidatesForResolution]] doesn't
   * produce conflicting candidates when resolving names in the upper [[Project]] - this is
   * technically still the same attribute.
   *
   * Probably there's a better way to do that, but we want to stay compatible with the fixed-point
   * [[Analyzer]].
   *
   * See SPARK-37865 for more details.
   */
  private def performIndividualOutputExpressionIdDeduplication(
      children: Seq[LogicalPlan],
      childOutputs: Seq[Seq[Attribute]],
      unresolvedOperator: LogicalPlan
  ): (Seq[LogicalPlan], Seq[Seq[Attribute]]) = {
    unresolvedOperator match {
      case _: Union => doPerformIndividualOutputExpressionIdDeduplication(children, childOutputs)
      case _ => (children, childOutputs)
    }
  }

  private def doPerformIndividualOutputExpressionIdDeduplication(
      children: Seq[LogicalPlan],
      childOutputs: Seq[Seq[Attribute]]
  ): (Seq[LogicalPlan], Seq[Seq[Attribute]]) = {
    children
      .zip(childOutputs)
      .map {
        case (child, childOutput) =>
          var outputChanged = false

          val expressionIds = new HashSet[ExprId]
          val newOutput = childOutput.map { attribute =>
            if (expressionIds.contains(attribute.exprId)) {
              outputChanged = true

              val newMetadata = new MetadataBuilder()
                .withMetadata(attribute.metadata)
                .putNull("__is_duplicate")
                .build()
              autoGeneratedAliasProvider.newAlias(
                child = attribute,
                name = Some(attribute.name),
                explicitMetadata = Some(newMetadata)
              )
            } else {
              expressionIds.add(attribute.exprId)

              attribute
            }
          }

          if (outputChanged) {
            (Project(projectList = newOutput, child = child), newOutput.map(_.toAttribute))
          } else {
            (child, childOutput)
          }
      }
      .unzip
  }

  /**
   * Check if we need to coerce child output attributes to wider types. We need to do this if:
   * - Output length differs between children. We will throw an appropriate error later during type
   *   coercion with more diagnostics.
   * - Output data types differ between children. We don't care about nullability for type coercion,
   *   it will be correctly assigned later by [[SetOperationLikeResolver.mergeChildOutputs]].
   */
  private def needToCoerceChildOutputs(
      childOutputs: Seq[Seq[Attribute]],
      unresolvedOperator: LogicalPlan): Boolean = {
    val firstChildOutput = childOutputs.head
    childOutputs.tail.exists { childOutput =>
      childOutput.length != firstChildOutput.length ||
      childOutput.zip(firstChildOutput).exists {
        case (lhsAttribute, rhsAttribute) =>
          !areDataTypesCompatibleInTheContextOfOperator(
            unresolvedOperator,
            lhsAttribute.dataType,
            rhsAttribute.dataType
          )
      }
    }
  }

  /**
   * This method returns whether types are compatible in the context of the specified operator.
   *
   * In fixed-point we only use [[DataType.equalsStructurally]] for [[Union]] type coercion. For
   * [[Except]] and [[Intersect]] we use [[DataTypeUtils.sameType]]. This method ensures we perform
   * the check for whether coercion is needed in the compatible way to the fixed-point.
   */
  private def areDataTypesCompatibleInTheContextOfOperator(
      unresolvedPlan: LogicalPlan,
      lhs: DataType,
      rhs: DataType): Boolean = {
    unresolvedPlan match {
      case _: Union => DataType.equalsStructurally(lhs, rhs, ignoreNullability = true)
      case _: Except | _: Intersect => DataTypeUtils.sameType(lhs, rhs)
      case other =>
        throw SparkException.internalError(
          s"Set operation resolver should not be used for ${other.nodeName}"
        )
    }
  }

  /**
   * Returns a sequence of data types representing the widened data types for each column:
   *  - Validates that the number of columns in each child of the set operator is equal.
   *  - Validates that the data types of columns can be widened to a common type.
   *  - Deduces the widened data types for each column.
   */
  private def validateAndDeduceTypes(
      unresolvedOperator: LogicalPlan,
      childOutputs: Seq[Seq[Attribute]]): Seq[DataType] = {
    val childDataTypes = childOutputs.map(attributes => attributes.map(attr => attr.dataType))

    val expectedNumColumns = childDataTypes.head.length

    childDataTypes.tail.zipWithIndex.foldLeft(childDataTypes.head) {
      case (widenedTypes, (childColumnTypes, childIndex)) =>
        if (childColumnTypes.length != expectedNumColumns) {
          throwNumColumnsMismatch(
            expectedNumColumns = expectedNumColumns,
            childColumnTypes = childColumnTypes,
            columnIndex = childIndex,
            unresolvedOperator = unresolvedOperator
          )
        }

        widenedTypes.zip(childColumnTypes).zipWithIndex.map {
          case ((widenedColumnType, columnTypeForCurrentRow), columnIndex) =>
            getTypeCoercion
              .findWiderTypeForTwo(widenedColumnType, columnTypeForCurrentRow)
              .getOrElse {
                throwIncompatibleColumnTypeError(
                  unresolvedOperator = unresolvedOperator,
                  columnIndex = columnIndex,
                  childIndex = childIndex,
                  widenedColumnType = widenedColumnType,
                  columnTypeForCurrentRow = columnTypeForCurrentRow
                )
              }
        }
    }
  }

  /**
   * Coerce `childOutputs` to the previously calculated `widenedTypes`. If the data types for
   * child output has changed, we have to add a [[Project]] operator with a [[Cast]] to the new
   * type.
   */
  private def coerceChildOutputs(
      children: Seq[LogicalPlan],
      childOutputs: Seq[Seq[Attribute]],
      widenedTypes: Seq[DataType]): (Seq[LogicalPlan], Seq[Seq[Attribute]]) = {
    val sessionLocalTimeZone = conf.sessionLocalTimeZone

    children
      .zip(childOutputs)
      .map {
        case (child, output) =>
          var outputChanged = false
          val newExpressions = output.zip(widenedTypes).map {
            case (attribute, widenedType) =>
              /**
               * Probably more correct way to compare data types here would be to call
               * [[DataType.equalsStructurally]] but fixed-point [[Analyzer]] rule
               * [[WidenSetOperationTypes]] uses `==`, so we do the same to stay compatible.
               */
              if (attribute.dataType == widenedType) {
                attribute
              } else {
                outputChanged = true
                autoGeneratedAliasProvider.newAlias(
                  child = Cast(attribute, widenedType, Some(sessionLocalTimeZone)),
                  name = Some(attribute.name)
                )
              }
          }

          if (outputChanged) {
            (Project(newExpressions, child), newExpressions.map(_.toAttribute))
          } else {
            (child, output)
          }
      }
      .unzip
  }

  /**
   * Helper method to call appropriate object method [[mergeChildOutputs]] for each operator.
   */
  private def mergeChildOutputs(
      unresolvedPlan: LogicalPlan,
      childOutputs: Seq[Seq[Attribute]]): Seq[Attribute] = {
    unresolvedPlan match {
      case _: Union => Union.mergeChildOutputs(childOutputs)
      case _: Except => Except.mergeChildOutputs(childOutputs)
      case _: Intersect => Intersect.mergeChildOutputs(childOutputs)
      case other =>
        throw SparkException.internalError(
          s"Set operation resolver should not be used for ${other.nodeName}"
        )
    }
  }

  private def getTypeCoercion: TypeCoercionBase = {
    if (conf.ansiEnabled) {
      AnsiTypeCoercion
    } else {
      TypeCoercion
    }
  }

  private def throwNumColumnsMismatch(
      expectedNumColumns: Int,
      childColumnTypes: Seq[DataType],
      columnIndex: Int,
      unresolvedOperator: LogicalPlan): Unit = {
    throw QueryCompilationErrors.numColumnsMismatch(
      operator = unresolvedOperator.nodeName.toUpperCase(),
      firstNumColumns = expectedNumColumns,
      invalidOrdinalNum = columnIndex + 1,
      invalidNumColumns = childColumnTypes.length,
      origin = unresolvedOperator.origin
    )
  }

  private def throwIncompatibleColumnTypeError(
      unresolvedOperator: LogicalPlan,
      columnIndex: Int,
      childIndex: Int,
      widenedColumnType: DataType,
      columnTypeForCurrentRow: DataType): Nothing = {
    throw QueryCompilationErrors.incompatibleColumnTypeError(
      operator = unresolvedOperator.nodeName.toUpperCase(),
      columnOrdinalNumber = columnIndex,
      tableOrdinalNumber = childIndex + 1,
      dataType1 = columnTypeForCurrentRow,
      dataType2 = widenedColumnType,
      hint = "",
      origin = unresolvedOperator.origin
    )
  }
}
