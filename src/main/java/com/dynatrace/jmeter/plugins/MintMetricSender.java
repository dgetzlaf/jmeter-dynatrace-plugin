package com.dynatrace.jmeter.plugins;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.util.EntityUtils;
import org.apache.jmeter.report.utils.MetricUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dynatrace.mint.MintMetricsLine;

public class MintMetricSender {
	private static final Logger log = LoggerFactory.getLogger(MintMetricSender.class);
	private static final String AUTHORIZATION_HEADER_NAME = "Authorization";
	private static final String AUTHORIZATION_HEADER_VALUE = "Api-token ";
	private CloseableHttpAsyncClient httpClient;
	private HttpPost httpRequest;
	private URL url;
	private String token;
	private Future<HttpResponse> lastRequest;
	private List<MintMetricsLine> metrics = new CopyOnWriteArrayList<>();

	public MintMetricSender() {
	}

	public synchronized void setup(String mintIngestUrl, String mintIngestToken) throws Exception {
		this.url = new URL(mintIngestUrl);
		this.token = mintIngestToken;

		IOReactorConfig ioReactorConfig = IOReactorConfig.custom().setIoThreadCount(1).setConnectTimeout(1000).setSoTimeout(3000)
				.build();
		ConnectingIOReactor ioReactor = new DefaultConnectingIOReactor(ioReactorConfig);
		PoolingNHttpClientConnectionManager connManager = new PoolingNHttpClientConnectionManager(ioReactor);
		httpClient = HttpAsyncClientBuilder.create().setConnectionManager(connManager).setMaxConnPerRoute(2).setMaxConnTotal(2)
				.setUserAgent("ApacheJMeter 5").disableCookieManagement().disableConnectionState().build();
		httpRequest = createRequest(this.url, this.token);
		httpClient.start();
	}

	private HttpPost createRequest(URL url, String token) throws URISyntaxException {
		RequestConfig defaultRequestConfig = RequestConfig.custom().setConnectTimeout(1000).setSocketTimeout(3000)
				.setConnectionRequestTimeout(100).build();
		HttpPost currentHttpRequest = new HttpPost(url.toURI());
		currentHttpRequest.setConfig(defaultRequestConfig);
		if (StringUtils.isNotBlank(token)) {
			currentHttpRequest.setHeader(AUTHORIZATION_HEADER_NAME, AUTHORIZATION_HEADER_VALUE + token);
		}

		log.debug("Created MintMetricSender with url: {}", url);
		return currentHttpRequest;
	}

	public synchronized void addMetric(MintMetricsLine line) {
		log.debug("addMetric({})", line);
		metrics.add(line);
	}

	public synchronized void writeAndSendMetrics() {
		List<MintMetricsLine> copyMetrics;
		if (metrics.isEmpty()) {
			return;
		}

		copyMetrics = metrics;
		metrics = new CopyOnWriteArrayList<>();

		this.writeAndSendMetrics(copyMetrics);
	}

	private void writeAndSendMetrics(final List<MintMetricsLine> copyMetrics) {
		try {
			if (this.httpRequest == null) {
				this.httpRequest = this.createRequest(this.url, this.token);
			}

			StringBuilder message = new StringBuilder();

			for (MintMetricsLine l : copyMetrics) {
				message.append(l.printMessage());
				message.append(System.getProperty("line.separator"));
			}

			log.debug("Sending metrics: {}", message.toString());
			this.httpRequest.setEntity(new StringEntity(message.toString(), StandardCharsets.UTF_8));
			this.lastRequest = this.httpClient.execute(this.httpRequest, new FutureCallback<HttpResponse>() {
				public void completed(HttpResponse response) {
					int code = response.getStatusLine().getStatusCode();
					if (MetricUtils.isSuccessCode(code)) {
						log.info("Success, number of metrics written: {}", copyMetrics.size());
						log.debug("Last message: {}", message.toString());
					} else {
						log.error("Error writing metrics to MINT Url: {}, responseCode: {}, responseBody: {}",
								new Object[] { url, code, getBody(response) });
						log.info("Last message: {}", message.toString());
					}

				}

				public void failed(Exception ex) {
					log.error("failed to send data to MINT server.", ex);
				}

				public void cancelled() {
					log.warn("Request to MINT server was cancelled");
				}
			});
		} catch (URISyntaxException var5) {
			log.error(var5.getMessage(), var5);
		}

	}

	private static String getBody(HttpResponse response) {
		String body = "";

		try {
			if (response != null && response.getEntity() != null) {
				body = EntityUtils.toString(response.getEntity());
			}
		} catch (Exception var3) {
		}

		return body;
	}

	public void destroy() {
		log.info("Destroying ");

		try {
			this.lastRequest.get(5L, TimeUnit.SECONDS);
		} catch (ExecutionException | TimeoutException | InterruptedException var2) {
			log.error("Error waiting for last request to be send to MINT server", var2);
		}

		if (this.httpRequest != null) {
			this.httpRequest.abort();
		}

		IOUtils.closeQuietly(this.httpClient);
	}
}