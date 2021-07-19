package com.twcrone.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.kickstart.spring.webclient.boot.GraphQLRequest;
import graphql.kickstart.spring.webclient.boot.GraphQLResponse;
import graphql.kickstart.spring.webclient.boot.GraphQLWebClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class QueryController {
    private final GraphQLWebClient graphQLWebClient;

    public QueryController(GraphQLWebClient graphQLWebClient) {
        this.graphQLWebClient = graphQLWebClient;
    }

    @GetMapping("/query")
    public List<Book> query() {
        GraphQLRequest request = GraphQLRequest.builder().query(QUERY).build();
        GraphQLResponse response = graphQLWebClient.post(request).block();
        return response.getFirstList(Book.class);
    }

    private static final String QUERY = "query GetBooks {\n" +
            "  books {\n" +
            "    title\n" +
            "    author\n" +
            "  }\n" +
            "}";
}
