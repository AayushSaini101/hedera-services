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

import com.hedera.services.state.submerkle.SequenceNumber;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.TokenID;

import java.util.function.Supplier;

public class SeqNoEntityIdSource implements EntityIdSource {
	private final Supplier<SequenceNumber> seqNo;

	/**
	 * Tracks the newly created {@link com.hedera.services.state.submerkle.EntityId} during the {@link com.hedera.services.txns.TransitionLogic} of an operation
	 * Utilised only in refactored Transition Logics - currently only {@link com.hedera.services.txns.token.TokenCreateTransitionLogic}
	 */
	private int provisionalIds = 0;

	public SeqNoEntityIdSource(Supplier<SequenceNumber> seqNo) {
		this.seqNo = seqNo;
	}

	@Override
	public AccountID newAccountId(AccountID sponsor) {
		return AccountID.newBuilder()
				.setRealmNum(sponsor.getRealmNum())
				.setShardNum(sponsor.getShardNum())
				.setAccountNum(seqNo.get().getAndIncrement())
				.build();
	}

	@Override
	public FileID newFileId(AccountID sponsor) {
		return FileID.newBuilder()
				.setRealmNum(sponsor.getRealmNum())
				.setShardNum(sponsor.getShardNum())
				.setFileNum(seqNo.get().getAndIncrement())
				.build();
	}

	@Override
	public TokenID newTokenId(AccountID sponsor) {
		provisionalIds++;
		return TokenID.newBuilder()
				.setRealmNum(sponsor.getRealmNum())
				.setShardNum(sponsor.getShardNum())
				.setTokenNum(seqNo.get().getAndIncrement())
				.build();
	}

	@Override
	public ScheduleID newScheduleId(AccountID sponsor) {
		return ScheduleID.newBuilder()
				.setRealmNum(sponsor.getRealmNum())
				.setShardNum(sponsor.getShardNum())
				.setScheduleNum(seqNo.get().getAndIncrement())
				.build();
	}

	@Override
	public void reclaimLastId() {
		seqNo.get().decrement();
	}

	@Override
	public void reclaimProvisionalIds() {
		final var curSeqNo = seqNo.get();
		while (provisionalIds != 0) {
			curSeqNo.decrement();
			--provisionalIds;
		}
	}

	@Override
	public void resetProvisionalIds() {
		provisionalIds = 0;
	}

	int getProvisionalIds() {
		return provisionalIds;
	}
}
