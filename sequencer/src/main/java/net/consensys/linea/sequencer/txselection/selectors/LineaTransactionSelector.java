/*
 * Copyright Consensys Software Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package net.consensys.linea.sequencer.txselection.selectors;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import net.consensys.linea.config.LineaProfitabilityConfiguration;
import net.consensys.linea.config.LineaTracerConfiguration;
import net.consensys.linea.config.LineaTransactionSelectorConfiguration;
import net.consensys.linea.jsonrpc.JsonRpcManager;
import net.consensys.linea.jsonrpc.JsonRpcRequestBuilder;
import net.consensys.linea.metrics.TransactionProfitabilityMetrics;
import net.consensys.linea.plugins.config.LineaL1L2BridgeSharedConfiguration;
import org.hyperledger.besu.datatypes.PendingTransaction;
import org.hyperledger.besu.plugin.data.TransactionProcessingResult;
import org.hyperledger.besu.plugin.data.TransactionSelectionResult;
import org.hyperledger.besu.plugin.services.BlockchainService;
import org.hyperledger.besu.plugin.services.tracer.BlockAwareOperationTracer;
import org.hyperledger.besu.plugin.services.txselection.PluginTransactionSelector;
import org.hyperledger.besu.plugin.services.txselection.TransactionEvaluationContext;

/** Class for transaction selection using a list of selectors. */
@Slf4j
public class LineaTransactionSelector implements PluginTransactionSelector {

  private TraceLineLimitTransactionSelector traceLineLimitTransactionSelector;
  private final List<PluginTransactionSelector> selectors;
  private final Optional<JsonRpcManager> rejectedTxJsonRpcManager;
  private final TransactionProfitabilityMetrics transactionProfitabilityMetrics;

  public LineaTransactionSelector(
      final BlockchainService blockchainService,
      final LineaTransactionSelectorConfiguration txSelectorConfiguration,
      final LineaL1L2BridgeSharedConfiguration l1L2BridgeConfiguration,
      final LineaProfitabilityConfiguration profitabilityConfiguration,
      final LineaTracerConfiguration tracerConfiguration,
      final Map<String, Integer> limitsMap,
      final Optional<JsonRpcManager> rejectedTxJsonRpcManager,
      final TransactionProfitabilityMetrics transactionProfitabilityMetrics) {
    this.rejectedTxJsonRpcManager = rejectedTxJsonRpcManager;
    this.transactionProfitabilityMetrics = transactionProfitabilityMetrics;
    selectors =
        createTransactionSelectors(
            blockchainService,
            txSelectorConfiguration,
            l1L2BridgeConfiguration,
            profitabilityConfiguration,
            tracerConfiguration,
            limitsMap,
            transactionProfitabilityMetrics);
  }

  /**
   * Creates a list of selectors based on Linea configuration.
   *
   * @param blockchainService Blockchain service.
   * @param txSelectorConfiguration The configuration to use.
   * @param profitabilityConfiguration The profitability configuration.
   * @param limitsMap The limits map.
   * @return A list of selectors.
   */
  private List<PluginTransactionSelector> createTransactionSelectors(
      final BlockchainService blockchainService,
      final LineaTransactionSelectorConfiguration txSelectorConfiguration,
      final LineaL1L2BridgeSharedConfiguration l1L2BridgeConfiguration,
      final LineaProfitabilityConfiguration profitabilityConfiguration,
      final LineaTracerConfiguration tracerConfiguration,
      final Map<String, Integer> limitsMap,
      final TransactionProfitabilityMetrics transactionProfitabilityMetrics) {

    traceLineLimitTransactionSelector =
        new TraceLineLimitTransactionSelector(
            blockchainService.getChainId().get(),
            limitsMap,
            txSelectorConfiguration,
            l1L2BridgeConfiguration,
            tracerConfiguration);

    return List.of(
        new MaxBlockCallDataTransactionSelector(txSelectorConfiguration.maxBlockCallDataSize()),
        new MaxBlockGasTransactionSelector(txSelectorConfiguration.maxGasPerBlock()),
        new ProfitableTransactionSelector(
            blockchainService,
            txSelectorConfiguration,
            profitabilityConfiguration,
            transactionProfitabilityMetrics),
        traceLineLimitTransactionSelector);
  }

  /**
   * Evaluates a transaction before processing using all selectors. Stops if any selector doesn't
   * select the transaction.
   *
   * @param evaluationContext The current selection context.
   * @return The first non-SELECTED result or SELECTED if all selectors select the transaction.
   */
  @Override
  public TransactionSelectionResult evaluateTransactionPreProcessing(
      final TransactionEvaluationContext<? extends PendingTransaction> evaluationContext) {
    return selectors.stream()
        .map(selector -> selector.evaluateTransactionPreProcessing(evaluationContext))
        .filter(result -> !result.equals(TransactionSelectionResult.SELECTED))
        .findFirst()
        .orElse(TransactionSelectionResult.SELECTED);
  }

  /**
   * Evaluates a transaction considering its processing result. Stops if any selector doesn't select
   * the transaction.
   *
   * @param evaluationContext The current selection context.
   * @param processingResult The result of the transaction processing.
   * @return The first non-SELECTED result or SELECTED if all selectors select the transaction.
   */
  @Override
  public TransactionSelectionResult evaluateTransactionPostProcessing(
      final TransactionEvaluationContext<? extends PendingTransaction> evaluationContext,
      final TransactionProcessingResult processingResult) {
    for (var selector : selectors) {
      TransactionSelectionResult result =
          selector.evaluateTransactionPostProcessing(evaluationContext, processingResult);
      if (!result.equals(TransactionSelectionResult.SELECTED)) {
        return result;
      }
    }
    return TransactionSelectionResult.SELECTED;
  }

  /**
   * Notifies all selectors when a transaction is selected.
   *
   * @param evaluationContext The current selection context.
   * @param processingResult The transaction processing result.
   */
  @Override
  public void onTransactionSelected(
      final TransactionEvaluationContext<? extends PendingTransaction> evaluationContext,
      final TransactionProcessingResult processingResult) {
    selectors.forEach(
        selector -> selector.onTransactionSelected(evaluationContext, processingResult));
  }

  /**
   * Notifies all selectors when a transaction is not selected.
   *
   * @param evaluationContext The current selection context.
   * @param transactionSelectionResult The reason for not selecting the transaction.
   */
  @Override
  public void onTransactionNotSelected(
      final TransactionEvaluationContext<? extends PendingTransaction> evaluationContext,
      final TransactionSelectionResult transactionSelectionResult) {
    selectors.forEach(
        selector ->
            selector.onTransactionNotSelected(evaluationContext, transactionSelectionResult));

    rejectedTxJsonRpcManager.ifPresent(
        jsonRpcManager -> {
          if (transactionSelectionResult.discard()) {
            jsonRpcManager.submitNewJsonRpcCallAsync(
                JsonRpcRequestBuilder.generateSaveRejectedTxJsonRpc(
                    jsonRpcManager.getNodeType(),
                    evaluationContext.getPendingTransaction().getTransaction(),
                    Instant.now(),
                    Optional.of(evaluationContext.getPendingBlockHeader().getNumber()),
                    transactionSelectionResult.toString(),
                    List.of()));
          }
        });
  }

  /**
   * Returns the operation tracer to be used while processing the transactions for the block.
   *
   * @return the operation tracer
   */
  @Override
  public BlockAwareOperationTracer getOperationTracer() {
    return traceLineLimitTransactionSelector.getOperationTracer();
  }
}
