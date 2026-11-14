package com.lumira.platform.common;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.function.Predicate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * 在实际连接时校验并固定目的地址，避免二次解析带来的 DNS 重绑定窗口。 Outbound connections whose destination is validated at the
 * moment the socket connects, against the address actually being dialled. Checking a hostname first
 * and letting the HTTP stack resolve it again leaves a DNS-rebinding window; here there is no
 * second lookup to race.
 */
public final class GuardedSockets {
    private GuardedSockets() {}

    @FunctionalInterface
    public interface AddressResolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    /**
     * 拒绝不允许地址的普通 TCP 套接字，由调用方叠加 TLS。 Plain TCP socket that refuses to connect to a disallowed
     * address. TLS is layered on top by the caller.
     */
    static final class GuardedSocket extends Socket {
        private final Predicate<InetAddress> allowed;
        private final AddressResolver resolver;
        private final DeadlineExchange.Scope deadline;

        GuardedSocket(Predicate<InetAddress> allowed) {
            this(allowed, InetAddress::getAllByName, null);
        }

        GuardedSocket(Predicate<InetAddress> allowed, AddressResolver resolver) {
            this(allowed, resolver, null);
        }

        GuardedSocket(
                Predicate<InetAddress> allowed,
                AddressResolver resolver,
                DeadlineExchange.Scope deadline) {
            this.allowed = allowed;
            this.resolver = resolver;
            this.deadline = deadline;
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (deadline != null) deadline.check();
            if (!(endpoint instanceof InetSocketAddress target) || target.getPort() < 0) {
                throw new ConnectException("Destination not permitted");
            }
            String host = target.getHostString();
            InetAddress[] addresses = resolveTarget(host, target.getAddress());
            if (deadline != null) deadline.check();
            if (addresses.length == 0) throw new ConnectException("Destination not permitted");
            for (InetAddress address : addresses) {
                if (address == null || !allowed.test(address))
                    throw new ConnectException("Destination not permitted");
            }
            if (deadline != null) deadline.check();
            // 校验所有解析结果后固定地址；失败的同一套接字不能可靠重试。
            // Validate every answer, then pin this socket to one address. A failed Socket.connect
            // cannot be reliably retried on the same Socket instance.
            super.connect(new InetSocketAddress(addresses[0], target.getPort()), timeout);
        }

        private InetAddress[] resolveTarget(String host, InetAddress resolvedAddress)
                throws IOException {
            String unbracketed =
                    host.startsWith("[") && host.endsWith("]")
                            ? host.substring(1, host.length() - 1)
                            : host;
            if (unbracketed.matches("\\d{1,3}(?:\\.\\d{1,3}){3}") || unbracketed.contains(":")) {
                if (resolvedAddress != null) return new InetAddress[] {resolvedAddress};
            }
            // 只采用注入解析器结果，避免提前的系统解析影响连接校验。
            // Resolve again through the injected resolver and use only its returned addresses.
            // InetSocketAddress may already have performed a system lookup before connect().
            return resolver.resolve(unbracketed);
        }
    }

    /**
     * 支持先明文连接再升级 TLS 的协议，例如 SMTP STARTTLS。 Plaintext counterpart for protocols that start
     * unencrypted and upgrade later (SMTP STARTTLS).
     */
    public static final class PlainFactory extends javax.net.SocketFactory {
        private final Predicate<InetAddress> allowed;

        public PlainFactory(Predicate<InetAddress> allowed) {
            this.allowed = allowed;
        }

        @Override
        public Socket createSocket() {
            return new GuardedSocket(allowed);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            var plain = new GuardedSocket(allowed);
            try {
                plain.connect(InetSocketAddress.createUnresolved(host, port), 5_000);
                return plain;
            } catch (IOException | RuntimeException failure) {
                plain.close();
                throw failure;
            }
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws IOException {
            return createSocket(host, port);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return createSocket(host.getHostName(), port);
        }

        @Override
        public Socket createSocket(
                InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return createSocket(address.getHostName(), port);
        }
    }

    /**
     * 为 HTTPS 与 SMTP 提供具有地址校验的套接字工厂。 Socket factory usable by HttpsURLConnection (HTTPS) and
     * Jakarta Mail (SMTP, STARTTLS and implicit TLS).
     */
    public static final class Factory extends SSLSocketFactory {
        private final SSLSocketFactory tls;
        private final Predicate<InetAddress> allowed;
        private final AddressResolver resolver;
        private final int connectTimeoutMillis;
        private final DeadlineExchange.Scope deadline;

        public Factory(SSLSocketFactory tls, Predicate<InetAddress> allowed) {
            this(tls, allowed, InetAddress::getAllByName, 5_000, null);
        }

        public Factory(
                SSLSocketFactory tls, Predicate<InetAddress> allowed, AddressResolver resolver) {
            this(tls, allowed, resolver, 5_000, null);
        }

        public Factory(
                SSLSocketFactory tls,
                Predicate<InetAddress> allowed,
                AddressResolver resolver,
                DeadlineExchange.Scope deadline) {
            this(tls, allowed, resolver, 5_000, deadline);
        }

        private Factory(
                SSLSocketFactory tls,
                Predicate<InetAddress> allowed,
                AddressResolver resolver,
                int connectTimeoutMillis,
                DeadlineExchange.Scope deadline) {
            this.tls = tls;
            this.allowed = allowed;
            this.resolver = resolver;
            this.connectTimeoutMillis = connectTimeoutMillis;
            this.deadline = deadline;
        }

        private GuardedSocket newSocket() throws IOException {
            var socket = new GuardedSocket(allowed, resolver, deadline);
            return deadline == null ? socket : deadline.track(socket);
        }

        /**
         * 返回未连接普通套接字，由调用方连接后包装 TLS。 Unconnected plain socket: the caller connects it, then wraps it
         * with {@link #createSocket(Socket, String, int, boolean)}.
         */
        @Override
        public Socket createSocket() throws IOException {
            return newSocket();
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            var plain = newSocket();
            try {
                plain.connect(InetSocketAddress.createUnresolved(host, port), connectTimeoutMillis);
                return tls.createSocket(plain, host, port, true);
            } catch (IOException | RuntimeException failure) {
                plain.close();
                throw failure;
            }
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws IOException {
            return createSocket(host, port);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return createSocket(host.getHostAddress(), port);
        }

        @Override
        public Socket createSocket(
                InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return createSocket(address.getHostAddress(), port);
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
                throws IOException {
            return tls.createSocket(socket, host, port, autoClose);
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return tls.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return tls.getSupportedCipherSuites();
        }
    }

    public record Response(int status, byte[] body) {}

    /**
     * 仅请求公网 HTTPS，不跟随跳转，限制响应大小，每次独立连接。 HTTPS request to a public destination only. No redirects,
     * bounded response, one connection per call.
     */
    public static Response exchange(
            URI uri,
            String method,
            Map<String, String> headers,
            byte[] body,
            Duration timeout,
            int maxResponseBytes)
            throws IOException {
        return exchange(
                uri,
                method,
                headers,
                body,
                timeout,
                maxResponseBytes,
                (SSLSocketFactory) SSLSocketFactory.getDefault(),
                PublicHosts::isPublicAddress,
                InetAddress::getAllByName);
    }

    /**
     * 公网请求保留地址校验，并由调用方总截止主动关闭资源。 Public request preserves address checks and tracks resources under
     * the caller's total deadline.
     */
    public static Response exchange(
            URI uri,
            String method,
            Map<String, String> headers,
            byte[] body,
            Duration timeout,
            int maxResponseBytes,
            DeadlineExchange.Scope deadline)
            throws IOException {
        return exchangeWithTimeouts(
                uri,
                method,
                headers,
                body,
                Duration.ofSeconds(5),
                timeout,
                maxResponseBytes,
                (SSLSocketFactory) SSLSocketFactory.getDefault(),
                PublicHosts::isPublicAddress,
                InetAddress::getAllByName,
                deadline);
    }

    public static Response exchange(
            URI uri,
            String method,
            Map<String, String> headers,
            byte[] body,
            Duration timeout,
            int maxResponseBytes,
            SSLSocketFactory tls,
            Predicate<InetAddress> allowed)
            throws IOException {
        return exchange(
                uri,
                method,
                headers,
                body,
                timeout,
                maxResponseBytes,
                tls,
                allowed,
                InetAddress::getAllByName);
    }

    public static Response exchangePublicCloud(
            URI uri,
            String method,
            Map<String, String> headers,
            byte[] body,
            Duration timeout,
            int maxResponseBytes)
            throws IOException {
        return exchange(
                uri,
                method,
                headers,
                body,
                timeout,
                maxResponseBytes,
                (SSLSocketFactory) SSLSocketFactory.getDefault(),
                PublicHosts::isPublicAddress,
                PublicCloudDns::resolvePublic);
    }

    public static Response exchangePublicCloud(
            URI uri,
            String method,
            Map<String, String> headers,
            byte[] body,
            Duration timeout,
            int maxResponseBytes,
            DeadlineExchange.Scope deadline)
            throws IOException {
        return exchangeWithTimeouts(
                uri,
                method,
                headers,
                body,
                Duration.ofSeconds(5),
                timeout,
                maxResponseBytes,
                (SSLSocketFactory) SSLSocketFactory.getDefault(),
                PublicHosts::isPublicAddress,
                PublicCloudDns::resolvePublic,
                deadline);
    }

    static Response exchangePinned(
            URI uri,
            String method,
            Map<String, String> headers,
            byte[] body,
            Duration connectTimeout,
            Duration readTimeout,
            int maxResponseBytes,
            AddressResolver resolver)
            throws IOException {
        return exchangePinned(
                uri,
                method,
                headers,
                body,
                connectTimeout,
                readTimeout,
                maxResponseBytes,
                (SSLSocketFactory) SSLSocketFactory.getDefault(),
                PublicHosts::isPublicAddress,
                resolver);
    }

    static Response exchangePinned(
            URI uri,
            String method,
            Map<String, String> headers,
            byte[] body,
            Duration connectTimeout,
            Duration readTimeout,
            int maxResponseBytes,
            SSLSocketFactory tls,
            Predicate<InetAddress> allowed,
            AddressResolver resolver)
            throws IOException {
        return exchangeWithTimeouts(
                uri,
                method,
                headers,
                body,
                connectTimeout,
                readTimeout,
                maxResponseBytes,
                tls,
                allowed,
                resolver,
                null);
    }

    private static Response exchange(
            URI uri,
            String method,
            Map<String, String> headers,
            byte[] body,
            Duration timeout,
            int maxResponseBytes,
            SSLSocketFactory tls,
            Predicate<InetAddress> allowed,
            AddressResolver resolver)
            throws IOException {
        return exchangeWithTimeouts(
                uri,
                method,
                headers,
                body,
                Duration.ofSeconds(5),
                timeout,
                maxResponseBytes,
                tls,
                allowed,
                resolver,
                null);
    }

    private static Response exchangeWithTimeouts(
            URI uri,
            String method,
            Map<String, String> headers,
            byte[] body,
            Duration connectTimeout,
            Duration readTimeout,
            int maxResponseBytes,
            SSLSocketFactory tls,
            Predicate<InetAddress> allowed,
            AddressResolver resolver,
            DeadlineExchange.Scope deadline)
            throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null)
            throw new IOException("HTTPS destination required");
        var connection = (HttpsURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
        try {
            int connectTimeoutMillis =
                    (int) Math.min(Integer.MAX_VALUE, Math.max(1L, connectTimeout.toMillis()));
            int readTimeoutMillis =
                    (int) Math.min(Integer.MAX_VALUE, Math.max(1L, readTimeout.toMillis()));
            connection.setSSLSocketFactory(
                    new Factory(
                            tls,
                            allowed,
                            resolver,
                            Math.min(5_000, connectTimeoutMillis),
                            deadline));
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(Math.min(5_000, connectTimeoutMillis));
            connection.setReadTimeout(readTimeoutMillis);
            connection.setRequestMethod(method);
            headers.forEach(connection::setRequestProperty);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (var out = connection.getOutputStream()) {
                    out.write(body);
                }
            }
            int status = connection.getResponseCode();
            InputStream stream =
                    status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] content =
                    stream == null
                            ? new byte[0]
                            : readBounded(
                                    deadline == null ? stream : deadline.track(stream),
                                    maxResponseBytes);
            return new Response(status, content);
        } catch (java.net.SocketTimeoutException timedOut) {
            throw new HttpTimeoutException("request timed out");
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readBounded(InputStream stream, int maximum) throws IOException {
        try (stream) {
            return stream.readNBytes(maximum);
        }
    }
}
