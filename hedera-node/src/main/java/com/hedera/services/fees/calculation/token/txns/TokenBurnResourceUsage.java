package com.hedera.services.fees.calculation.token.txns;

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

import com.hedera.services.context.primitives.StateView;
import com.hedera.services.fees.calculation.TxnResourceUsageEstimator;
import com.hedera.services.fees.calculation.utils.ResourceUsageSubtypeHelper;
import com.hedera.services.usage.SigUsage;
import com.hedera.services.usage.token.TokenBurnUsage;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.TokenType;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.exception.InvalidTxBodyException;
import com.hederahashgraph.fee.SigValueObj;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Optional;
import java.util.function.BiFunction;

@Singleton
public class TokenBurnResourceUsage implements TxnResourceUsageEstimator {
	private static final ResourceUsageSubtypeHelper subtypeHelper = new ResourceUsageSubtypeHelper();

	static BiFunction<TransactionBody, SigUsage, TokenBurnUsage> factory = TokenBurnUsage::newEstimate;

	@Inject
	public TokenBurnResourceUsage() {
	}

	@Override
	public boolean applicableTo(TransactionBody txn) {
		return txn.hasTokenBurn();
	}

	@Override
	public FeeData usageGiven(TransactionBody txn, SigValueObj svo, StateView view) throws InvalidTxBodyException {
		final var target = txn.getTokenBurn().getToken();
		Optional<TokenType> tokenType = view.tokenType(target);
		SubType subType = subtypeHelper.determineTokenType(tokenType);
		var sigUsage = new SigUsage(svo.getTotalSigCount(), svo.getSignatureSize(), svo.getPayerAcctSigCount());
		var estimate = factory.apply(txn, sigUsage).givenSubType(subType);
		return estimate.get();
	}
}
