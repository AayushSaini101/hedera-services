package com.hedera.services.fees;

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

import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.ExchangeRateSet;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TimestampSeconds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class BasicHbarCentExchangeTest {
	private long crossoverTime = 1_234_567L;
	private ExchangeRateSet rates = ExchangeRateSet.newBuilder()
			.setCurrentRate(ExchangeRate.newBuilder()
					.setHbarEquiv(1).setCentEquiv(12)
					.setExpirationTime(TimestampSeconds.newBuilder().setSeconds(crossoverTime)))
			.setNextRate(ExchangeRate.newBuilder()
					.setExpirationTime(TimestampSeconds.newBuilder().setSeconds(crossoverTime * 2))
					.setHbarEquiv(1).setCentEquiv(24))
			.build();

	private BasicHbarCentExchange subject;

	@BeforeEach
	void setUp() {
		subject = new BasicHbarCentExchange();
	}

	@Test
	void updatesWorkWithCurrentRate() {
		// when:
		subject.updateRates(rates);

		// expect:
		assertEquals(rates, subject.activeRates());
		assertEquals(rates.getCurrentRate(), subject.activeRate(beforeCrossInstant));
		assertEquals(rates.getCurrentRate(), subject.rate(beforeCrossTime));
		// and:
		assertEquals(rates, subject.fcActiveRates().toGrpc());
	}

	@Test
	void updatesWorkWithNextRate() {
		// when:
		subject.updateRates(rates);

		// expect:
		assertEquals(rates.getNextRate(), subject.activeRate(afterCrossInstant));
		assertEquals(rates.getNextRate(), subject.rate(afterCrossTime));
		// and:
		assertEquals(rates, subject.fcActiveRates().toGrpc());
	}

	private Timestamp beforeCrossTime = Timestamp.newBuilder()
			.setSeconds(crossoverTime - 1).build();
	private Timestamp afterCrossTime = Timestamp.newBuilder()
			.setSeconds(crossoverTime).build();
	private Instant afterCrossInstant = Instant.ofEpochSecond(crossoverTime);
	private Instant beforeCrossInstant = Instant.ofEpochSecond(crossoverTime - 1);
}
