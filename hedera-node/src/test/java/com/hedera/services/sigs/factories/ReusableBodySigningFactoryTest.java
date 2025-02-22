package com.hedera.services.sigs.factories;

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

import com.hedera.services.utils.TxnAccessor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.hedera.services.sigs.factories.PlatformSigFactoryTest.EXPECTED_SIG;
import static com.hedera.services.sigs.factories.PlatformSigFactoryTest.data;
import static com.hedera.services.sigs.factories.PlatformSigFactoryTest.pk;
import static com.hedera.services.sigs.factories.PlatformSigFactoryTest.sig;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ReusableBodySigningFactoryTest {
	@Mock
	private TxnAccessor accessor;

	private ReusableBodySigningFactory subject = new ReusableBodySigningFactory();

	@Test
	void resetWorks() {
		// when:
		subject.resetFor(accessor);

		// then:
		assertSame(accessor, subject.getAccessor());
	}

	@Test
	void createsExpectedSig() {
		given(accessor.getTxnBytes()).willReturn(data);

		// when:
		subject.resetFor(accessor);
		// and:
		final var actualSig = subject.create(pk, sig);

		// then:
		Assertions.assertEquals(EXPECTED_SIG, actualSig);
	}
}
