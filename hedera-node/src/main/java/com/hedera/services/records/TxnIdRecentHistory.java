package com.hedera.services.records;

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

import com.hedera.services.state.submerkle.ExpirableTxnRecord;
import com.hedera.services.state.submerkle.RichInstant;
import com.hedera.services.txns.diligence.DuplicateClassification;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.hedera.services.txns.diligence.DuplicateClassification.BELIEVED_UNIQUE;
import static com.hedera.services.txns.diligence.DuplicateClassification.DUPLICATE;
import static com.hedera.services.txns.diligence.DuplicateClassification.NODE_DUPLICATE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static java.util.Comparator.comparing;
import static java.util.Comparator.comparingLong;
import static java.util.stream.Collectors.toList;

public class TxnIdRecentHistory {
	private static final Comparator<RichInstant> RI_CMP =
			comparingLong(RichInstant::getSeconds).thenComparingInt(RichInstant::getNanos);
	private static final Comparator<ExpirableTxnRecord> CONSENSUS_TIME_COMPARATOR =
			comparing(r -> r.getConsensusTimestamp(), RI_CMP);

	private int numDuplicates = 0;

	List<ExpirableTxnRecord> memory = null;
	List<ExpirableTxnRecord> classifiableRecords = null;
	List<ExpirableTxnRecord> unclassifiableRecords = null;

	private static final Set<ResponseCodeEnum> UNCLASSIFIABLE_STATUSES = EnumSet.of(
			INVALID_NODE_ACCOUNT,
			INVALID_PAYER_SIGNATURE);

	public ExpirableTxnRecord priorityRecord() {
		if (areForgotten(classifiableRecords)) {
			return areForgotten(unclassifiableRecords) ? null : unclassifiableRecords.get(0);
		} else {
			return classifiableRecords.get(0);
		}
	}

	public List<ExpirableTxnRecord> duplicateRecords() {
		return Stream.concat(classifiableDuplicates(), unclassifiableDuplicates())
				.sorted(CONSENSUS_TIME_COMPARATOR)
				.collect(toList());
	}

	private Stream<ExpirableTxnRecord> classifiableDuplicates() {
		if (areForgotten(classifiableRecords) || classifiableRecords.size() == 1) {
			return Stream.empty();
		} else {
			return classifiableRecords.subList(1, classifiableRecords.size()).stream();
		}
	}

	private Stream<ExpirableTxnRecord> unclassifiableDuplicates() {
		final var startIndex = areForgotten(classifiableRecords) ? 1 : 0;
		if (areForgotten(unclassifiableRecords) || unclassifiableRecords.size() <= startIndex) {
			return Stream.empty();
		} else {
			return unclassifiableRecords.subList(startIndex, unclassifiableRecords.size()).stream();
		}
	}

	public boolean isForgotten() {
		return areForgotten(classifiableRecords) && areForgotten(unclassifiableRecords);
	}

	private boolean areForgotten(final List<ExpirableTxnRecord> records) {
		return records == null || records.isEmpty();
	}

	public void observe(final ExpirableTxnRecord expirableTxnRecord, final ResponseCodeEnum status) {
		if (UNCLASSIFIABLE_STATUSES.contains(status)) {
			addUnclassifiable(expirableTxnRecord);
		} else {
			addClassifiable(expirableTxnRecord);
		}
	}

	public void stage(ExpirableTxnRecord unorderedRecord) {
		if (memory == null) {
			memory = new ArrayList<>();
		}
		memory.add(unorderedRecord);
	}

	public void observeStaged() {
		memory.sort(CONSENSUS_TIME_COMPARATOR);
		memory.forEach(expirableTxnRecord -> this.observe(expirableTxnRecord,
				ResponseCodeEnum.valueOf(expirableTxnRecord.getReceipt().getStatus())));
		memory = null;
	}

	private void addClassifiable(final ExpirableTxnRecord expirableTxnRecord) {
		if (classifiableRecords == null) {
			classifiableRecords = new LinkedList<>();
		}
		int i = 0;
		final var submittingMember = expirableTxnRecord.getSubmittingMember();
		final var iter = classifiableRecords.listIterator();
		boolean isNodeDuplicate = false;
		while (i < numDuplicates) {
			if (submittingMember == iter.next().getSubmittingMember()) {
				isNodeDuplicate = true;
				break;
			}
			i++;
		}
		if (isNodeDuplicate) {
			classifiableRecords.add(expirableTxnRecord);
		} else {
			numDuplicates++;
			iter.add(expirableTxnRecord);
		}
	}

	private void addUnclassifiable(final ExpirableTxnRecord expirableTxnRecord) {
		if (unclassifiableRecords == null) {
			unclassifiableRecords = new LinkedList<>();
		}
		unclassifiableRecords.add(expirableTxnRecord);
	}

	public void forgetExpiredAt(final long now) {
		if (classifiableRecords != null) {
			forgetFromList(classifiableRecords, now);
		}
		if (unclassifiableRecords != null) {
			forgetFromList(unclassifiableRecords, now);
		}
	}

	private void forgetFromList(final List<ExpirableTxnRecord> records, final long now) {
		final var size = records.size();
		if (size > 1) {
			records.removeIf(expirableTxnRecord -> expirableTxnRecord.getExpiry() <= now);
		} else if (size == 1) {
			final var onlyRecord = records.get(0);
			if (onlyRecord.getExpiry() <= now) {
				records.clear();
			}
		}
	}

	public DuplicateClassification currentDuplicityFor(final long submittingMember) {
		if (numDuplicates == 0) {
			return BELIEVED_UNIQUE;
		}
		final var iter = classifiableRecords.listIterator();
		for (int i = 0; i < numDuplicates; i++) {
			if (iter.next().getSubmittingMember() == submittingMember) {
				return NODE_DUPLICATE;
			}
		}
		return DUPLICATE;
	}
}
