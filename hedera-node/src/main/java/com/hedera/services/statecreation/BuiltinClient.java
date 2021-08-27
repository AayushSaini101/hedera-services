package com.hedera.services.statecreation;

import com.google.common.base.Stopwatch;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.context.ServicesContext;
import com.hedera.services.statecreation.creationtxns.ContractCreateTxnFactory;
import com.hedera.services.statecreation.creationtxns.CryptoCreateTxnFactory;
import com.hedera.services.statecreation.creationtxns.NftCreateTxnFactory;
import com.hedera.services.statecreation.creationtxns.ScheduleCreateTxnFactory;
import com.hedera.services.statecreation.creationtxns.TokenAssociateCreateTxnFactory;
import com.hedera.services.statecreation.creationtxns.TokenCreateTxnFactory;
import com.hedera.services.statecreation.creationtxns.TopicCreateTxnFactory;
import com.hedera.services.statecreation.creationtxns.FileCreateTxnFactory;
import com.hedera.services.statecreation.creationtxns.UniqTokenCreateTxnFactory;
import com.hedera.services.utils.SignedTxnAccessor;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Transaction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Properties;
import java.util.Random;
//import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hedera.services.statecreation.creationtxns.utils.TempUtils.asAccount;


public class BuiltinClient implements Runnable {
	static Logger log = LogManager.getLogger(BuiltinClient.class);

	private final static int ONE_SECOND = 1000; // ms

	final private static int MAX_NFT_PER_TOKEN = 1000;
	final private static int NFT_MINT_BATCH_SIZE = 10;

	final private static int SYSTEM_ACCOUNTS = 1000;
	final private Map<Integer, String> processOrders;
	final private Properties properties;
	final private AtomicBoolean allCreated;
	final ServicesContext ctx;
	private Random random = new Random();

	private static int totalAccounts;
	private static int tokenNumStart;
	private static int totalTokens;
	private static int uniqTokenNumStart;
	private static int totalUniqTokens;
	private static int contractFileNumStart;
	private static int totalContractFiles;

//	private Stopwatch stopWatch = Stopwatch.createUnstarted();


	private static AtomicInteger currentEntityNumEnd = new AtomicInteger(1001);

	public BuiltinClient(final Properties layoutProps,
			final Map<Integer, String> processOrders, final ServicesContext ctx,
			final AtomicBoolean allCreated) {
		this.processOrders = processOrders;
		this.properties = layoutProps;
		this.ctx = ctx;
		this.allCreated = allCreated;
	}

	@Override
	public void run() {
		log.info("In built client, wait for server to start up from genesis...");
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {	}

//		stopWatch.start();
		processOrders.forEach(this::createEntitiesFor);

		log.info("All entities created. Shutdown the client thread");

		try {
			Thread.sleep(30000);
		} catch (InterruptedException e) {	}

		allCreated.set(true);
		log.info("Current seqNo: " + ctx.seqNo().current());
	}

	private void createEntitiesFor(Integer posi, String entityType) {
		String valStr = properties.getProperty(processOrders.get(posi) + ".total");
		int totalToCreate = Integer.parseInt(valStr);
		//valStr = properties.getProperty(entityType + ".creation.rate");
		//log.info("Create " + totalToCreate + " : " + entityType + ", rate: " + valStr);

		//int creationRate = Integer.parseInt(valStr);

		if(totalToCreate > 0) {
			log.info("Start to build " + valStr + " " + entityType + ". Starting number: " + currentEntityNumEnd.get());// + ", at rate: " + creationRate);
			//createEntitiesFor(entityType,  totalToCreate, creationRate, properties, processOrders);
			createEntitiesFor(entityType,  totalToCreate, properties, processOrders);
			log.info( entityType + " value range [{} - {}]",
					currentEntityNumEnd.get(),currentEntityNumEnd.get() + totalToCreate - 1);

			log.info("Current seqNo: " + ctx.seqNo().current());
			currentEntityNumEnd.set(currentEntityNumEnd.get() + totalToCreate);
		}
	}

	private void createEntitiesFor(final String entityType,
			final int totalToCreate,
			//final int creationRate,
			final Properties properties,
			final Map<Integer, String> layoutProps) {

		switch (entityType) {
			case "accounts":
				//createAccounts(totalToCreate, creationRate);
				createAccounts(totalToCreate);
				break;
			case "topics":
				//createTopics(totalToCreate, creationRate);
				createTopics(totalToCreate);
				break;
			case "tokens":
				//createTokens(totalToCreate, creationRate);
				createTokens(totalToCreate);
				break;
			case "files":
				//createFiles(totalToCreate, creationRate, false);
				createFiles(totalToCreate, false);
				break;
			case "smartContracts":
				//createSmartContracts(totalToCreate, creationRate, properties);
				createSmartContracts(totalToCreate, properties);
				break;
			case "tokenAssociations":
				//createTokenAssociations(totalToCreate, creationRate);
				createTokenAssociations(totalToCreate);
				break;
			case "uniqueTokens":
				//createUniqTokens(totalToCreate, creationRate);
				createUniqTokens(totalToCreate);
				break;
			case "nfts":
				//createNfts(totalToCreate, creationRate);
				createNfts(totalToCreate);
				break;
			case "schedules":
				//createSchedules(totalToCreate, creationRate);
				createSchedules(totalToCreate);
				break;
			default:
				log.info("not implemented yet for " + entityType);
				break;
		}
	}

//	private void pauseForRemainingMs() {
//		stopWatch.stop();
//		long remaining = ONE_SECOND - stopWatch.elapsed(TimeUnit.MILLISECONDS);
//		if(remaining > 0) {
//			log.info("Slow down to send txn for {} ms", remaining);
//			try {
//				Thread.sleep(remaining);
//			} catch (InterruptedException e) { }
//		}
//		stopWatch.reset();
//		stopWatch.start();
//	}
//
//	private void pauseForRemainingMs(final long remainingMs) {
//		if(remainingMs > 0) {
//			log.info("Slow down to send txn for {} ms", remainingMs);
//			try {
//				Thread.sleep(remainingMs);
//			} catch (InterruptedException e) { }
//		}
//	}


	private void backOff() {
		try {
			Thread.sleep(100);
		} catch (InterruptedException e) { }
	}

	@FunctionalInterface
	interface CreateTxnThrows<R, T> {
		R create(T t) throws Throwable;
	}

	private int getReportStep(final int totalToCreate) {
		if(totalToCreate > 1_000_000) {
			return totalToCreate / 100;
		} else if(totalToCreate > 100_000) {
			return totalToCreate / 50;
		} else if (totalToCreate > 10) {
			return totalToCreate / 10;
		}
		return totalToCreate;
	}

	private boolean submitOneTxn(final Transaction txn,
			final HederaFunctionality txnType,
			final int index,
			final int reportStep
			//final int totalToCreate
			) throws InvalidProtocolBufferException, Throwable {
		SignedTxnAccessor accessor = new SignedTxnAccessor(txn);
		ResponseCodeEnum responseCode = ctx.submissionManager().trySubmission(accessor);

		if(responseCode != OK) {
			log.info("Backoff and resubmit later: response code is {} for {} txn #{} and body {}",
					responseCode , txnType, index, txn.toString());
			backOff();
			log.info("Transaction type {} handled: {}", txnType, ctx.opCounters().handledSoFar(txnType));
			return false;
		} else {
			if(index % reportStep  == 0 && txnType != HederaFunctionality.TokenMint) {
				log.info("Successfully submitted {} txn #{} ", txnType, index);
				log.info("Transaction type {} handled: {}", txnType, ctx.opCounters().handledSoFar(txnType));
				log.info("Current seqNo: " + ctx.seqNo().current());
			}
		}
		return true;
	}

	private void waitingForServer(final HederaFunctionality txnType, final int txnSubmitted,
			final int txnsPendingAllowed, final int totalWaiting,
			final int reportEvery) {
		int count = 0;
		while((ctx.opCounters().handledSoFar(txnType) <  txnSubmitted - txnsPendingAllowed)	&& count < totalWaiting) {
			count++;
			if(count % reportEvery == 0) {
				log.info("Pause for transaction type {} handled: {}, submitted = {}", txnType,
						ctx.opCounters().handledSoFar(txnType), txnSubmitted);
			}
			backOff();
		}
	}

	private <T> void create(CreateTxnThrows<Transaction, Integer> createOne,
			final int totalToCreate,
			//final int creationRate,
			//final String txnType,
			final HederaFunctionality txnType,
			final int reportSteps) {

		int reportEvery;
		if(reportSteps > 0) {
			reportEvery = totalToCreate / reportSteps;
		} else {
			reportEvery = getReportStep(totalToCreate);
		}
		int i = 0;
		while(i < totalToCreate) {
			int j = 0;
			long startTime = System.currentTimeMillis();
			try {
				Transaction txn = createOne.create(i);
				if(submitOneTxn(txn, txnType, i, reportEvery)) {
					i++;
				}
			} catch (InvalidProtocolBufferException e) {
				log.warn("Bad transaction body for {}: ", txnType, e);
			} catch (Throwable e) {
				log.warn("Possible invalid signature for transaction {}: ", txnType, e);
			}
//			j++;
//			if(j >= creationRate) {
//				j = 0;
//				long durationInMs = System.currentTimeMillis() - startTime;
//				if(ONE_SECOND > durationInMs) {
//					pauseForRemainingMs(ONE_SECOND - durationInMs);
//				}
//			}

			waitingForServer(txnType, i, 5000, 100, 10);

//			int count = 0;
//			while((ctx.opCounters().handledSoFar(txnType) <  i - 1000)	&& count < 100) {
//				count++;
//				if(count % 10 == 0) {
//					log.info("Pause for transaction type {} handled: {}, submitted = {}", txnType,
//							ctx.opCounters().handledSoFar(txnType), i);
//				}
//				backOff();
//			}
		}
	}

	private void createAccounts(final int totalToCreate) {
		CreateTxnThrows<Transaction, Integer> createAccount = (Integer i) -> {
			Transaction txn = CryptoCreateTxnFactory.newSignedCryptoCreate()
					.balance(i * 1_000_000L)
					.receiverSigRequired(false)
					.fee(1_000_000_000L)
					.memo("Memo for account " + i)
					.get();
			return txn;
		};

		create(createAccount, totalToCreate, HederaFunctionality.CryptoCreate, 0);

		totalAccounts = totalToCreate;

		try {
			Thread.sleep(10000);
		} catch (InterruptedException e) { }

		log.info("Done creating {} accounts", totalToCreate);
	}

	private void createTopics(final int totalToCreate//, final int creationRate
	) {

		CreateTxnThrows<Transaction, Integer> createTopic = (Integer i) -> {
			Transaction txn = TopicCreateTxnFactory.newSignedConsensusCreateTopic()
					.fee(1_000_000_000L)
					//.payer("0.0." + selectRandomAccount()) to avoid INSUFFICIENT_PAYER_BALANCE issue
					.memo("Memo for topics " + i)
					.get();
			return txn;
		};

		create(createTopic, totalToCreate, HederaFunctionality.ConsensusCreateTopic, 0);

		log.info("Done creating {} topics", totalToCreate);
	}

	private void createTokens(final int totalToCreate) {
		CreateTxnThrows<Transaction, Integer> createToken = (Integer i) -> {
			Transaction txn = TokenCreateTxnFactory.newSignedTokenCreate()
					.fee(1_000_000_000L)
					.name("token" + i)
					.symbol("SYMBOL" + i)
					.treasury(asAccount("0.0." + selectRandomAccount()))
					.get();
			return txn;
		};

		create(createToken, totalToCreate, HederaFunctionality.TokenCreate, 10);
		tokenNumStart = currentEntityNumEnd.get();
		totalTokens = totalToCreate;
		log.info("Done creating {} fungible tokens", totalToCreate);
	}

	private void createFiles(final int totalToCreate, //, final int creationRate,
			final boolean forContractFile) {

		CreateTxnThrows<Transaction, Integer> createFile = (Integer i) -> {
			Transaction txn = FileCreateTxnFactory.newSignedFileCreate()
					.fee(1_000_000_000L)
					.forContractFile(forContractFile)
					.get();
			return txn;
		};

		create(createFile, totalToCreate,  HederaFunctionality.FileCreate, 10);

		if(forContractFile) {
			contractFileNumStart = currentEntityNumEnd.get();
			totalContractFiles = totalToCreate;
		}
		log.info("Done creating {} files", totalToCreate);
	}

	private void createSmartContracts(final int totalToCreate, //final int creationRate,
			final Properties properties ) {
		final int totalContractFile = Integer.parseInt(properties.getProperty("smartContracts.total.file"));
		//final int fileCreationRate = Integer.parseInt(properties.getProperty("files.creation.rate"));
		createFiles(totalContractFile, true);

		CreateTxnThrows<Transaction, Integer> createContract = (Integer i) -> {
			Transaction txn = ContractCreateTxnFactory.newSignedContractCreate()
					.fee(10_000_000L)
					.initialBalance(100_000_000L)
					.fileID(FileID.newBuilder()
							.setFileNum(selectRandomContractFile()).setRealmNum(0).setShardNum(0)
							.build())
					.payer("0.0.2")
					.gas(5_000_000L)
					.get();
			return txn;
		};
		create(createContract, totalToCreate, HederaFunctionality.ContractCreate, 10);
		log.info("Done creating {} smart contracts", totalToCreate);
	}

	private void createSchedules(final int totalToCreate//, final int creationRate
			 ) {
		CreateTxnThrows<Transaction, Integer> createSchedule = (Integer i) -> {
			Transaction txn = ScheduleCreateTxnFactory.newSignedScheduleCreate()
					.fee(1_000_000_000L)
					.designatingPayer(asAccount("0.0." + selectRandomAccount()))
					.memo("Schedule " + i)
					.from(selectRandomAccount())
					.to(selectRandomAccount())
					.payer("0.0.2")
					.get();
			return txn;
		};

		create(createSchedule, totalToCreate, HederaFunctionality.ScheduleCreate, 10);

		log.info("Done creating {} schedule transactions", totalToCreate);
	}

	private void createTokenAssociations(final int totalToCreate//, final int creationRate
	) {
		CreateTxnThrows<Transaction, Integer> createTokenAssociation = (Integer i) -> {
			Transaction txn = TokenAssociateCreateTxnFactory.newSignedTokenAssociate()
					.fee(1_000_000_000L)
					.targeting(selectRandomAccount())
					.associating(selectRandomToken())
					.get();
			return txn;
		};

		create(createTokenAssociation, totalToCreate, HederaFunctionality.TokenAssociateToAccount, 0);

//		try {
//			Thread.sleep(10000);
//		} catch (InterruptedException e) {	}
		log.info("Done creating {} Token Associations", totalToCreate);
	}

	private void createUniqTokens(final int totalToCreate //, final int creationRate
	) {
		CreateTxnThrows<Transaction, Integer> createUniqueToken = (Integer i) -> {
			Transaction txn = UniqTokenCreateTxnFactory.newSignedUniqTokenCreate()
					.fee(1_000_000_000L)
					.name("uniqToken" + i)
					.symbol("UNIQ" + i)
					.treasury(asAccount("0.0." + selectRandomAccount()))
					.get();
			return txn;
		};

		create(createUniqueToken, totalToCreate, HederaFunctionality.TokenCreate, 10);

		uniqTokenNumStart = currentEntityNumEnd.get();
		totalUniqTokens = totalToCreate;

//		try {
//			Thread.sleep(10000);
//		} catch (InterruptedException e) { }

		log.info("Done creating {} uniqTokens", totalToCreate);
	}

	private void createNfts(int totalToCreate //, final int creationRate
	) {
		int nftsPerToken = (int) Math.ceil(totalToCreate / totalUniqTokens);
		if (nftsPerToken > MAX_NFT_PER_TOKEN) {
			log.warn("One token can't have {} NFTs. Max is {}", nftsPerToken, MAX_NFT_PER_TOKEN);
			nftsPerToken = MAX_NFT_PER_TOKEN;
		}
		totalToCreate = nftsPerToken * totalUniqTokens;
		log.info("NFTs per uniq token: {}", nftsPerToken );
		log.info("Will mint total {} NFTs for {} unique tokens", totalToCreate, totalUniqTokens);

		int totalMintTimes;
		int batchSize;
		if(nftsPerToken > NFT_MINT_BATCH_SIZE) {
			totalMintTimes = (int)Math.ceil(nftsPerToken / NFT_MINT_BATCH_SIZE) * totalUniqTokens;
			batchSize = NFT_MINT_BATCH_SIZE;
		} else {
			totalMintTimes = totalUniqTokens;
			batchSize = 1;
		}

		int reportEvery = totalUniqTokens / 100;

		for (int i = 0; i < totalUniqTokens; i++) {
			int k = 0;
			try {
				int rounds = nftsPerToken / NFT_MINT_BATCH_SIZE;
				int j = 0;
				for(j = 0; j < rounds; j++) {
					mintOneBatchNFTs(i, nftsPerToken, j, NFT_MINT_BATCH_SIZE, reportEvery);
				}
				int remaining = nftsPerToken - rounds * NFT_MINT_BATCH_SIZE;
				if(remaining > 0) {
					log.info("Submit remaining {} NFTs creation for token #{}", remaining, i+uniqTokenNumStart);
					mintOneBatchNFTs(i, nftsPerToken, j, remaining, reportEvery);
				}
			} catch (Throwable e) {
				log.warn("Something happened while creating nfts: ", e);
			}
			k++;

//			int count = 0;
//			while((ctx.opCounters().handledSoFar(HederaFunctionality.TokenMint) < i * nftsPerToken / batchSize - 5000)
//					&& count < 1000) {
//				count++;
//				if(count % 10 == 0) {
//					log.info("Pause for transaction type {} handled: {}, submitted = {}", HederaFunctionality.TokenMint,
//							ctx.opCounters().handledSoFar(HederaFunctionality.TokenMint), i * nftsPerToken / batchSize);
//					//count = 0;
//				}
//				backOff();
//			}

			waitingForServer(HederaFunctionality.TokenMint, i * nftsPerToken / batchSize,
					5000, 1000, 10);

//			if(k * nftsPerToken > creationRate * NFT_MINT_BATCH_SIZE) {
//				k = 0;
//				pauseForRemainingMs();
//			}
			if(i % reportEvery == 0) {
				log.info("Successfully submitted {} for unique token #{} with {} NFTs", HederaFunctionality.TokenMint, i, nftsPerToken);
			}
		}

		// final wait to give platform time to clear wait queue.
//		int wait = 0;
//		while(ctx.opCounters().handledSoFar(HederaFunctionality.TokenMint) < totalMintTimes - 10000 && wait < 50000 ) {
//			wait++;
//			if(wait % 100 == 0) {
//				log.info("Transaction type {} handled: {}", HederaFunctionality.TokenMint,
//						ctx.opCounters().handledSoFar(HederaFunctionality.TokenMint));
//			}
//			backOff();
//		}
//
		log.info("Wait for platform to consume excessive mint operation before moving on");
		waitingForServer(HederaFunctionality.TokenMint, totalMintTimes, 10000, 50000, 100);

//		try {
//			Thread.sleep(10000);
//		} catch (InterruptedException e) {	}
		log.info("Done creating {} NFTs", nftsPerToken * totalUniqTokens);
	}

	private void mintOneBatchNFTs(final int i, final int nftsPerToken, final int round,
			final int batchSize, final int reportStep) throws Throwable {
		Transaction txn = NftCreateTxnFactory.newSignedNftCreate()
				.fee(1_000_000_000L)
				.forUniqToken(i + uniqTokenNumStart)
				.metaDataPer(batchSize)
				.get();
		int nftCreatedSofar = i * nftsPerToken + ((round - 1) > 0 ? (round - 1) : 0) * NFT_MINT_BATCH_SIZE + batchSize;
		submitOneTxn(txn, HederaFunctionality.TokenMint, nftCreatedSofar / batchSize, reportStep);
	}

	private int selectRandomAccount() {
		return SYSTEM_ACCOUNTS + random.nextInt(totalAccounts);
	}
	private int selectRandomToken() {
		return tokenNumStart + random.nextInt(totalTokens);
	}
	private int selectRandomContractFile() {
		return contractFileNumStart + random.nextInt(totalContractFiles);
	}
}
