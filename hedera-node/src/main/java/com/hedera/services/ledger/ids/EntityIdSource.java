package com.hedera.services.ledger.ids;

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

import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.swirlds.common.SwirldDualState;
import com.swirlds.common.SwirldTransaction;

import java.time.Instant;

/**
 * Defines a type able to create ids of various entities under various conditions.
 */
public interface EntityIdSource {
	/**
	 * Returns the {@link AccountID} to use for a new account with the given sponsor.
	 *
	 * @param newAccountSponsor the sponsor of the new account
	 * @return an appropriate id to use
	 */
	AccountID newAccountId(AccountID newAccountSponsor);

	/**
	 * Returns the {@link FileID} to use for a new account with the given sponsor.
	 *
	 * @param newFileSponsor the sponsor of the new account.
	 * @return an appropriate id to use
	 */
	FileID newFileId(AccountID newFileSponsor);

	/**
	 * Returns the {@link TokenID} to use for a new token with the given sponsor.
	 *
	 * @param sponsor the sponsor of the new token.
	 * @return an appropriate id to use
	 */
	TokenID newTokenId(AccountID sponsor);

	/**
	 * Returns the {@link ScheduleID} to use for a new scheduled entity with the given sponsor.
	 *
	 * @param sponsor the sponsor of the new scheduled entity.
	 * @return an appropriate id to use
	 */
	ScheduleID newScheduleId(AccountID sponsor);

	/**
	 * Reclaims the last id issued.
	 */
	void reclaimLastId();

	/**
	 * Reclaims the IDs issued during one
	 * {@link com.hedera.services.ServicesState#handleTransaction(long, boolean, Instant, Instant, SwirldTransaction, SwirldDualState)} transition
	 */
	void reclaimProvisionalIds();

	/**
	 * Resets the provisional ids created during one
	 * {@link com.hedera.services.ServicesState#handleTransaction(long, boolean, Instant, Instant, SwirldTransaction, SwirldDualState)} transition
	 */
	void resetProvisionalIds();
}
