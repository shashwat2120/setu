package com.setu.gateway;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link Upstream} over Apache HttpClient 5's classic (blocking) client, with a connection pool. Blocking on purpose: the gateway runs on
 * the servlet stack, one request thread per exchange, and the classic client needs no selector. It never follows redirects (a 302 from a
 * service is the client's business), never retries by itself (the gateway decides what is safe to retry), never decompresses (bytes pass
 * through untouched) and keeps no cookies (the gateway is not a browser).
 */
public class ApacheUpstream implements Upstream, DisposableBean {

    private final CloseableHttpClient client;

    public ApacheUpstream(java.time.Duration connectTimeout, java.time.Duration responseTimeout) {
        PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnTotal(256)
            .setMaxConnPerRoute(64)
            .setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(connectTimeout))
                .setSocketTimeout(Timeout.of(responseTimeout))
                // a service may close an idle keep-alive connection at any time: re-check one that has sat unused before reusing it
                .setValidateAfterInactivity(org.apache.hc.core5.util.TimeValue.ofSeconds(1))
                .build())
            .build();
        this.client = HttpClients.custom()
            .setConnectionManager(connections)
            .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.of(responseTimeout)).build())
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .disableContentCompression()
            .disableCookieManagement()
            .evictExpiredConnections()
            .evictIdleConnections(org.apache.hc.core5.util.TimeValue.ofSeconds(30))
            .build();
    }

    @Override
    public Response exchange(Request request) throws UpstreamException {
        BasicClassicHttpRequest outgoing = new BasicClassicHttpRequest(request.method(), request.uri());
        for (Header header : request.headers()) {
            outgoing.addHeader(header.name(), header.value());
        }
        if (request.body() != null) {
            outgoing.setEntity(new InputStreamEntity(request.body(), request.contentLength(), null));
        }
        ClassicHttpResponse response = null;
        try {
            response = client.executeOpen(null, outgoing, null);
            List<Header> headers = new ArrayList<>();
            for (org.apache.hc.core5.http.Header h : response.getHeaders()) {
                headers.add(new Header(h.getName(), h.getValue()));
            }
            InputStream body = response.getEntity() == null ? InputStream.nullInputStream() : response.getEntity().getContent();
            return new Response(response.getCode(), headers, body, response);
        } catch (IOException e) {
            closeQuietly(response);
            throw new UpstreamException(kindOf(e), request.method() + " " + request.uri() + " failed: " + e, e);
        } catch (RuntimeException e) {
            closeQuietly(response);
            throw new UpstreamException(UpstreamException.Kind.OTHER, request.method() + " " + request.uri() + " failed: " + e, e);
        }
    }

    private static UpstreamException.Kind kindOf(IOException e) {
        if (e instanceof SocketTimeoutException || e instanceof org.apache.hc.client5.http.ConnectTimeoutException) {
            return e instanceof org.apache.hc.client5.http.ConnectTimeoutException ? UpstreamException.Kind.CONNECT : UpstreamException.Kind.TIMEOUT;
        }
        if (e instanceof ConnectException || e instanceof NoRouteToHostException || e instanceof UnknownHostException
            || e instanceof NoHttpResponseException || e instanceof ConnectionClosedException || e instanceof java.net.SocketException) {
            return UpstreamException.Kind.CONNECT;
        }
        return UpstreamException.Kind.OTHER;
    }

    private static void closeQuietly(ClassicHttpResponse response) {
        if (response != null) {
            try {
                response.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }

    @Override
    public void destroy() throws IOException {
        client.close();
    }
}
