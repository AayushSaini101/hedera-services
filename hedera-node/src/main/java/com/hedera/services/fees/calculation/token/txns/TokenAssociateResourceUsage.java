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
import com.hedera.services.usage.SigUsage;
import com.hedera.services.usage.token.TokenAssociateUsage;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.exception.InvalidTxBodyException;
import com.hederahashgraph.fee.SigValueObj;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.function.BiFunction;

import static com.hedera.services.state.merkle.MerkleEntityId.fromAccountId;

@Singleton
public class TokenAssociateResourceUsage implements TxnResourceUsageEstimator {
	static BiFunction<TransactionBody, SigUsage, TokenAssociateUsage> factory = TokenAssociateUsage::newEstimate;

	@Inject
	public TokenAssociateResourceUsage() {
	}

	@Override
	public boolean applicableTo(TransactionBody txn) {
		return txn.hasTokenAssociate();
	}

	@Override
	public FeeData usageGiven(TransactionBody txn, SigValueObj svo, StateView view) throws InvalidTxBodyException {
		var op = txn.getTokenAssociate();
		var account = view.accounts().get(fromAccountId(op.getAccount()));
		if (account == null) {
			return FeeData.getDefaultInstance();
		} else {
			var sigUsage = new SigUsage(svo.getTotalSigCount(), svo.getSignatureSize(), svo.getPayerAcctSigCount());
			var estimate = factory.apply(txn, sigUsage);
			return estimate.givenCurrentExpiry(account.getExpiry()).get();
		}
	}
}
