package com.hedera.services.context.properties;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.utils.MiscUtils;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.ServicesConfigurationList;
import com.hederahashgraph.api.proto.java.Setting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static com.hedera.services.context.properties.BootstrapProperties.GLOBAL_DYNAMIC_PROPS;
import static com.hedera.services.context.properties.BootstrapProperties.transformFor;
import static com.hedera.services.utils.EntityIdUtils.parseAccount;
import static java.util.Map.entry;

@Singleton
public class ScreenedSysFileProps implements PropertySource {
	private static final Logger log = LogManager.getLogger(ScreenedSysFileProps.class);

	static final String UNUSABLE_PROP_TPL = "Value '%s' is unusable for '%s', being ignored!";
	static final String MISPLACED_PROP_TPL = "Property '%s' is not global/dynamic, please find it a proper home!";
	static final String DEPRECATED_PROP_TPL = "Property name '%s' is deprecated, please use '%s' instead!";
	static final String UNPARSEABLE_PROP_TPL = "Value '%s' is unparseable for '%s' (%s), being ignored!";
	static final String UNTRANSFORMABLE_PROP_TPL = "Value '%s' is untransformable for deprecated '%s' (%s), being " +
			"ignored!";

	private static final Map<String, String> STANDARDIZED_NAMES = Map.ofEntries(
			entry("configAccountNum", "ledger.maxAccountNum"),
			entry("defaultContractDurationSec", "contracts.defaultLifetime"),
			entry("maxGasLimit", "contracts.maxGas"),
			entry("maxContractStateSize", "contracts.maxStorageKb"),
			entry("maxFileSize", "files.maxSizeKb"),
			entry("defaultFeeCollectionAccount", "ledger.fundingAccount"),
			entry("txReceiptTTL", "cache.records.ttl"),
			entry("exchangeRateAllowedPercentage", "rates.intradayChangeLimitPercent"),
			entry("accountBalanceExportPeriodMinutes", "balances.exportPeriodSecs"),
			entry("accountBalanceExportEnabled", "balances.exportEnabled"),
			entry("nodeAccountBalanceValidity", "balances.nodeBalanceWarningThreshold"),
			entry("accountBalanceExportDir", "balances.exportDir.path"),
			entry("transferListSizeLimit", "ledger.transfers.maxLen"),
			entry("txMaximumDuration", "hedera.transaction.maxValidDuration"),
			entry("txMinimumDuration", "hedera.transaction.minValidDuration"),
			entry("txMinimumRemaining", "hedera.transaction.minValidityBufferSecs"),
			entry("maximumAutoRenewDuration", "ledger.autoRenewPeriod.maxDuration"),
			entry("minimumAutoRenewDuration", "ledger.autoRenewPeriod.minDuration"),
			entry("localCallEstReturnBytes", "contracts.localCall.estRetBytes")
	);
	private static final Map<String, UnaryOperator<String>> STANDARDIZED_FORMATS = Map.ofEntries(
			entry("defaultFeeCollectionAccount", legacy -> "" + parseAccount(legacy).getAccountNum()),
			entry("accountBalanceExportPeriodMinutes", legacy -> "" + (60 * Integer.parseInt(legacy)))
	);
	@SuppressWarnings("unchecked")
	private static final Map<String, Predicate<Object>> VALUE_SCREENS = Map.ofEntries(
			entry(
					"rates.intradayChangeLimitPercent",
					limitPercent -> (int) limitPercent > 0),
			entry("scheduling.whitelist",
					whitelist -> ((Set<HederaFunctionality>) whitelist)
							.stream()
							.noneMatch(MiscUtils.QUERY_FUNCTIONS::contains)),
			entry(
					"tokens.maxSymbolUtf8Bytes",
					maxUtf8Bytes -> (int) maxUtf8Bytes <= MerkleToken.UPPER_BOUND_SYMBOL_UTF8_BYTES),
			entry(
					"tokens.maxTokenNameUtf8Bytes",
					maxUtf8Bytes -> (int) maxUtf8Bytes <= MerkleToken.UPPER_BOUND_TOKEN_NAME_UTF8_BYTES),
			entry(
					"ledger.transfers.maxLen",
					maxLen -> (int) maxLen >= 2),
			entry(
					"ledger.tokenTransfers.maxLen",
					maxLen -> (int) maxLen >= 2)
	);

	Map<String, Object> from121 = Collections.emptyMap();

	@Inject
	public ScreenedSysFileProps() {
	}

	void screenNew(ServicesConfigurationList rawProps) {
		from121 = rawProps.getNameValueList()
				.stream()
				.map(this::withStandardizedName)
				.filter(this::isValidGlobalDynamic)
				.filter(this::hasParseableValue)
				.filter(this::isUsableGlobalDynamic)
				.collect(Collectors.toMap(Setting::getName, this::asTypedValue, (a, b) -> b));
		var msg = "Global/dynamic properties overridden in system file are:\n  " + GLOBAL_DYNAMIC_PROPS.stream()
				.filter(from121::containsKey)
				.sorted()
				.map(name -> String.format("%s=%s", name, from121.get(name)))
				.collect(Collectors.joining("\n  "));
		log.info(msg);
	}

	private boolean isUsableGlobalDynamic(Setting prop) {
		var name = prop.getName();
		return Optional.ofNullable(VALUE_SCREENS.get(name))
				.map(screen -> {
					var usable = screen.test(asTypedValue(prop));
					if (!usable) {
						log.warn(String.format(
								UNUSABLE_PROP_TPL,
								prop.getValue(),
								name));
					}
					return usable;
				}).orElse(true);
	}

	private boolean isValidGlobalDynamic(Setting prop) {
		var name = prop.getName();
		var clearlyBelongs = GLOBAL_DYNAMIC_PROPS.contains(name);
		if (!clearlyBelongs) {
			log.warn(String.format(MISPLACED_PROP_TPL, name));
		}
		return clearlyBelongs;
	}

	private Setting withStandardizedName(Setting rawProp) {
		/* Note rawName is never null as gRPC object getters return a non-null default value for any missing field */
		final var rawName = rawProp.getName();
		final var standardizedName = STANDARDIZED_NAMES.getOrDefault(rawName, rawName);
		if (!rawName.equals(standardizedName)) {
			log.warn(String.format(DEPRECATED_PROP_TPL, rawName, standardizedName));
		}
		final var builder = rawProp.toBuilder().setName(standardizedName);
		if (STANDARDIZED_FORMATS.containsKey(rawName)) {
			try {
				builder.setValue(STANDARDIZED_FORMATS.get(rawName).apply(rawProp.getValue()));
			} catch (Exception reason) {
				log.warn(String.format(
						UNTRANSFORMABLE_PROP_TPL,
						rawProp.getValue(),
						rawName,
						reason.getClass().getSimpleName()));
				return rawProp;
			}
		}
		return builder.build();
	}

	private Object asTypedValue(Setting prop) {
		return transformFor(prop.getName()).apply(prop.getValue());
	}

	private boolean hasParseableValue(Setting prop) {
		try {
			transformFor(prop.getName()).apply(prop.getValue());
			return true;
		} catch (Exception reason) {
			log.warn(String.format(
					UNPARSEABLE_PROP_TPL,
					prop.getValue(),
					prop.getName(),
					reason.getClass().getSimpleName()));
			return false;
		}
	}

	@Override
	public boolean containsProperty(String name) {
		return from121.containsKey(name);
	}

	@Override
	public Object getProperty(String name) {
		return from121.get(name);
	}

	@Override
	public Set<String> allPropertyNames() {
		return from121.keySet();
	}
}
