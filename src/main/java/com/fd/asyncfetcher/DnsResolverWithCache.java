package com.fd.asyncfetcher;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.http.conn.DnsResolver;
import com.fd.DnsCache;

/**
 * DnsResolver with simplecache
 * use module:simplecache and dnscache
 * @author caoliuyi
 *
 */

public class DnsResolverWithCache implements DnsResolver {
	private DnsCache cache;

	public DnsResolverWithCache(DnsCache cache) {
		this.cache = cache;
	}

	public InetAddress[] resolve(String host) throws UnknownHostException {
		InetAddress addr = null;
		try {
			addr = cache.getAndPutInetAddressInCache(host);
		} catch (Exception e) {
		}
		if (addr == null)
			throw new UnknownHostException();
		return new InetAddress[] { addr };
	}

}
