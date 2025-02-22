package com.hedera.services.keys;

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
import com.hedera.services.sigs.PlatformSigsCreationResult;
import com.hedera.services.sigs.PlatformSigsFactory;
import com.hedera.services.sigs.factories.TxnScopedPlatformSigFactory;
import com.hedera.services.sigs.sourcing.PubKeyToSigBytes;
import com.hedera.services.sigs.verification.SyncVerifier;
import com.hedera.services.utils.PlatformTxnAccessor;
import com.hedera.services.utils.TxnAccessor;
import com.hedera.test.factories.scenarios.TxnHandlingScenario;
import com.hederahashgraph.api.proto.java.Transaction;
import com.swirlds.common.crypto.TransactionSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;

import static com.hedera.services.keys.StandardSyncActivationCheck.allKeysAreActive;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.verify;

class StandardSyncActivationCheckTest {
	private JKey key;
	private Transaction signedTxn;
	private List<TransactionSignature> sigs;
	private PubKeyToSigBytes sigBytes;
	private PlatformSigsCreationResult result;

	private SyncVerifier syncVerifier;
	private PlatformTxnAccessor accessor;
	private PlatformSigsFactory sigsFactory;
	private TxnScopedPlatformSigFactory scopedSig;
	private Function<byte[], TransactionSignature> sigsFn;
	private Function<TxnAccessor, TxnScopedPlatformSigFactory> scopedSigProvider;
	private BiPredicate<JKey, Function<byte[], TransactionSignature>> isActive;
	private Function<List<TransactionSignature>, Function<byte[], TransactionSignature>> sigsFnProvider;

	@BeforeEach
	private void setup() throws Exception {
		sigs = mock(List.class);
		key = TxnHandlingScenario.MISC_TOPIC_ADMIN_KT.asJKey();
		sigBytes = mock(PubKeyToSigBytes.class);
		signedTxn = mock(Transaction.class);

		sigsFn = mock(Function.class);
		result = mock(PlatformSigsCreationResult.class);
		accessor = mock(PlatformTxnAccessor.class);
		given(accessor.getTxnBytes()).willReturn("Goodness".getBytes());
		given(accessor.getSignedTxnWrapper()).willReturn(signedTxn);
		isActive = mock(BiPredicate.class);
		syncVerifier = mock(SyncVerifier.class);
		sigsFnProvider = mock(Function.class);
		given(sigsFnProvider.apply(sigs)).willReturn(sigsFn);
		scopedSig = mock(TxnScopedPlatformSigFactory.class);
		scopedSigProvider = mock(Function.class);
		given(scopedSigProvider.apply(any())).willReturn(scopedSig);
		sigsFactory = mock(PlatformSigsFactory.class);
		given(sigsFactory.createEd25519From(List.of(key), sigBytes, scopedSig)).willReturn(result);
	}

	@Test
	void happyPathFlows() {
		given(result.hasFailed()).willReturn(false);
		given(result.getPlatformSigs()).willReturn(sigs);
		given(isActive.test(any(), any())).willReturn(true);

		final var flag = allKeysAreActive(
				List.of(key),
				syncVerifier,
				accessor,
				sigsFactory,
				sigBytes,
				scopedSigProvider,
				isActive,
				sigsFnProvider);

		verify(isActive).test(key, sigsFn);
		assertTrue(flag);
	}

	@Test
	void failsOnInActive() {
		given(result.hasFailed()).willReturn(false);
		given(result.getPlatformSigs()).willReturn(sigs);
		given(isActive.test(any(), any())).willReturn(false);

		final var flag = allKeysAreActive(
				List.of(key),
				syncVerifier,
				accessor,
				sigsFactory,
				sigBytes,
				scopedSigProvider,
				isActive,
				sigsFnProvider);

		verify(isActive).test(key, sigsFn);
		assertFalse(flag);
	}

	@Test
	void shortCircuitsOnCreationFailure() {
		given(result.hasFailed()).willReturn(true);

		final var flag = allKeysAreActive(
				List.of(key),
				syncVerifier,
				accessor,
				sigsFactory,
				sigBytes,
				scopedSigProvider,
				isActive,
				sigsFnProvider);

		assertFalse(flag);
	}
}
