package com.hedera.services.ledger.accounts;

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

import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.state.merkle.MerkleUniqueTokenId;
import com.hedera.services.store.models.NftId;
import com.swirlds.fcmap.FCMap;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Set;
import java.util.function.Supplier;

import static com.hedera.services.state.merkle.MerkleUniqueTokenId.fromNftId;

@Singleton
public class BackingNfts implements BackingStore<NftId, MerkleUniqueToken> {
	private final Supplier<FCMap<MerkleUniqueTokenId, MerkleUniqueToken>> delegate;

	@Inject
	public BackingNfts(Supplier<FCMap<MerkleUniqueTokenId, MerkleUniqueToken>> delegate) {
		this.delegate = delegate;
	}

	@Override
	public void rebuildFromSources() {
		/* No-op */
	}

	@Override
	public MerkleUniqueToken getRef(NftId id) {
		return delegate.get().getForModify(fromNftId(id));
	}

	@Override
	public MerkleUniqueToken getImmutableRef(NftId id) {
		return delegate.get().get(fromNftId(id));
	}

	@Override
	public void put(NftId id, MerkleUniqueToken nft) {
		final var key = fromNftId(id);
		final var currentNfts = delegate.get();
		if (!currentNfts.containsKey(key)) {
			currentNfts.put(key, nft);
		}
	}

	@Override
	public void remove(NftId id) {
		delegate.get().remove(fromNftId(id));
	}

	@Override
	public boolean contains(NftId id) {
		return delegate.get().containsKey(fromNftId(id));
	}

	@Override
	public Set<NftId> idSet() {
		throw new UnsupportedOperationException();
	}
}
