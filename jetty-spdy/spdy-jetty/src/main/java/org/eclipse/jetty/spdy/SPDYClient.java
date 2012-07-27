// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.spdy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class SPDYClient
{
    private final Map<String, AsyncConnectionFactory> factories = new ConcurrentHashMap<>();
    private final AsyncConnectionFactory defaultAsyncConnectionFactory = new ClientSPDYAsyncConnectionFactory();
    private final short version;
    private final Factory factory;
    private volatile SocketAddress bindAddress;
    private volatile long idleTimeout = -1;
    private volatile int initialWindowSize = 65536;

    protected SPDYClient(short version, Factory factory)
    {
        this.version = version;
        this.factory = factory;
    }

    /**
     * @return the address to bind the socket channel to
     * @see #setBindAddress(SocketAddress)
     */
    public SocketAddress getBindAddress()
    {
        return bindAddress;
    }

    /**
     * @param bindAddress the address to bind the socket channel to
     * @see #getBindAddress()
     */
    public void setBindAddress(SocketAddress bindAddress)
    {
        this.bindAddress = bindAddress;
    }

    public Future<Session> connect(InetSocketAddress address, SessionFrameListener listener) throws IOException
    {
        if (!factory.isStarted())
            throw new IllegalStateException(Factory.class.getSimpleName() + " is not started");

        SocketChannel channel = SocketChannel.open();
        if (bindAddress != null)
            channel.bind(bindAddress);
        channel.socket().setTcpNoDelay(true);
        channel.configureBlocking(false);

        SessionPromise result = new SessionPromise(channel, this, listener);

        channel.connect(address);
        factory.selector.connect(channel, result);

        return result;
    }

    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    public void setIdleTimeout(long idleTimeout)
    {
        this.idleTimeout = idleTimeout;
    }

    public int getInitialWindowSize()
    {
        return initialWindowSize;
    }

    public void setInitialWindowSize(int initialWindowSize)
    {
        this.initialWindowSize = initialWindowSize;
    }

    protected String selectProtocol(List<String> serverProtocols)
    {
        if (serverProtocols == null)
            return "spdy/2";

        for (String serverProtocol : serverProtocols)
        {
            for (String protocol : factories.keySet())
            {
                if (serverProtocol.equals(protocol))
                    return protocol;
            }
            String protocol = factory.selectProtocol(serverProtocols);
            if (protocol != null)
                return protocol;
        }

        return null;
    }

    public AsyncConnectionFactory getAsyncConnectionFactory(String protocol)
    {
        for (Map.Entry<String, AsyncConnectionFactory> entry : factories.entrySet())
        {
            if (protocol.equals(entry.getKey()))
                return entry.getValue();
        }
        for (Map.Entry<String, AsyncConnectionFactory> entry : factory.factories.entrySet())
        {
            if (protocol.equals(entry.getKey()))
                return entry.getValue();
        }
        return null;
    }

    public void putAsyncConnectionFactory(String protocol, AsyncConnectionFactory factory)
    {
        factories.put(protocol, factory);
    }

    public AsyncConnectionFactory removeAsyncConnectionFactory(String protocol)
    {
        return factories.remove(protocol);
    }

    public AsyncConnectionFactory getDefaultAsyncConnectionFactory()
    {
        return defaultAsyncConnectionFactory;
    }

    protected SSLEngine newSSLEngine(SslContextFactory sslContextFactory, SocketChannel channel)
    {
        String peerHost = channel.socket().getInetAddress().getHostAddress();
        int peerPort = channel.socket().getPort();
        SSLEngine engine = sslContextFactory.newSslEngine(peerHost, peerPort);
        engine.setUseClientMode(true);
        return engine;
    }

    protected FlowControlStrategy newFlowControlStrategy()
    {
        return FlowControlStrategyFactory.newFlowControlStrategy(version);
    }

    public void replaceAsyncConnection(AsyncEndPoint endPoint, AsyncConnection connection)
    {
        AsyncConnection oldConnection = endPoint.getAsyncConnection();
        endPoint.setAsyncConnection(connection);
        factory.selector.connectionUpgraded(endPoint, oldConnection);
    }

    public static class Factory extends AggregateLifeCycle
    {
        private final Map<String, AsyncConnectionFactory> factories = new ConcurrentHashMap<>();
        private final Queue<Session> sessions = new ConcurrentLinkedQueue<>();
        private final ByteBufferPool bufferPool = new StandardByteBufferPool();
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        private final Executor threadPool;
        private final SslContextFactory sslContextFactory;
        private final SelectorManager selector;
        private final long defaultTimeout = 30000;
        private final long idleTimeout;

        //TODO: Replace with Builder?!
        public Factory()
        {
            this(null, null, 30000);
        }

        public Factory(SslContextFactory sslContextFactory)
        {
            this(null, sslContextFactory, 30000);
        }

        public Factory(SslContextFactory sslContextFactory, long idleTimeout)
        {
            this(null, sslContextFactory, idleTimeout);
        }

        public Factory(Executor threadPool)
        {
            this(threadPool, null, 30000);
        }

        public Factory(Executor threadPool, long idleTimeout)
        {
            this(threadPool, null, idleTimeout);
        }

        public Factory(Executor threadPool, SslContextFactory sslContextFactory)
        {
            this(threadPool, sslContextFactory, 30000);
        }

        public Factory(Executor threadPool, SslContextFactory sslContextFactory, long idleTimeout)
        {
            this.idleTimeout = idleTimeout;
            if (threadPool == null)
                threadPool = new QueuedThreadPool();
            this.threadPool = threadPool;
            addBean(threadPool);

            this.sslContextFactory = sslContextFactory;
            if (sslContextFactory != null)
                addBean(sslContextFactory);

            selector = new ClientSelectorManager();
            addBean(selector);

            factories.put("spdy/2", new ClientSPDYAsyncConnectionFactory());
        }

        public SPDYClient newSPDYClient(short version)
        {
            return new SPDYClient(version, this);
        }

        @Override
        protected void doStop() throws Exception
        {
            closeConnections();
            super.doStop();
        }

        protected String selectProtocol(List<String> serverProtocols)
        {
            for (String serverProtocol : serverProtocols)
            {
                for (String protocol : factories.keySet())
                {
                    if (serverProtocol.equals(protocol))
                        return protocol;
                }
            }
            return null;
        }

        private boolean sessionOpened(Session session)
        {
            // Add sessions only if the factory is not stopping
            return isRunning() && sessions.offer(session);
        }

        private boolean sessionClosed(Session session)
        {
            // Remove sessions only if the factory is not stopping
            // to avoid concurrent removes during iterations
            return isRunning() && sessions.remove(session);
        }

        private void closeConnections()
        {
            for (Session session : sessions)
                session.goAway();
            sessions.clear();
        }

        protected Collection<Session> getSessions()
        {
            return Collections.unmodifiableCollection(sessions);
        }

        private class ClientSelectorManager extends SelectorManager
        {

            @Override
            protected AsyncEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key) throws IOException
            {
                SessionPromise attachment = (SessionPromise)key.attachment();

                long clientIdleTimeout = attachment.client.getIdleTimeout();
                if (clientIdleTimeout < 0)
                    clientIdleTimeout = idleTimeout;
                AsyncEndPoint result = new SelectChannelEndPoint(channel, selectSet, key, scheduler, clientIdleTimeout);

                return result;
            }

            @Override
            protected void execute(Runnable task)
            {
                threadPool.execute(task);
            }

            @Override
            public AsyncConnection newConnection(final SocketChannel channel, AsyncEndPoint endPoint, final Object attachment)
            {
                SessionPromise sessionPromise = (SessionPromise)attachment;
                final SPDYClient client = sessionPromise.client;

                try
                {
                    if (sslContextFactory != null)
                    {
                        final SSLEngine engine = client.newSSLEngine(sslContextFactory, channel);
                        SslConnection sslConnection = new SslConnection(bufferPool, threadPool, endPoint, engine)
                        {
                            @Override
                            public void onClose()
                            {
                                NextProtoNego.remove(engine);
                                super.onClose();
                            }
                        };

                        AsyncEndPoint sslEndPoint = sslConnection.getSslEndPoint();
                        NextProtoNegoClientAsyncConnection connection = new NextProtoNegoClientAsyncConnection(channel, sslEndPoint, attachment, client.factory.threadPool, client);
                        sslEndPoint.setAsyncConnection(connection);
                        connectionOpened(connection);

                        NextProtoNego.put(engine, connection);

                        return sslConnection;
                    }
                    else
                    {
                        AsyncConnectionFactory connectionFactory = new ClientSPDYAsyncConnectionFactory();
                        AsyncConnection connection = connectionFactory.newAsyncConnection(channel, endPoint, attachment);
                        endPoint.setAsyncConnection(connection);
                        return connection;
                    }
                }
                catch (RuntimeException x)
                {
                    sessionPromise.failed(null,x);
                    throw x;
                }
            }
        }
    }

    private static class SessionPromise extends Promise<Session>
    {
        private final SocketChannel channel;
        private final SPDYClient client;
        private final SessionFrameListener listener;

        private SessionPromise(SocketChannel channel, SPDYClient client, SessionFrameListener listener)
        {
            this.channel = channel;
            this.client = client;
            this.listener = listener;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning)
        {
            try
            {
                super.cancel(mayInterruptIfRunning);
                channel.close();
                return true;
            }
            catch (IOException x)
            {
                return true;
            }
        }
    }

    private static class ClientSPDYAsyncConnectionFactory implements AsyncConnectionFactory
    {
        @Override
        public AsyncConnection newAsyncConnection(SocketChannel channel, AsyncEndPoint endPoint, Object attachment)
        {
            SessionPromise sessionPromise = (SessionPromise)attachment;
            SPDYClient client = sessionPromise.client;
            Factory factory = client.factory;

            CompressionFactory compressionFactory = new StandardCompressionFactory();
            Parser parser = new Parser(compressionFactory.newDecompressor());
            Generator generator = new Generator(factory.bufferPool, compressionFactory.newCompressor());

            SPDYAsyncConnection connection = new ClientSPDYAsyncConnection(endPoint, factory.bufferPool, parser, factory);
            endPoint.setAsyncConnection(connection);

            FlowControlStrategy flowControlStrategy = client.newFlowControlStrategy();

            StandardSession session = new StandardSession(client.version, factory.bufferPool, factory.threadPool, factory.scheduler, connection, connection, 1, sessionPromise.listener, generator, flowControlStrategy);
            session.setWindowSize(client.getInitialWindowSize());
            parser.addListener(session);
            sessionPromise.completed(session);
            connection.setSession(session);

            factory.sessionOpened(session);

            return connection;
        }

        private class ClientSPDYAsyncConnection extends SPDYAsyncConnection
        {
            private final Factory factory;

            public ClientSPDYAsyncConnection(AsyncEndPoint endPoint, ByteBufferPool bufferPool, Parser parser, Factory factory)
            {
                super(endPoint, bufferPool, parser, factory.threadPool);
                this.factory = factory;
            }

            @Override
            public void onClose()
            {
                super.onClose();
                factory.sessionClosed(getSession());
            }
        }
    }
}
