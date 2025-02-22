package com.hedera.test.factories.keys;

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

import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.Key;
import com.swirlds.common.CommonUtils;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;

import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class OverlappingKeyGenerator implements KeyGenerator {
	private int nextKey = 0;
	private List<Key> precomputed = new ArrayList<>();
	private Map<String, PrivateKey> pkMap = new HashMap<>();

	public static OverlappingKeyGenerator withDefaultOverlaps() {
		return new OverlappingKeyGenerator(3, 1);
	}

	private OverlappingKeyGenerator(int n, int minOverlapLen) {
		Set<ByteString> usedPrefixes = new HashSet<>();
		Map<ByteString, Key> byPrefix = new HashMap<>();
		while (precomputed.size() < n) {
			Key candidate = genSingleEd25519Key(pkMap);
			ByteString prefix = pubKeyPrefixOf(candidate, minOverlapLen);
			if (byPrefix.containsKey(prefix)) {
				if (!usedPrefixes.contains(prefix)) {
					precomputed.add(byPrefix.get(prefix));
					usedPrefixes.add(prefix);
				}
				if (precomputed.size() < n) {
					precomputed.add(candidate);
				}
			} else {
				byPrefix.put(prefix, candidate);
			}
		}
	}

	/**
	 * Generates a single Ed25519 key.
	 *
	 * @param pubKey2privKeyMap
	 * 		map of public key hex string as key and the private key as value
	 * @return generated Ed25519 key
	 */
	public static Key genSingleEd25519Key(Map<String, PrivateKey> pubKey2privKeyMap) {
		final var pair = new KeyPairGenerator().generateKeyPair();
		final var pubKey = ((EdDSAPublicKey) pair.getPublic()).getAbyte();
		final var pubKeyHex = com.swirlds.common.CommonUtils.hex(pubKey);
		final var akey = Key.newBuilder().setEd25519(ByteString.copyFrom(pubKey)).build();

		pubKey2privKeyMap.put(pubKeyHex, pair.getPrivate());
		return akey;
	}

	private ByteString pubKeyPrefixOf(Key key, int prefixLen) {
		return key.getEd25519().substring(0, prefixLen);
	}

	@Override
	public Key genEd25519AndUpdateMap(Map<String, PrivateKey> mutablePkMap) {
		Key key = precomputed.get(nextKey);
		nextKey = (nextKey + 1) % precomputed.size();
		String hexPubKey = CommonUtils.hex(key.getEd25519().toByteArray());
		mutablePkMap.put(hexPubKey, pkMap.get(hexPubKey));
		return key;
	}
}
