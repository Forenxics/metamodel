/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.metamodel.elasticsearch.rest;

import static org.easymock.EasyMock.*;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.metamodel.data.DataSet;
import org.easymock.EasyMock;
import org.elasticsearch.action.search.ClearScrollRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Cancellable;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseListener;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.junit.Test;

public class ElasticSearchRestDataSetTest {

    /**
     * The body returned by the Elasticsearch "main response" endpoint
     * ({@code GET /}). The high level REST client validates the server version
     * with it before it performs the first real request, so the mocked client
     * has to answer it.
     */
    private static final String VERSION_RESPONSE = "{\"name\":\"node-1\",\"cluster_name\":\"elasticsearch\","
            + "\"cluster_uuid\":\"1c3a5b6d\",\"version\":{\"number\":\"7.17.28\",\"build_flavor\":\"default\","
            + "\"build_type\":\"tar\",\"build_hash\":\"8f0d0a1b2c3d4e5f60718293a4b5c6d7e8f90123\","
            + "\"build_date\":\"2025-01-01T00:00:00.000000000Z\",\"build_snapshot\":false,"
            + "\"lucene_version\":\"8.11.1\",\"minimum_wire_compatibility_version\":\"6.8.0\","
            + "\"minimum_index_compatibility_version\":\"6.0.0-beta1\"},"
            + "\"tagline\":\"You Know, for Search\"}";

    @Test
    public void testClose() throws IOException {
        final RestClient client = createMock(RestClient.class);
        final Response versionResponse = createMock(Response.class);
        final Response clearScrollResponse = createMock(Response.class);
        final StatusLine statusLine = createMock(StatusLine.class);
        final HttpEntity versionEntity = createMock(HttpEntity.class);
        final HttpEntity httpEntity = createMock(HttpEntity.class);
        final Header header = createMock(Header.class);

        // Instead of mocking all the classes above, I would rather just mock the RestHighLevelClient, because I only
        // want to verify that the clearScroll method is invoked on it when the ElasticSearchRestDataSet is closed, but
        // because of the manner in which the RestHighLevelClient is implemented it's impossible to mock this in a nice
        // manner.
        final RestHighLevelClient restHighLevelClient = new MockRestHighLevelClient(client);
        final SearchResponse searchResponse = createMock(SearchResponse.class);

        final ClearScrollRequest clearScrollRequest = new ClearScrollRequest();
        clearScrollRequest.addScrollId("id");

        expect(statusLine.getStatusCode()).andReturn(200).anyTimes();
        expect(header.getValue()).andReturn("application/*").anyTimes();

        // the server version validation request
        expect(versionResponse.getStatusLine()).andReturn(statusLine).anyTimes();
        expect(versionResponse.getHeader("X-Elastic-Product")).andReturn("Elasticsearch").anyTimes();
        expect(versionEntity.getContentType()).andReturn(header).anyTimes();
        expect(versionEntity.getContent()).andReturn(new ByteArrayInputStream(VERSION_RESPONSE.getBytes()));
        expect(versionResponse.getEntity()).andReturn(versionEntity);

        // the clear scroll request
        expect(clearScrollResponse.getStatusLine()).andReturn(statusLine).anyTimes();
        expect(clearScrollResponse.getHeader("X-Elastic-Product")).andReturn("Elasticsearch").anyTimes();
        expect(httpEntity.getContentType()).andReturn(header).anyTimes();
        expect(httpEntity.getContent())
                .andReturn(new ByteArrayInputStream("{\"succeeded\": true,\"num_freed\": 3}".getBytes()));
        expect(clearScrollResponse.getEntity()).andReturn(httpEntity);

        expect(searchResponse.getScrollId()).andReturn("id");
        // The high level client validates the server version with an
        // asynchronous GET / before it performs the first real request.
        expect(client.performRequestAsync(anyObject(), anyObject())).andAnswer(() -> {
            final Object[] args = getCurrentArguments();
            final ResponseListener listener = (ResponseListener) args[1];
            listener.onSuccess(versionResponse);
            return createMock(Cancellable.class);
        }).anyTimes();

        // the clear scroll request itself
        final int[] requestCount = new int[1];
        expect(client.performRequest(anyObject())).andAnswer(() -> {
            requestCount[0]++;
            return clearScrollResponse;
        }).anyTimes();

        EasyMock.replay(client, searchResponse, versionResponse, clearScrollResponse, statusLine, versionEntity,
                httpEntity, header);

        final DataSet dataSet = new ElasticSearchRestDataSet(restHighLevelClient, searchResponse, Collections
                .emptyList());

        dataSet.close();

        assertEquals("exactly one clear scroll request must be performed", 1, requestCount[0]);
        EasyMock.verify(client, searchResponse, versionResponse, clearScrollResponse, statusLine, versionEntity,
                httpEntity, header);
    }

    class MockRestHighLevelClient extends RestHighLevelClient {
        MockRestHighLevelClient(final RestClient client) {
            super(client, RestClient::close, Collections.emptyList());
        }
    }
}
