/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.grizzly.connectionpool;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
/**
 *
 * @author oleksiys
 */
public class MultiEndPointPoolTest {
    private static final int PORT = 18334;
    private static final int NUMBER_OF_PORTS_TO_BIND = 3;
    
    private final Set<Connection> serverSideConnections =
            Collections.newSetFromMap(new ConcurrentHashMap<Connection, Boolean>());
    
    private TCPNIOTransport transport;
    
    @Before
    public void init() throws IOException {
        final FilterChain filterChain = FilterChainBuilder.stateless()
                .add(new TransportFilter())
                .add(new BaseFilter() {

            @Override
            public NextAction handleAccept(FilterChainContext ctx) throws IOException {
                serverSideConnections.add(ctx.getConnection());
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleClose(FilterChainContext ctx) throws IOException {
                serverSideConnections.remove(ctx.getConnection());
                return ctx.getStopAction();
            }
        }).build();
        
        transport = TCPNIOTransportBuilder.newInstance().build();
        transport.setProcessor(filterChain);
        
        for (int i = 0; i < NUMBER_OF_PORTS_TO_BIND; i++) {
            transport.bind(PORT + i);
        }
        
        transport.start();
    }
    
    @After
    public void tearDown() throws IOException {
        serverSideConnections.clear();
        
        if (transport != null) {
            transport.stop();
        }
    }
    
    @Test
    public void testBasicPollRelease() throws Exception {
        final MultiEndpointPool<SocketAddress> pool = new MultiEndpointPool<SocketAddress>(
                transport, 3, 15, null, -1, 1000, 1000);
        
        final MultiEndpointKey<SocketAddress> key1 =
                new MultiEndpointKey<SocketAddress>("endpoint1",
                new InetSocketAddress("localhost", PORT));
        
        final MultiEndpointKey<SocketAddress> key2 =
                new MultiEndpointKey<SocketAddress>("endpoint2",
                new InetSocketAddress("localhost", PORT + 1));
        
        Connection c11 = pool.take(key1);
        assertNotNull(c11);
        assertEquals(1, pool.size());
        Connection c12 = pool.take(key1);
        assertNotNull(c12);
        assertEquals(2, pool.size());
        
        Connection c21 = pool.take(key2);
        assertNotNull(c21);
        assertEquals(3, pool.size());
        Connection c22 = pool.take(key2);
        assertNotNull(c22);
        assertEquals(4, pool.size());
        
        pool.release(key1, c11);
        assertEquals(4, pool.size());
        
        pool.release(key2, c21);
        assertEquals(4, pool.size());
        
        c11 = pool.take(key1);
        assertNotNull(c11);
        assertEquals(4, pool.size());
        
        pool.detach(key1, c11);
        assertEquals(3, pool.size());
        
        pool.release(key1, c11);
        assertEquals(3, pool.size());
        
        assertTrue(pool.attach(key1, c11));
        assertEquals(4, pool.size());
        
        pool.release(key1, c11);
        assertEquals(4, pool.size());
        
        c11 = pool.take(key1);
        assertNotNull(c11);

        c21 = pool.take(key2);
        assertNotNull(c21);

        c11.close().get(10, TimeUnit.SECONDS);
        assertEquals(3, pool.size());
        
        c12.close().get(10, TimeUnit.SECONDS);
        assertEquals(2, pool.size());

        c21.close().get(10, TimeUnit.SECONDS);
        assertEquals(1, pool.size());

        c22.close().get(10, TimeUnit.SECONDS);
        assertEquals(0, pool.size());
    }
    
    @Test
    public void testTotalPoolSizeLimit() throws Exception {
        final MultiEndpointPool<SocketAddress> pool = new MultiEndpointPool<SocketAddress>(
                transport, 2, 2, null, -1, 1000, 1000);
        
        final MultiEndpointKey<SocketAddress> key1 =
                new MultiEndpointKey<SocketAddress>("endpoint1",
                new InetSocketAddress("localhost", PORT));
        
        final MultiEndpointKey<SocketAddress> key2 =
                new MultiEndpointKey<SocketAddress>("endpoint2",
                new InetSocketAddress("localhost", PORT + 1));
        
        Connection c11 = pool.take(key1);
        assertNotNull(c11);
        assertEquals(1, pool.size());
        final Connection c12 = pool.take(key1);
        assertNotNull(c12);
        assertEquals(2, pool.size());
        
        Connection c21 = pool.poll(key2, 2, TimeUnit.SECONDS);
        assertNull(c21);
        assertEquals(2, pool.size());
        
        final Thread t = new Thread() {

            @Override
            public void run() {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                }
                
                c12.closeSilently();
            }
        };
        t.start();
        
        c21 = pool.poll(key2, 10, TimeUnit.SECONDS);
        assertNotNull(c12);
        assertEquals(2, pool.size());
        
        c11.close().get(10, TimeUnit.SECONDS);
        assertEquals(1, pool.size());

        c21.close().get(10, TimeUnit.SECONDS);
        assertEquals(0, pool.size());
    }
}
