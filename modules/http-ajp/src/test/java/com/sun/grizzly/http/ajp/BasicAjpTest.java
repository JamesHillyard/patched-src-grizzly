/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.grizzly.http.ajp;

import com.sun.grizzly.tcp.Adapter;
import com.sun.grizzly.tcp.Request;
import com.sun.grizzly.tcp.Response;
import com.sun.grizzly.tcp.StaticResourcesAdapter;
import com.sun.grizzly.tcp.http11.GrizzlyAdapter;
import com.sun.grizzly.tcp.http11.GrizzlyRequest;
import com.sun.grizzly.tcp.http11.GrizzlyResponse;
import com.sun.grizzly.util.FutureImpl;
import java.io.ByteArrayInputStream;
import java.nio.channels.Channel;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Test simple Ajp communication usecases.
 *
 * @author Alexey Stashok
 * @author Justin Lee
 */
public class BasicAjpTest extends AjpTestBase {

    @Test
    public void testStaticRequests() throws IOException, InstantiationException {
        StaticResourcesAdapter a = new StaticResourcesAdapter("src/test/resources");
        a.setUseSendFile(false);   // TODO Re-enable once sendFile is implemented.
        configureHttpServer(a);

        final String[] files = {/*"/ajpindex.html", */"/ajplarge.html"};
        for (String file : files) {
            try {
                requestFile(file);
            } catch (Exception e) {
                throw new RuntimeException("Testing file " + file + ": " + e.getMessage(), e);
            }
        }
    }

    @Test
    public void testDynamicRequests() throws IOException, InstantiationException {
        final String message = "Test Message";
        final StringBuilder builder = new StringBuilder();
        while (builder.length() < 100000) {
            builder.append(message);
        }
        for (String test : new String[]{message, builder.toString()}) {
            dynamicRequest(test);
        }
    }

    private void requestFile(String file) throws IOException {
        AjpForwardRequestPacket forward = new AjpForwardRequestPacket("GET", file, PORT, 0);
        send(forward.toByteArray());

        AjpResponse ajpResponse = Utils.parseResponse(readAjpMessage());

        Assert.assertEquals("Testing file " + file, 200, ajpResponse.getResponseCode());
        Assert.assertEquals("Testing file " + file, "OK", ajpResponse.getResponseMessage());

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        do {
            ajpResponse = Utils.parseResponse(readAjpMessage());
            if (ajpResponse.getType() == AjpConstants.JK_AJP13_SEND_BODY_CHUNK) {
                stream.write(ajpResponse.getBody());
            }
        } while (ajpResponse.getType() == AjpConstants.JK_AJP13_SEND_BODY_CHUNK);
        
        System.out.println("src/test/resources" + file);
        Assert.assertArrayEquals("Testing file " + file, readFile("src/test/resources" + file), stream.toByteArray());

        Assert.assertEquals("Testing file " + file, AjpConstants.JK_AJP13_END_RESPONSE, ajpResponse.getType());
    }

    private void dynamicRequest(final String message) throws IOException, InstantiationException {
        try {
            configureHttpServer(new GrizzlyAdapter() {
                @Override
                public void service(GrizzlyRequest request, GrizzlyResponse response) throws Exception {
                    response.setContentLength(message.length());
                    response.setContentType("text");
                    response.setLocale(Locale.US);
                    for (int i = 1; i <= 10; i++) {
                        response.addHeader("header", "value" + i);
                    }

                    response.getOutputBuffer().write(message);
                }
            });
            AjpForwardRequestPacket forward = new AjpForwardRequestPacket("GET", "/bob", PORT, 0);
            send(forward.toByteArray());
            AjpResponse ajpResponse = Utils.parseResponse(readAjpMessage());

            Assert.assertEquals(200, ajpResponse.getResponseCode());
            Assert.assertEquals("OK", ajpResponse.getResponseMessage());
            Assert.assertEquals("Should get all the grizzly headers back", 10,
                    Collections.list(ajpResponse.getHeaders().values("header")).size());

            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            do {
                ajpResponse = Utils.parseResponse(readAjpMessage());
                if (ajpResponse.getType() == AjpConstants.JK_AJP13_SEND_BODY_CHUNK) {
                    stream.write(ajpResponse.getBody());
                }
            } while (ajpResponse.getType() == AjpConstants.JK_AJP13_SEND_BODY_CHUNK);
            Assert.assertEquals(message, new String(stream.toByteArray()));

            Assert.assertEquals(AjpConstants.JK_AJP13_END_RESPONSE, ajpResponse.getType());
        } finally {
            after();
        }
    }

    public void testPingPong() throws Exception {
        configureHttpServer(new StaticResourcesAdapter("src/test/resources"));
        
        final byte[] request = new byte[] {0x12, 0x34, 0, 1, AjpConstants.JK_AJP13_CPING_REQUEST};
        
        send(request);
        
        final DataInputStream responseInputStream = new DataInputStream(new ByteArrayInputStream(readAjpMessage()));
        
        Assert.assertEquals((byte) 'A', responseInputStream.read());
        Assert.assertEquals((byte) 'B', responseInputStream.read());
        Assert.assertEquals((short) 1, responseInputStream.readShort());
        Assert.assertEquals(AjpConstants.JK_AJP13_CPONG_REPLY, responseInputStream.read());
    }

    @Test
    public void testShutdownHandler() throws Exception {
        final FutureImpl<Boolean> shutdownFuture = new FutureImpl();
        final ShutdownHandler shutDownHandler = new ShutdownHandler() {

            public void onShutdown(Channel initiator) {
                shutdownFuture.setResult(true);
            }
        };

        configureHttpServer(new StaticResourcesAdapter("src/test/resources"));
        selectorThread.addShutdownHandler(shutDownHandler);
        
        final byte[] request = new byte[] {0x12, 0x34, 0, 1, AjpConstants.JK_AJP13_SHUTDOWN};
        
        send(request);

        final Boolean b = shutdownFuture.get(10, TimeUnit.SECONDS);
        Assert.assertTrue(b);
    }

    @Test
    public void testNullAttribute() throws Exception {
        configureHttpServer(new Adapter() {

            public void service(Request request, Response response) throws Exception {
                final Set<String> attributeNames = request.getAttributes().keySet();

                final boolean isOk =
                        attributeNames.contains("JK_LB_ACTIVATION") &&
                        request.getAttribute("JK_LB_ACTIVATION") == null &&
                        attributeNames.contains("AJP_REMOTE_PORT") &&
                        "60955".equals(request.getAttribute("AJP_REMOTE_PORT"));
                
                
                if (isOk) {
                    response.setStatus(200);
                    response.setMessage("FINE");
                } else {
                    response.setStatus(500);
                    response.setMessage("Attributes don't match");
                }
            }

            public void afterService(Request req, Response res) throws Exception {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        });

        send(NULL_ATTR_PAYLOAD);

        AjpResponse ajpResponse = Utils.parseResponse(readAjpMessage());

        Assert.assertEquals(200, ajpResponse.getResponseCode());
        Assert.assertEquals("FINE", ajpResponse.getResponseMessage());
    }
    
    @Test
    public void testFormParameters() throws Exception {
        final Map<String, String[]> patternMap = new HashMap<String, String[]>();
        patternMap.put("title", new String[] {"Developing PaaS Components"});
        patternMap.put("authors", new String[] {"Shalini M"});
        patternMap.put("price", new String[] {"100$"});
        
        configureHttpServer(new GrizzlyAdapter() {

            @Override
            public void service(GrizzlyRequest request, GrizzlyResponse response) throws Exception {
                final Map<String, String[]> paramMap = request.getParameterMap();
                boolean isOk = paramMap.size() == patternMap.size();
                
                if (isOk) {
                    // if sizes are equal - compare content
                    for (Map.Entry<String, String[]> patternEntry : patternMap.entrySet()) {
                        final String key = patternEntry.getKey();
                        final String[] value = patternEntry.getValue();
                        isOk = paramMap.containsKey(key) &&
                                Arrays.equals(value, paramMap.get(key));
                        
                        if (!isOk) break;
                    }
                }
                
                if (isOk) {
                    response.setStatus(200, "FINE");
                } else {
                    response.setStatus(500, "Attributes don't match");
                }
            }

        });
        
        send(FORM_PARAMETERS_PAYLOAD1);
        Thread.sleep(1000);
        send(FORM_PARAMETERS_PAYLOAD2);

        AjpResponse ajpResponse = Utils.parseResponse(readAjpMessage());

        Assert.assertEquals(200, ajpResponse.getResponseCode());
        Assert.assertEquals("FINE", ajpResponse.getResponseMessage());
    }

    private static final byte[] NULL_ATTR_PAYLOAD = {
        0x12, 0x34, 0x01, (byte) 0xCE, 0x02, 0x02, 0x00,
        0x08, 0x48, 0x54, 0x54, 0x50, 0x2F, 0x31, 0x2E, 0x31, 0x00, 0x00,
        0x1B, 0x2F, 0x53, 0x69, 0x6D, 0x70, 0x6C, 0x65, 0x57, 0x65, 0x62,
        0x41, 0x70, 0x70, 0x2F, 0x53, 0x69, 0x6D, 0x70, 0x6C, 0x65, 0x53,
        0x65, 0x72, 0x76, 0x6C, 0x65, 0x74, 0x00, 0x00, 0x03, 0x3A, 0x3A,
        0x31, 0x00, (byte) 0xFF, (byte) 0xFF, 0x00, 0x09, 0x6C, 0x6F, 0x63,
        0x61, 0x6C, 0x68, 0x6F, 0x73, 0x74, 0x00, (byte) 0xBB, (byte) 0xD0,
        0x00, 0x00, 0x09, (byte) 0xA0, 0x0B, 0x00, 0x0F, 0x6C, 0x6F, 0x63,
        0x61, 0x6C, 0x68, 0x6F, 0x73, 0x74, 0x3A, 0x34, 0x38, 0x30, 0x38,
        0x30, 0x00, (byte) 0xA0, 0x0E, 0x00, 0x53, 0x4D, 0x6F, 0x7A, 0x69,
        0x6C, 0x6C, 0x61, 0x2F, 0x35, 0x2E, 0x30, 0x20, 0x28, 0x4D, 0x61,
        0x63, 0x69, 0x6E, 0x74, 0x6F, 0x73, 0x68, 0x3B, 0x20, 0x49, 0x6E,
        0x74, 0x65, 0x6C, 0x20, 0x4D, 0x61, 0x63, 0x20, 0x4F, 0x53, 0x20,
        0x58, 0x20, 0x31, 0x30, 0x2E, 0x35, 0x3B, 0x20, 0x72, 0x76, 0x3A,
        0x35, 0x2E, 0x30, 0x2E, 0x31, 0x29, 0x20, 0x47, 0x65, 0x63, 0x6B,
        0x6F, 0x2F, 0x32, 0x30, 0x31, 0x30, 0x30, 0x31, 0x30, 0x31, 0x20,
        0x46, 0x69, 0x72, 0x65, 0x66, 0x6F, 0x78, 0x2F, 0x35, 0x2E, 0x30,
        0x2E, 0x31, 0x00, (byte) 0xA0, 0x01, 0x00, 0x3F, 0x74, 0x65, 0x78,
        0x74, 0x2F, 0x68, 0x74, 0x6D, 0x6C, 0x2C, 0x61, 0x70, 0x70, 0x6C,
        0x69, 0x63, 0x61, 0x74, 0x69, 0x6F, 0x6E, 0x2F, 0x78, 0x68, 0x74,
        0x6D, 0x6C, 0x2B, 0x78, 0x6D, 0x6C, 0x2C, 0x61, 0x70, 0x70, 0x6C,
        0x69, 0x63, 0x61, 0x74, 0x69, 0x6F, 0x6E, 0x2F, 0x78, 0x6D, 0x6C,
        0x3B, 0x71, 0x3D, 0x30, 0x2E, 0x39, 0x2C, 0x2A, 0x2F, 0x2A, 0x3B,
        0x71, 0x3D, 0x30, 0x2E, 0x38, 0x00, 0x00, 0x0F, 0x41, 0x63, 0x63,
        0x65, 0x70, 0x74, 0x2D, 0x4C, 0x61, 0x6E, 0x67, 0x75, 0x61, 0x67,
        0x65, 0x00, 0x00, 0x0E, 0x65, 0x6E, 0x2D, 0x75, 0x73, 0x2C, 0x65,
        0x6E, 0x3B, 0x71, 0x3D, 0x30, 0x2E, 0x35, 0x00, 0x00, 0x0F, 0x41,
        0x63, 0x63, 0x65, 0x70, 0x74, 0x2D, 0x45, 0x6E, 0x63, 0x6F, 0x64,
        0x69, 0x6E, 0x67, 0x00, 0x00, 0x0D, 0x67, 0x7A, 0x69, 0x70, 0x2C,
        0x20, 0x64, 0x65, 0x66, 0x6C, 0x61, 0x74, 0x65, 0x00, 0x00, 0x0E,
        0x41, 0x63, 0x63, 0x65, 0x70, 0x74, 0x2D, 0x43, 0x68, 0x61, 0x72,
        0x73, 0x65, 0x74, 0x00, 0x00, 0x1E, 0x49, 0x53, 0x4F, 0x2D, 0x38,
        0x38, 0x35, 0x39, 0x2D, 0x31, 0x2C, 0x75, 0x74, 0x66, 0x2D, 0x38,
        0x3B, 0x71, 0x3D, 0x30, 0x2E, 0x37, 0x2C, 0x2A, 0x3B, 0x71, 0x3D,
        0x30, 0x2E, 0x37, 0x00, (byte) 0xA0, 0x06, 0x00, 0x0A, 0x6B, 0x65,
        0x65, 0x70, 0x2D, 0x61, 0x6C, 0x69, 0x76, 0x65, 0x00, 0x00, 0x0D,
        0x43, 0x61, 0x63, 0x68, 0x65, 0x2D, 0x43, 0x6F, 0x6E, 0x74, 0x72,
        0x6F, 0x6C, 0x00, 0x00, 0x09, 0x6D, 0x61, 0x78, 0x2D, 0x61, 0x67,
        0x65, 0x3D, 0x30, 0x00, (byte) 0xA0, 0x08, 0x00, 0x01, 0x30, 0x00,
        0x0A, 0x00, 0x0F, 0x41, 0x4A, 0x50, 0x5F, 0x52, 0x45, 0x4D, 0x4F,
        0x54, 0x45, 0x5F, 0x50, 0x4F, 0x52, 0x54, 0x00, 0x00, 0x05, 0x36,
        0x30, 0x39, 0x35, 0x35, 0x00, 0x0A, 0x00, 0x10, 0x4A, 0x4B, 0x5F,
        0x4C, 0x42, 0x5F, 0x41, 0x43, 0x54, 0x49, 0x56, 0x41, 0x54, 0x49,
        0x4F, 0x4E, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    
    private static final byte[] FORM_PARAMETERS_PAYLOAD1 = {
        0x12, 0x34, 0x02, 0x6C, 0x02, 0x04, 0x00, 0x08, 0x48, 0x54,
        0x54, 0x50, 0x2F, 0x31, 0x2E, 0x31, 0x00, 0x00, 0x1B, 0x2F,
        0x62, 0x6F, 0x6F, 0x6B, 0x73, 0x74, 0x6F, 0x72, 0x65, 0x2F,
        0x42, 0x6F, 0x6F, 0x6B, 0x53, 0x74, 0x6F, 0x72, 0x65, 0x53,
        0x65, 0x72, 0x76, 0x6C, 0x65, 0x74, 0x00, 0x00, 0x09, 0x31,
        0x32, 0x37, 0x2E, 0x30, 0x2E, 0x30, 0x2E, 0x31, 0x00, (byte) 0xFF,
        (byte) 0xFF, 0x00, 0x09, 0x6C, 0x6F, 0x63, 0x61, 0x6C, 0x68, 0x6F,
        0x73, 0x74, 0x00, (byte) 0xBB, (byte) 0xD0, 0x00, 0x00, 0x0B, (byte) 0xA0, 0x0B,
        0x00, 0x0F, 0x6C, 0x6F, 0x63, 0x61, 0x6C, 0x68, 0x6F, 0x73,
        0x74, 0x3A, 0x34, 0x38, 0x30, 0x38, 0x30, 0x00, (byte) 0xA0, 0x0E,
        0x00, 0x46, 0x4D, 0x6F, 0x7A, 0x69, 0x6C, 0x6C, 0x61, 0x2F,
        0x35, 0x2E, 0x30, 0x20, 0x28, 0x58, 0x31, 0x31, 0x3B, 0x20,
        0x4C, 0x69, 0x6E, 0x75, 0x78, 0x20, 0x78, 0x38, 0x36, 0x5F,
        0x36, 0x34, 0x3B, 0x20, 0x72, 0x76, 0x3A, 0x36, 0x2E, 0x30,
        0x2E, 0x31, 0x29, 0x20, 0x47, 0x65, 0x63, 0x6B, 0x6F, 0x2F,
        0x32, 0x30, 0x31, 0x30, 0x30, 0x31, 0x30, 0x31, 0x20, 0x46,
        0x69, 0x72, 0x65, 0x66, 0x6F, 0x78, 0x2F, 0x36, 0x2E, 0x30,
        0x2E, 0x31, 0x00, (byte) 0xA0, 0x01, 0x00, 0x3F, 0x74, 0x65, 0x78,
        0x74, 0x2F, 0x68, 0x74, 0x6D, 0x6C, 0x2C, 0x61, 0x70, 0x70,
        0x6C, 0x69, 0x63, 0x61, 0x74, 0x69, 0x6F, 0x6E, 0x2F, 0x78,
        0x68, 0x74, 0x6D, 0x6C, 0x2B, 0x78, 0x6D, 0x6C, 0x2C, 0x61,
        0x70, 0x70, 0x6C, 0x69, 0x63, 0x61, 0x74, 0x69, 0x6F, 0x6E,
        0x2F, 0x78, 0x6D, 0x6C, 0x3B, 0x71, 0x3D, 0x30, 0x2E, 0x39,
        0x2C, 0x2A, 0x2F, 0x2A, 0x3B, 0x71, 0x3D, 0x30, 0x2E, 0x38,
        0x00, 0x00, 0x0F, 0x41, 0x63, 0x63, 0x65, 0x70, 0x74, 0x2D,
        0x4C, 0x61, 0x6E, 0x67, 0x75, 0x61, 0x67, 0x65, 0x00, 0x00,
        0x0E, 0x65, 0x6E, 0x2D, 0x75, 0x73, 0x2C, 0x65, 0x6E, 0x3B,
        0x71, 0x3D, 0x30, 0x2E, 0x35, 0x00, 0x00, 0x0F, 0x41, 0x63,
        0x63, 0x65, 0x70, 0x74, 0x2D, 0x45, 0x6E, 0x63, 0x6F, 0x64,
        0x69, 0x6E, 0x67, 0x00, 0x00, 0x0D, 0x67, 0x7A, 0x69, 0x70,
        0x2C, 0x20, 0x64, 0x65, 0x66, 0x6C, 0x61, 0x74, 0x65, 0x00,
        0x00, 0x0E, 0x41, 0x63, 0x63, 0x65, 0x70, 0x74, 0x2D, 0x43,
        0x68, 0x61, 0x72, 0x73, 0x65, 0x74, 0x00, 0x00, 0x1E, 0x49,
        0x53, 0x4F, 0x2D, 0x38, 0x38, 0x35, 0x39, 0x2D, 0x31, 0x2C,
        0x75, 0x74, 0x66, 0x2D, 0x38, 0x3B, 0x71, 0x3D, 0x30, 0x2E,
        0x37, 0x2C, 0x2A, 0x3B, 0x71, 0x3D, 0x30, 0x2E, 0x37, 0x00,
        (byte) 0xA0, 0x06, 0x00, 0x0A, 0x6B, 0x65, 0x65, 0x70, 0x2D, 0x61,
        0x6C, 0x69, 0x76, 0x65, 0x00, (byte) 0xA0, 0x0D, 0x00, 0x31, 0x68,
        0x74, 0x74, 0x70, 0x3A, 0x2F, 0x2F, 0x6C, 0x6F, 0x63, 0x61,
        0x6C, 0x68, 0x6F, 0x73, 0x74, 0x3A, 0x34, 0x38, 0x30, 0x38,
        0x30, 0x2F, 0x62, 0x6F, 0x6F, 0x6B, 0x73, 0x74, 0x6F, 0x72,
        0x65, 0x2F, 0x42, 0x6F, 0x6F, 0x6B, 0x53, 0x74, 0x6F, 0x72,
        0x65, 0x53, 0x65, 0x72, 0x76, 0x6C, 0x65, 0x74, 0x00, (byte) 0xA0,
        0x09, 0x00, (byte) 0x90, 0x41, 0x44, 0x4D, 0x49, 0x4E, 0x43, 0x4F,
        0x4E, 0x53, 0x4F, 0x4C, 0x45, 0x53, 0x45, 0x53, 0x53, 0x49,
        0x4F, 0x4E, 0x3D, 0x67, 0x66, 0x56, 0x70, 0x54, 0x6C, 0x76,
        0x58, 0x39, 0x38, 0x39, 0x51, 0x59, 0x44, 0x72, 0x6D, 0x53,
        0x53, 0x57, 0x47, 0x6A, 0x72, 0x33, 0x47, 0x4A, 0x30, 0x66,
        0x57, 0x68, 0x47, 0x76, 0x44, 0x37, 0x51, 0x58, 0x4A, 0x66,
        0x32, 0x52, 0x52, 0x59, 0x4C, 0x56, 0x4B, 0x6E, 0x59, 0x71,
        0x32, 0x57, 0x79, 0x57, 0x50, 0x21, 0x2D, 0x32, 0x31, 0x32,
        0x37, 0x33, 0x35, 0x39, 0x37, 0x31, 0x34, 0x3B, 0x20, 0x4A,
        0x53, 0x45, 0x53, 0x53, 0x49, 0x4F, 0x4E, 0x49, 0x44, 0x3D,
        0x34, 0x62, 0x64, 0x31, 0x33, 0x32, 0x30, 0x64, 0x30, 0x61,
        0x66, 0x35, 0x66, 0x33, 0x35, 0x62, 0x66, 0x35, 0x64, 0x31,
        0x37, 0x66, 0x39, 0x66, 0x30, 0x65, 0x62, 0x38, 0x3B, 0x20,
        0x74, 0x72, 0x65, 0x65, 0x46, 0x6F, 0x72, 0x6D, 0x5F, 0x74,
        0x72, 0x65, 0x65, 0x2D, 0x68, 0x69, 0x3D, 0x00, (byte) 0xA0, 0x07,
        0x00, 0x21, 0x61, 0x70, 0x70, 0x6C, 0x69, 0x63, 0x61, 0x74,
        0x69, 0x6F, 0x6E, 0x2F, 0x78, 0x2D, 0x77, 0x77, 0x77, 0x2D,
        0x66, 0x6F, 0x72, 0x6D, 0x2D, 0x75, 0x72, 0x6C, 0x65, 0x6E,
        0x63, 0x6F, 0x64, 0x65, 0x64, 0x00, (byte) 0xA0, 0x08, 0x00, 0x02,
        0x36, 0x33, 0x00, (byte) 0xFF
    };
    
    private static final byte[] FORM_PARAMETERS_PAYLOAD2 = {
        0x12, 0x34, 0x00, 0x41, 0x00, 0x3F, 0x74, 0x69, 0x74, 0x6C,
        0x65, 0x3D, 0x44, 0x65, 0x76, 0x65, 0x6C, 0x6F, 0x70, 0x69,
        0x6E, 0x67, 0x2B, 0x50, 0x61, 0x61, 0x53, 0x2B, 0x43, 0x6F,
        0x6D, 0x70, 0x6F, 0x6E, 0x65, 0x6E, 0x74, 0x73, 0x26, 0x61,
        0x75, 0x74, 0x68, 0x6F, 0x72, 0x73, 0x3D, 0x53, 0x68, 0x61,
        0x6C, 0x69, 0x6E, 0x69, 0x2B, 0x4D, 0x26, 0x70, 0x72, 0x69,
        0x63, 0x65, 0x3D, 0x31, 0x30, 0x30, 0x25, 0x32, 0x34
    };
}
