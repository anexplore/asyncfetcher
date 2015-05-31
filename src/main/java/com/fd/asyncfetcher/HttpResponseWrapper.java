package com.fd.asyncfetcher;

import org.apache.http.Header;
import org.apache.http.client.methods.HttpUriRequest;

public class HttpResponseWrapper {
	public int redirectCount = 0;
	public boolean needRedirect = false;
	public HttpUriRequest lastRequest;
	public String realUrl = null;
	public String content = null;
	public Header[] headers = null;
	public long contentLength = 0;
	public long headerLength = 0;
	public int status = 200;
	
	public Header[] getAllHeader() {
		return headers;
	}

	public void setHeaders(Header[] allHeader) {
		headers = new Header[allHeader.length];
		System.arraycopy(allHeader, 0, headers, 0, allHeader.length);
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public long getContentLength() {
		return contentLength;
	}

	public void setContentLength(long contentLength) {
		this.contentLength = contentLength;
	}

	public HttpUriRequest getLastRequest() {
		return lastRequest;
	}

	public void setLastRequest(HttpUriRequest lastRequest) {
		this.lastRequest = lastRequest;
	}

	public long getHeaderLength() {
		return headerLength;
	}

	public Header[] getHeaders() {
		return headers;
	}

	public String getRealUrl() {
		return realUrl;
	}

	public void setRealUrl(String realUrl) {
		this.realUrl = realUrl;
	}

}
