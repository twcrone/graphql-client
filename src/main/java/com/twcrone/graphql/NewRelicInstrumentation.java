package com.twcrone.graphql;

import com.newrelic.api.agent.NewRelic;
import graphql.ExceptionWhileDataFetching;
import graphql.ExecutionResult;
import graphql.execution.ExecutionContext;
import graphql.execution.instrumentation.SimpleInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.language.Field;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.schema.DataFetchingEnvironment;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An implementation of {@link graphql.execution.instrumentation.Instrumentation} that reports to New Relic
 */
public class NewRelicInstrumentation extends SimpleInstrumentation {
    private static final String METRIC_COUNT = "Custom/GraphQL/CallCount/Operations/%s";
    private static final String CATEGORY = "GraphQL";
    private static final String GRAPHQL_FIELDS_PARAM = "graphQL.fields";
    private static final String GRAPHQL_VARIABLES_PARAM = "variables.%s";
    public static final String QUERY_PARAM = "query";
    public static final String OPERATION_NAME_PARAM = "operationName";
    public static final int DEFAULT_SECURE_VALUE_ELISION_KEEP_CHAR_COUNT = 4;

    private static final List<String> TOP_LEVEL_FIELDS = Arrays.asList("actor", "account", "currentUser", "user", "docs", "nrPlatform");

    private final boolean noticeErrors;
    private boolean elideSecureValues;
    private Function<SecureValue, Integer> secureValueElisionOriginalCharCountProvider;

    public NewRelicInstrumentation() {
        this(false);
    }

    public NewRelicInstrumentation(boolean noticeErrors) {
        this.noticeErrors = noticeErrors;
        this.elideSecureValues = false;
        this.secureValueElisionOriginalCharCountProvider = secureValue -> 0;
    }

    public NewRelicInstrumentation(boolean noticeErrors, boolean elideSecureValues) {
        this(noticeErrors);
        this.elideSecureValues = elideSecureValues;
        this.secureValueElisionOriginalCharCountProvider = secureValue -> DEFAULT_SECURE_VALUE_ELISION_KEEP_CHAR_COUNT;
    }

    public NewRelicInstrumentation(boolean noticeErrors, boolean elideSecureValues, int maxElidedSecureValueSize) {
        this(noticeErrors, elideSecureValues);
        this.secureValueElisionOriginalCharCountProvider = secureValue -> maxElidedSecureValueSize;
    }

    public NewRelicInstrumentation(boolean noticeErrors, boolean elideSecureValues, Function<SecureValue, Integer> secureValueElisionOriginalCharCountProvider) {
        this(noticeErrors, elideSecureValues);
        this.secureValueElisionOriginalCharCountProvider = secureValueElisionOriginalCharCountProvider;
    }

    @Override
    public ExecutionContext instrumentExecutionContext(
            ExecutionContext executionContext,
            InstrumentationExecutionParameters parameters
    ) {
        if (executionContext == null) { return null; }

        List<String> fields = getFields(executionContext);

        changeTransactionName(executionContext, fields);

        NewRelic.addCustomParameter(GRAPHQL_FIELDS_PARAM, String.join("|", fields));
        fields.forEach((field) -> NewRelic.incrementCounter(String.format(METRIC_COUNT, field)));

        Map<String, Object> variables = executionContext.getVariables();
        if (elideSecureValues) {
            variables = sanitizeSecureValueVariables(variables);
        }
        variables.forEach((key, value) ->
                NewRelic.addCustomParameter(String.format(GRAPHQL_VARIABLES_PARAM, key), Objects.toString(value))
        );


        return executionContext;
    }

    private Map<String, Object> sanitizeSecureValueVariables(Map<String, Object> variables) {
        HashMap<String, Object> sanitizedVariables = new HashMap<>(variables.size());
        variables.forEach((name, value) -> {
            if (value instanceof SecureValue) {
                SecureValue secureValue = ((SecureValue) value);
                String elideValue = secureValue.getElidedValue(secureValueElisionOriginalCharCountProvider.apply(secureValue));
                sanitizedVariables.put(name, elideValue);
            } else {
                sanitizedVariables.put(name, value);
            }
        });
        return sanitizedVariables;
    }

    @Override
    public CompletableFuture<ExecutionResult> instrumentExecutionResult(ExecutionResult executionResult, InstrumentationExecutionParameters parameters) {
        if (executionResult == null) { return CompletableFuture.completedFuture(null); }

        noticeExpectedErrors(executionResult);

        NewRelic.addCustomParameter(QUERY_PARAM, parameters.getQuery());
        NewRelic.addCustomParameter(OPERATION_NAME_PARAM, parameters.getOperation());

        return CompletableFuture.completedFuture(executionResult);
    }

    /**
     * Returns the list of fields requested in the query.
     * This is based on best effort to get an operation name. But it will miss operations for more complex schemas.
     *
     * For the common stitch points "actor", "account", "user" etc.. it will retrieve the sub fields.
     *
     * @param executionContext The context of the current execution
     * @return The list of top-level operations defined in the query
     */
    private List<String> getFields(ExecutionContext executionContext) {
        return extractFields(executionContext.getOperationDefinition().getSelectionSet())
                .flatMap(this::convertFields)
                .sorted()
                .collect(Collectors.toList());
    }

    private Stream<String> convertFields(Field topField) {
        if (!TOP_LEVEL_FIELDS.contains(topField.getName())) {
            return Stream.of(topField.getName());
        }

        return extractFields(topField.getSelectionSet())
                .filter(subField -> !subField.getName().equals("__typename"))
                .map(subField -> topField.getName() + "." + subField.getName());
    }

    private Stream<Field> extractFields(SelectionSet selectionSet) {
        return selectionSet.getSelections()
                .stream()
                .map(this::convertToSelectionOrNull)
                .filter(Objects::nonNull);
    }

    private Field convertToSelectionOrNull(Selection selection) {
        if (selection instanceof Field) {
            return (Field) selection;
        }

        return null;
    }

    /**
     * Changes the transaction name to add the {@link graphql.language.OperationDefinition.Operation} and the queried fields.
     *
     * If you are defining your own transaction name in the fetcher execution this will be replaced.
     * The transaction name will follow the format GraphQL/{OPERATION}/{field}[::{otherField}...]
     *
     * @param executionContext The execution context to retrieve the operation from
     * @param fields The list of fields in the query
     */
    private void changeTransactionName(ExecutionContext executionContext, List<String> fields) {
        NewRelic.setTransactionName(
                CATEGORY,
                executionContext.getOperationDefinition().getOperation().toString() + "/" + String.join("::", fields)
        );
    }

    /**
     * Notices errors that happen before {@link graphql.schema.DataFetcher#get(DataFetchingEnvironment)} is executed
     * If you want to notice errors inside {@link graphql.schema.DataFetcher#get(DataFetchingEnvironment)} configure
     *
     * @param executionResult The result of the operation
     */
    private void noticeExpectedErrors(ExecutionResult executionResult) {
        if (!noticeErrors || executionResult.getErrors() == null) {
            return;
        }

        executionResult.getErrors().stream()
                .filter(error -> !(error instanceof ExceptionWhileDataFetching))
                .forEach(error -> NewRelic.noticeError(error.toString(), true));
    }
}