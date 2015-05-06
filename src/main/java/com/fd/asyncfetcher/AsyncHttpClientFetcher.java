package com.fd.asyncfetcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HeaderElementIterator;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.conn.util.PublicSuffixMatcher;
import org.apache.http.conn.util.PublicSuffixMatcherLoader;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.message.BasicHeaderElementIterator;
import org.apache.http.nio.conn.NHttpClientConnectionManager;
import org.apache.http.nio.conn.NoopIOSessionStrategy;
import org.apache.http.nio.conn.SchemeIOSessionStrategy;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.Args;
import org.apache.http.util.ByteArrayBuffer;
import org.apache.http.util.EntityUtils;
import org.apache.http.util.TextUtils;

import com.fd.DnsCache;

public class AsyncHttpClientFetcher {
	private CloseableHttpAsyncClient client;
	private RequestConfig defaultRequestConfig;
	private RequestConfig noRedirectCfg;
	private DnsCache dnsCache;
	private volatile boolean isValid = false;
	private int readTimeout = 30000;
	private int connectTimeout = 10000;
	private int connectionRequestTimeout = 0;
	private int retryCount = 3;
	private long maxContentLength = 10L * 1024 * 1024; // 10Mb
	private boolean keepAlive = true;
	private long defaultKeepAlive = 3000;
	private int dnsCacheSize = 100000;
	private int maxTotalConnection = 100;
	private int maxConnectionPerRoute = 10;
	private int ioThreadNum = 200;
	private String userAgent = "Mozilla/5.0 (compatible; MSIE 10.0; Windows NT 6.1; Trident/6.0)";
	private HttpHost proxy;
	private AsResponseContentEncoding encodingProcessor = new AsResponseContentEncoding();

	/**
	 * after set all configs, we should always call init before any connection
	 * 
	 * @throws IOReactorException
	 */
	public void init() throws IOReactorException {
		dnsCache = new DnsCache("dns-cache", dnsCacheSize);
		defaultRequestConfig = RequestConfig.custom()
				.setCookieSpec(CookieSpecs.IGNORE_COOKIES)
				.setExpectContinueEnabled(true)
				.setConnectTimeout(getConnectTimeout())
				.setConnectionRequestTimeout(getConnectionRequestTimeout())
				.setSocketTimeout(getReadTimeout()).build();

		HttpAsyncClientBuilder builder = HttpAsyncClients.custom();
		ConnectionKeepAliveStrategy myStrategy = null;
		IOReactorConfig.Builder ioConfigBuilder = IOReactorConfig
				.copy(IOReactorConfig.DEFAULT)
				.setConnectTimeout(getConnectTimeout())
				.setIoThreadCount(getIoThreadNum())
				.setSoTimeout(getReadTimeout());
		if (keepAlive) {
			ioConfigBuilder.setSoKeepAlive(true);
		} else {
			ioConfigBuilder.setSoKeepAlive(false);
		}
		IOReactorConfig ioConfig = ioConfigBuilder.build();
		if (keepAlive) {
			myStrategy = new ConnectionKeepAliveStrategy() {
				public long getKeepAliveDuration(HttpResponse response,
						HttpContext context) {
					HeaderElementIterator it = new BasicHeaderElementIterator(
							response.headerIterator(HTTP.CONN_KEEP_ALIVE));
					while (it.hasNext()) {
						HeaderElement he = it.nextElement();
						String param = he.getName();
						String value = he.getValue();
						if (value != null && param.equalsIgnoreCase("timeout")) {
							try {
								return Long.parseLong(value) * 1000;
							} catch (NumberFormatException ignore) {
							}
						}
					}
					return defaultKeepAlive;
				}

			};
			builder.setKeepAliveStrategy(myStrategy);
		} else {
			builder.setConnectionReuseStrategy(new ConnectionReuseStrategy() {
				@Override
				public boolean keepAlive(HttpResponse response,
						HttpContext context) {
					return false;
				}
			});
		}
		NHttpClientConnectionManager connManager = null;
		SSLContext sslcontext = SSLContexts.createSystemDefault();
		final String[] supportedProtocols = split(System
				.getProperty("https.protocols"));
		final String[] supportedCipherSuites = split(System
				.getProperty("https.cipherSuites"));
		PublicSuffixMatcher publicSuffixMatcher = PublicSuffixMatcherLoader
				.getDefault();
		HostnameVerifier hostnameVerifier = new DefaultHostnameVerifier(
				publicSuffixMatcher);
		SchemeIOSessionStrategy sslStrategy = new SSLIOSessionStrategy(
				sslcontext, supportedProtocols, supportedCipherSuites,
				hostnameVerifier);

		ConnectingIOReactor ioreactor = new DefaultConnectingIOReactor(ioConfig);
		Registry<SchemeIOSessionStrategy> socketFactoryRegistry = RegistryBuilder
				.<SchemeIOSessionStrategy> create()
				.register("http", NoopIOSessionStrategy.INSTANCE)
				.register("https", sslStrategy).build();
		connManager = new PoolingNHttpClientConnectionManager(ioreactor, null,
				socketFactoryRegistry, new DnsResolverWithCache(dnsCache));

		((PoolingNHttpClientConnectionManager) connManager).setMaxTotal(this
				.getMaxTotalConnection());
		((PoolingNHttpClientConnectionManager) connManager)
				.setDefaultMaxPerRoute(this.getMaxConnectionPerRoute());
		client = builder.setConnectionManager(connManager)
				.setDefaultIOReactorConfig(ioConfig).setProxy(getProxy())
				.setDefaultRequestConfig(defaultRequestConfig)
				.setUserAgent(this.getUserAgent()).disableCookieManagement()
				.setMaxConnPerRoute(getMaxConnectionPerRoute())
				.setMaxConnTotal(getMaxTotalConnection()).build();
		noRedirectCfg = RequestConfig.copy(defaultRequestConfig)
				.setRedirectsEnabled(false).build();
		client.start();
		isValid = true;
	}

	private static String[] split(final String s) {
		if (TextUtils.isBlank(s)) {
			return null;
		}
		return s.split(" *, *");
	}

	public void httpGetWithHeaders(final HttpGet httpget,
			final ProcessTask<HttpResponseWrapper> process) throws Exception {
		if (!isValid)
			throw new RuntimeException("not valid now, you should init first");
		final HttpResponseWrapper tmpWrapper = new HttpResponseWrapper();
		// Create a custom response handler
		final FutureCallback<HttpResponse> responseHandler = new FutureCallback<HttpResponse>() {
			public HttpResponseWrapper handleResponse(
					final HttpResponse response)
					throws ClientProtocolException, IOException {
				try {
					encodingProcessor.process(response, null);
				} catch (Exception e) {
				}
				HttpResponseWrapper wrapper = new HttpResponseWrapper();
				int status = response.getStatusLine().getStatusCode();
				if (status >= 200 && status < 300) {
					wrapper.needRedirect = false;
					HttpEntity entity = response.getEntity();
					if (entity == null) {
						return wrapper;
					}
					if (entity.getContentLength() > maxContentLength) {
						EntityUtils.consumeQuietly(response.getEntity());
						throw new RuntimeException("too large content: "
								+ httpget.getURI().toString());
					}
					wrapper.setHeaders(response.getAllHeaders());
					byte[] bytes = toByteArray(entity);
					wrapper.setContentLength(bytes.length);
					ContentType contentType = ContentType.get(entity);
					if (contentType != null) {
						Charset charset = contentType.getCharset();
						if (charset != null) {
							charset = gb2312ToGBK(charset);
							wrapper.setContent(new String(bytes, charset));
							return wrapper;
						}
					}
					String charSet = CharsetDetector.getCharset(bytes);
					if (charSet.equalsIgnoreCase("GB2312")) {
						charSet = "GBK";
					}
					wrapper.setContent(new String(bytes, charSet));
					return wrapper;
				} else if (HttpRedirectTool.isRedirected(httpget, response)) {
					EntityUtils.consumeQuietly(response.getEntity());
					HttpUriRequest nq = HttpRedirectTool.getRedirect(
							tmpWrapper.getLastRequest(), response);
					if (nq != null) {
						wrapper.needRedirect = true;
						wrapper.lastRequest = nq;
					} else {
						wrapper = null;
					}
					return wrapper;
				} else {
					EntityUtils.consumeQuietly(response.getEntity());
					throw new ClientProtocolException(
							"Unexpected response status: "
									+ status
									+ tmpWrapper.getLastRequest()
											.getRequestLine().getUri());
				}
			}

			@Override
			public void cancelled() {
			}

			@Override
			public void completed(HttpResponse response) {
				try {
					HttpResponseWrapper wrapper = handleResponse(response);
					if (wrapper != null) {
						wrapper.redirectCount = tmpWrapper.redirectCount;
						wrapper.realUrl = tmpWrapper.lastRequest
								.getRequestLine().getUri();
					}
					if (process == null) {
						// 不做任何处理
					} else if (wrapper == null) {
						// 返回会null
						process.process(wrapper);
					} else if (!wrapper.needRedirect) {
						// 返回不为null 且 不需要跳转
						process.process(wrapper);
					} else {
						// 需要跳转
						if (tmpWrapper.redirectCount > 3) {
							// 超过次数限制
							process.process(wrapper);
						} else {
							// 没有超过次数限制
							tmpWrapper.redirectCount += 1;
							tmpWrapper.lastRequest = wrapper.lastRequest;
							if (tmpWrapper.lastRequest instanceof HttpGet) {
								((HttpGet) tmpWrapper.lastRequest)
										.setConfig(noRedirectCfg);
							}
							for (Header h : httpget.getAllHeaders()) {
								tmpWrapper.lastRequest.addHeader(h);
							}
							client.execute(tmpWrapper.lastRequest, this);
						}
					}
				} catch (Exception e) {
				}
			}

			@Override
			public void failed(Exception response) {
			}

		};
		httpget.setConfig(noRedirectCfg);
		tmpWrapper.lastRequest = httpget;
		client.execute(httpget, responseHandler);
	}

	public void httpPost(HttpPost httppost, final ProcessTask<String> process,
			final String encoding) throws Exception {
		FutureCallback<HttpResponse> responseHandler = new FutureCallback<HttpResponse>() {
			public String handleResponse(final HttpResponse response)
					throws ClientProtocolException, IOException {
				try {
					encodingProcessor.process(response, null);
				} catch (Exception e) {
				}
				int status = response.getStatusLine().getStatusCode();
				if (status >= 200 && status < 300) {
					HttpEntity entity = response.getEntity();
					return entity != null ? EntityUtils.toString(entity,
							Charset.forName(encoding)) : null;
				} else {
					EntityUtils.consumeQuietly(response.getEntity());
					throw new ClientProtocolException(
							"Unexpected response status: " + status);
				}
			}

			@Override
			public void cancelled() {
			}

			@Override
			public void completed(HttpResponse response) {
				try {
					String wrapper = handleResponse(response);
					if (process != null) {
						process.process(wrapper);
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}

			@Override
			public void failed(Exception response) {
			}
		};
		client.execute(httppost, responseHandler);
	}

	public Charset gb2312ToGBK(Charset src) {
		if (src.name().equalsIgnoreCase("GB2312")) {
			return Charset.forName("GBK");
		}
		return src;
	}

	/**
	 * close http client
	 * 
	 * @throws IOException
	 */
	public void close() throws IOException {
		isValid = false;
		if (this.client != null) {
			client.close();
		}
	}

	/**
	 * Read the contents of an entity and return it as a byte array.
	 * 
	 * @param entity
	 *            the entity to read from=
	 * @return byte array containing the entity content. May be null if
	 *         {@link HttpEntity#getContent()} is null.
	 * @throws IOException
	 *             if an error occurs reading the input stream
	 * @throws IllegalArgumentException
	 *             if entity is null or if content length > Integer.MAX_VALUE
	 */
	private byte[] toByteArray(final HttpEntity entity) throws IOException {
		Args.notNull(entity, "Entity");
		final InputStream instream = entity.getContent();
		if (instream == null) {
			return null;
		}
		try {
			Args.check(entity.getContentLength() <= this.maxContentLength,
					"HTTP entity too large to be buffered in memory");
			int i = (int) entity.getContentLength();
			if (i < 0) {
				i = 4096;
			}
			final ByteArrayBuffer buffer = new ByteArrayBuffer(i);
			final byte[] tmp = new byte[4096];
			int l;
			while ((l = instream.read(tmp)) != -1) {
				if (buffer.length() >= this.maxContentLength)
					throw new IOException("entity too long");
				buffer.append(tmp, 0, l);
			}
			return buffer.toByteArray();
		} finally {
			instream.close();
		}
	}

	public int getReadTimeout() {
		return readTimeout;
	}

	public void setReadTimeout(int readTimeout) {
		this.readTimeout = readTimeout;
	}

	public int getConnectTimeout() {
		return connectTimeout;
	}

	public void setConnectTimeout(int connectTimeout) {
		this.connectTimeout = connectTimeout;
	}

	public int getConnectionRequestTimeout() {
		return connectionRequestTimeout;
	}

	public void setConnectionRequestTimeout(int connectionRequestTimeout) {
		this.connectionRequestTimeout = connectionRequestTimeout;
	}

	public long getMaxContentLength() {
		return maxContentLength;
	}

	public void setMaxContentLength(long maxContentLength) {
		this.maxContentLength = maxContentLength;
	}

	public int getDnsCacheSize() {
		return dnsCacheSize;
	}

	public void setDnsCacheSize(int dnsCacheSize) {
		this.dnsCacheSize = dnsCacheSize;
	}

	public long getDefaultKeepAlive() {
		return defaultKeepAlive;
	}

	public void setDefaultKeepAlive(long defaultKeepAlive) {
		this.defaultKeepAlive = defaultKeepAlive;
	}

	public boolean isKeepAlive() {
		return keepAlive;
	}

	public void setKeepAlive(boolean keepAlive) {
		this.keepAlive = keepAlive;
	}

	public int getRetryCount() {
		return retryCount;
	}

	public void setRetryCount(int retryCount) {
		this.retryCount = retryCount;
	}

	public int getMaxTotalConnection() {
		return maxTotalConnection;
	}

	public void setMaxTotalConnection(int maxTotalConnection) {
		this.maxTotalConnection = maxTotalConnection;
	}

	public int getMaxConnectionPerRoute() {
		return maxConnectionPerRoute;
	}

	public void setMaxConnectionPerRoute(int maxConnectionPerRoute) {
		this.maxConnectionPerRoute = maxConnectionPerRoute;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public void setUserAgent(String userAgent) {
		this.userAgent = userAgent;
	}

	public HttpHost getProxy() {
		return proxy;
	}

	public void setProxy(HttpHost proxy) {
		this.proxy = proxy;
	}

	public void setHttpProxy(String host, int port) {
		this.setProxy(new HttpHost(host, port));
	}

	public int getIoThreadNum() {
		return ioThreadNum;
	}

	public void setIoThreadNum(int ioThreadNum) {
		this.ioThreadNum = ioThreadNum;
	}

	public DnsCache getDnsCache() {
		return dnsCache;

	}
}
