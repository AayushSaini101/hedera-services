package com.hedera.services.sigs.metadata;

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

import com.hedera.services.sigs.metadata.lookups.SafeLookupResult;
import com.hedera.services.sigs.order.KeyOrderingFailure;
import com.hedera.services.state.merkle.MerkleSchedule;
import com.hedera.services.state.merkle.MerkleScheduleTest;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.RichInstant;
import com.hedera.services.store.schedule.ScheduleStore;
import com.hedera.test.factories.scenarios.TxnHandlingScenario;
import com.hedera.test.utils.IdUtils;
import com.hedera.test.utils.TxnUtils;
import com.hederahashgraph.api.proto.java.ScheduleID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ScheduleDelegatingSigMetadataLookupTest {
    final int TX_BYTES_LENGTH = 64;

    byte[] transactionBody = TxnUtils.randomUtf8Bytes(TX_BYTES_LENGTH);
    EntityId schedulingAccount = new EntityId(1,2, 3);
    RichInstant schedulingTXValidStart = new RichInstant(123, 456);

    ScheduleID id = IdUtils.asSchedule("1.2.666");

    String memo = "Hey there!";
    MerkleSchedule schedule;
    ScheduleStore scheduleStore;

    Function<ScheduleID, SafeLookupResult<ScheduleSigningMetadata>> subject;

    @BeforeEach
    void setup() {
        schedule = MerkleSchedule.from(MerkleScheduleTest.scheduleCreateTxnWith(
                TxnHandlingScenario.TOKEN_ADMIN_KT.asKey(),
                memo,
                IdUtils.asAccount("0.0.2"),
                schedulingAccount.toGrpcAccountId(),
                schedulingTXValidStart.toGrpc()).toByteArray(), 0L);
        schedule.setPayer(new EntityId(0, 0, 2));

        scheduleStore = mock(ScheduleStore.class);

        subject = SigMetadataLookup.SCHEDULE_REF_LOOKUP_FACTORY.apply(scheduleStore);
    }

    @Test
    void returnsExpectedFailIfExplicitlyMissing() {
        given(scheduleStore.resolve(id)).willReturn(ScheduleID.newBuilder()
                .setShardNum(0L)
                .setRealmNum(0L)
                .setScheduleNum(0L)
                .build());

        // when:
        var result = subject.apply(id);

        // then:
        assertEquals(KeyOrderingFailure.MISSING_SCHEDULE, result.failureIfAny());
    }

    @Test
    void returnsExpectedFailIfMissing() {
        given(scheduleStore.resolve(id)).willReturn(ScheduleStore.MISSING_SCHEDULE);

        // when:
        var result = subject.apply(id);

        // then:
        assertEquals(KeyOrderingFailure.MISSING_SCHEDULE, result.failureIfAny());
    }

    @Test
    void returnsExpectedMetaIfPresent() {
        // setup:
        var expected = ScheduleSigningMetadata.from(schedule);

        given(scheduleStore.resolve(id)).willReturn(id);
        given(scheduleStore.get(id)).willReturn(schedule);

        // when:
        var result = subject.apply(id);

        // then:
        assertEquals(KeyOrderingFailure.NONE, result.failureIfAny());
        // and:
        assertEquals(expected.adminKey(), result.metadata().adminKey());
    }
}
