package com.hedera.services.sigs;

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

import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.sigs.factories.BodySigningSigFactory;
import com.hedera.services.sigs.order.SigRequirements;
import com.hedera.services.sigs.sourcing.PubKeyToSigBytes;
import com.hedera.services.utils.PlatformTxnAccessor;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Transaction;
import com.swirlds.common.SwirldDualState;
import com.swirlds.common.SwirldTransaction;
import com.swirlds.common.crypto.Signature;

import java.time.Instant;

/**
 * Provides an "expand" operation that acts in-place on the {@link com.swirlds.common.crypto.TransactionSignature}
 * list of a {@link com.swirlds.common.Transaction} whose contents are known to be a valid
 * Hedera gRPC {@link Transaction}.
 *
 * <p>This operation allows Hedera Services to use the Platform to efficiently
 * verify <i>many</i> of the cryptographic signatures in its gRPC transactions. (There
 * are still cases where Hedera Services does a single-threaded verification itself.)
 *
 * <p>For each platform txn added to the hashgraph, {@code expandIn} checks which
 * Hedera keys must have active signatures for the wrapped gRPC txn to be valid;
 * and creates the cryptographic signatures at the bases of the signing hierarchies
 * for these keys. This implicitly requests the Platform to verify these cryptographic
 * signatures, by setting them in the sigs list of the platform txn, <b>before</b>
 * {@link com.hedera.services.ServicesState#handleTransaction(long, boolean, Instant, Instant, SwirldTransaction,
 * SwirldDualState)}
 * is called with {@code isConsensus=true}.
 */
public final class HederaToPlatformSigOps {
	private HederaToPlatformSigOps() {
		throw new UnsupportedOperationException("Utility Class");
	}

	/**
	 * Try to set the {@link Signature} list on the accessible platform txn to exactly
	 * the base-level signatures of the signing hierarchy for each Hedera {@link JKey}
	 * required to sign the wrapped gRPC txn. (Signatures for the payer always come first.)
	 *
	 * <p>Exceptional conditions are treated as follows:
	 * <ul>
	 *     <li>If an error occurs while determining the Hedera signing keys,
	 *     abort processing and return a {@link ResponseCodeEnum} representing this
	 *     error.</li>
	 *     <li>If an error occurs while creating the platform {@link Signature}
	 *     objects for either the payer or the entities in non-payer roles, ignore
	 *     it silently. </li>
	 * </ul>
	 *
	 * @param txnAccessor
	 * 		the accessor for the platform txn
	 * @param keyOrderer
	 * 		facility for listing Hedera keys required to sign the gRPC txn
	 * @param pkToSigFn
	 * 		source of crypto sigs for the simple keys in the Hedera key leaves
	 * @return a representation of the outcome
	 */
	public static ResponseCodeEnum expandIn(
			final PlatformTxnAccessor txnAccessor,
			final SigRequirements keyOrderer,
			final PubKeyToSigBytes pkToSigFn
	) {
		txnAccessor.getPlatformTxn().clear();

		return new Expansion(txnAccessor, keyOrderer, pkToSigFn, new BodySigningSigFactory(txnAccessor)).execute();
	}
}
