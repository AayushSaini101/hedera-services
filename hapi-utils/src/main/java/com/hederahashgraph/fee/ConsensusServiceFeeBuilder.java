package com.hederahashgraph.fee;

/*-
 * ‌
 * Hedera Services API Utilities
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

import com.google.common.base.Charsets;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ConsensusCreateTopicTransactionBody;
import com.hederahashgraph.api.proto.java.ConsensusUpdateTopicTransactionBody;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.builder.RequestBuilder;
import com.hederahashgraph.exception.InvalidTxBodyException;

import java.time.Duration;
import java.time.Instant;

/**
 * Fee builder for Consensus service transactions.
 */
public class ConsensusServiceFeeBuilder extends FeeBuilder {

    /**
     * Computes fee for ConsensusCreateTopic transaction
     *
     * @param txBody transaction body
     * @param sigValObj signature value object
     *
     * @return fee data
     * @throws InvalidTxBodyException when transaction body is invalid
     */
    public static FeeData getConsensusCreateTopicFee(TransactionBody txBody, SigValueObj sigValObj)
            throws InvalidTxBodyException {
        if (txBody == null || !txBody.hasConsensusCreateTopic()) {
            throw new InvalidTxBodyException("consensusCreateTopic field not available for Fee Calculation");
        }
        ConsensusCreateTopicTransactionBody createTopicTxBody = txBody.getConsensusCreateTopic();
        int variableSize = computeVariableSizedFieldsUsage(createTopicTxBody.getAdminKey(),
                createTopicTxBody.getSubmitKey(), createTopicTxBody.getMemo(), createTopicTxBody.hasAutoRenewAccount());
        long extraRbsServices = 0;
        if (createTopicTxBody.hasAutoRenewPeriod()) {
            extraRbsServices = getTopicRamBytes(variableSize) * createTopicTxBody.getAutoRenewPeriod().getSeconds();
        }
        return getTxFeeMatrices(
                txBody, sigValObj,
                variableSize + LONG_SIZE,  // For autoRenewPeriod
                extraRbsServices,
                BASIC_ENTITY_ID_SIZE * RECEIPT_STORAGE_TIME_SEC);  // For topicID in receipt
    }

    /**
     * Computes fee for consensus update topic transaction
     *
     * @param txBody transaction body
     * @param rbsIncrease rbs increase
     * @param sigValObj signature value object
     *
     * @return fee data
     * @throws InvalidTxBodyException when transaction body is invalid
     */
    public static FeeData getConsensusUpdateTopicFee(TransactionBody txBody, long rbsIncrease, SigValueObj sigValObj)
            throws InvalidTxBodyException {
        ConsensusUpdateTopicTransactionBody updateTopicTxBody = txBody.getConsensusUpdateTopic();
        int variableSize = computeVariableSizedFieldsUsage(updateTopicTxBody.getAdminKey(),
                updateTopicTxBody.getSubmitKey(), updateTopicTxBody.getMemo().getValue(),
                updateTopicTxBody.hasAutoRenewAccount());
        return getTxFeeMatrices(txBody, sigValObj,
                getConsensusUpdateTopicTransactionBodySize(updateTopicTxBody, variableSize),
                rbsIncrease, 0);
    }

    private static int getConsensusUpdateTopicTransactionBodySize(
            ConsensusUpdateTopicTransactionBody updateTopicTxBody, int variableSize) {
        variableSize += BASIC_ENTITY_ID_SIZE; // topicID
        if (updateTopicTxBody.hasExpirationTime()) {
            variableSize += LONG_SIZE;
        }
        if (updateTopicTxBody.hasAutoRenewPeriod()) {
            variableSize += LONG_SIZE;
        }
        return variableSize;
    }

    /**
     * Computes additional rbs (services) for update topic transaction. If any of the variable sized fields change,
     * or the expiration time changes, rbs for the topic may increase
     *
     * @param txValidStartTimestamp transaction valid start timestamp
     * @param oldAdminKey old admin key
     * @param oldSubmitKey old submit key
     * @param oldMemo old memo
     * @param hasOldAutoRenewAccount boolean representing old auto renew account
     * @param oldExpirationTimeStamp old expiration timestamp
     * @param updateTopicTxBody update topic transaction body
     *
     * @return long representing rbs increase
     */
    public static long getUpdateTopicRbsIncrease(
            Timestamp txValidStartTimestamp,
            Key oldAdminKey, Key oldSubmitKey, String oldMemo, boolean hasOldAutoRenewAccount, Timestamp oldExpirationTimeStamp,
            ConsensusUpdateTopicTransactionBody updateTopicTxBody) {
        int oldRamBytes = getTopicRamBytes(
                computeVariableSizedFieldsUsage(oldAdminKey, oldSubmitKey, oldMemo, hasOldAutoRenewAccount));
        // If value is null, do not update memo field
        String newMemo = updateTopicTxBody.hasMemo() ? updateTopicTxBody.getMemo().getValue() : oldMemo;
        boolean hasNewAutoRenewAccount = hasOldAutoRenewAccount;
        if (updateTopicTxBody.hasAutoRenewAccount()) {  // no change if unspecified
            hasNewAutoRenewAccount = true;
            AccountID account = updateTopicTxBody.getAutoRenewAccount();
            if (account.getAccountNum() == 0 && account.getShardNum() == 0 && account.getRealmNum() == 0) {
                hasNewAutoRenewAccount = false; // cleared if set to 0.0.0
            }
        }
        Key newAdminKey = oldAdminKey;
        if (updateTopicTxBody.hasAdminKey()) { // no change if unspecified
            newAdminKey = updateTopicTxBody.getAdminKey();
            if (newAdminKey.hasKeyList() && newAdminKey.getKeyList().getKeysCount() == 0) {
                newAdminKey = null;  // cleared if set to empty KeyList
            }
        }
        Key newSubmitKey = oldSubmitKey;
        if (updateTopicTxBody.hasSubmitKey()) { // no change if unspecified
            newSubmitKey = updateTopicTxBody.getSubmitKey();
            if (newSubmitKey.hasKeyList() && newSubmitKey.getKeyList().getKeysCount() == 0) {
                newSubmitKey = null;  // cleared if set to empty KeyList
            }
        }
        int newRamBytes = getTopicRamBytes(
                computeVariableSizedFieldsUsage(newAdminKey, newSubmitKey, newMemo, hasNewAutoRenewAccount));

        Timestamp newExpirationTimeStamp =
                updateTopicTxBody.hasExpirationTime() ? updateTopicTxBody.getExpirationTime() : oldExpirationTimeStamp;
        return calculateRbsIncrease(txValidStartTimestamp, oldRamBytes, oldExpirationTimeStamp,
                newRamBytes, newExpirationTimeStamp);
    }

    private static long calculateRbsIncrease(
            Timestamp txValidStartTimestamp, long oldRamBytes, Timestamp oldExpirationTimeStamp,
            long newRamBytes, Timestamp newExpirationTimeStamp) {
        Instant txValidStart = RequestBuilder.convertProtoTimeStamp(txValidStartTimestamp);
        Instant oldExpiration = RequestBuilder.convertProtoTimeStamp(oldExpirationTimeStamp);
        Instant newExpiration = RequestBuilder.convertProtoTimeStamp(newExpirationTimeStamp);

        // RBS which has already been paid for.
        long oldLifetime = Math.min(MAX_ENTITY_LIFETIME, Duration.between(txValidStart, oldExpiration).getSeconds());
        long newLifetime = Math.min(MAX_ENTITY_LIFETIME, Duration.between(txValidStart, newExpiration).getSeconds());
        long rbsRefund = oldRamBytes * oldLifetime;
        long rbsCharge = newRamBytes * newLifetime;
        long netRbs = rbsCharge - rbsRefund;
        return netRbs > 0 ? netRbs : 0;
    }

    /**
     * Computes fee for consensus delete topic transaction
     *
     * @param txBody transaction body
     * @param sigValObj signature value object
     *
     * @return fee data
     * @throws InvalidTxBodyException when transaction body is invalid
     */
    public static FeeData getConsensusDeleteTopicFee(TransactionBody txBody, SigValueObj sigValObj)
            throws InvalidTxBodyException {
        if (txBody == null || !txBody.hasConsensusDeleteTopic()) {
            throw new InvalidTxBodyException("consensusDeleteTopic field not available for Fee Calculation");
        }
        return getTxFeeMatrices(txBody, sigValObj, BASIC_ENTITY_ID_SIZE, 0, 0);
    }

    /**
     * Given transaction specific additional rbh and bpt components, computes fee components for node, network
     * and services.
     * @throws InvalidTxBodyException
     */
    private static FeeData getTxFeeMatrices(
            TransactionBody txBody, SigValueObj sigValObj, int txBodyDataSize, long extraRbsServices,
            long extraRbsNetwork)
            throws InvalidTxBodyException {
        FeeComponents.Builder feeComponentsBuilder = FeeComponents.newBuilder()
                .setVpt(sigValObj.getTotalSigCount())
                .setSbh(0)
                .setGas(0)
                .setTv(0)
                .setBpr(INT_SIZE)
                .setSbpr(0);
        feeComponentsBuilder.setBpt(getCommonTransactionBodyBytes(txBody)
                + txBodyDataSize
                + sigValObj.getSignatureSize());
        feeComponentsBuilder.setRbh(
                getBaseTransactionRecordSize(txBody) * RECEIPT_STORAGE_TIME_SEC
                + extraRbsServices);
        long rbsNetwork = getDefaultRBHNetworkSize() + extraRbsNetwork;
        return getFeeDataMatrices(feeComponentsBuilder.build(), sigValObj.getPayerAcctSigCount(), rbsNetwork);
    }

    /**
     * @param variableSize value returned by {@link #computeVariableSizedFieldsUsage(Key, Key, String, boolean)}.
     * @return Estimation of size (in bytes) used by Topic in memory.
     */
    public static int getTopicRamBytes(int variableSize) {
        return BASIC_ENTITY_ID_SIZE +  // topicID
                3 * LONG_SIZE +  // expirationTime, sequenceNumber, autoRenewPeriod
                BOOL_SIZE +  // deleted
                TX_HASH_SIZE +  // runningHash
                variableSize;  // adminKey, submitKey, memo, autoRenewAccount
    }

    /**
     * Compute variable sized fields usage
     *
     * @param adminKey admin key
     * @param submitKey submit key
     * @param memo memo
     * @param hasAutoRenewAccount boolean representing an auto renew account
     *
     * @return size (in bytes) used by variable sized fields in topic
     */
    public static int computeVariableSizedFieldsUsage(
            Key adminKey, Key submitKey, String memo, boolean hasAutoRenewAccount) {
        int size = 0;
        if (memo != null) {
            size += memo.getBytes(Charsets.UTF_8).length;
        }
        size += getAccountKeyStorageSize(adminKey);
        size += getAccountKeyStorageSize(submitKey);
        size += hasAutoRenewAccount ? BASIC_ENTITY_ID_SIZE : 0;
        return size;
    }
}
