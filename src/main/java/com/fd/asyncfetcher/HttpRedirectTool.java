package com.fd.asyncfetcher;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.ProtocolException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.util.TextUtils;

/**
 * 跳转辅助工具
 * 
 * @author caoly
 * 
 */
public class HttpRedirectTool {
	private static final String[] REDIRECT_METHODS = new String[] {
			HttpGet.METHOD_NAME, HttpHead.METHOD_NAME };

	public static HttpUriRequest getRedirect(final HttpRequest request,
			final HttpResponse response)  {
		try {
			final URI uri = getLocationURI(request, response);
			final String method = request.getRequestLine().getMethod();
			if (method.equalsIgnoreCase(HttpHead.METHOD_NAME)) {
				return new HttpHead(uri);
			} else if (method.equalsIgnoreCase(HttpGet.METHOD_NAME)) {
				return new HttpGet(uri);
			} else {
				final int status = response.getStatusLine().getStatusCode();
				if (status == HttpStatus.SC_TEMPORARY_REDIRECT) {
					return RequestBuilder.copy(request).setUri(uri).build();
				} else {
					return new HttpGet(uri);
				}
			}
		} catch(Exception e) {
			
		}
		return null;
	}

	public static URI getLocationURI(final HttpRequest request,
			final HttpResponse response) throws ProtocolException {
		// get the location header to find out where to redirect to
		final Header locationHeader = response.getFirstHeader("location");
		if (locationHeader == null) {
			throw new ProtocolException("Received redirect response "
					+ response.getStatusLine() + " but no location header");
		}
		final String location = locationHeader.getValue();
		URI uri = createLocationURI(location);
		// rfc2616 demands the location value be a complete URI
		// Location = "Location" ":" absoluteURI
		try {
			if (!uri.isAbsolute()) {
				// Adjust location URI
				// assert requestURI is complete URI
				final URI requestURI = new URI(request.getRequestLine()
						.getUri());
				uri = URIUtils.resolve(requestURI, uri);
			}
		} catch (final URISyntaxException ex) {
			throw new ProtocolException(ex.getMessage(), ex);
		}
		return uri;
	}

	public static URI createLocationURI(final String location)
			throws ProtocolException {
		try {
			final URIBuilder b = new URIBuilder(new URI(location).normalize());
			final String host = b.getHost();
			if (host != null) {
				b.setHost(host.toLowerCase(Locale.ENGLISH));
			}
			final String path = b.getPath();
			if (TextUtils.isEmpty(path)) {
				b.setPath("/");
			}
			return b.build();
		} catch (final URISyntaxException ex) {
			throw new ProtocolException("Invalid redirect URI: " + location, ex);
		}
	}

	public static boolean isRedirected(final HttpRequest request,
			final HttpResponse response) {
		final int statusCode = response.getStatusLine().getStatusCode();
		final String method = request.getRequestLine().getMethod();
		final Header locationHeader = response.getFirstHeader("location");
		switch (statusCode) {
		case HttpStatus.SC_MOVED_TEMPORARILY:
			return isRedirectable(method) && locationHeader != null;
		case HttpStatus.SC_MOVED_PERMANENTLY:
		case HttpStatus.SC_TEMPORARY_REDIRECT:
			return isRedirectable(method);
		case HttpStatus.SC_SEE_OTHER:
			return true;
		default:
			return false;
		} // end of switch
	}

	public static String getHost(String url) {
		if (url == null) {
			return null;
		}
		try {
			URI u = new URI(url);
			return u.getHost();
		} catch (Exception e) {

		}
		return null;
	}

	public static boolean isRedirectable(final String method) {
		for (final String m : REDIRECT_METHODS) {
			if (m.equalsIgnoreCase(method)) {
				return true;
			}
		}
		return false;
	}

}
