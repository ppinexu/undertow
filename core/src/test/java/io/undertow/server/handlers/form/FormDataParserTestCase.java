/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.server.handlers.form;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.HttpClientUtils;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.testutils.TestHttpClient;
import io.undertow.util.StatusCodes;
import junit.textui.TestRunner;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * @author Stuart Douglas
 */
@RunWith(DefaultServer.Parameterized.class)
public class FormDataParserTestCase {

    static class AggregateRunner extends TestRunner {

    }

    private final HttpHandler rootHandler;

    public FormDataParserTestCase(final HttpHandler rootHandler) {
        this.rootHandler = rootHandler;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> handlerChains() {
        List<Object[]> ret = new ArrayList<>();
        final FormParserFactory parserFactory = FormParserFactory.builder().build();
        HttpHandler fd = new HttpHandler() {
            @Override
            public void handleRequest(final HttpServerExchange exchange) throws Exception {
                final FormDataParser parser = parserFactory.createParser(exchange);
                parser.parse(new HttpHandler() {
                    @Override
                    public void handleRequest(final HttpServerExchange exchange) throws Exception {
                        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
                        Iterator<String> it = data.iterator();
                        while (it.hasNext()) {
                            String fd = it.next();
                            for (FormData.FormValue val : data.get(fd)) {
                                exchange.getResponseHeaders().add(new HttpString("res"), fd + ":" + val.getValue());
                            }
                        }
                    }
                });

            }
        };
        ret.add(new Object[]{fd});
        final BlockingHandler blocking = new BlockingHandler();

        blocking.setRootHandler(new HttpHandler() {


            @Override
            public void handleRequest(final HttpServerExchange exchange) throws Exception {
                final FormDataParser parser = parserFactory.createParser(exchange);
                try {
                    FormData data = parser.parseBlocking();
                    Iterator<String> it = data.iterator();
                    while (it.hasNext()) {
                        String fd = it.next();
                        for (FormData.FormValue val : data.get(fd)) {
                            exchange.getResponseHeaders().add(new HttpString("res"), fd + ":" + val.getValue());
                        }
                    }
                } catch (IOException e) {
                    exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                }
            }
        });
        ret.add(new Object[]{blocking});
        return ret;

    }

    @Test
    public void testFormDataParsing() throws Exception {
        runTestUrlEncoded(new BasicNameValuePair("name", "A Value"));
        runTestUrlEncoded(new BasicNameValuePair("name", "A Value"), new BasicNameValuePair("Single-value", null));
        runTestUrlEncoded(new BasicNameValuePair("name", "A Value"), new BasicNameValuePair("A/name/with_special*chars", "A $ value&& with=SomeCharacters"));
        runTestUrlEncoded(new BasicNameValuePair("name", "A Value"), new BasicNameValuePair("Single-value", null) , new BasicNameValuePair("A/name/with_special*chars", "A $ value&& with=SomeCharacters"));
    }

    @Test
    public void testRawFormDataParsingIncorrectValue() throws Exception {
        testRawFormDataParsing(new BasicNameValuePair("name", "%"));
        testRawFormDataParsing(new BasicNameValuePair("Name%", "value"));
    }

    private void testRawFormDataParsing(NameValuePair wrongPair) throws Exception {
        NameValuePair correctPair = new BasicNameValuePair("correctName", "A Value");
        NameValuePair correctPair2 = new BasicNameValuePair("correctName2", "A Value2");

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder
                .append(URLEncodedUtils.format(java.util.Collections.singleton(correctPair), HTTP.DEF_CONTENT_CHARSET))
                .append("&")
                .append(wrongPair.getName()).append("=").append(wrongPair.getValue())
                .append("&")
                .append(URLEncodedUtils.format(java.util.Collections.singleton(correctPair2), HTTP.DEF_CONTENT_CHARSET));

        final List<NameValuePair> expectedData = new ArrayList<>();
        expectedData.add(correctPair);
        expectedData.add(correctPair2);
        runTest(expectedData, new StringEntity(stringBuilder.toString(), ContentType.APPLICATION_FORM_URLENCODED));
    }

    private void runTestUrlEncoded(final NameValuePair... pairs) throws Exception {
        final List<NameValuePair> data = new ArrayList<>(Arrays.asList(pairs));
        runTest(data, new UrlEncodedFormEntity(data));
    }

    private void runTest(List<NameValuePair> data, StringEntity entity) throws  Exception {
        DefaultServer.setRootHandler(rootHandler);
        TestHttpClient client = new TestHttpClient();
        try {
            HttpPost post = new HttpPost(DefaultServer.getDefaultServerURL() + "/path");
            post.setHeader(Headers.CONTENT_TYPE_STRING, FormEncodedDataDefinition.APPLICATION_X_WWW_FORM_URLENCODED);
            post.setEntity(entity);
            HttpResponse result = client.execute(post);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            checkResult(data, result);
            HttpClientUtils.readResponse(result);
        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    private void checkResult(final List<NameValuePair> data, final HttpResponse result) {
        Map<String, String> res = new HashMap<>();
        for(Header d : result.getHeaders("res")) {
            String[] split = d.getValue().split(":");
            res.put(split[0], split.length == 1 ? "" : split[1]);
        }

        Assert.assertEquals(data.size(), res.size());

        for (NameValuePair vp : data) {
            Assert.assertEquals(vp.getValue() == null ? "" : vp.getValue(), res.get(vp.getName()));
        }
    }

}
