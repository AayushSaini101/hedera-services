package com.hedera.services.bdd.spec.transactions.file;

/*-
 * ‌
 * Hedera Services Test Clients
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
import com.google.common.io.Files;
import com.google.protobuf.ByteString;
import com.google.protobuf.StringValue;
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.fees.FeeCalculator;
import com.hedera.services.bdd.spec.queries.file.HapiGetFileContents;
import com.hedera.services.bdd.spec.queries.file.HapiGetFileInfo;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnFactory;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.suites.HapiApiSuite;
import com.hedera.services.usage.file.ExtantFileContext;
import com.hederahashgraph.api.proto.java.ExchangeRateSet;
import com.hederahashgraph.api.proto.java.FileGetInfoResponse;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.FileUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ServicesConfigurationList;
import com.hederahashgraph.api.proto.java.Setting;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.suites.HapiApiSuite.ONE_HBAR;
import static java.util.Collections.EMPTY_MAP;
import static java.util.Collections.EMPTY_SET;

public class HapiFileUpdate extends HapiTxnOp<HapiFileUpdate> {
	static final Logger log = LogManager.getLogger(HapiFileUpdate.class);
	static final ByteString RANDOM_4K = ByteString.copyFrom(TxnUtils.randomUtf8Bytes(TxnUtils.BYTES_4K));

	/* WARNING - set to true only if you really want to replace 0.0.121/2! */
	private boolean dropUnmentionedProperties = false;
	private boolean useBadlyEncodedWacl = false;
	private boolean useEmptyWacl = false;

	private final String file;
	private OptionalLong expiryExtension = OptionalLong.empty();
	private Optional<Long> lifetimeSecs = Optional.empty();
	private Optional<String> newMemo = Optional.empty();
	private Optional<String> newWaclKey = Optional.empty();
	private Optional<String> newContentsPath = Optional.empty();
	private Optional<String> literalNewContents = Optional.empty();
	private Optional<String> basePropsFile = Optional.empty();
	private Optional<ByteString> newContents = Optional.empty();
	private Optional<Set<String>> propDeletions = Optional.empty();
	private Optional<Map<String, String>> propOverrides = Optional.empty();
	private Optional<Function<HapiApiSpec, ByteString>> contentFn = Optional.empty();

	Optional<Consumer<FileID>> preUpdateCb = Optional.empty();
	Optional<Consumer<ResponseCodeEnum>> postUpdateCb = Optional.empty();

	public HapiFileUpdate(String file) {
		this.file = file;
	}

	@Override
	public HederaFunctionality type() {
		return HederaFunctionality.FileUpdate;
	}

	public HapiFileUpdate droppingUnmentioned() {
		dropUnmentionedProperties = true;
		return this;
	}

	public HapiFileUpdate lifetime(long secs) {
		lifetimeSecs = Optional.of(secs);
		return this;
	}

	public HapiFileUpdate extendingExpiryBy(long secs) {
		expiryExtension = OptionalLong.of(secs);
		return this;
	}

	public HapiFileUpdate entityMemo(String explicit) {
		newMemo = Optional.of(explicit);
		return this;
	}

	public HapiFileUpdate wacl(String name) {
		newWaclKey = Optional.of(name);
		return this;
	}

	public HapiFileUpdate contents(Function<HapiApiSpec, ByteString> fn) {
		contentFn = Optional.of(fn);
		return this;
	}

	public HapiFileUpdate settingProps(String path) {
		return settingProps(path, EMPTY_MAP);
	}

	public HapiFileUpdate settingProps(String path, Map<String, String> overrides) {
		basePropsFile = Optional.of(path);
		propOverrides = Optional.of(overrides);
		return this;
	}

	public HapiFileUpdate overridingProps(Map<String, String> overrides) {
		propOverrides = Optional.of(overrides);
		return this;
	}

	public HapiFileUpdate erasingProps(Set<String> tbd) {
		propDeletions = Optional.of(tbd);
		return this;
	}

	private Setting asSetting(String name, String value) {
		return Setting.newBuilder().setName(name).setValue(value).build();
	}

	public HapiFileUpdate contents(ByteString byteString) {
		newContents = Optional.of(byteString);
		return this;
	}

	public HapiFileUpdate contents(byte[] literal) {
		newContents = Optional.of(ByteString.copyFrom(literal));
		return this;
	}

	public HapiFileUpdate contents(String literal) {
		literalNewContents = Optional.of(literal);
		contents(literal.getBytes());
		return this;
	}

	public HapiFileUpdate path(String path) {
		newContentsPath = Optional.of(path);
		return this;
	}

	private Key emptyWacl() {
		return Key.newBuilder()
				.setKeyList(KeyList.getDefaultInstance())
				.build();
	}

	private Key badlyEncodedWacl() {
		return Key.newBuilder()
				.setKeyList(KeyList.newBuilder()
						.addKeys(Key.getDefaultInstance())
						.addKeys(Key.getDefaultInstance()))
				.build();
	}

	public HapiFileUpdate useBadWacl() {
		useBadlyEncodedWacl = true;
		return this;
	}

	public HapiFileUpdate useEmptyWacl() {
		useEmptyWacl = true;
		return this;
	}

	public HapiFileUpdate alertingPre(Consumer<FileID> preCb) {
		preUpdateCb = Optional.of(preCb);
		return this;
	}

	public HapiFileUpdate alertingPost(Consumer<ResponseCodeEnum> postCb) {
		postUpdateCb = Optional.of(postCb);
		return this;
	}

	@Override
	protected void updateStateOf(HapiApiSpec spec) throws Throwable {
		postUpdateCb.ifPresent(cb -> cb.accept(actualStatus));
		if (actualStatus != ResponseCodeEnum.SUCCESS) {
			return;
		}
		newWaclKey.ifPresent(k -> spec.registry().saveKey(file, spec.registry().getKey(k)));
		expiryExtension.ifPresent(extension -> {
			try {
				spec.registry().saveTimestamp(
						file,
						Timestamp.newBuilder().setSeconds(
								spec.registry().getTimestamp(file).getSeconds() + extension).build());
			} catch (Exception ignore) { }
		});
		if (file.equals(spec.setup().exchangeRatesName()) && newContents.isPresent()) {
			var newRateSet = ExchangeRateSet.parseFrom(newContents.get());
			spec.ratesProvider().updateRateSet(newRateSet);
		}

		if (verboseLoggingOn) {
			log.info("Updated file  {} with ID {}.", file, lastReceipt.getFileID());
		}
	}

	@Override
	protected Consumer<TransactionBody.Builder> opBodyDef(HapiApiSpec spec) throws Throwable {
		Optional<Key> wacl = useBadlyEncodedWacl
				? Optional.of(badlyEncodedWacl())
				: (useEmptyWacl ? Optional.of(emptyWacl()) : newWaclKey.map(spec.registry()::getKey));
		if (newContentsPath.isPresent()) {
			newContents = Optional.of(ByteString.copyFrom(Files.toByteArray(new File(newContentsPath.get()))));
		} else if (contentFn.isPresent()) {
			newContents = Optional.of(contentFn.get().apply(spec));
		} else if (propOverrides.isPresent() || propDeletions.isPresent()) {
			if (propOverrides.isEmpty()) {
				propOverrides = Optional.of(Collections.emptyMap());
			}

			ServicesConfigurationList defaults = readBaseProps(spec);
			ServicesConfigurationList.Builder list = ServicesConfigurationList.newBuilder();
			Map<String, String> overrides = propOverrides.get();
			Map<String, String> defaultPairs = defaults.getNameValueList()
					.stream()
					.collect(Collectors.toMap(Setting::getName, Setting::getValue));

			Set<String> keys = new HashSet<>();
			defaults.getNameValueList()
					.stream()
					.map(Setting::getName)
					.filter(key -> !propDeletions.orElse(EMPTY_SET).contains(key))
					.forEach(keys::add);
			overrides.keySet().stream().forEach(keys::add);

			keys.forEach(key -> {
				if (overrides.containsKey(key))	{
					list.addNameValue(asSetting(key, overrides.get(key)));
				} else {
					list.addNameValue(asSetting(key, defaultPairs.get(key)));
				}
			});

			newContents = Optional.of(list.build().toByteString());
		}

		long nl = -1;
		if (expiryExtension.isPresent()) {
			try {
				var oldExpiry = spec.registry().getTimestamp(file).getSeconds();
				nl = oldExpiry - Instant.now().getEpochSecond() + expiryExtension.getAsLong();
			} catch (Exception ignore) { }
		} else if (lifetimeSecs.isPresent()) {
			nl = lifetimeSecs.get();
		}
		final OptionalLong newLifetime = (nl == -1) ? OptionalLong.empty() : OptionalLong.of(nl);
		var fid = TxnUtils.asFileId(file, spec);
		FileUpdateTransactionBody opBody = spec
				.txns()
				.<FileUpdateTransactionBody, FileUpdateTransactionBody.Builder>body(
						FileUpdateTransactionBody.class, builder -> {
							builder.setFileID(fid);
							newMemo.ifPresent(s -> builder.setMemo(StringValue.newBuilder().setValue(s).build()));
							wacl.ifPresent(k -> builder.setKeys(k.getKeyList()));
							newContents.ifPresent(b -> builder.setContents(b));
							newLifetime.ifPresent(s -> builder.setExpirationTime(TxnFactory.expiryGiven(s)));
						}
				);
		preUpdateCb.ifPresent(cb -> cb.accept(fid));
		return builder -> builder.setFileUpdate(opBody);
	}

	private ServicesConfigurationList readBaseProps(HapiApiSpec spec) {
		if (dropUnmentionedProperties) {
			return ServicesConfigurationList.getDefaultInstance();
		}

		if (!basePropsFile.isPresent()) {
			if (!file.equals(HapiApiSuite.API_PERMISSIONS) && !file.equals(HapiApiSuite.APP_PROPERTIES)) {
				throw new IllegalStateException("Property overrides make no sense for file '" + file + "'!");
			}
			int getsRemaining = 10;
			var gotFileContents = false;
			HapiGetFileContents subOp = null;
			while (!gotFileContents) {
				try {
					var candSubOp = getFileContents(file);
					payer.ifPresent(name -> candSubOp.payingWith(payerToUse(name, spec)));
					allRunFor(spec, candSubOp);
					gotFileContents = true;
					subOp = candSubOp;
				} catch (Throwable ignore) {
					getsRemaining--;
				}
				if (getsRemaining == 0) {
					break;
				}
			}
			if (!gotFileContents) {
				Assertions.fail("Unable to use 'overridingProps', couldn't get existing file contents!");
			}
			try {
				byte[] bytes = subOp.getResponse().getFileGetContents().getFileContents().getContents().toByteArray();
				ServicesConfigurationList defaults = ServicesConfigurationList.parseFrom(bytes);
				return defaults;
			} catch (Exception e) {
				log.error("No available defaults for " + file + " --- aborting!", e);
				throw new IllegalStateException("Property overrides via fileUpdate must have available defaults!");
			}
		} else {
			String defaultsPath = basePropsFile.get();
			try {
				byte[] bytes = java.nio.file.Files.readAllBytes(new File(defaultsPath).toPath());
				ServicesConfigurationList defaults = ServicesConfigurationList.parseFrom(bytes);
				return defaults;
			} catch (Exception e) {
				log.error("No available defaults for " + file + " --- aborting!", e);
				throw new IllegalStateException("Property overrides via fileUpdate must have available defaults!");
			}
		}
	}

	@Override
	protected List<Function<HapiApiSpec, Key>> defaultSigners() {
		List<Function<HapiApiSpec, Key>> signers = new ArrayList<>(oldDefaults());
		if (newWaclKey.isPresent()) {
			signers.add(spec -> spec.registry().getKey(newWaclKey.get()));
		}
		return signers;
	}

	private List<Function<HapiApiSpec, Key>> oldDefaults() {
		return List.of(
			spec -> spec.registry().getKey(effectivePayer(spec)),
			spec -> spec.registry().getKey(file));
	}

	@Override
	protected Function<Transaction, TransactionResponse> callToUse(HapiApiSpec spec) {
		return spec.clients().getFileSvcStub(targetNodeFor(spec), useTls)::updateFile;
	}

	@Override
	protected long feeFor(HapiApiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
		try {
			final FileGetInfoResponse.FileInfo info = lookupInfo(spec);
			FeeCalculator.ActivityMetrics metricsCalc = (_txn, svo) -> {
				var ctx = ExtantFileContext.newBuilder()
						.setCurrentExpiry(info.getExpirationTime().getSeconds())
						.setCurrentMemo(info.getMemo())
						.setCurrentWacl(info.getKeys())
						.setCurrentSize(info.getSize())
						.build();
				return fileOpsUsage.fileUpdateUsage(_txn, suFrom(svo), ctx);
			};
			return spec.fees().forActivityBasedOp(HederaFunctionality.FileUpdate, metricsCalc, txn, numPayerKeys);
		} catch (Throwable ignore) {
			return ONE_HBAR;
		}
	}

	private FileGetInfoResponse.FileInfo lookupInfo(HapiApiSpec spec) throws Throwable {
		HapiGetFileInfo subOp = getFileInfo(file).noLogging().fee(ONE_HBAR);
		Optional<Throwable> error = subOp.execFor(spec);
		if (error.isPresent()) {
			if (!loggingOff) {
				log.warn("Unable to look up current file info!", error.get());
			}
			throw error.get();
		}
		return subOp.getResponse().getFileGetInfo().getFileInfo();
	}

	@Override
	protected HapiFileUpdate self() {
		return this;
	}

	@Override
	protected MoreObjects.ToStringHelper toStringHelper() {
		MoreObjects.ToStringHelper helper = super.toStringHelper()
				.add("fileName", file);
		newContentsPath.ifPresent(p -> helper.add("path", p));
		literalNewContents.ifPresent(l -> helper.add("contents", l));
		return helper;
	}

	private String payerToUse(String designated, HapiApiSpec spec) {
		return isPrivileged(designated, spec) ? spec.setup().genesisAccountName() : designated;
	}

	private boolean isPrivileged(String account, HapiApiSpec spec) {
		return account.equals(spec.setup().addressBookControlName()) ||
				account.equals(spec.setup().exchangeRatesControlName()) ||
				account.equals(spec.setup().feeScheduleControlName()) ||
				account.equals(spec.setup().strongControlName());
	}
}
