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
package net.consensys.linea.sequencer.txpoolvalidation.validators;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import net.consensys.linea.bl.TransactionProfitabilityCalculator;
import net.consensys.linea.config.LineaProfitabilityConfiguration;
import net.consensys.linea.jsonrpc.JsonRpcManager;
import net.consensys.linea.jsonrpc.JsonRpcRequestBuilder;
import net.consensys.linea.metrics.TransactionProfitabilityMetrics;
import net.consensys.linea.util.LineaPricingUtils;
import org.apache.tuweni.units.bigints.UInt256s;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.plugin.data.BlockHeader;
import org.hyperledger.besu.plugin.services.BesuConfiguration;
import org.hyperledger.besu.plugin.services.BlockchainService;
import org.hyperledger.besu.plugin.services.txvalidator.PluginTransactionPoolValidator;

/**
 * Validator that checks if the upfront gas price, that the transaction is willing to pay, is
 * profitable. This check does not apply to transaction with priority and can be enabled/disabled
 * independently for transactions received via API or P2P.
 */
@Slf4j
public class ProfitabilityValidator implements PluginTransactionPoolValidator {
  final BesuConfiguration besuConfiguration;
  final BlockchainService blockchainService;
  final LineaProfitabilityConfiguration profitabilityConf;
  final TransactionProfitabilityCalculator profitabilityCalculator;
  final Optional<JsonRpcManager> rejectedTxJsonRpcManager;

  public ProfitabilityValidator(
      final BesuConfiguration besuConfiguration,
      final BlockchainService blockchainService,
      final LineaProfitabilityConfiguration profitabilityConf,
      final Optional<JsonRpcManager> rejectedTxJsonRpcManager,
      final TransactionProfitabilityMetrics profitabilityMetrics) {
    this.besuConfiguration = besuConfiguration;
    this.blockchainService = blockchainService;
    this.profitabilityConf = profitabilityConf;
    this.profitabilityCalculator =
        new TransactionProfitabilityCalculator(profitabilityConf, profitabilityMetrics);
    this.rejectedTxJsonRpcManager = rejectedTxJsonRpcManager;
  }

  @Override
  public Optional<String> validateTransaction(
      final Transaction transaction, final boolean isLocal, final boolean hasPriority) {

    if (!hasPriority
        && (isLocal && profitabilityConf.txPoolCheckApiEnabled()
            || !isLocal && profitabilityConf.txPoolCheckP2pEnabled())) {

      final Wei baseFee =
          blockchainService
              .getNextBlockBaseFee()
              .orElseThrow(() -> new RuntimeException("We only support a base fee market"));

      final BlockHeader chainHeadHeader = blockchainService.getChainHeadHeader();
      final LineaPricingUtils.PricingData pricingData =
          LineaPricingUtils.extractPricingFromExtraData(chainHeadHeader.getExtraData());

      final Optional<String> errMsg =
          profitabilityCalculator.isProfitable(
                  "Txpool",
                  transaction,
                  profitabilityConf.txPoolMinMargin(),
                  baseFee,
                  calculateUpfrontGasPrice(transaction, baseFee),
                  transaction.getGasLimit(),
                  besuConfiguration.getMinGasPrice(),
                  pricingData)
              ? Optional.empty()
              : Optional.of("Gas price too low");
      errMsg.ifPresent(s -> reportRejectedTransaction(transaction, s));
      return errMsg;
    }

    return Optional.empty();
  }

  private Wei calculateUpfrontGasPrice(final Transaction transaction, final Wei baseFee) {

    return transaction
        .getMaxFeePerGas()
        .map(Wei::fromQuantity)
        .map(
            maxFee ->
                UInt256s.min(
                    maxFee,
                    baseFee.add(Wei.fromQuantity(transaction.getMaxPriorityFeePerGas().get()))))
        .orElseGet(() -> Wei.fromQuantity(transaction.getGasPrice().get()));
  }

  private void reportRejectedTransaction(final Transaction transaction, final String reason) {
    rejectedTxJsonRpcManager.ifPresent(
        jsonRpcManager -> {
          final String jsonRpcCall =
              JsonRpcRequestBuilder.generateSaveRejectedTxJsonRpc(
                  jsonRpcManager.getNodeType(),
                  transaction,
                  Instant.now(),
                  Optional.empty(), // block number is not available
                  reason,
                  List.of());
          jsonRpcManager.submitNewJsonRpcCallAsync(jsonRpcCall);
        });
  }
}
