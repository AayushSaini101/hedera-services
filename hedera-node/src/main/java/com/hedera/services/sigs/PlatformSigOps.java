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
import com.hedera.services.legacy.exception.KeyPrefixMismatchException;
import com.hedera.services.sigs.factories.TxnScopedPlatformSigFactory;
import com.hedera.services.sigs.sourcing.PubKeyToSigBytes;

import java.util.List;

import static com.hedera.services.keys.HederaKeyTraversal.visitSimpleKeys;

/**
 * Provides static methods to work with {@link com.swirlds.common.crypto.Signature} objects.
 */
public final class PlatformSigOps {
	private PlatformSigOps() {
		throw new UnsupportedOperationException("Utility Class");
	}

	/**
	 * Return the result of trying to create one or more platform sigs using a given
	 * {@link TxnScopedPlatformSigFactory}, where this {@code factory} should be invoked for
	 * each public key in a left-to-right DFS traversal of the simple keys from a list of
	 * Hedera keys, using signature bytes from a given {@link PubKeyToSigBytes}.
	 *
	 * @param pubKeys
	 * 		a list of Hedera keys to traverse for public keys.
	 * @param sigBytesFn
	 * 		a source of cryptographic signatures to associate to the public keys.
	 * @param factory
	 * 		a factory to convert public keys and cryptographic sigs into sigs.
	 * @return the result of attempting this creation.
	 */
	public static PlatformSigsCreationResult createEd25519PlatformSigsFrom(
			final List<JKey> pubKeys,
			final PubKeyToSigBytes sigBytesFn,
			final TxnScopedPlatformSigFactory factory
	) {
		final var result = new PlatformSigsCreationResult();
		for (final var pk : pubKeys) {
			visitSimpleKeys(pk, ed25519Key -> createPlatformSigFor(ed25519Key, sigBytesFn, factory, result));
		}
		return result;
	}

	private static void createPlatformSigFor(
			final JKey ed25519Key,
			final PubKeyToSigBytes sigBytesFn,
			final TxnScopedPlatformSigFactory factory,
			final PlatformSigsCreationResult result
	) {
		if (result.hasFailed()) {
			return;
		}

		try {
			final var keyBytes = ed25519Key.getEd25519();
			final var sigBytes = sigBytesFn.sigBytesFor(keyBytes);
			if (sigBytes.length > 0) {
				result.getPlatformSigs().add(factory.create(keyBytes, sigBytes));
			}
		} catch (KeyPrefixMismatchException kmpe) {
			/* Nbd if a signature map is ambiguous for a key linked to a scheduled transaction. */
			if (!ed25519Key.isForScheduledTxn()) {
				result.setTerminatingEx(kmpe);
			}
		} catch (Exception e) {
			result.setTerminatingEx(e);
		}
	}
}
