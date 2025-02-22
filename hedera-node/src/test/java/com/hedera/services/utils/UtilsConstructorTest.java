package com.hedera.services.utils;

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

import com.hedera.services.context.domain.security.PermissionFileUtils;
import com.hedera.services.context.properties.PropUtils;
import com.hedera.services.contracts.execution.DomainUtils;
import com.hedera.services.contracts.sources.AddressKeyedMapFactory;
import com.hedera.services.exceptions.ValidationUtils;
import com.hedera.services.fees.calculation.FeeCalcUtils;
import com.hedera.services.fees.calculation.meta.FixedUsageEstimates;
import com.hedera.services.files.MetadataMapFactory;
import com.hedera.services.grpc.marshalling.AdjustmentUtils;
import com.hedera.services.keys.HederaKeyActivation;
import com.hedera.services.keys.HederaKeyTraversal;
import com.hedera.services.keys.RevocationServiceCharacteristics;
import com.hedera.services.keys.StandardSyncActivationCheck;
import com.hedera.services.sigs.HederaToPlatformSigOps;
import com.hedera.services.sigs.PlatformSigOps;
import com.hedera.services.sigs.factories.PlatformSigFactory;
import com.hedera.services.sigs.utils.ImmutableKeyUtils;
import com.hedera.services.sigs.utils.PrecheckUtils;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.internals.IdentityCodeUtils;
import com.hedera.services.state.migration.LegacyStateChildIndices;
import com.hedera.services.state.migration.Release0170Migration;
import com.hedera.services.state.migration.StateChildIndices;
import com.hedera.services.state.migration.StateVersions;
import com.hedera.services.stats.MiscRunningAvgs;
import com.hedera.services.stats.MiscSpeedometers;
import com.hedera.services.stats.ServicesStatsConfig;
import com.hedera.services.store.tokens.views.utils.GrpcUtils;
import com.hedera.services.txns.submission.PresolvencyFlaws;
import com.hedera.services.txns.validation.PureValidation;
import com.hedera.services.txns.validation.TokenListChecks;
import com.hedera.services.txns.validation.TransferListChecks;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

class UtilsConstructorTest {
	private static final Set<Class<?>> toBeTested = new HashSet<>(Arrays.asList(
			PermissionFileUtils.class,
			PropUtils.class,
			DomainUtils.class,
			AddressKeyedMapFactory.class,
			ValidationUtils.class,
			FeeCalcUtils.class,
			FixedUsageEstimates.class,
			AdjustmentUtils.class,
			HederaKeyActivation.class,
			HederaKeyTraversal.class,
			RevocationServiceCharacteristics.class,
			StandardSyncActivationCheck.class,
			HederaToPlatformSigOps.class,
			PlatformSigOps.class,
			PlatformSigFactory.class,
			ImmutableKeyUtils.class,
			PrecheckUtils.class,
			MerkleAccount.ChildIndices.class,
			IdentityCodeUtils.class,
			LegacyStateChildIndices.class,
			Release0170Migration.class,
			StateChildIndices.class,
			StateVersions.class,
			MiscRunningAvgs.Names.class,
			MiscRunningAvgs.Descriptions.class,
			MiscSpeedometers.Names.class,
			MiscSpeedometers.Descriptions.class,
			ServicesStatsConfig.class,
			GrpcUtils.class,
			PresolvencyFlaws.class,
			PureValidation.class,
			TokenListChecks.class,
			TransferListChecks.class,
			EntityIdUtils.class,
			HederaDateTimeFormatter.class,
			TokenTypesMapper.class,
			UnzipUtility.class,
			MiscUtils.class,
			MetadataMapFactory.class
	));

	@Test
	void throwsInConstructor() {
		for (final var clazz : toBeTested) {
			assertFor(clazz);
		}
	}

	private static final String UNEXPECTED_THROW = "Unexpected `%s` was thrown in `%s` constructor!";
	private static final String NO_THROW = "No exception was thrown in `%s` constructor!";

	private void assertFor(final Class<?> clazz) {
		try {
			final var constructor = clazz.getDeclaredConstructor();
			constructor.setAccessible(true);

			constructor.newInstance();
		} catch (final InvocationTargetException expected) {
			final var cause = expected.getCause();
			Assertions.assertTrue(cause instanceof UnsupportedOperationException,
					String.format(UNEXPECTED_THROW, cause, clazz));
			return;
		} catch (final Exception e) {
			Assertions.fail(String.format(UNEXPECTED_THROW, e, clazz));
		}
		Assertions.fail(String.format(NO_THROW, clazz));
	}
}
