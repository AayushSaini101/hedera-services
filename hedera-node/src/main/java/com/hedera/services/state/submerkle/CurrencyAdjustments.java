package com.hedera.services.state.submerkle;

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

import com.google.common.base.MoreObjects;
import com.hedera.services.utils.EntityIdUtils;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.TransferList;
import com.swirlds.common.io.SelfSerializable;
import com.swirlds.common.io.SerializableDataInputStream;
import com.swirlds.common.io.SerializableDataOutputStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static com.hedera.services.utils.MiscUtils.readableTransferList;

public class CurrencyAdjustments implements SelfSerializable {
	static final int MERKLE_VERSION = 1;
	static final long RUNTIME_CONSTRUCTABLE_ID = 0xd8b06bd46e12a466L;

	private static final long[] NO_ADJUSTMENTS = new long[0];
	static final int MAX_NUM_ADJUSTMENTS = 25;

	long[] hbars = NO_ADJUSTMENTS;
	List<EntityId> accountIds = Collections.emptyList();

	public CurrencyAdjustments() {
		/* For RuntimeConstructable */
	}

	public CurrencyAdjustments(long[] amounts, List<EntityId> parties) {
		hbars = amounts;
		accountIds = parties;
	}

	/* --- SelfSerializable --- */

	@Override
	public long getClassId() {
		return RUNTIME_CONSTRUCTABLE_ID;
	}

	@Override
	public int getVersion() {
		return MERKLE_VERSION;
	}

	@Override
	public void deserialize(SerializableDataInputStream in, int version) throws IOException {
		accountIds = in.readSerializableList(MAX_NUM_ADJUSTMENTS, true, EntityId::new);
		hbars = in.readLongArray(MAX_NUM_ADJUSTMENTS);
	}

	@Override
	public void serialize(SerializableDataOutputStream out) throws IOException {
		out.writeSerializableList(accountIds, true, true);
		out.writeLongArray(hbars);
	}

	/* ---- Object --- */

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || CurrencyAdjustments.class != o.getClass()) {
			return false;
		}

		CurrencyAdjustments that = (CurrencyAdjustments) o;
		return accountIds.equals(that.accountIds) && Arrays.equals(hbars, that.hbars);
	}

	@Override
	public int hashCode() {
		int result = Long.hashCode(RUNTIME_CONSTRUCTABLE_ID);
		result = result * 31 + Integer.hashCode(MERKLE_VERSION);
		result = result * 31 + accountIds.hashCode();
		return result * 31 + Arrays.hashCode(hbars);
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(this)
				.add("readable", readableTransferList(toGrpc()))
				.toString();
	}

	/* --- Helpers --- */

	public TransferList toGrpc() {
		var grpc = TransferList.newBuilder();
		IntStream.range(0, hbars.length)
				.mapToObj(i -> AccountAmount.newBuilder()
						.setAmount(hbars[i])
						.setAccountID(EntityIdUtils.asAccount(accountIds.get(i))))
				.forEach(grpc::addAccountAmounts);
		return grpc.build();
	}

	public static CurrencyAdjustments fromGrpc(TransferList grpc) {
		return fromGrpc(grpc.getAccountAmountsList());
	}

	public static CurrencyAdjustments fromGrpc(List<AccountAmount> adjustments) {
		final var pojo = new CurrencyAdjustments();
		final int n = adjustments.size();
		if (n > 0) {
			final var amounts = new long[n];
			final List<EntityId> accounts = new ArrayList<>(n);
			for (var i = 0; i < n; i++) {
				final var adjustment = adjustments.get(i);
				amounts[i] = adjustment.getAmount();
				accounts.add(EntityId.fromGrpcAccountId(adjustment.getAccountID()));
			}
			pojo.hbars = amounts;
			pojo.accountIds = accounts;
		}
		return pojo;
	}
}
