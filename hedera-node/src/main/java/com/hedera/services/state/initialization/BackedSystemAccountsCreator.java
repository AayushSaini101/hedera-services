package com.hedera.services.state.initialization;

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
import com.hedera.services.config.AccountNumbers;
import com.hedera.services.config.HederaNumbers;
import com.hedera.services.context.annotations.CompositeProps;
import com.hedera.services.context.properties.PropertySource;
import com.hedera.services.exceptions.NegativeAccountBalanceException;
import com.hedera.services.keys.LegacyEd25519KeyReader;
import com.hedera.services.ledger.accounts.BackingStore;
import com.hedera.services.ledger.accounts.HederaAccountCustomizer;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.swirlds.common.AddressBook;
import com.swirlds.common.CommonUtils;
import com.swirlds.fcmap.FCMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;

import static com.hedera.services.state.submerkle.EntityId.MISSING_ENTITY_ID;
import static com.hedera.services.utils.MiscUtils.asFcKeyUnchecked;

@Singleton
public class BackedSystemAccountsCreator implements SystemAccountsCreator {
	private static final Logger log = LogManager.getLogger(BackedSystemAccountsCreator.class);

	private final HederaNumbers hederaNums;
	private final AccountNumbers accountNums;
	private final PropertySource properties;
	private final LegacyEd25519KeyReader b64KeyReader;

	private JKey genesisKey;
	private String hexedABytes;

	@Inject
	public BackedSystemAccountsCreator(
			HederaNumbers hederaNums,
			AccountNumbers accountNums,
			@CompositeProps PropertySource properties,
			LegacyEd25519KeyReader b64KeyReader
	) {
		this.hederaNums = hederaNums;
		this.accountNums = accountNums;
		this.properties = properties;
		this.b64KeyReader = b64KeyReader;
	}

	@Override
	public void createSystemAccounts(FCMap<MerkleEntityId, MerkleAccount> accounts, AddressBook addressBook) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void ensureSystemAccounts(
			BackingStore<AccountID, MerkleAccount> accounts,
			AddressBook addressBook
	) {
		long N = properties.getIntProperty("ledger.numSystemAccounts");
		long expiry = properties.getLongProperty("bootstrap.system.entityExpiry");
		long tinyBarFloat = properties.getLongProperty("ledger.totalTinyBarFloat");

		for (long num = 1; num <= N; num++) {
			var id = idWith(num);
			if (accounts.contains(id)) {
				continue;
			}
			if (num == accountNums.treasury()) {
				accounts.put(id, accountWith(tinyBarFloat, expiry));
			} else {
				accounts.put(id, accountWith(0, expiry));
			}
		}

		for (long num = 900; num <= 1000; num++) {
			var id = idWith(num);
			if (!accounts.contains(id)) {
				accounts.put(id, accountWith(0, expiry));
			}
		}

		var allIds = accounts.idSet();
		var ledgerFloat = allIds.stream().mapToLong(id -> accounts.getImmutableRef(id).getBalance()).sum();
		var msg = String.format("Ledger float is %d tinyBars in %d accounts.", ledgerFloat, allIds.size());
		log.info(msg);
	}

	private MerkleAccount accountWith(long balance, long expiry) {
		var account = new HederaAccountCustomizer()
				.isReceiverSigRequired(false)
				.proxy(MISSING_ENTITY_ID)
				.isDeleted(false)
				.expiry(expiry)
				.memo("")
				.isSmartContract(false)
				.key(getGenesisKey())
				.autoRenewPeriod(expiry)
				.customizing(new MerkleAccount());
		try {
			account.setBalance(balance);
		} catch (NegativeAccountBalanceException e) {
			throw new IllegalStateException(e);
		}
		return account;
	}

	private JKey getGenesisKey() {
		if (genesisKey == null) {
			try {
				genesisKey = asFcKeyUnchecked(Key.newBuilder()
						.setKeyList(KeyList.newBuilder()
								.addKeys(Key.newBuilder()
										.setEd25519(ByteString.copyFrom(CommonUtils.unhex(getHexedABytes())))))
						.build());
			} catch (IllegalArgumentException e) {
				throw new IllegalStateException("Could not construct genesis key!", e);
			}
		}
		return genesisKey;
	}

	private String getHexedABytes() {
		if (hexedABytes == null) {
			hexedABytes = b64KeyReader.hexedABytesFrom(
					properties.getStringProperty("bootstrap.genesisB64Keystore.path"),
					properties.getStringProperty("bootstrap.genesisB64Keystore.keyName"));
		}
		return hexedABytes;
	}

	private AccountID idWith(long num) {
		return new MerkleEntityId(hederaNums.shard(), hederaNums.realm(), num).toAccountId();
	}
}
