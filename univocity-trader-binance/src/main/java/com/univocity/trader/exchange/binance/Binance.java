package com.univocity.trader.exchange.binance;

import com.univocity.trader.*;
import com.univocity.trader.config.*;
import com.univocity.trader.exchange.binance.api.client.domain.market.*;
import com.univocity.trader.simulation.*;

/**
 * @author uniVocity Software Pty Ltd - <a href="mailto:dev@univocity.com">dev@univocity.com</a>
 */
public final class Binance {

	public static final class Configuration extends com.univocity.trader.config.Configuration<Configuration, Account> {
		private Configuration() {
			super("binance.properties");
		}

		@Override
		protected ConfigurationGroup[] getAdditionalConfigurationGroups() {
			return new ConfigurationGroup[0];
		}

		@Override
		protected Account newAccountConfiguration(String id) {
			return new Account(id);
		}
	}


	public static final class Simulator extends AbstractMarketSimulator<Configuration, Account> {
		private Simulator() {
			super(new Configuration());
		}
	}

	public static final class Trader extends LiveTrader<Candlestick, Configuration, Account> {
		private Trader() {
			super(new BinanceExchange(), new Configuration());
		}
	}

	public static Simulator simulator() {
		return new Simulator();
	}

	public static Trader liveTrader() {
		return new Trader();
	}

}