package com.couchbase.lite.replicator;

import com.couchbase.lite.Manager;
import com.couchbase.lite.Misc;
import com.squareup.okhttp.mockwebserver.Dispatcher;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.RecordedRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Misc helper methods for MockWebserver-based Mock objects
 */
public class MockHelper {

    public static final String PATH_REGEX_CHECKPOINT = "/db/_local.*";
    public static final String PATH_REGEX_CHANGES = "/db/_changes.*";
    public static final String PATH_REGEX_REVS_DIFF = "/db/_revs_diff.*";
    public static final String PATH_REGEX_BULK_DOCS = "/db/_bulk_docs.*";
    public static final String PATH_REGEX_SESSION = "/db/_session.*";
    public static final String PATH_REGEX_FACEBOOK_AUTH = "/db/_facebook.*";

    public static MockWebServer getMockWebServer(MockDispatcher dispatcher) {

        MockWebServer server = new MockWebServer();

        server.setDispatcher(dispatcher);

        return server;

    }

    /**
     * Get a "preloaded" mock sync gateway suitable to be used as a pull replication target.
     * It's preloaded in the sense that it is ready serve up mock documents.
     *
     * @param dispatcher the MockDispatcher
     * @param numMockDocsToServe how many docs should be served to pull replicator?
     * @param numDocsPerChangesResponse how many docs to add to each _changes response?  MAXINT for all.
     *
     */
    public static MockWebServer getPreloadedPullTargetServer(MockDispatcher dispatcher, int numMockDocsToServe, int numDocsPerChangesResponse) {

        MockWebServer server = getMockWebServer(dispatcher);

        List<MockDocumentGet.MockDocument> mockDocs = new ArrayList<MockDocumentGet.MockDocument>();
        for (int i=0; i<numMockDocsToServe; i++) {

            String docId = String.format("doc%s", i);
            String revIdHash = Misc.TDCreateUUID().substring(0, 3);
            String revId = String.format("1-%s", revIdHash);
            int seq = i;

            // mock documents to be pulled
            MockDocumentGet.MockDocument mockDoc = new MockDocumentGet.MockDocument(docId, revId, seq);
            mockDoc.setJsonMap(MockHelper.generateRandomJsonMap());
            mockDocs.add(mockDoc);

        }

        // checkpoint GET response w/ 404
        MockResponse fakeCheckpointResponse = new MockResponse();
        MockHelper.set404NotFoundJson(fakeCheckpointResponse);
        dispatcher.enqueueResponse(MockHelper.PATH_REGEX_CHECKPOINT, fakeCheckpointResponse);

        // _changes response
        int numChangeResponses = 0;
        Batcher<MockDocumentGet.MockDocument> batcher =
                new Batcher<MockDocumentGet.MockDocument>(mockDocs, numDocsPerChangesResponse);
        while (batcher.hasMoreBatches()) {
            List<MockDocumentGet.MockDocument> batch = batcher.nextBatch();
            MockChangesFeed mockChangesFeed = new MockChangesFeed();
            for (MockDocumentGet.MockDocument mockDoc : batch) {
                mockChangesFeed.add(new MockChangesFeed.MockChangedDoc(mockDoc));
            }
            MockResponse mockResponse = mockChangesFeed.generateMockResponse();
            String body = new String(mockResponse.getBody());
            dispatcher.enqueueResponse(MockHelper.PATH_REGEX_CHANGES, mockResponse);
            numChangeResponses += 1;
        }
        if (numChangeResponses == 0) {
            // in the degenerate case, add empty changes response
            dispatcher.enqueueResponse(MockHelper.PATH_REGEX_CHANGES, new MockChangesFeed().generateMockResponse());
        }

        // doc responses
        for (MockDocumentGet.MockDocument mockDoc : mockDocs) {
            MockDocumentGet mockDocumentGet = new MockDocumentGet(mockDoc);
            dispatcher.enqueueResponse(mockDoc.getDocPathRegex(), mockDocumentGet.generateMockResponse());
        }

        // put checkpoint response -- add enough to cover the max amount of PUT checkpoints
        // we might expect to see (with some overkill)
        for (int i=0; i<numMockDocsToServe; i++) {
            MockCheckpointPut mockCheckpointPut = new MockCheckpointPut();
            dispatcher.enqueueResponse(MockHelper.PATH_REGEX_CHECKPOINT, mockCheckpointPut);
        }

        return server;

    }


    public static void set200OKJson(MockResponse mockResponse) {
        mockResponse.setStatus("HTTP/1.1 200 OK").setHeader("Content-Type", "application/json");
    }

    public static void set201OKJson(MockResponse mockResponse) {
        mockResponse.setStatus("HTTP/1.1 201 OK").setHeader("Content-Type", "application/json");
    }

    public static void set404NotFoundJson(MockResponse mockResponse) {
        mockResponse.setStatus("HTTP/1.1 404 NOT FOUND").setHeader("Content-Type", "application/json");
    }

    public static void addFake404CheckpointResponse(MockWebServer mockWebServer) {
        MockResponse fakeCheckpointResponse = new MockResponse();
        MockHelper.set404NotFoundJson(fakeCheckpointResponse);
        mockWebServer.enqueue(fakeCheckpointResponse);
    }


    public static Map<String, Object> generateRandomJsonMap() {

        Map<String, Object> randomJsonMap = new HashMap<String, Object>();
        randomJsonMap.put(Misc.TDCreateUUID(), false);
        randomJsonMap.put("uuid", Misc.TDCreateUUID());
        return randomJsonMap;
    }

    public static SmartMockResponse wrap(MockResponse mockResponse) {
        return new WrappedSmartMockResponse(mockResponse);
    }

    public static Map<String, Object> getJsonMapFromRequest(byte[] requestBody) throws IOException {
        return Manager.getObjectMapper().readValue(requestBody, Map.class);
    }

    public static class Batcher<T> {

        private BlockingQueue<T> items;
        private int batchSize;

        public Batcher(List<T> items, int batchSize) {
            this.items = new LinkedBlockingQueue<T>(items);
            this.batchSize = batchSize;
        }

        public boolean hasMoreBatches() {
            return !this.items.isEmpty();
        }

        public List<T> nextBatch() {
            List<T> batch = new ArrayList<T>();
            for (int i=0; i<batchSize; i++) {
                if (!this.items.isEmpty()) {
                    try {
                        T item = this.items.take();
                        batch.add(item);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            return batch;
        }


    }


}
