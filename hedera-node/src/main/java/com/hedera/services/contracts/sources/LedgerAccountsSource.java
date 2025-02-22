package com.hedera.services.contracts.sources;

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

import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.ledger.accounts.HederaAccountCustomizer;
import com.hedera.services.legacy.core.jproto.JContractIDKey;
import com.hedera.services.state.submerkle.EntityId;
import com.hederahashgraph.api.proto.java.AccountID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ethereum.core.AccountState;
import org.ethereum.datasource.Source;
import org.ethereum.util.ALock;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.math.BigInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.hedera.services.utils.EntityIdUtils.accountParsedFromSolidityAddress;
import static com.hedera.services.utils.EntityIdUtils.asContract;
import static com.hedera.services.utils.EntityIdUtils.asLiteralString;

@Singleton
public class LedgerAccountsSource implements Source<byte[], AccountState> {
	private static final Logger log = LogManager.getLogger(LedgerAccountsSource.class);

	private final HederaLedger ledger;
	private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
	private final ALock rLock = new ALock(rwLock.readLock());
	private final ALock wLock = new ALock(rwLock.writeLock());

	@Inject
	public LedgerAccountsSource(HederaLedger ledger) {
		this.ledger = ledger;
	}

	@Override
	public void delete(byte[] key) {
		throw new UnsupportedOperationException();
	}

	@Override
	public AccountState get(byte[] key) {
		try (ALock ignored = rLock.lock()) {
			var id = accountParsedFromSolidityAddress(key);
			if (!ledger.exists(id) || ledger.isDetached(id)) {
				return null;
			}

			final long expiry = ledger.expiry(id);
			final long balance = ledger.getBalance(id);
			final long autoRenewPeriod = ledger.autoRenewPeriod(id);
			final boolean isDeleted = ledger.isDeleted(id);
			final boolean isSmartContract = ledger.isSmartContract(id);
			final boolean isReceiverSigRequired = ledger.isReceiverSigRequired(id);
			final EntityId proxy = ledger.proxy(id);

			final var evmState = new AccountState(BigInteger.ZERO, BigInteger.valueOf(balance));
			evmState.setShardId(id.getShardNum());
			evmState.setRealmId(id.getRealmNum());
			evmState.setAccountNum(id.getAccountNum());
			evmState.setAutoRenewPeriod(autoRenewPeriod);
			evmState.setReceiverSigRequired(isReceiverSigRequired);
			evmState.setDeleted(isDeleted);
			evmState.setExpirationTime(expiry);
			evmState.setSmartContract(isSmartContract);
			if (proxy != null) {
				evmState.setProxyAccountShard(proxy.shard());
				evmState.setProxyAccountRealm(proxy.realm());
				evmState.setProxyAccountNum(proxy.num());
			}

			return evmState;
		}
	}

	@Override
	public void put(byte[] key, AccountState evmState) {
		var id = accountParsedFromSolidityAddress(key);

		if (evmState == null) {
			String id_str = asLiteralString(id);
			log.warn("Ignoring null state put to account {}!", id_str);
			return;
		}

		try (ALock ignored = wLock.lock()) {
			if (ledger.exists(id)) {
				updateForEvm(id, evmState);
			} else {
				createForEvm(id, evmState);
			}
		}
	}

	private void updateForEvm(AccountID id, AccountState evmState) {
		long oldBalance = ledger.getBalance(id);
		long newBalance = evmState.getBalance().longValue();
		long adjustment = newBalance - oldBalance;

		ledger.adjustBalance(id, adjustment);
		HederaAccountCustomizer customizer = new HederaAccountCustomizer()
				.expiry(evmState.getExpirationTime())
				.isDeleted(evmState.isDeleted());
		ledger.customize(id, customizer);
	}

	private void createForEvm(AccountID id, AccountState evmState) {
		var proxy = new EntityId(
				evmState.getProxyAccountShard(),
				evmState.getProxyAccountRealm(),
				evmState.getProxyAccountNum());
		var key = new JContractIDKey(asContract(id));
		HederaAccountCustomizer customizer = new HederaAccountCustomizer()
				.key(key)
				.memo("")
				.proxy(proxy)
				.expiry(evmState.getExpirationTime())
				.autoRenewPeriod(evmState.getAutoRenewPeriod())
				.isSmartContract(true);

		long balance = evmState.getBalance().longValue();
		ledger.spawn(id, balance, customizer);
	}

	@Override
	public boolean flush() {
		return false;
	}
}
