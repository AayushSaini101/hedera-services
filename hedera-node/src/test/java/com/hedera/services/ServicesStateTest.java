package com.hedera.services;

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

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.context.init.ServicesInitFlow;
import com.hedera.services.sigs.ExpansionHelper;
import com.hedera.services.sigs.order.SigRequirements;
import com.hedera.services.sigs.sourcing.PubKeyToSigBytes;
import com.hedera.services.state.DualStateAccessor;
import com.hedera.services.state.StateAccessor;
import com.hedera.services.state.forensics.HashLogger;
import com.hedera.services.state.merkle.MerkleDiskFs;
import com.hedera.services.state.merkle.MerkleEntityAssociation;
import com.hedera.services.state.merkle.MerkleNetworkContext;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.state.merkle.MerkleUniqueTokenId;
import com.hedera.services.state.migration.LegacyStateChildIndices;
import com.hedera.services.state.migration.StateChildIndices;
import com.hedera.services.state.org.StateMetadata;
import com.hedera.services.state.migration.StateVersions;
import com.hedera.services.state.submerkle.ExchangeRates;
import com.hedera.services.state.submerkle.SequenceNumber;
import com.hedera.services.store.tokens.views.internals.PermHashInteger;
import com.hedera.services.txns.ProcessLogic;
import com.hedera.services.txns.span.ExpandHandleSpan;
import com.hedera.services.utils.PlatformTxnAccessor;
import com.hedera.test.extensions.LogCaptor;
import com.hedera.test.extensions.LogCaptureExtension;
import com.hedera.test.extensions.LoggingSubject;
import com.hedera.test.extensions.LoggingTarget;
import com.hedera.test.utils.IdUtils;
import com.swirlds.common.Address;
import com.swirlds.common.AddressBook;
import com.swirlds.common.NodeId;
import com.swirlds.common.Platform;
import com.swirlds.common.SwirldDualState;
import com.swirlds.common.SwirldTransaction;
import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.fchashmap.FCOneToManyRelation;
import com.swirlds.fcmap.FCMap;
import com.swirlds.merkletree.MerkleBinaryTree;
import com.swirlds.merkletree.MerklePair;
import com.swirlds.merkletree.MerkleTreeInternalNode;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;

import static com.hedera.services.context.AppsManager.APPS;
import static com.hedera.services.state.submerkle.EntityId.MISSING_ENTITY_ID;
import static com.hedera.services.state.submerkle.RichInstant.MISSING_INSTANT;
import static com.swirlds.common.constructable.ConstructableRegistry.registerConstructable;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith({ MockitoExtension.class, LogCaptureExtension.class })
class ServicesStateTest {
	private final Instant creationTime = Instant.ofEpochSecond(1_234_567L, 8);
	private final Instant consensusTime = Instant.ofEpochSecond(2_345_678L, 9);
	private final NodeId selfId = new NodeId(false, 1L);

	@Mock
	private HashLogger hashLogger;
	@Mock
	private Platform platform;
	@Mock
	private AddressBook addressBook;
	@Mock
	private Address address;
	@Mock
	private ServicesApp app;
	@Mock
	private MerkleDiskFs diskFs;
	@Mock
	private MerkleNetworkContext networkContext;
	@Mock
	private SwirldTransaction transaction;
	@Mock
	private SwirldDualState dualState;
	@Mock
	private StateMetadata metadata;
	@Mock
	private ProcessLogic logic;
	@Mock
	private ExpansionHelper expansionHelper;
	@Mock
	private PlatformTxnAccessor txnAccessor;
	@Mock
	private ExpandHandleSpan expandHandleSpan;
	@Mock
	private SigRequirements retryingKeyOrder;
	@Mock
	private PubKeyToSigBytes pubKeyToSigBytes;
	@Mock
	private StateAccessor workingState;
	@Mock
	private DualStateAccessor dualStateAccessor;
	@Mock
	private ServicesInitFlow initFlow;
	@Mock
	private ServicesApp.Builder appBuilder;

	@LoggingTarget
	private LogCaptor logCaptor;
	@LoggingSubject
	private ServicesState subject = new ServicesState();


	@AfterEach
	void cleanup() {
		if (APPS.includes(selfId.getId())) {
			APPS.clear(selfId.getId());
		}
	}

	@Test
	void logsSummaryAsExpected() {
		// setup:
		subject.setMetadata(metadata);

		subject.setChild(StateChildIndices.NETWORK_CTX, networkContext);

		given(metadata.app()).willReturn(app);
		given(app.hashLogger()).willReturn(hashLogger);
		given(networkContext.toString()).willReturn("IMAGINE");

		// when:
		subject.logSummary();

		// then:
		verify(hashLogger).logHashesFor(subject);
		assertEquals("IMAGINE", logCaptor.infoLogs().get(0));
	}

	@Test
	void getsAccountIdAsExpected() {
		// setup:
		subject.setChild(StateChildIndices.ADDRESS_BOOK, addressBook);

		given(addressBook.getAddress(selfId.getId())).willReturn(address);
		given(address.getMemo()).willReturn("0.0.3");

		// when:
		final var parsedAccount = subject.getAccountFromNodeId(selfId);

		// then:
		assertEquals(IdUtils.asAccount("0.0.3"), parsedAccount);
	}

	@Test
	void onReleaseAndArchiveNoopIfMetadataNull() {
		// when:
		Assertions.assertDoesNotThrow(subject::archive);
		Assertions.assertDoesNotThrow(subject::onRelease);
	}

	@Test
	void onReleaseForwardsToMetadataIfNonNull() {
		// setup:
		subject.setMetadata(metadata);

		// when:
		subject.onRelease();

		// then:
		verify(metadata).release();
	}

	@Test
	void archiveForwardsToMetadata() {
		// setup:
		subject.setMetadata(metadata);

		// when:
		subject.archive();

		// then:
		verify(metadata).archive();
	}

	@Test
	void noMoreTransactionsIsNoop() {
		// expect:
		assertDoesNotThrow(subject::noMoreTransactions);
	}

	@Test
	void expandsSigsAsExpected() throws InvalidProtocolBufferException {
		// setup:
		subject.setMetadata(metadata);

		given(metadata.app()).willReturn(app);
		given(app.expansionHelper()).willReturn(expansionHelper);
		given(app.expandHandleSpan()).willReturn(expandHandleSpan);
		given(app.retryingSigReqs()).willReturn(retryingKeyOrder);
		given(txnAccessor.getPkToSigsFn()).willReturn(pubKeyToSigBytes);
		given(expandHandleSpan.track(transaction)).willReturn(txnAccessor);

		// when:
		subject.expandSignatures(transaction);

		// then:
		verify(expansionHelper).expandIn(txnAccessor, retryingKeyOrder, pubKeyToSigBytes);
	}

	@Test
	void warnsOfIpbe() throws InvalidProtocolBufferException {
		// setup:
		subject.setMetadata(metadata);

		given(metadata.app()).willReturn(app);
		given(app.expandHandleSpan()).willReturn(expandHandleSpan);
		given(expandHandleSpan.track(transaction)).willThrow(InvalidProtocolBufferException.class);

		// when:
		subject.expandSignatures(transaction);

		// then:
		assertThat(
				logCaptor.warnLogs(),
				contains(Matchers.startsWith("Method expandSignatures called with non-gRPC txn")));;
	}

	@Test
	void warnsOfRace() throws InvalidProtocolBufferException {
		// setup:
		subject.setMetadata(metadata);

		given(metadata.app()).willReturn(app);
		given(app.expandHandleSpan()).willReturn(expandHandleSpan);
		given(expandHandleSpan.track(transaction)).willThrow(ConcurrentModificationException.class);

		// when:
		subject.expandSignatures(transaction);

		// then:
		assertThat(
				logCaptor.warnLogs(),
				contains(Matchers.startsWith("Unable to expand signatures, will be verified synchronously")));
	}

	@Test
	void handleNonConsensusTransactionAsExpected() {
		// setup:
		subject.setMetadata(metadata);

		// when:
		subject.handleTransaction(
				1L, false, creationTime, null, transaction, dualState);

		// then:
		verifyNoInteractions(metadata);
	}

	@Test
	void handleConsensusTransactionAsExpected() {
		// setup:
		subject.setMetadata(metadata);

		given(metadata.app()).willReturn(app);
		given(app.logic()).willReturn(logic);
		given(app.dualStateAccessor()).willReturn(dualStateAccessor);

		// when:
		subject.handleTransaction(
				1L, true, creationTime, consensusTime, transaction, dualState);

		// then:
		verify(dualStateAccessor).setDualState(dualState);
		verify(logic).incorporateConsensusTxn(transaction, consensusTime, 1L);
	}

	@Test
	void addressBookCopyWorks() {
		given(addressBook.copy()).willReturn(addressBook);
		// and:
		subject.setChild(StateChildIndices.ADDRESS_BOOK, addressBook);

		// when:
		final var bookCopy = subject.getAddressBookCopy();

		// then:
		assertSame(addressBook, bookCopy);
		verify(addressBook).copy();
	}

	@Test
	void minimumVersionIsRelease0130() {
		// expect:
		assertEquals(StateVersions.RELEASE_0120_VERSION, subject.getMinimumSupportedVersion());
	}

	@Test
	void minimumChildCountsAsExpected() {
		// expect:
		assertEquals(
				LegacyStateChildIndices.NUM_0160_CHILDREN,
				subject.getMinimumChildCount(StateVersions.RELEASE_0160_VERSION));
		assertEquals(
				StateChildIndices.NUM_POST_0160_CHILDREN,
				subject.getMinimumChildCount(StateVersions.RELEASE_0170_VERSION));
		assertEquals(
				StateChildIndices.NUM_PRE_0160_CHILDREN,
				subject.getMinimumChildCount(StateVersions.RELEASE_0120_VERSION));
		assertThrows(IllegalArgumentException.class,
				() -> subject.getMinimumChildCount(StateVersions.RELEASE_0170_VERSION + 1));
	}

	@Test
	void merkleMetaAsExpected() {
		// expect:
		assertEquals(0x8e300b0dfdafbb1aL, subject.getClassId());
		assertEquals(StateVersions.CURRENT_VERSION, subject.getVersion());
	}

	@Test
	void doesntMigrateFromRelease0170() {
		// given:
		subject.addDeserializedChildren(Collections.emptyList(), StateVersions.RELEASE_0170_VERSION);

		// expect:
		assertDoesNotThrow(subject::initialize);
	}

	@Test
	void genesisInitCreatesChildren() {
		// setup:
		ServicesState.setAppBuilder(() -> appBuilder);

		given(appBuilder.bootstrapProps(any())).willReturn(appBuilder);
		given(appBuilder.initialState(subject)).willReturn(appBuilder);
		given(appBuilder.platform(platform)).willReturn(appBuilder);
		given(appBuilder.selfId(1L)).willReturn(appBuilder);
		given(appBuilder.build()).willReturn(app);
		// and:
		given(app.hashLogger()).willReturn(hashLogger);
		given(app.initializationFlow()).willReturn(initFlow);
		given(platform.getSelfId()).willReturn(selfId);

		// when:
		subject.genesisInit(platform, addressBook);

		// then:
		assertFalse(subject.isImmutable());
		// and:
		assertSame(addressBook, subject.addressBook());
		assertNotNull(subject.accounts());
		assertNotNull(subject.storage());
		assertNotNull(subject.topics());
		assertNotNull(subject.tokens());
		assertNotNull(subject.tokenAssociations());
		assertNotNull(subject.scheduleTxs());
		assertNotNull(subject.networkCtx());
		assertNotNull(subject.runningHashLeaf());
		assertNull(subject.networkCtx().consensusTimeOfLastHandledTxn());
		assertEquals(StateVersions.CURRENT_VERSION, subject.networkCtx().getStateVersion());
		assertEquals(1001L, subject.networkCtx().seqNo().current());
		assertNotNull(subject.diskFs());
		// and:
		verify(initFlow).runWith(subject);
		verify(appBuilder).bootstrapProps(any());
		verify(appBuilder).initialState(subject);
		verify(appBuilder).platform(platform);
		verify(appBuilder).selfId(selfId.getId());
		// and:
		assertTrue(APPS.includes(selfId.getId()));

		// cleanup:
		ServicesState.setAppBuilder(DaggerServicesApp::builder);
	}

	@Test
	void nonGenesisInitReusesContextIfPresent() {
		subject.setChild(StateChildIndices.DISK_FS, diskFs);
		subject.setChild(StateChildIndices.NETWORK_CTX, networkContext);

		given(app.hashLogger()).willReturn(hashLogger);
		given(app.initializationFlow()).willReturn(initFlow);
		given(platform.getSelfId()).willReturn(selfId);
		// and:
		APPS.save(selfId.getId(), app);

		// when:
		subject.init(platform, addressBook);

		// then:
		assertSame(addressBook, subject.addressBook());
		assertSame(app, subject.getMetadata().app());
		// and:
		verify(initFlow).runWith(subject);
		verify(diskFs).checkHashesAgainstDiskContents();
		verify(hashLogger).logHashesFor(subject);
	}

	@Test
	void migratesFromRelease0160AsExpected() throws ConstructableRegistryException {
		// setup:
		registerConstructable(new ClassConstructorPair(FCMap.class, FCMap::new));
		registerConstructable(new ClassConstructorPair(MerklePair.class, MerklePair::new));
		registerConstructable(new ClassConstructorPair(MerkleBinaryTree.class, MerkleBinaryTree::new));
		registerConstructable(new ClassConstructorPair(MerkleTreeInternalNode.class, MerkleTreeInternalNode::new));
		// and:
		final var addressBook = new AddressBook();
		final var networkContext = new MerkleNetworkContext();
		networkContext.setSeqNo(new SequenceNumber(1234L));
		networkContext.setMidnightRates(new ExchangeRates(1, 2, 3, 4, 5, 6));
		final FCMap<MerkleUniqueTokenId, MerkleUniqueToken> nfts = new FCMap<>();
		final FCMap<MerkleEntityAssociation, MerkleTokenRelStatus> tokenRels = new FCMap<>();
		final var nftKey = new MerkleUniqueTokenId(MISSING_ENTITY_ID, 1L);
		final var nftVal = new MerkleUniqueToken(MISSING_ENTITY_ID, "TBD".getBytes(), MISSING_INSTANT);
		final var tokenRelsKey = new MerkleEntityAssociation(0, 0, 2, 0, 0, 3);
		final var tokenRelsVal = new MerkleTokenRelStatus(1_234L, true, false, false);
		// and:
		nfts.put(nftKey, nftVal);
		tokenRels.put(tokenRelsKey, tokenRelsVal);
		// and:
		final List<MerkleNode> legacyChildren = legacyChildrenWith(addressBook, networkContext, nfts, tokenRels, true);

		// given:
		subject.addDeserializedChildren(legacyChildren, StateVersions.RELEASE_0160_VERSION);

		// when:
		subject.initialize();

		// then:
		assertEquals(addressBook, subject.getChild(StateChildIndices.ADDRESS_BOOK));
		assertEquals(addressBook, subject.addressBook());
		assertEquals(
				networkContext.midnightRates(),
				((MerkleNetworkContext) subject.getChild(StateChildIndices.NETWORK_CTX)).midnightRates());
		assertEquals(networkContext.midnightRates(), subject.networkCtx().midnightRates());
		assertEquals(
				nftVal,
				((FCMap<MerkleUniqueTokenId, MerkleUniqueToken>) subject.getChild(StateChildIndices.UNIQUE_TOKENS))
						.get(nftKey));
		assertEquals(nftVal, subject.uniqueTokens().get(nftKey));
		assertEquals(
				tokenRelsVal,
				((FCMap<MerkleEntityAssociation, MerkleTokenRelStatus>) subject.getChild(
						StateChildIndices.TOKEN_ASSOCIATIONS))
						.get(tokenRelsKey));
		assertEquals(tokenRelsVal, subject.tokenAssociations().get(tokenRelsKey));
	}

	@Test
	void migratesFromPreRelease0160AsExpected() throws ConstructableRegistryException {
		// setup:
		registerConstructable(new ClassConstructorPair(FCMap.class, FCMap::new));
		registerConstructable(new ClassConstructorPair(MerklePair.class, MerklePair::new));
		registerConstructable(new ClassConstructorPair(MerkleBinaryTree.class, MerkleBinaryTree::new));
		registerConstructable(new ClassConstructorPair(MerkleTreeInternalNode.class, MerkleTreeInternalNode::new));
		// and:
		final var addressBook = new AddressBook();
		final var networkContext = new MerkleNetworkContext();
		networkContext.setSeqNo(new SequenceNumber(1234L));
		networkContext.setMidnightRates(new ExchangeRates(1, 2, 3, 4, 5, 6));
		final FCMap<MerkleUniqueTokenId, MerkleUniqueToken> nfts = new FCMap<>();
		final FCMap<MerkleEntityAssociation, MerkleTokenRelStatus> tokenRels = new FCMap<>();
		final var tokenRelsKey = new MerkleEntityAssociation(0, 0, 2, 0, 0, 3);
		final var tokenRelsVal = new MerkleTokenRelStatus(1_234L, true, false, true);
		// and:
		tokenRels.put(tokenRelsKey, tokenRelsVal);
		// and:
		final List<MerkleNode> legacyChildren = legacyChildrenWith(addressBook, networkContext, nfts, tokenRels, false);

		// given:
		subject.addDeserializedChildren(legacyChildren, StateVersions.RELEASE_0160_VERSION);

		// when:
		subject.initialize();

		// then:
		assertEquals(addressBook, subject.getChild(StateChildIndices.ADDRESS_BOOK));
		assertEquals(addressBook, subject.addressBook());
		assertEquals(
				networkContext.midnightRates(),
				((MerkleNetworkContext) subject.getChild(StateChildIndices.NETWORK_CTX)).midnightRates());
		assertEquals(networkContext.midnightRates(), subject.networkCtx().midnightRates());
		assertEquals(
				tokenRelsVal,
				((FCMap<MerkleEntityAssociation, MerkleTokenRelStatus>) subject.getChild(
						StateChildIndices.TOKEN_ASSOCIATIONS))
						.get(tokenRelsKey));
		assertEquals(tokenRelsVal, subject.tokenAssociations().get(tokenRelsKey));
	}

	@Test
	void forwardsFcomtrAsExpected() {
		// setup:
		final FCOneToManyRelation<PermHashInteger, Long> a = new FCOneToManyRelation<>();
		final FCOneToManyRelation<PermHashInteger, Long> b = new FCOneToManyRelation<>();
		final FCOneToManyRelation<PermHashInteger, Long> c = new FCOneToManyRelation<>();
		// and:
		subject.setMetadata(metadata);

		given(metadata.getUniqueTokenAssociations()).willReturn(a);
		given(metadata.getUniqueOwnershipAssociations()).willReturn(b);
		given(metadata.getUniqueTreasuryOwnershipAssociations()).willReturn(c);

		// expect:
		assertSame(a, subject.uniqueTokenAssociations());
		assertSame(b, subject.uniqueOwnershipAssociations());
		assertSame(c, subject.uniqueTreasuryOwnershipAssociations());
	}

	@Test
	void copySetsMutabilityAsExpected() {
		// when:
		final var copy = subject.copy();

		// then:
		assertTrue(subject.isImmutable());
		assertFalse(copy.isImmutable());
	}

	@Test
	void copyUpdateCtxWithNonNullMeta() {
		// setup:
		subject.setMetadata(metadata);

		given(metadata.app()).willReturn(app);
		given(app.workingState()).willReturn(workingState);

		// when:
		final var copy = subject.copy();

		// then:
		verify(workingState).updateFrom(copy);
	}

	@Test
	void copiesNonNullChildren() {
		// setup:
		subject.setChild(StateChildIndices.ADDRESS_BOOK, addressBook);
		subject.setChild(StateChildIndices.NETWORK_CTX, networkContext);
		subject.setChild(StateChildIndices.DISK_FS, diskFs);
		// and:
		subject.setMetadata(metadata);
		subject.setDeserializedVersion(10);

		given(addressBook.copy()).willReturn(addressBook);
		given(networkContext.copy()).willReturn(networkContext);
		given(diskFs.copy()).willReturn(diskFs);
		given(metadata.copy()).willReturn(metadata);
		given(metadata.app()).willReturn(app);
		given(app.workingState()).willReturn(workingState);

		// when:
		final var copy = subject.copy();

		// then:
		assertEquals(10, copy.getDeserializedVersion());
		assertSame(metadata, copy.getMetadata());
		verify(metadata).copy();
		// and:
		assertSame(addressBook, copy.addressBook());
		assertSame(networkContext, copy.networkCtx());
		assertSame(diskFs, copy.diskFs());
	}

	private List<MerkleNode> legacyChildrenWith(
			AddressBook addressBook,
			MerkleNetworkContext networkContext,
			FCMap<MerkleUniqueTokenId, MerkleUniqueToken> nfts,
			FCMap<MerkleEntityAssociation, MerkleTokenRelStatus> tokenRels,
			boolean withNfts
	) {
		final List<MerkleNode> legacyChildren = new ArrayList<>();
		legacyChildren.add(addressBook);
		legacyChildren.add(networkContext);
		legacyChildren.add(null);
		legacyChildren.add(null);
		legacyChildren.add(null);
		legacyChildren.add(null);
		legacyChildren.add(tokenRels);
		legacyChildren.add(null);
		legacyChildren.add(null);
		legacyChildren.add(null);
		if (withNfts) {
			legacyChildren.add(nfts);
		}
		return legacyChildren;
	}
}
