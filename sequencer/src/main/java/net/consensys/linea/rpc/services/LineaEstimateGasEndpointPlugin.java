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

package net.consensys.linea.rpc.services;

import static net.consensys.linea.sequencer.modulelimit.ModuleLineCountValidator.createLimitModules;

import com.google.auto.service.AutoService;
import lombok.extern.slf4j.Slf4j;
import net.consensys.linea.AbstractLineaRequiredPlugin;
import net.consensys.linea.rpc.methods.LineaEstimateGas;
import org.hyperledger.besu.plugin.BesuContext;
import org.hyperledger.besu.plugin.BesuPlugin;
import org.hyperledger.besu.plugin.services.BesuConfiguration;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.RpcEndpointService;
import org.hyperledger.besu.plugin.services.TransactionSimulationService;

/** Registers RPC endpoints. This class provides RPC endpoints under the 'linea' namespace. */
@AutoService(BesuPlugin.class)
@Slf4j
public class LineaEstimateGasEndpointPlugin extends AbstractLineaRequiredPlugin {
  private BesuConfiguration besuConfiguration;
  private RpcEndpointService rpcEndpointService;
  private TransactionSimulationService transactionSimulationService;
  private LineaEstimateGas lineaEstimateGasMethod;
  private MetricsSystem metricsSystem;

  /**
   * Register the RPC service.
   *
   * @param context the BesuContext to be used.
   */
  @Override
  public void doRegister(final BesuContext context) {
    besuConfiguration =
        context
            .getService(BesuConfiguration.class)
            .orElseThrow(
                () ->
                    new RuntimeException(
                        "Failed to obtain BesuConfiguration from the BesuContext."));

    rpcEndpointService =
        context
            .getService(RpcEndpointService.class)
            .orElseThrow(
                () ->
                    new RuntimeException(
                        "Failed to obtain RpcEndpointService from the BesuContext."));

    transactionSimulationService =
        context
            .getService(TransactionSimulationService.class)
            .orElseThrow(
                () ->
                    new RuntimeException(
                        "Failed to obtain TransactionSimulatorService from the BesuContext."));

    lineaEstimateGasMethod =
        new LineaEstimateGas(
            besuConfiguration, transactionSimulationService, blockchainService, rpcEndpointService);

    this.metricsSystem =
        context
            .getService(MetricsSystem.class)
            .orElseThrow(
                () -> new RuntimeException("Failed to obtain MetricsSystem from the BesuContext."));

    rpcEndpointService.registerRPCEndpoint(
        lineaEstimateGasMethod.getNamespace(),
        lineaEstimateGasMethod.getName(),
        lineaEstimateGasMethod::execute);
  }

  @Override
  public void beforeExternalServices() {
    super.beforeExternalServices();
    lineaEstimateGasMethod.init(
        lineaRpcConfiguration(),
        transactionPoolValidatorConfiguration(),
        profitabilityConfiguration(),
        createLimitModules(tracerConfiguration()),
        l1L2BridgeSharedConfiguration(),
        metricsSystem);
  }
}
