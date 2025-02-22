package com.hedera.services.state.expiry;

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
import com.hedera.services.fees.charging.NarratedCharging;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.legacy.core.jproto.TxnReceipt;
import com.hedera.services.state.EntityCreator;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.submerkle.CurrencyAdjustments;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.ExpirableTxnRecord;
import com.hedera.services.state.submerkle.FcAssessedCustomFee;
import com.hedera.services.state.submerkle.FcTokenAssociation;
import com.hedera.services.state.submerkle.NftAdjustments;
import com.hedera.services.state.submerkle.RichInstant;
import com.hedera.services.state.submerkle.TxnId;
import com.hedera.services.utils.TxnAccessor;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.swirlds.fcmap.FCMap;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static com.hedera.services.state.submerkle.EntityId.fromGrpcScheduleId;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;

@Singleton
public class ExpiringCreations implements EntityCreator {
	private HederaLedger ledger;

	private final ExpiryManager expiries;
	private final NarratedCharging narratedCharging;
	private final GlobalDynamicProperties dynamicProperties;
	private final Supplier<FCMap<MerkleEntityId, MerkleAccount>> accounts;

	@Inject
	public ExpiringCreations(
			final ExpiryManager expiries,
			final NarratedCharging narratedCharging,
			final GlobalDynamicProperties dynamicProperties,
			final Supplier<FCMap<MerkleEntityId, MerkleAccount>> accounts
	) {
		this.accounts = accounts;
		this.expiries = expiries;
		this.narratedCharging = narratedCharging;
		this.dynamicProperties = dynamicProperties;
	}

	@Override
	public void setLedger(final HederaLedger ledger) {
		this.ledger = ledger;

		narratedCharging.setLedger(ledger);
	}

	@Override
	public ExpirableTxnRecord saveExpiringRecord(
			final AccountID payer,
			final ExpirableTxnRecord expiringRecord,
			final long consensusTime,
			final long submittingMember
	) {
		final long expiry = consensusTime + dynamicProperties.cacheRecordsTtl();
		expiringRecord.setExpiry(expiry);
		expiringRecord.setSubmittingMember(submittingMember);

		final var key = MerkleEntityId.fromAccountId(payer);
		addToState(key, expiringRecord);
		expiries.trackRecordInState(payer, expiringRecord.getExpiry());

		return expiringRecord;
	}

	@Override
	public ExpirableTxnRecord.Builder buildExpiringRecord(
			final long otherNonThresholdFees,
			final byte[] hash,
			final TxnAccessor accessor,
			final Instant consensusTime,
			final TxnReceipt receipt,
			final List<TokenTransferList> explicitTokenTransfers,
			final List<FcAssessedCustomFee> customFeesCharged,
			final List<FcTokenAssociation> newTokenAssociations
	) {
		final long amount = narratedCharging.totalFeesChargedToPayer() + otherNonThresholdFees;
		final var transfersList = ledger.netTransfersInTxn();
		final var tokenTransferList = explicitTokenTransfers != null
				? explicitTokenTransfers
				: ledger.netTokenTransfersInTxn();
		final var currencyAdjustments = transfersList.getAccountAmountsCount() > 0
				? CurrencyAdjustments.fromGrpc(transfersList) : null;

		final var builder = ExpirableTxnRecord.newBuilder()
				.setReceipt(receipt)
				.setTxnHash(hash)
				.setTxnId(TxnId.fromGrpc(accessor.getTxnId()))
				.setConsensusTime(RichInstant.fromJava(consensusTime))
				.setMemo(accessor.getMemo())
				.setFee(amount)
				.setTransferList(currencyAdjustments)
				.setScheduleRef(accessor.isTriggeredTxn() ? fromGrpcScheduleId(accessor.getScheduleRef()) : null)
				.setCustomFeesCharged(customFeesCharged)
				.setNewTokenAssociations(newTokenAssociations != null ?
						newTokenAssociations : ledger.getNewTokenAssociations());

		if (!tokenTransferList.isEmpty()) {
			setTokensAndTokenAdjustments(builder, tokenTransferList);
		}

		return builder;
	}

	@Override
	public ExpirableTxnRecord.Builder buildFailedExpiringRecord(
			final TxnAccessor accessor,
			final Instant consensusTime
	) {
		final var txnId = accessor.getTxnId();

		return ExpirableTxnRecord.newBuilder()
				.setTxnId(TxnId.fromGrpc(txnId))
				.setReceipt(TxnReceipt.newBuilder().setStatus(FAIL_INVALID.name()).build())
				.setMemo(accessor.getMemo())
				.setTxnHash(accessor.getHash())
				.setConsensusTime(RichInstant.fromJava(consensusTime))
				.setScheduleRef(accessor.isTriggeredTxn() ? fromGrpcScheduleId(accessor.getScheduleRef()) : null);
	}

	private void setTokensAndTokenAdjustments(
			final ExpirableTxnRecord.Builder builder,
			final List<TokenTransferList> tokenTransferList
	) {
		final List<EntityId> tokens = new ArrayList<>();
		final List<CurrencyAdjustments> tokenAdjustments = new ArrayList<>();
		final List<NftAdjustments> nftTokenAdjustments = new ArrayList<>();
		for (final var tokenTransfer : tokenTransferList) {
			tokens.add(EntityId.fromGrpcTokenId(tokenTransfer.getToken()));
			tokenAdjustments.add(CurrencyAdjustments.fromGrpc(tokenTransfer.getTransfersList()));
			nftTokenAdjustments.add(NftAdjustments.fromGrpc(tokenTransfer.getNftTransfersList()));
		}
		builder.setTokens(tokens).setTokenAdjustments(tokenAdjustments).setNftTokenAdjustments(nftTokenAdjustments);
	}

	private void addToState(final MerkleEntityId key, final ExpirableTxnRecord expirableTxnRecord) {
		final var currentAccounts = accounts.get();
		final var mutableAccount = currentAccounts.getForModify(key);
		mutableAccount.records().offer(expirableTxnRecord);
	}
}
