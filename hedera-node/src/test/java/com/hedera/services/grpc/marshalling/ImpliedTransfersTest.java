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

import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.FcAssessedCustomFee;
import com.hedera.services.state.submerkle.FcCustomFee;
import com.hedera.services.store.models.Id;
import com.hedera.services.txns.customfees.CustomFeeSchedules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static com.hedera.test.utils.IdUtils.asAccount;
import static com.hedera.test.utils.IdUtils.tokenChange;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ImpliedTransfersTest {
	@Mock
	private GlobalDynamicProperties dynamicProperties;
	@Mock
	private CustomFeeSchedules customFeeSchedules;
	@Mock
	private CustomFeeSchedules newCustomFeeSchedules;

	@Test
	void impliedXfersObjectContractSanityChecks() {
		// given:
		final var twoChanges = List.of(tokenChange(
				new Id(1, 2, 3),
				asAccount("4.5.6"),
				7));
		final var oneImpliedXfers = ImpliedTransfers.invalid(props, TOKEN_WAS_DELETED);
		final var twoImpliedXfers = ImpliedTransfers.valid(
				props, twoChanges, entityCustomFees, assessedCustomFees);
		// and:
		final var oneRepr = "ImpliedTransfers{meta=ImpliedTransfersMeta{code=TOKEN_WAS_DELETED, maxExplicitHbarAdjusts=5, " +
				"maxExplicitTokenAdjusts=50, maxExplicitOwnershipChanges=12, maxNestedCustomFees=1, " +
				"maxXferBalanceChanges=20, areNftsEnabled=true, tokenFeeSchedules=[]}, changes=[], " +
				"tokenFeeSchedules=[], assessedCustomFees=[]}";
		final var twoRepr = "ImpliedTransfers{meta=ImpliedTransfersMeta{code=OK, maxExplicitHbarAdjusts=5, " +
				"maxExplicitTokenAdjusts=50, maxExplicitOwnershipChanges=12, maxNestedCustomFees=1, " +
				"maxXferBalanceChanges=20, areNftsEnabled=true, tokenFeeSchedules=[" +
				"CustomFeeMeta{tokenId=Id{shard=0, realm=0, num=123}, treasuryId=Id{shard=2, realm=3, num=4}, " +
				"customFees=[]}]}, changes=[BalanceChange{token=Id{shard=1, realm=2, num=3}, " +
				"account=Id{shard=4, realm=5, num=6}, units=7}], tokenFeeSchedules=[" +
				"CustomFeeMeta{tokenId=Id{shard=0, realm=0, num=123}, treasuryId=Id{shard=2, realm=3, num=4}, " +
				"customFees=[]}], assessedCustomFees=[FcAssessedCustomFee{token=EntityId{shard=0, realm=0, num=123}, " +
				"account=EntityId{shard=0, realm=0, num=124}, units=123, effective payer accounts=[123]}]}";

		// expect:
		assertNotEquals(oneImpliedXfers, twoImpliedXfers);
		assertNotEquals(oneImpliedXfers.hashCode(), twoImpliedXfers.hashCode());
		// and:
		assertEquals(oneRepr, oneImpliedXfers.toString());
		assertEquals(twoRepr, twoImpliedXfers.toString());
	}

	@Test
	void metaRecognizesIdenticalConditions() {
		// given:
		final var meta = new ImpliedTransfersMeta(props, OK, entityCustomFees);

		given(dynamicProperties.maxTransferListSize()).willReturn(maxExplicitHbarAdjusts);
		given(dynamicProperties.maxTokenTransferListSize()).willReturn(maxExplicitTokenAdjusts);
		given(dynamicProperties.maxNftTransfersLen()).willReturn(maxExplicitOwnershipChanges);
		given(dynamicProperties.maxXferBalanceChanges()).willReturn(maxBalanceChanges);
		given(dynamicProperties.maxCustomFeeDepth()).willReturn(maxFeeNesting);
		given(dynamicProperties.areNftsEnabled()).willReturn(areNftsEnabled);
		given(customFeeSchedules.lookupMetaFor(any())).willReturn(entityCustomFees.get(0));

		// expect:
		assertTrue(meta.wasDerivedFrom(dynamicProperties, customFeeSchedules));

		// and:
		given(newCustomFeeSchedules.lookupMetaFor(any())).willReturn(newCustomFeeMeta);

		// expect:
		assertFalse(meta.wasDerivedFrom(dynamicProperties, newCustomFeeSchedules));

		// and:
		given(dynamicProperties.maxTransferListSize()).willReturn(maxExplicitHbarAdjusts - 1);

		// expect:
		assertFalse(meta.wasDerivedFrom(dynamicProperties, customFeeSchedules));

		// and:
		given(dynamicProperties.maxTransferListSize()).willReturn(maxExplicitHbarAdjusts);
		given(dynamicProperties.maxTokenTransferListSize()).willReturn(maxExplicitTokenAdjusts + 1);

		// expect:
		assertFalse(meta.wasDerivedFrom(dynamicProperties, customFeeSchedules));

		// and:
		given(dynamicProperties.maxTokenTransferListSize()).willReturn(maxExplicitTokenAdjusts);
		given(dynamicProperties.maxNftTransfersLen()).willReturn(maxExplicitOwnershipChanges - 1);

		// expect:
		assertFalse(meta.wasDerivedFrom(dynamicProperties, customFeeSchedules));

		// and:
		given(dynamicProperties.maxNftTransfersLen()).willReturn(maxExplicitOwnershipChanges);
		given(dynamicProperties.maxXferBalanceChanges()).willReturn(maxBalanceChanges - 1);

		// expect:
		assertFalse(meta.wasDerivedFrom(dynamicProperties, customFeeSchedules));

		// and:
		given(dynamicProperties.maxXferBalanceChanges()).willReturn(maxBalanceChanges);
		given(dynamicProperties.maxCustomFeeDepth()).willReturn(maxFeeNesting + 1);

		// expect:
		assertFalse(meta.wasDerivedFrom(dynamicProperties, customFeeSchedules));

		// and:
		given(dynamicProperties.maxCustomFeeDepth()).willReturn(maxFeeNesting);
		given(dynamicProperties.areNftsEnabled()).willReturn(!areNftsEnabled);

		// expect:
		assertFalse(meta.wasDerivedFrom(dynamicProperties, customFeeSchedules));
	}

	private final int maxExplicitHbarAdjusts = 5;
	private final int maxExplicitTokenAdjusts = 50;
	private final int maxExplicitOwnershipChanges = 12;
	private final int maxFeeNesting = 1;
	private final int maxBalanceChanges = 20;
	private final boolean areNftsEnabled = true;
	private final ImpliedTransfersMeta.ValidationProps props = new ImpliedTransfersMeta.ValidationProps(
			maxExplicitHbarAdjusts,
			maxExplicitTokenAdjusts,
			maxExplicitOwnershipChanges,
			maxFeeNesting,
			maxBalanceChanges,
			areNftsEnabled);
	private final EntityId customFeeToken = new EntityId(0, 0, 123);
	private final EntityId customFeeCollector = new EntityId(0, 0, 124);
	private final Id someId = new Id(1, 2, 3);
	private final Id someTreasuryId = new Id(2, 3, 4);
	private final List<CustomFeeMeta> entityCustomFees = List.of(
			new CustomFeeMeta(customFeeToken.asId(), someTreasuryId, new ArrayList<>()));
	private final CustomFeeMeta newCustomFeeMeta = new CustomFeeMeta(
			someId,
			someTreasuryId,
			List.of(FcCustomFee.fixedFee(10L, customFeeToken, customFeeCollector)));
	private final List<FcAssessedCustomFee> assessedCustomFees = List.of(
			new FcAssessedCustomFee(customFeeCollector, customFeeToken, 123L, new long[] { 123L }));
}
