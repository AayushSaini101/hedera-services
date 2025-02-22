package com.hedera.services.bdd.spec.transactions.token;

/*-
 * ‌
 * Hedera Services Test Clients
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
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.fees.FeeCalculator;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.suites.HapiApiSuite;
import com.hedera.services.usage.token.TokenMintUsage;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.TokenInfo;
import com.hederahashgraph.api.proto.java.TokenMintTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenFeeScheduleUpdate.lookupInfo;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

public class HapiTokenMint extends HapiTxnOp<HapiTokenMint> {
	static final Logger log = LogManager.getLogger(HapiTokenMint.class);

	private long amount;
	private String token;
	private boolean rememberingNothing = false;
	private List<ByteString> metadata;
	private SubType subType;

    @Override
	public HederaFunctionality type() {
		return HederaFunctionality.TokenMint;
	}

	public HapiTokenMint(String token, long amount) {
		this.token = token;
		this.amount = amount;
		this.metadata = Collections.emptyList();
		this.subType = figureSubType();
	}

	public HapiTokenMint(String token, List<ByteString> metadata) {
		this.token = token;
		this.metadata = metadata;
		this.subType = figureSubType();
	}

	public HapiTokenMint(String token, List<ByteString> metadata, String txNamePrefix) {
		this.token = token;
		this.metadata = metadata;
		this.amount = 0;
	}

	public HapiTokenMint(String token, List<ByteString> metadata, long amount) {
		this.token = token;
		this.metadata = metadata;
		this.amount = amount;
		this.subType = figureSubType();
	}

	public HapiTokenMint rememberingNothing() {
		rememberingNothing = true;
		return this;
	}

	@Override
	protected HapiTokenMint self() {
		return this;
	}

	@Override
	protected long feeFor(HapiApiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
		try {
			final TokenInfo info = lookupInfo(spec, token, log, loggingOff);
			FeeCalculator.ActivityMetrics metricsCalc = (_txn, svo) -> {
				var estimate = TokenMintUsage
						.newEstimate(_txn, suFrom(svo))
						.givenSubType(subType);
				if (subType == SubType.TOKEN_NON_FUNGIBLE_UNIQUE) {
					final var lifetime = info.getExpiry().getSeconds() -
							_txn.getTransactionID().getTransactionValidStart().getSeconds();
					estimate.givenExpectedLifetime(lifetime);
				}
				return estimate.get();
			};
			return spec.fees().forActivityBasedOp(
					HederaFunctionality.TokenMint, subType, metricsCalc, txn, numPayerKeys);
		} catch (Throwable ignore) {
			return HapiApiSuite.ONE_HBAR;
		}
	}

	private SubType figureSubType() {
		if (metadata.isEmpty()) {
			return SubType.TOKEN_FUNGIBLE_COMMON;
		} else {
			return SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
		}
	}

	@Override
	protected Consumer<TransactionBody.Builder> opBodyDef(HapiApiSpec spec) throws Throwable {
		var tId = TxnUtils.asTokenId(token, spec);
		TokenMintTransactionBody opBody = spec
				.txns()
				.<TokenMintTransactionBody, TokenMintTransactionBody.Builder>body(
						TokenMintTransactionBody.class, b -> {
							b.setToken(tId);
							b.setAmount(amount);
							b.addAllMetadata(metadata);
						});
		return b -> b.setTokenMint(opBody);
	}

	@Override
	protected List<Function<HapiApiSpec, Key>> defaultSigners() {
		return List.of(
				spec -> spec.registry().getKey(effectivePayer(spec)),
				spec -> spec.registry().getSupplyKey(token));
	}

	@Override
	protected Function<Transaction, TransactionResponse> callToUse(HapiApiSpec spec) {
		return spec.clients().getTokenSvcStub(targetNodeFor(spec), useTls)::mintToken;
	}

	@Override
	public void updateStateOf(HapiApiSpec spec) throws Throwable {
		if (rememberingNothing || actualStatus != SUCCESS) {
			return;
		}
		lookupSubmissionRecord(spec);
		spec.registry().saveCreationTime(token, recordOfSubmission.getConsensusTimestamp());
	}

	@Override
	protected MoreObjects.ToStringHelper toStringHelper() {
		MoreObjects.ToStringHelper helper = super.toStringHelper()
				.add("token", token)
				.add("amount", amount)
				.add("metadata", metadata);
		return helper;
	}
}
