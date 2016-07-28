/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Gareth Jon Lynch
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.gazbert.bxbot.core.exchanges;

import com.gazbert.bxbot.core.api.exchange.AuthenticationConfig;
import com.gazbert.bxbot.core.api.exchange.ExchangeAdapter;
import com.gazbert.bxbot.core.api.exchange.ExchangeConfig;
import com.gazbert.bxbot.core.api.trading.*;
import com.google.common.base.MoreObjects;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.*;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * TODO Work in progress...
 * <p>
 * Exchange Adapter for integrating with the Kraken exchange.
 * The Kraken API is documented <a href="https://www.kraken.com/en-gb/help/api">here</a>.
 * </p>
 * <p>
 * <strong>
 * DISCLAIMER:
 * This Exchange Adapter is provided as-is; it might have bugs in it and you could lose money. Despite running live
 * on Kraken, it has only been unit tested up until the point of calling the
 * {@link #sendPublicRequestToExchange(String, Map)} and {@link #sendAuthenticatedRequestToExchange(String, Map)}
 * methods. Use it at our own risk!
 * </strong>
 * </p>
 * <p>
 * <p>
 * TODO API version
 * </p>
 * <p>
 * <p>
 * TODO Fees
 * </p>
 * <p>
 * <p>
 * TODO Call rate limit
 * </p>
 * <p>
 * <p>
 * TODO update this...
 * Kraken markets assets (currencies) can be referenced using their ISO4217-A3 names in the case of ISO registered names,
 * their 3 letter commonly used names in the case of unregistered names, or their X-ISO4217-A3 code (see http://www.ifex-project.org/).
 * E.g. you can access the XBT/USD market using either of the following ids: 'XBTUSD' or 'XXBTZUSD'. The exchange always
 * returns market id back in the latter format, i.e. 'XXBTZUSD'.
 * </p>
 * <p>
 * <p>
 * The Exchange Adapter is <em>not</em> thread safe. It expects to be called using a single thread in order to
 * preserve trade execution order. The {@link URLConnection} achieves this by blocking/waiting on the input stream
 * (response) for each API call.
 * </p>
 * <p>
 * <p>
 * The {@link TradingApi} calls will throw a {@link ExchangeNetworkException} if a network error occurs trying to
 * connect to the exchange. A {@link TradingApiException} is thrown for <em>all</em> other failures.
 * </p>
 *
 * @author gazbert
 * @since 16/07/2016
 */
public final class KrakenExchangeAdapter extends AbstractExchangeAdapter implements ExchangeAdapter {

    private static final Logger LOG = LogManager.getLogger();

    /**
     * The base URI for all Kraken API calls.
     */
    private static final String KRAKEN_BASE_URI = "https://api.kraken.com/";

    /**
     * The version of the Kraken API being used.
     */
    private static final String KRAKEN_API_VERSION = "0";

    /**
     * The public API path part of the Kraken base URI.
     */
    private static final String KRAKEN_PUBLIC_PATH = "/public/";

    /**
     * The private API path part of the Kraken base URI.
     */
    private static final String KRAKEN_PRIVATE_PATH = "/private/";

    /**
     * The public API URI.
     */
    private static final String PUBLIC_API_BASE_URL = KRAKEN_BASE_URI + KRAKEN_API_VERSION + KRAKEN_PUBLIC_PATH;

    /**
     * The Authenticated API URI.
     */
    private static final String AUTHENTICATED_API_URL = KRAKEN_BASE_URI + KRAKEN_API_VERSION + KRAKEN_PRIVATE_PATH;

    /**
     * Used for reporting unexpected errors.
     */
    private static final String UNEXPECTED_ERROR_MSG = "Unexpected error has occurred in Kraken Exchange Adapter. ";

    /**
     * Unexpected IO error message for logging.
     */
    private static final String UNEXPECTED_IO_ERROR_MSG = "Failed to connect to Exchange due to unexpected IO error.";

    /**
     * Error message for when API call to get Market Orders fails.
     */
    private static final String FAILED_TO_GET_MARKET_ORDERS = "Failed to get Market Order Book from exchange. Details: ";

    /**
     * Error message for when API call to get Balance fails.
     */
    private static final String FAILED_TO_GET_BALANCE = "Failed to get Balance from exchange. Details: ";

    /**
     * Error message for when API call to get Ticker fails.
     */
    private static final String FAILED_TO_GET_TICKER = "Failed to get Ticker from exchange. Details: ";

    /**
     * Error message for when API call to get Open Orders fails.
     */
    private static final String FAILED_TO_GET_OPEN_ORDERS = "Failed to get Open Orders from exchange. Details: ";

    /**
     * Name of PUBLIC key prop in config file.
     */
    private static final String KEY_PROPERTY_NAME = "key";

    /**
     * Name of secret prop in config file.
     */
    private static final String SECRET_PROPERTY_NAME = "secret";

    /**
     * Nonce used for sending authenticated messages to the exchange.
     */
    private static long nonce = 0;

    /**
     * Used to indicate if we have initialised the MAC authentication protocol.
     */
    private boolean initializedMACAuthentication = false;

    /**
     * The key used in the MAC message.
     */
    private String key = "";

    /**
     * The secret used for signing MAC message.
     */
    private String secret = "";

    /**
     * Provides the "Message Authentication Code" (MAC) algorithm used for the secure messaging layer.
     * Used to encrypt the hash of the entire message with the private key to ensure message integrity.
     */
    private Mac mac;

    /**
     * GSON engine used for parsing JSON in Kraken API call responses.
     */
    private Gson gson;


    @Override
    public void init(ExchangeConfig config) {

        LOG.info(() -> "About to initialise Kraken ExchangeConfig: " + config);
        setAuthenticationConfig(config);
        setNetworkConfig(config);

        nonce = System.currentTimeMillis() / 1000; // set the initial nonce used in the secure messaging.
        initSecureMessageLayer();
        initGson();
    }

    // ------------------------------------------------------------------------------------------------
    // Kraken API Calls adapted to the Trading API.
    // See https://www.kraken.com/en-gb/help/api
    // ------------------------------------------------------------------------------------------------

    @Override
    public MarketOrderBook getMarketOrders(String marketId) throws TradingApiException, ExchangeNetworkException {

        try {

            final Map<String, String> params = getRequestParamMap();
            params.put("pair", marketId);

            final ExchangeHttpResponse response = sendPublicRequestToExchange("Depth", params);
            LOG.debug(() -> "Market Orders response: " + response);

            if (response.getStatusCode() == HttpURLConnection.HTTP_OK) {

                final Type resultType = new TypeToken<KrakenResponse<KrakenMarketOrderBookResult>>() {}.getType();
                final KrakenResponse krakenResponse = gson.fromJson(response.getPayload(), resultType);

                final List<String> errors = krakenResponse.error;
                if (errors == null || errors.isEmpty()) {

                    // Assume we'll always get something here if errors array is empty; else blow fast wih NPE
                    final KrakenMarketOrderBookResult krakenOrderBookResult = (KrakenMarketOrderBookResult) krakenResponse.result;

                    // TODO Exchange returns the marketId as the key into the result map - be defensive here or just get grab first entry and assume we'll always get the (correct) 1 returned?
                    final KrakenOrderBook krakenOrderBook = krakenOrderBookResult.get(marketId);
                    if (krakenOrderBook != null) {

                        final List<MarketOrder> buyOrders = new ArrayList<>();
                        for (KrakenMarketOrder krakenBuyOrder : krakenOrderBook.bids) {
                            final MarketOrder buyOrder = new MarketOrder(
                                    OrderType.BUY,
                                    krakenBuyOrder.get(0),
                                    krakenBuyOrder.get(1),
                                    krakenBuyOrder.get(0).multiply(krakenBuyOrder.get(1)));
                            buyOrders.add(buyOrder);
                        }

                        final List<MarketOrder> sellOrders = new ArrayList<>();
                        for (KrakenMarketOrder krakenSellOrder : krakenOrderBook.asks) {
                            final MarketOrder sellOrder = new MarketOrder(
                                    OrderType.SELL,
                                    krakenSellOrder.get(0),
                                    krakenSellOrder.get(1),
                                    krakenSellOrder.get(0).multiply(krakenSellOrder.get(1)));
                            sellOrders.add(sellOrder);
                        }

                        return new MarketOrderBook(marketId, sellOrders, buyOrders);

                    } else {
                        final String errorMsg = FAILED_TO_GET_MARKET_ORDERS + response;
                        LOG.error(errorMsg);
                        throw new TradingApiException(errorMsg);
                    }

                } else {
                    final String errorMsg = FAILED_TO_GET_MARKET_ORDERS + response;
                    LOG.error(errorMsg);
                    throw new TradingApiException(errorMsg);
                }

            } else {
                final String errorMsg = FAILED_TO_GET_MARKET_ORDERS + response;
                LOG.error(errorMsg);
                throw new TradingApiException(errorMsg);
            }

        } catch (ExchangeNetworkException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public List<OpenOrder> getYourOpenOrders(String marketId) throws TradingApiException, ExchangeNetworkException {

        try {

            final ExchangeHttpResponse response = sendAuthenticatedRequestToExchange("OpenOrders", null);
            LOG.debug(() -> "Open Orders response: " + response);

            if (response.getStatusCode() == HttpURLConnection.HTTP_OK) {

                final Type resultType = new TypeToken<KrakenResponse<KrakenOpenOrderResult>>() {}.getType();
                final KrakenResponse krakenResponse = gson.fromJson(response.getPayload(), resultType);

                final List<String> errors = krakenResponse.error;
                if (errors == null || errors.isEmpty()) {

                    final List<OpenOrder> openOrders = new ArrayList<>();

                    // Assume we'll always get something here if errors array is empty; else blow fast wih NPE
                    final KrakenOpenOrderResult krakenOpenOrderResult = (KrakenOpenOrderResult) krakenResponse.result;

                    final Map<String, KrakenOpenOrder> krakenOpenOrders = krakenOpenOrderResult.open;
                    for (final Map.Entry<String, KrakenOpenOrder> openOrder : krakenOpenOrders.entrySet()) {

                        OrderType orderType;
                        final KrakenOpenOrder krakenOpenOrder = openOrder.getValue();
                        final KrakenOpenOrderDescription krakenOpenOrderDescription = krakenOpenOrder.descr;

                        switch (krakenOpenOrderDescription.type) {
                            case "buy":
                                orderType = OrderType.BUY;
                                break;
                            case "sell":
                                orderType = OrderType.SELL;
                                break;
                            default:
                                throw new TradingApiException(
                                        "Unrecognised order type received in getYourOpenOrders(). Value: " +
                                                openOrder.getValue().descr.ordertype);
                        }

                        final OpenOrder order = new OpenOrder(
                                openOrder.getKey(),
                                new Date((long) krakenOpenOrder.opentm), // opentm == creationDate
                                marketId,
                                orderType,
                                krakenOpenOrderDescription.price,
                                (krakenOpenOrder.vol.subtract(krakenOpenOrder.vol_exec)), // vol_exec == amount of order that has been executed
                                krakenOpenOrder.vol, // vol == orig order amount
                                //krakenOpenOrder.cost, // cost == total value of order in API docs, but it's always 0 :-(
                                krakenOpenOrderDescription.price.multiply(krakenOpenOrder.vol)
                        );

                        openOrders.add(order);
                    }

                    return openOrders;

                } else {
                    final String errorMsg = FAILED_TO_GET_OPEN_ORDERS + response;
                    LOG.error(errorMsg);
                    throw new TradingApiException(errorMsg);
                }

            } else {
                final String errorMsg = FAILED_TO_GET_OPEN_ORDERS + response;
                LOG.error(errorMsg);
                throw new TradingApiException(errorMsg);
            }

        } catch (ExchangeNetworkException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public String createOrder(String marketId, OrderType orderType, BigDecimal quantity, BigDecimal price) throws
            TradingApiException, ExchangeNetworkException {
        throw new UnsupportedOperationException("Not developed yet!");
    }

    @Override
    public boolean cancelOrder(String orderId, String marketIdNotNeeded) throws TradingApiException, ExchangeNetworkException {
        throw new UnsupportedOperationException("Not developed yet!");
    }

    @Override
    public BigDecimal getLatestMarketPrice(String marketId) throws TradingApiException, ExchangeNetworkException {

        try {

            final Map<String, String> params = getRequestParamMap();
            params.put("pair", marketId);

            final ExchangeHttpResponse response = sendPublicRequestToExchange("Ticker", params);
            LOG.debug(() -> "Latest Market Price response: " + response);

            if (response.getStatusCode() == HttpURLConnection.HTTP_OK) {

                final Type resultType = new TypeToken<KrakenResponse<KrakenTickerResult>>() {
                }.getType();
                final KrakenResponse krakenResponse = gson.fromJson(response.getPayload(), resultType);

                final List<String> errors = krakenResponse.error;
                if (errors == null || errors.isEmpty()) {

                    // Assume we'll always get something here if errors array is empty; else blow fast wih NPE
                    final KrakenTickerResult tickerResult = (KrakenTickerResult) krakenResponse.result;

                    // We'll always get something here - else we'd have barfed in KrakenTickerResultDeserializer
                    final Map<String, List<String>> tickerParams = tickerResult.get(marketId);

                    // 'c' key into map is the last market price: last trade closed array(<price>, <lot volume>)
                    return new BigDecimal(tickerParams.get("c").get(0));

                } else {
                    final String errorMsg = FAILED_TO_GET_TICKER + response;
                    LOG.error(errorMsg);
                    throw new TradingApiException(errorMsg);
                }

            } else {
                final String errorMsg = FAILED_TO_GET_TICKER + response;
                LOG.error(errorMsg);
                throw new TradingApiException(errorMsg);
            }

        } catch (ExchangeNetworkException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public BalanceInfo getBalanceInfo() throws TradingApiException, ExchangeNetworkException {

        try {

            final ExchangeHttpResponse response = sendAuthenticatedRequestToExchange("Balance", null);
            LOG.debug(() -> "Balance Info response: " + response);

            if (response.getStatusCode() == HttpURLConnection.HTTP_OK) {

                final Type resultType = new TypeToken<KrakenResponse<KrakenBalanceResult>>() {
                }.getType();
                final KrakenResponse krakenResponse = gson.fromJson(response.getPayload(), resultType);

                final List<String> errors = krakenResponse.error;
                if (errors == null || errors.isEmpty()) {

                    // Assume we'll always get something here if errors array is empty; else blow fast wih NPE
                    final KrakenBalanceResult balanceResult = (KrakenBalanceResult) krakenResponse.result;

                    final Map<String, BigDecimal> balancesAvailable = new HashMap<>();
                    final Set<Map.Entry<String, BigDecimal>> entries = balanceResult.entrySet();
                    for (final Map.Entry<String, BigDecimal> entry : entries) {
                        balancesAvailable.put(entry.getKey(), entry.getValue());
                    }

                    // 2nd arg of BalanceInfo constructor for reserved/on-hold balances is not provided by exchange.
                    return new BalanceInfo(balancesAvailable, new HashMap<>());

                } else {
                    final String errorMsg = FAILED_TO_GET_BALANCE + response;
                    LOG.error(errorMsg);
                    throw new TradingApiException(errorMsg);
                }

            } else {
                final String errorMsg = FAILED_TO_GET_BALANCE + response;
                LOG.error(errorMsg);
                throw new TradingApiException(errorMsg);
            }

        } catch (ExchangeNetworkException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public BigDecimal getPercentageOfBuyOrderTakenForExchangeFee(String marketId) throws TradingApiException,
            ExchangeNetworkException {
        throw new UnsupportedOperationException("Not developed yet!");
    }

    @Override
    public BigDecimal getPercentageOfSellOrderTakenForExchangeFee(String marketId) throws TradingApiException,
            ExchangeNetworkException {
        throw new UnsupportedOperationException("Not developed yet!");
    }

    @Override
    public String getImplName() {
        return "Kraken API v1";
    }

    // ------------------------------------------------------------------------------------------------
    //  GSON classes for JSON responses.
    //  See https://www.kraken.com/en-gb/help/api
    // ------------------------------------------------------------------------------------------------

    /**
     * GSON base class for all Kraken responses.
     * <p>
     * All Kraken responses have the following format:
     * <p>
     * <pre>
     *
     * error = array of error messages in the format of:
     *
     * <char-severity code><string-error category>:<string-error type>[:<string-extra info>]
     *    - severity code can be E for error or W for warning
     *
     * result = result of API call (may not be present if errors occur)
     *
     * </pre>
     * <p>
     * The result Type is what varies with each API call.
     */
    private static class KrakenResponse<T> {

        // field names map to the JSON arg names
        public List<String> error;
        public T result; // TODO fix up the Generics abuse ;-o

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("error", error)
                    .add("result", result)
                    .toString();
        }
    }

    /**
     * GSON class that wraps Depth API call result - the Market Order Book.
     */
    private static class KrakenMarketOrderBookResult extends HashMap<String, KrakenOrderBook> {
    }

    /**
     * GSON class that wraps a Balance API call result.
     */
    private static class KrakenBalanceResult extends HashMap<String, BigDecimal> {
    }

    /**
     * GSON class that wraps a Ticker API call result.
     */
    private static class KrakenTickerResult extends HashMap<String, Map<String, List<String>>> {
    }

    /**
     * GSON class that wraps an Open Order API call result - your open orders.
     */
    private static class KrakenOpenOrderResult {

        public Map<String, KrakenOpenOrder> open;

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("open", open)
                    .toString();
        }
    }

    /**
     * GSON class the represents a Kraken Open Order.
     */
    private static class KrakenOpenOrder {

        // field names map to the JSON arg names
        public String refid;
        public String userref;
        public String status;
        public double opentm;
        public double starttm;
        public double expiretm;
        public KrakenOpenOrderDescription descr;
        public BigDecimal vol;
        public BigDecimal vol_exec;
        public BigDecimal cost;
        public BigDecimal fee;
        public BigDecimal price;
        public String misc;
        public String oflags;

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("refid", refid)
                    .add("userref", userref)
                    .add("status", status)
                    .add("opentm", opentm)
                    .add("starttm", starttm)
                    .add("expiretm", expiretm)
                    .add("descr", descr)
                    .add("vol", vol)
                    .add("vol_exec", vol_exec)
                    .add("cost", cost)
                    .add("fee", fee)
                    .add("price", price)
                    .add("misc", misc)
                    .add("oflags", oflags)
                    .toString();
        }
    }

    /**
     * GSON class the represents a Kraken Open Order description.
     */
    private static class KrakenOpenOrderDescription {

        public String pair;
        public String type;
        public String ordertype;
        public BigDecimal price;
        public BigDecimal price2;
        public String leverage;
        public String order;

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("pair", pair)
                    .add("type", type)
                    .add("ordertype", ordertype)
                    .add("price", price)
                    .add("price2", price2)
                    .add("leverage", leverage)
                    .add("order", order)
                    .toString();
        }
    }

    /**
     * GSON class for a Market Order Book.
     */
    private static class KrakenOrderBook {

        // field names map to the JSON arg names
        public KrakenMarketOrder[] bids;
        public KrakenMarketOrder[] asks;

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("bids", bids)
                    .add("asks", asks)
                    .toString();
        }
    }

    /**
     * GSON class for holding Market Orders.
     * First element in array is price, second element is amount, 3rd is UNIX time.
     */
    private static class KrakenMarketOrder extends ArrayList<BigDecimal> {
        private static final long serialVersionUID = -4959711260742077759L;
    }

    /**
     * Custom GSON Deserializer for Ticker API call result.
     * <p>
     * Have to do this because last entry in the Ticker param map is a String, not an array like the rest of 'em!
     */
    private class KrakenTickerResultDeserializer implements JsonDeserializer<KrakenTickerResult> {

        public KrakenTickerResult deserialize(JsonElement json, Type type, JsonDeserializationContext context)
                throws JsonParseException {

            final KrakenTickerResult krakenTickerResult = new KrakenTickerResult();
            if (json.isJsonObject()) {

                final JsonObject jsonObject = json.getAsJsonObject();

                // assume 1 (KV) entry as per API spec - the K is the market id, the V is a Map of ticker params
                final String marketId = jsonObject.entrySet().iterator().next().getKey();
                final JsonElement tickerParams = jsonObject.entrySet().iterator().next().getValue();

                final JsonObject tickerMap = tickerParams.getAsJsonObject();
                for (Map.Entry<String, JsonElement> jsonTickerParam : tickerMap.entrySet()) {

                    final String key = jsonTickerParam.getKey();
                    if (key.equals("c")) {
                        final List<String> lastTradeDetails = context.deserialize(jsonTickerParam.getValue(), List.class);
                        final Map<String, List<String>> lastTradeMap = new HashMap<>();
                        lastTradeMap.put("c", lastTradeDetails);
                        krakenTickerResult.put(marketId, lastTradeMap);

                        // got what we want
                        return krakenTickerResult;
                    }
                }
            }

            // bad news if we get here!
            throw new IllegalArgumentException("KrakenTickerResultDeserializer failed to extract Last Market Price from" +
                    " KrakenTickerResult. The 'c' parameter was missing in Ticker result map. ");
        }
    }

    // ------------------------------------------------------------------------------------------------
    //  Transport layer methods
    // ------------------------------------------------------------------------------------------------

    /**
     * Makes a public API call to the Kraken exchange.
     *
     * @param apiMethod the API method to call.
     * @param params    any (optional) query param args to use in the API call.
     * @return the response from the exchange.
     * @throws ExchangeNetworkException if there is a network issue connecting to exchange.
     * @throws TradingApiException      if anything unexpected happens.
     */
    private ExchangeHttpResponse sendPublicRequestToExchange(String apiMethod, Map<String, String> params)
            throws ExchangeNetworkException, TradingApiException {

        if (params == null) {
            params = new HashMap<>(); // no params, so empty query string
        }

        // Build the query string with any given params
        final StringBuilder queryString = new StringBuilder("?");
        for (final String param : params.keySet()) {
            if (queryString.length() > 1) {
                queryString.append("&");
            }
            //noinspection deprecation
            queryString.append(param).append("=").append(URLEncoder.encode(params.get(param)));
        }

        // Request headers required by Exchange
        final Map<String, String> requestHeaders = new HashMap<>();
        requestHeaders.put("Content-Type", "application/x-www-form-urlencoded");

        try {

            final URL url = new URL(PUBLIC_API_BASE_URL + apiMethod + queryString);
            return sendNetworkRequest(url, "GET", null, requestHeaders);

        } catch (MalformedURLException e) {
            final String errorMsg = UNEXPECTED_IO_ERROR_MSG;
            LOG.error(errorMsg, e);
            throw new TradingApiException(errorMsg, e);
        }
    }

    /**
     * <p>
     * Makes an authenticated API call to the Kraken exchange.
     * </p>
     * <p>
     * <pre>
     * Kraken requires the following HTTP headers to bet set:
     *
     * API-Key = API key
     * API-Sign = Message signature using HMAC-SHA512 of (URI path + SHA256(nonce + POST data)) and base64 decoded secret API key
     *
     * The nonce must always increasing unsigned 64 bit integer.
     *
     * Note: Sometimes requests can arrive out of order or NTP can cause your clock to rewind, resulting in nonce issues.
     * If you encounter this issue, you can change the nonce window in your account API settings page.
     * The amount to set it to depends upon how you increment the nonce. Depending on your connectivity, a setting that
     * would accommodate 3-15 seconds of network issues is suggested.
     *
     * </pre>
     *
     * @param apiMethod the API method to call.
     * @param params    the query param args to use in the API call.
     * @return the response from the exchange.
     * @throws ExchangeNetworkException if there is a network issue connecting to exchange.
     * @throws TradingApiException      if anything unexpected happens.
     */
    private ExchangeHttpResponse sendAuthenticatedRequestToExchange(String apiMethod, Map<String, String> params)
            throws ExchangeNetworkException, TradingApiException {

        if (!initializedMACAuthentication) {
            final String errorMsg = "MAC Message security layer has not been initialized.";
            LOG.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        try {

            if (params == null) {
                // create empty map for non param API calls, e.g. "trades"
                params = new HashMap<>();
            }

            // The nonce is required by Kraken in every request.
            // It MUST be incremented each time and the nonce param MUST match the value used in signature.
            nonce++;
            params.put("nonce", Long.toString(nonce));

            // Current adapter does not support optional 2FA
            // params.put("otp", "false");

            // Build the URL with query param args in it - yuk!
            String postData = "";
            for (final String param : params.keySet()) {
                if (postData.length() > 0) {
                    postData += "&";
                }
                //noinspection deprecation
                postData += param + "=" + URLEncoder.encode(params.get(param));
            }

            // And now the tricky part... ;-o

            final byte[] pathInBytes = ("/" + KRAKEN_API_VERSION + KRAKEN_PRIVATE_PATH + apiMethod).getBytes("UTF-8");
            final String noncePrependedToPostData = Long.toString(nonce) + postData;

            // Create sha256 hash of nonce and post data:
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(noncePrependedToPostData.getBytes("UTF-8"));
            final BigInteger messageHash = new BigInteger(md.digest());

            // Create hmac_sha512 digest of path and previous sha256 hash
            mac.reset(); // force reset
            mac.update(pathInBytes);
            mac.update(messageHash.toByteArray());

            // Signature in Base64
            final String signature = Base64.getEncoder().encodeToString((new BigInteger(mac.doFinal())).toByteArray());

            // Request headers required by Exchange
            final Map<String, String> requestHeaders = new HashMap<>();
            requestHeaders.put("Content-Type", "application/x-www-form-urlencoded");
            requestHeaders.put("API-Key", key);
            requestHeaders.put("API-Sign", signature);

            final URL url = new URL(AUTHENTICATED_API_URL + apiMethod);
            return sendNetworkRequest(url, "POST", postData, requestHeaders);

        } catch (MalformedURLException | NoSuchAlgorithmException | UnsupportedEncodingException e) {

            final String errorMsg = UNEXPECTED_IO_ERROR_MSG;
            LOG.error(errorMsg, e);
            throw new TradingApiException(errorMsg, e);
        }
    }

    /**
     * Initialises the secure messaging layer
     * Sets up the MAC to safeguard the data we send to the exchange.
     * We fail hard n fast if any of this stuff blows.
     */
    private void initSecureMessageLayer() {

        try {
            // Kraken secret key is in Base64, so we need to decode it first
            final byte[] base64DecodedSecret = Base64.getDecoder().decode(secret);

            final SecretKeySpec keyspec = new SecretKeySpec(base64DecodedSecret, "HmacSHA512");
            mac = Mac.getInstance("HmacSHA512");
            mac.init(keyspec);
            initializedMACAuthentication = true;
        } catch (NoSuchAlgorithmException e) {
            final String errorMsg = "Failed to setup MAC security. HINT: Is HmacSHA512 installed?";
            LOG.error(errorMsg, e);
            throw new IllegalStateException(errorMsg, e);
        } catch (InvalidKeyException e) {
            final String errorMsg = "Failed to setup MAC security. Secret key seems invalid!";
            LOG.error(errorMsg, e);
            throw new IllegalArgumentException(errorMsg, e);
        }
    }

    // ------------------------------------------------------------------------------------------------
    //  Config methods
    // ------------------------------------------------------------------------------------------------

    private void setAuthenticationConfig(ExchangeConfig exchangeConfig) {
        final AuthenticationConfig authenticationConfig = getAuthenticationConfig(exchangeConfig);
        key = getAuthenticationConfigItem(authenticationConfig, KEY_PROPERTY_NAME);
        secret = getAuthenticationConfigItem(authenticationConfig, SECRET_PROPERTY_NAME);
    }

    // ------------------------------------------------------------------------------------------------
    //  Util methods
    // ------------------------------------------------------------------------------------------------

    /**
     * Initialises the GSON layer.
     */
    private void initGson() {
        final GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(KrakenTickerResult.class, new KrakenTickerResultDeserializer());
        gson = gsonBuilder.create();
    }

    /*
     * Hack for unit-testing map params passed to transport layer.
     */
    private Map<String, String> getRequestParamMap() {
        return new HashMap<>();
    }
}