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
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.store.models.Id;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TopicID;
import com.swirlds.common.io.SelfSerializable;
import com.swirlds.common.io.SerializableDataInputStream;
import com.swirlds.common.io.SerializableDataOutputStream;

import java.io.IOException;
import java.util.Objects;

import static com.hedera.services.state.merkle.internals.IdentityCodeUtils.codeFromNum;
import static com.hedera.services.state.merkle.internals.IdentityCodeUtils.numFromCode;

public class EntityId implements SelfSerializable {
	private static final long DEFAULT_SHARD = 0L;
	private static final long DEFAULT_REALM = 0L;

	static final int MERKLE_VERSION = 1;
	static final long RUNTIME_CONSTRUCTABLE_ID = 0xf35ba643324efa37L;

	public static final EntityId MISSING_ENTITY_ID = new EntityId(0, 0, 0);

	private long shard;
	private long realm;
	private long num;

	public EntityId() {
		/* For RuntimeConstructable */
	}

	public EntityId(Id id) {
		this.shard = id.getShard();
		this.realm = id.getRealm();
		this.num = id.getNum();
	}

	public EntityId(long shard, long realm, long num) {
		this.shard = shard;
		this.realm = realm;
		this.num = num;
	}

	/**
	 * Gives a "compressed" code to identify this entity id.
	 *
	 * @return the code for this id
	 */
	public int identityCode() {
		return codeFromNum(num);
	}

	/**
	 * Builds an entity id from its encoded format.
	 *
	 * @param code the compressed representation
	 * @return the equivalent entity id
	 */
	public static EntityId fromIdentityCode(int code) {
		return new EntityId(DEFAULT_SHARD, DEFAULT_REALM, numFromCode(code));
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
		shard = in.readLong();
		realm = in.readLong();
		num = in.readLong();
	}

	@Override
	public void serialize(SerializableDataOutputStream out) throws IOException {
		out.writeLong(shard);
		out.writeLong(realm);
		out.writeLong(num);
	}

	/* --- Object --- */
	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || EntityId.class != o.getClass()) {
			return false;
		}
		EntityId that = (EntityId) o;
		return shard == that.shard && realm == that.realm && num == that.num;
	}

	public boolean matches(AccountID aId) {
		return shard == aId.getShardNum() && realm == aId.getRealmNum() && num == aId.getAccountNum();
	}

	public boolean matches(Id id) {
		return shard == id.getShard() && realm == id.getRealm() && num == id.getNum();
	}

	@Override
	public int hashCode() {
		return Objects.hash(shard, realm, num);
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(this)
				.add("shard", shard)
				.add("realm", realm)
				.add("num", num)
				.toString();
	}

	public String toAbbrevString() {
		return String.format("%d.%d.%d", shard, realm, num);
	}

	public EntityId copy() {
		return new EntityId(shard, realm, num);
	}

	public long shard() {
		return shard;
	}

	public long realm() {
		return realm;
	}

	public long num() {
		return num;
	}

	/* --- Helpers --- */
	public static EntityId fromGrpcAccountId(AccountID id) {
		if (id == null) {
			throw new IllegalArgumentException("Given account id was null!");
		}
		return new EntityId(id.getShardNum(), id.getRealmNum(), id.getAccountNum());
	}

	public static EntityId fromGrpcFileId(FileID id) {
		if (id == null) {
			throw new IllegalArgumentException("Given file id was null!");
		}
		return new EntityId(id.getShardNum(), id.getRealmNum(), id.getFileNum());
	}

	public static EntityId fromGrpcTopicId(TopicID id) {
		if (id == null) {
			throw new IllegalArgumentException("Given topic id was null!");
		}
		return new EntityId(id.getShardNum(), id.getRealmNum(), id.getTopicNum());
	}

	public static EntityId fromGrpcTokenId(TokenID id) {
		if (id == null) {
			throw new IllegalArgumentException("Given token id was null!");
		}
		return new EntityId(id.getShardNum(), id.getRealmNum(), id.getTokenNum());
	}

	public static EntityId fromGrpcScheduleId(ScheduleID id) {
		if (id == null) {
			throw new IllegalArgumentException("Given schedule id was null!");
		}
		return new EntityId(id.getShardNum(), id.getRealmNum(), id.getScheduleNum());
	}

	public static EntityId fromGrpcContractId(ContractID id) {
		if (id == null) {
			throw new IllegalArgumentException("Given contract id was null!");
		}
		return new EntityId(id.getShardNum(), id.getRealmNum(), id.getContractNum());
	}

	public ContractID toGrpcContractId() {
		return ContractID.newBuilder()
				.setShardNum(shard)
				.setRealmNum(realm)
				.setContractNum(num)
				.build();
	}

	public TokenID toGrpcTokenId() {
		return TokenID.newBuilder()
				.setShardNum(shard)
				.setRealmNum(realm)
				.setTokenNum(num)
				.build();
	}

	public ScheduleID toGrpcScheduleId() {
		return ScheduleID.newBuilder()
				.setShardNum(shard)
				.setRealmNum(realm)
				.setScheduleNum(num)
				.build();
	}

	public AccountID toGrpcAccountId() {
		return AccountID.newBuilder()
				.setShardNum(shard)
				.setRealmNum(realm)
				.setAccountNum(num)
				.build();
	}

	public MerkleEntityId asMerkle() {
		return new MerkleEntityId(shard, realm, num);
	}

	public Id asId() {
		return new Id(shard, realm, num);
	}
}
