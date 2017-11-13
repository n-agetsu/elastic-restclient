package sample;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.stream.IntStream;
import java.util.stream.Stream;

class IndexPerformanceTest {
    private static final Logger LOG = LogManager.getLogger(IndexPerformanceTest.class);

    private static final HttpHost ES_HOST = new HttpHost("localhost", 9200, "http");
    private static final int SEND_DOC_NUM = 10000;
    private static final String INDEX = "posts";
    private static final String TYPE = "doc";

    private static ObjectMapper BURN_MAPPER;
    private static RestHighLevelClient highLevelClient;
    private static RestClient client;

    @BeforeAll
    static void setup() throws JsonProcessingException {
        highLevelClient = new RestHighLevelClient(RestClient.builder(ES_HOST));
        client = RestClient.builder(ES_HOST).build();
        BURN_MAPPER = new ObjectMapper().registerModule(new AfterburnerModule());
    }

    @BeforeEach
    void beforeEach() throws IOException {
        // create mapping
        // Use low level REST client,
        // because Elasticsearch6.0-rc2 high level REST Client doesn't support Mapping API.
        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject();
        {
            builder.startObject("settings")
                     .field("number_of_shards", 1)
                   .endObject()
                    .startObject("mappings")
                      .startObject(TYPE)
                        .startObject("properties")
                          .startObject("user")
                            .field("type", "text")
                          .endObject()
                          .startObject("postDate")
                            .field("type", "date")
                          .endObject()
                          .startObject("message")
                            .field("type", "text")
                          .endObject()
                        .endObject()
                      .endObject()
                    .endObject();
        }
        builder.endObject();
        HttpEntity entity = new NStringEntity(builder.string(), ContentType.APPLICATION_JSON);
        Response response = client.performRequest("PUT", "/" + INDEX, Collections.emptyMap(), entity);
        boolean acknowledged = BURN_MAPPER.readTree(response.getEntity().getContent()).get("acknowledged").asBoolean();
        if (!acknowledged) {
            LOG.error("create mapping posts failed, cause {}" + EntityUtils.toString(response.getEntity()));
            System.exit(1);
        }
    }

    @AfterEach
    void afterEach() throws IOException {
        // delete index for cleanup
        // Use low level REST client,
        // because Elasticsearch6.0-rc2 high level REST Client doesn't support DELETE INDEX API.
        Response response = client.performRequest("DELETE", "/" + INDEX);
        boolean acknowledged = BURN_MAPPER.readTree(response.getEntity().getContent()).get("acknowledged").asBoolean();
        if (!acknowledged) {
            LOG.error("delete index posts, cause {}" + EntityUtils.toString(response.getEntity()));
            System.exit(1);
        }
    }

    @DisplayName("Index Performance Test - Non Bulk API")
    @RepeatedTest(value = 10, name = RepeatedTest.LONG_DISPLAY_NAME)
    void testIndexRequest() throws IOException {
        for (int i = 0; i < SEND_DOC_NUM; i++) {
            Post post = new Post("test user" + i, new Date(), "trying out Elasticsearch");
            IndexRequest request = new IndexRequest()
                    .index(INDEX).type(TYPE).id(String.valueOf(i))
                    .source(BURN_MAPPER.writeValueAsBytes(post), XContentType.JSON);
            highLevelClient.index(request);
        }
    }

    @DisplayName("Index Performance Test - Bulk API")
    @ParameterizedTest(name = "run {0} with bulkSize {1}")
    @MethodSource("repeatCountAndBulkSize")
    void testIndexBulkRequest(int repeatCount, int bulkSize) throws IOException {
        BulkRequest bulkRequest = new BulkRequest();
        for (int i = 0; i < SEND_DOC_NUM; i++) {
            Post post = new Post("test user" + i, new Date(), "trying out Elasticsearch");
            IndexRequest request = new IndexRequest()
                    .index(INDEX).type(TYPE).id(String.valueOf(i))
                    .source(BURN_MAPPER.writeValueAsBytes(post), XContentType.JSON);
            bulkRequest.add(request);
            boolean last = i == SEND_DOC_NUM - 1;
            if (i % bulkSize == 0 || last) {
                highLevelClient.bulk(bulkRequest);
                if (!last) {
                    bulkRequest = new BulkRequest();
                }
            }
        }
    }

    private static Stream<Arguments> repeatCountAndBulkSize() {
        // test 10 times per batchSize
        Stream.Builder<Arguments> builder = Stream.builder();
        IntStream.range(0, 10).forEach(i -> builder.add(Arguments.of(i, 10)));
        IntStream.range(0, 10).forEach(i -> builder.add(Arguments.of(i, 50)));
        IntStream.range(0, 10).forEach(i -> builder.add(Arguments.of(i, 100)));
        IntStream.range(0, 10).forEach(i -> builder.add(Arguments.of(i, 1000)));
        IntStream.range(0, 10).forEach(i -> builder.add(Arguments.of(i, 10000)));
        return builder.build();
    }

    @AfterAll
    static void cleanup() {
        try {
            highLevelClient.close();
        } catch (IOException e) {
            LOG.error(e);
        }

        try {
            client.close();
        } catch (IOException e) {
            LOG.error(e);
        }
    }
}
