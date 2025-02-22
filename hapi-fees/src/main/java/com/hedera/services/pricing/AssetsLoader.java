package com.hedera.services.pricing;

/*-
 * ‌
 * Hedera Services API Fees
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.SubType;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * Loads assets used to generate a fee schedule from JSON resources on the classpath.
 *
 * Please see the individual methods for details.
 */
class AssetsLoader {
	private static final String CAPACITIES_RESOURCE = "capacities.json";
	private static final String CONSTANT_WEIGHTS_RESOURCE = "constant-weights.json";
	private static final String CANONICAL_PRICES_RESOURCE = "canonical-prices.json";

	private Map<UsableResource, BigDecimal> cachedCapacities = null;
	private Map<HederaFunctionality, BigDecimal> cachedConstWeights = null;
	private Map<HederaFunctionality, Map<SubType, BigDecimal>> cachedCanonicalPrices = null;

	/**
	 * Loads a map that, for each supported operation, gives the fraction of that
	 * operation's total price that should come from its constant term in the fee
	 * schedule.
	 *
	 * This fraction is the "weight" of the constant term; and is currently set at
	 * 0.9 for operations that create new entities, and 0.2 for other operations.
	 *
	 * @return the "weight" of the constant term for each operation
	 * @throws IOException if the backing JSON resource cannot be loaded
	 */
	Map<HederaFunctionality, BigDecimal> loadConstWeights() throws IOException {
		if (cachedConstWeights != null) {
			return cachedConstWeights;
		}
		try (var fin = AssetsLoader.class.getClassLoader().getResourceAsStream(CONSTANT_WEIGHTS_RESOURCE)) {
			final var om = new ObjectMapper();
			final var constWeights = om.readValue(fin, Map.class);

			final Map<HederaFunctionality, BigDecimal> typedConstWeights = new EnumMap<>(HederaFunctionality.class);
			constWeights.forEach((funcName, weight) -> {
				final var function = HederaFunctionality.valueOf((String) funcName);
				final var bdWeight = BigDecimal.valueOf((Double) weight);
				typedConstWeights.put(function, bdWeight);
			});

			cachedConstWeights = typedConstWeights;
			return typedConstWeights;
		}
	}

	/**
	 * Loads a map that, for each resource type available in the network, gives the
	 * "capacity" of that resource. These capacities do not have an absolute meaning,
	 * and are just compared to infer the relative scarcity of each resource.
	 *
	 * @return the network "capacity" of each resource type
	 * @throws IOException if the backing JSON resource cannot be loaded
	 */
	Map<UsableResource, BigDecimal> loadCapacities() throws IOException {
		if (cachedCapacities != null) {
			return cachedCapacities;
		}
		try (var fin = AssetsLoader.class.getClassLoader().getResourceAsStream(CAPACITIES_RESOURCE)) {
			final var om = new ObjectMapper();
			final var capacities = om.readValue(fin, Map.class);

			final Map<UsableResource, BigDecimal> typedCapacities = new EnumMap<>(UsableResource.class);
			capacities.forEach((resourceName, amount) -> {
				final var resource = UsableResource.valueOf((String) resourceName);
				final var bdAmount = (amount instanceof Long)
						? BigDecimal.valueOf((Long) amount)
						: BigDecimal.valueOf((Integer) amount);
				typedCapacities.put(resource, bdAmount);
			});

			cachedCapacities = typedCapacities;
			return typedCapacities;
		}
	}

	/**
	 * Loads a map that, for each supported operation, gives the desired price in
	 * USD for the "base configuration" of each type of that operation. (Types are
	 * given by the values of the {@link SubType} enum; that is, DEFAULT,
	 * TOKEN_NON_FUNGIBLE_UNIQUE, and TOKEN_FUNGIBLE_COMMON.)
	 *
	 * @return the desired per-type prices, in USD
	 * @throws IOException if the backing JSON resource cannot be loaded
	 */
	Map<HederaFunctionality, Map<SubType, BigDecimal>> loadCanonicalPrices() throws IOException {
		if (cachedCanonicalPrices != null) {
			return cachedCanonicalPrices;
		}
		try (var fin = AssetsLoader.class.getClassLoader().getResourceAsStream(CANONICAL_PRICES_RESOURCE)) {
			final var om = new ObjectMapper();
			final var prices = om.readValue(fin, Map.class);

			final Map<HederaFunctionality, Map<SubType, BigDecimal>> typedPrices =
					new EnumMap<>(HederaFunctionality.class);
			prices.forEach((funName, priceMap) -> {
				final var function = HederaFunctionality.valueOf((String) funName);
				final Map<SubType, BigDecimal> scopedPrices = new EnumMap<>(SubType.class);
				((Map) priceMap).forEach((typeName, price) -> {
					final var type = SubType.valueOf((String) typeName);
					final var bdPrice = (price instanceof Double)
							? BigDecimal.valueOf((Double) price)
							: BigDecimal.valueOf((Integer) price);
					scopedPrices.put(type, bdPrice);
				});
				typedPrices.put(function, scopedPrices);
			});

			cachedCanonicalPrices = typedPrices;
			return typedPrices;
		}
	}
}
