package com.hedera.services.grpc.marshalling;

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

import com.google.common.base.MoreObjects;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.txns.customfees.CustomFeeSchedules;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.swirlds.common.SwirldDualState;
import com.swirlds.common.SwirldTransaction;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import java.time.Instant;
import java.util.List;

/**
 * Encapsulates the validity of a CryptoTransfer transaction, given a choice of two parameters: the maximum
 * allowed number of ℏ adjustments, and the maximum allowed number of token unit adjustments.
 *
 * Note that we need to remember these two parameters in order to safely reuse this validation across "span"
 * between the {@link com.hedera.services.ServicesState#expandSignatures(SwirldTransaction)} and
 * {@link com.hedera.services.ServicesState#handleTransaction(long, boolean, Instant, Instant,
 * SwirldTransaction, SwirldDualState)} callbacks.
 *
 * This is because either parameter <i>could</i> change due to an update of file 0.0.121 between the two
 * callbacks. So we have to double-check that neither <i>did</i> change before reusing the work captured by this
 * validation result.
 */
public class ImpliedTransfersMeta {
	private final ResponseCodeEnum code;
	private final ValidationProps validationProps;
	private final List<CustomFeeMeta> customFeeMeta;

	public ImpliedTransfersMeta(
			ValidationProps validationProps,
			ResponseCodeEnum code,
			List<CustomFeeMeta> customFeeMeta
	) {
		this.code = code;
		this.validationProps = validationProps;
		this.customFeeMeta = customFeeMeta;
	}

	public List<CustomFeeMeta> getCustomFeeMeta() {
		return customFeeMeta;
	}

	public boolean wasDerivedFrom(GlobalDynamicProperties dynamicProperties, CustomFeeSchedules customFeeSchedules) {
		final var validationParamsMatch =
				(validationProps.maxHbarAdjusts == dynamicProperties.maxTransferListSize()) &&
						(validationProps.maxTokenAdjusts == dynamicProperties.maxTokenTransferListSize()) &&
						(validationProps.maxOwnershipChanges == dynamicProperties.maxNftTransfersLen()) &&
						(validationProps.maxXferBalanceChanges == dynamicProperties.maxXferBalanceChanges()) &&
						(validationProps.maxNestedCustomFees == dynamicProperties.maxCustomFeeDepth()) &&
						(validationProps.areNftsEnabled == dynamicProperties.areNftsEnabled());
		if (!validationParamsMatch) {
			return false;
		}
		for (var meta : customFeeMeta) {
			final var tokenId = meta.getTokenId();
			var newCustomMeta = customFeeSchedules.lookupMetaFor(tokenId);
			if (!meta.equals(newCustomMeta)) {
				return false;
			}
		}
		return true;
	}

	public ResponseCodeEnum code() {
		return code;
	}

	@Override
	public boolean equals(Object obj) {
		return EqualsBuilder.reflectionEquals(this, obj);
	}

	@Override
	public int hashCode() {
		return HashCodeBuilder.reflectionHashCode(this);
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(ImpliedTransfersMeta.class)
				.add("code", code)
				.add("maxExplicitHbarAdjusts", validationProps.maxHbarAdjusts)
				.add("maxExplicitTokenAdjusts", validationProps.maxTokenAdjusts)
				.add("maxExplicitOwnershipChanges", validationProps.maxOwnershipChanges)
				.add("maxNestedCustomFees", validationProps.maxNestedCustomFees)
				.add("maxXferBalanceChanges", validationProps.maxXferBalanceChanges)
				.add("areNftsEnabled", validationProps.areNftsEnabled)
				.add("tokenFeeSchedules", customFeeMeta)
				.toString();
	}

	public static class ValidationProps {
		private final int maxHbarAdjusts;
		private final int maxTokenAdjusts;
		private final int maxOwnershipChanges;
		private final int maxNestedCustomFees;
		private final int maxXferBalanceChanges;
		private final boolean areNftsEnabled;

		public ValidationProps(
				int maxHbarAdjusts,
				int maxTokenAdjusts,
				int maxOwnershipChanges,
				int maxNestedCustomFees,
				int maxXferBalanceChanges,
				boolean areNftsEnabled
		) {
			this.maxHbarAdjusts = maxHbarAdjusts;
			this.maxTokenAdjusts = maxTokenAdjusts;
			this.maxOwnershipChanges = maxOwnershipChanges;
			this.maxNestedCustomFees = maxNestedCustomFees;
			this.maxXferBalanceChanges = maxXferBalanceChanges;
			this.areNftsEnabled = areNftsEnabled;
		}

		public int getMaxHbarAdjusts() {
			return maxHbarAdjusts;
		}

		public int getMaxTokenAdjusts() {
			return maxTokenAdjusts;
		}

		public int getMaxOwnershipChanges() {
			return maxOwnershipChanges;
		}

		public int getMaxNestedCustomFees() {
			return maxNestedCustomFees;
		}

		public int getMaxXferBalanceChanges() {
			return maxXferBalanceChanges;
		}

		public boolean areNftsEnabled() {
			return areNftsEnabled;
                }

		@Override
		public boolean equals(Object obj) {
			return EqualsBuilder.reflectionEquals(this, obj);
		}

		@Override
		public int hashCode() {
			return HashCodeBuilder.reflectionHashCode(this);
		}
	}
}
