package com.twcrone.graphql;

import com.coxautodev.graphql.tools.GraphQLQueryResolver;

import java.util.*;

public class Query implements GraphQLQueryResolver {
    private final PostDao postDao;

    public Query(PostDao postDao) {
        this.postDao = postDao;
    }

    public List<Post> getRecentPosts(int count, int offset) {
        return postDao.getRecentPosts(count, offset);
    }
}
