package com.univocity.trader.simulation;

import com.univocity.trader.*;
import com.univocity.trader.account.*;
import com.univocity.trader.candles.*;
import com.univocity.trader.config.*;
import com.univocity.trader.strategy.*;
import org.apache.commons.lang3.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

import static com.univocity.trader.indicators.base.TimeInterval.*;

/**
 * @author uniVocity Software Pty Ltd - <a href="mailto:dev@univocity.com">dev@univocity.com</a>
 */
public abstract class MarketSimulator<C extends Configuration<C, A>, A extends AccountConfiguration<A>> extends AbstractSimulator<C, A> {

	private static final Logger log = LoggerFactory.getLogger(MarketSimulator.class);

	private final Supplier<Exchange<?, A>> exchangeSupplier;
	private CandleRepository candleRepository;
	private ExecutorService executor;

	protected MarketSimulator(C configuration, Supplier<Exchange<?, A>> exchangeSupplier) {
		super(configuration);
		this.exchangeSupplier = exchangeSupplier;
	}


	protected void initialize() {
		resetBalances();
	}

	protected CandleRepository createCandleRepository() {
		return new CandleRepository(configure().database());
	}

	@Override
	protected final void executeSimulation(Stream<Parameters> parameters) {
		getCandleRepository();
		executor = Executors.newCachedThreadPool();
		try {
			executeWithParameters(parameters);
		} finally {
			executor.shutdown();
			candleRepository.clearCaches();
		}
	}

	protected void executeWithParameters(Stream<Parameters> parameters) {
		parameters.forEach(p -> {
			initialize();
			executeSimulation(createEngines(p));
			liquidateOpenPositions();
			reportResults(p);
		});
	}

	protected TradingManager createTradingManager(AccountManager accountManager, String assetSymbol, String fundSymbol, Parameters parameters) {
		SimulatedExchange exchange = new SimulatedExchange(accountManager);
		exchange.setSymbolInformation(this.symbolInformation);
		SymbolPriceDetails symbolPriceDetails = new SymbolPriceDetails(exchange, accountManager.getReferenceCurrencySymbol());

		return new TradingManager(exchange, symbolPriceDetails, accountManager, assetSymbol, fundSymbol, parameters);
	}

	protected Map<String, Engine[]> createEngines(Parameters parameters) {
		Set<Object> allInstances = new HashSet<>();
		Map<String, Engine[]> symbolHandlers = new HashMap<>();

		getAllPairs().forEach((symbol, pair) -> {
			String assetSymbol = pair[0];
			String fundSymbol = pair[1];

			if (assetSymbol.equals(fundSymbol)) {
				return;
			}

			List<AccountManager> accountsTradingSymbol = new ArrayList<>();
			for (AccountManager account : accounts()) {
				if (account.configuration().symbolPairs().keySet().contains(symbol)) {
					accountsTradingSymbol.add(account);
				}
			}

			Engine[] engines = new Engine[accountsTradingSymbol.size()];
			for (int i = 0; i < engines.length; i++) {
				TradingManager tradingManager = createTradingManager(accounts()[i], assetSymbol, fundSymbol, parameters);
				Engine engine = new TradingEngine(tradingManager, parameters, allInstances);
				engines[i] = engine;
			}

			if (engines.length > 0) {
				symbolHandlers.put(symbol, engines);
			}
		});

		allInstances.clear();
		return symbolHandlers;
	}

	protected final void executeSimulation(Map<String, Engine[]> symbolHandlers) {
		ConcurrentHashMap<String, Enumeration<Candle>> markets = new ConcurrentHashMap<>();

		LocalDateTime start = getSimulationStart();
		LocalDateTime end = getSimulationEnd();

		int activeQueries = 0;
		Map<String, CompletableFuture<Enumeration<Candle>>> futures = new HashMap<>();
		for (String symbol : symbolHandlers.keySet()) {
			activeQueries++;
			boolean loadAllDataFirst = simulation.cacheCandles() || activeQueries > simulation.activeQueryLimit();

			futures.put(symbol, CompletableFuture.supplyAsync(
					() -> candleRepository.iterate(symbol, start.toInstant(ZoneOffset.UTC), end.toInstant(ZoneOffset.UTC), loadAllDataFirst), executor)
			);
		}

		futures.forEach((symbol, candles) -> {
			try {
				markets.put(symbol, candles.get());
			} catch (Exception e) {
				log.error("Error querying " + symbol + " candles from database", e);
			}
		});

		//TODO: allow the original randomized candle processing to happen via configuration.
		final var sortedMarkets = new TreeMap<>(markets);
		MarketReader[] readers = buildMarketReaderList(sortedMarkets, symbolHandlers);

		executeSimulation(readers);
	}

	protected void executeSimulation(MarketReader[] readers) {
		LocalDateTime start = getSimulationStart();
		LocalDateTime end = getSimulationEnd();

		final long startTime = getStartTime();
		final long endTime = getEndTime();

		long candlesProcessed = 0;

		for (long clock = startTime; clock <= endTime; clock += MINUTE.ms) {
			boolean resetClock = false;
			for (int i = 0; i < readers.length; i++) {
				MarketReader reader = readers[i];
				Candle candle = reader.pending;
				if (candle != null) {
					if (candle.openTime + 1 >= clock && candle.openTime <= clock + MINUTE.ms - 1) {
						for (int j = 0; j < reader.engines.length; j++) {
							reader.engines[j].process(candle, false);
						}

						reader.pending = null;
						if (reader.input.hasMoreElements()) {
							Candle next = reader.input.nextElement();
							if (next != null) {
								reader.pending = next;
								if (!resetClock && next.openTime + 1 >= clock && next.openTime <= clock + MINUTE.ms - 1) {
									resetClock = true;
								}
							}
						}
					}
				} else {
					if (reader.input.hasMoreElements()) {
						Candle next = reader.input.nextElement();
						if (next != null) {
							candlesProcessed++;
							reader.pending = next;
						}
					}
				}
			}
			if (resetClock) {
				clock -= MINUTE.ms;
			}
		}
		if (candlesProcessed == 0) {
			throw new IllegalStateException("No candles processed in real time trading simulation from " + start + " to " + end);
		}
	}

	private MarketReader[] buildMarketReaderList(Map<String, Enumeration<Candle>> markets, Map<String, Engine[]> symbolHandlers) {
		List<MarketReader> out = new ArrayList<>();

		for (Map.Entry<String, Enumeration<Candle>> e : markets.entrySet()) {
			MarketReader reader = new MarketReader();
			reader.symbol = e.getKey();
			reader.engines = symbolHandlers.get(e.getKey());
			reader.input = e.getValue();
			out.add(reader);
		}

		return out.toArray(new MarketReader[0]);
	}

	protected void liquidateOpenPositions() {
		for (AccountManager account : accounts()) {
			TradingManager[] managers = account.getAllTradingManagers();
			for (int i = 0; i < managers.length; i++) {
				managers[i].getTrader().liquidateOpenPositions();
			}
		}
	}

	protected void reportResults(Parameters parameters) {
		for (AccountManager account : accounts()) {
			reportResults(account, parameters);
		}
	}

	protected void reportResults(AccountManager account, Parameters parameters) {
		String id = account.getClient().getId();
		System.out.print("-------");
		if (parameters != null && parameters != Parameters.NULL) {
			System.out.print(" | Parameters: " + parameters);
		}
		if (StringUtils.isNotBlank(id)) {
			System.out.print(" | Client: " + id);
		}
		System.out.println(" | -------");
		System.out.print(account.toString());
		System.out.println("Approximate holdings: $" + account.getTotalFundsInReferenceCurrency() + " " + account.getReferenceCurrencySymbol());

		TradingManager managers[] = account.getAllTradingManagers();
		for (int i = 0; i < managers.length; i++) {
			managers[i].getTrader().notifySimulationEnd();
		}
	}


	public final void backfillHistory() {
		TreeSet<String> allSymbols = new TreeSet<>();
		configuration.accounts().forEach(a -> allSymbols.addAll(a.symbolPairs().keySet()));
		allSymbols.addAll(new CandleRepository(configure().database()).getKnownSymbols());
		backfillHistory(allSymbols);
	}

	public final void backfillHistory(String... symbolsToUpdate) {
		LinkedHashSet<String> allSymbols = new LinkedHashSet<>();
		Collections.addAll(allSymbols, symbolsToUpdate);
		backfillHistory(allSymbols);
	}

	public final void backfillHistory(Collection<String> symbols) {
		Exchange<?, A> exchange = exchangeSupplier.get();
		backfillHistory(exchange, symbols);
	}

	protected void backfillHistory(Exchange<?, A> exchange, Collection<String> symbols) {
		CandleRepository candleRepository = new CandleRepository(configure().database());
		final Instant start = simulation.backfillFrom().toInstant(ZoneOffset.UTC);
		final Instant end = simulation.backfillTo().toInstant(ZoneOffset.UTC);
		CandleHistoryBackfill backfill = new CandleHistoryBackfill(candleRepository);
		backfill.resumeBackfill(configuration.simulation().resumeBackfill());
		for (String symbol : symbols) {
			backfill.fillHistoryGaps(exchange, symbol, start, end, configuration.tickInterval());
		}
	}

	protected static class MarketReader {
		String symbol;
		Enumeration<Candle> input;
		Candle pending;
		Engine[] engines;
	}

	public final CandleRepository getCandleRepository() {
		if (candleRepository == null) {
			candleRepository = createCandleRepository();
		}
		return candleRepository;
	}
}