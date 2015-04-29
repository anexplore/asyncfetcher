package com.fd.asyncfetcher;

public interface ProcessTask<T> {
	public void process(T t);
}
