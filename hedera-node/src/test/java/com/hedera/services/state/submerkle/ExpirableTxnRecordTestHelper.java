package com.hedera.services.state.submerkle;

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
import com.hedera.services.legacy.core.jproto.TxnReceipt;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TransactionRecord;

import java.util.ArrayList;
import java.util.List;

import static com.hedera.services.state.submerkle.EntityId.fromGrpcScheduleId;
import static com.hedera.services.state.submerkle.ExpirableTxnRecord.NO_TOKENS;
import static java.util.stream.Collectors.toList;

public class ExpirableTxnRecordTestHelper {
	public static ExpirableTxnRecord fromGprc(TransactionRecord record) {
		List<EntityId> tokens = NO_TOKENS;
		List<CurrencyAdjustments> tokenAdjustments = null;
		List<NftAdjustments> nftTokenAdjustments = null;
		int n = record.getTokenTransferListsCount();

		if (n > 0) {
			tokens = new ArrayList<>();
			for (TokenTransferList tokenTransfers : record.getTokenTransferListsList()) {
				tokens.add(EntityId.fromGrpcTokenId(tokenTransfers.getToken()));
				if (!tokenTransfers.getTransfersList().isEmpty()) {
					if (tokenAdjustments == null) {
						tokenAdjustments = new ArrayList<>();
					}
					tokenAdjustments.add(CurrencyAdjustments.fromGrpc(tokenTransfers.getTransfersList()));
				}
				if (!tokenTransfers.getNftTransfersList().isEmpty()) {
					if (nftTokenAdjustments == null) {
						nftTokenAdjustments = new ArrayList<>();
					}
					nftTokenAdjustments.add(NftAdjustments.fromGrpc(tokenTransfers.getNftTransfersList()));
				}
			}

		}

		return createExpiryTxnRecordFrom(record, tokens, tokenAdjustments, nftTokenAdjustments);
	}

	private static ExpirableTxnRecord createExpiryTxnRecordFrom(final TransactionRecord record,
			final List<EntityId> tokens,
			final List<CurrencyAdjustments> tokenAdjustments,
			final List<NftAdjustments> nftTokenAdjustments) {

		final var fcAssessedFees = record.getAssessedCustomFeesCount() > 0
				? record.getAssessedCustomFeesList().stream().map(FcAssessedCustomFee::fromGrpc).collect(toList())
				: null;
		return ExpirableTxnRecord.newBuilder()
				.setReceipt(TxnReceipt.fromGrpc(record.getReceipt()))
				.setTxnHash(record.getTransactionHash().toByteArray())
				.setTxnId(TxnId.fromGrpc(record.getTransactionID()))
				.setConsensusTime(RichInstant.fromGrpc(record.getConsensusTimestamp()))
				.setMemo(record.getMemo())
				.setFee(record.getTransactionFee())
				.setTransferList(
						record.hasTransferList() ? CurrencyAdjustments.fromGrpc(record.getTransferList()) : null)
				.setContractCallResult(record.hasContractCallResult() ? SolidityFnResult.fromGrpc(
						record.getContractCallResult()) : null)
				.setContractCreateResult(record.hasContractCreateResult() ? SolidityFnResult.fromGrpc(
						record.getContractCreateResult()) : null)
				.setTokens(tokens)
				.setTokenAdjustments(tokenAdjustments)
				.setNftTokenAdjustments(nftTokenAdjustments)
				.setScheduleRef(record.hasScheduleRef() ? fromGrpcScheduleId(record.getScheduleRef()) : null)
				.setCustomFeesCharged(fcAssessedFees)
				.setNewTokenAssociations(record.getAutomaticTokenAssociationsList()
						.stream().map(a -> FcTokenAssociation.fromGrpc(a)).collect(toList()))
				.build();
	}
}
