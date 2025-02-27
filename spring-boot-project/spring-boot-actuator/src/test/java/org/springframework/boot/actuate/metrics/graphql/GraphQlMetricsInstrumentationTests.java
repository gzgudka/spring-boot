/*
 * Copyright 2012-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.actuate.metrics.graphql;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.parameters.InstrumentationCreateStateParameters;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaGenerator;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.actuate.metrics.AutoTimer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link GraphQlMetricsInstrumentation}.
 *
 * @author Brian Clozel
 */
@SuppressWarnings("unchecked")
class GraphQlMetricsInstrumentationTests {

	private final ExecutionInput input = ExecutionInput.newExecutionInput("{greeting}").build();

	private final GraphQLSchema schema = SchemaGenerator.createdMockedSchema("type Query { greeting: String }");

	private MeterRegistry registry;

	private GraphQlMetricsInstrumentation instrumentation;

	private GraphQlTagsProvider graphQlTagsProvider;

	private InstrumentationState state;

	private InstrumentationExecutionParameters parameters;

	@BeforeEach
	void setup() {
		this.graphQlTagsProvider = mock(GraphQlTagsProvider.class);
		this.registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, new MockClock());
		this.instrumentation = new GraphQlMetricsInstrumentation(this.registry, this.graphQlTagsProvider,
				AutoTimer.ENABLED);
		this.state = this.instrumentation
				.createState(new InstrumentationCreateStateParameters(this.schema, this.input));
		this.parameters = new InstrumentationExecutionParameters(this.input, this.schema, this.state);
	}

	@Test
	void shouldRecordTimerWhenResult() {
		InstrumentationContext<ExecutionResult> execution = this.instrumentation.beginExecution(this.parameters,
				this.state);
		ExecutionResult result = new ExecutionResultImpl("Hello", null);
		execution.onCompleted(result, null);

		Timer timer = this.registry.find("graphql.request").timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);
	}

	@Test
	void shouldRecordDataFetchingCount() throws Exception {
		InstrumentationContext<ExecutionResult> execution = this.instrumentation.beginExecution(this.parameters,
				this.state);
		ExecutionResult result = new ExecutionResultImpl("Hello", null);

		DataFetcher<String> dataFetcher = mock(DataFetcher.class);
		given(dataFetcher.get(any())).willReturn("Hello");
		InstrumentationFieldFetchParameters fieldFetchParameters = mockFieldFetchParameters(false);

		DataFetcher<?> instrumented = this.instrumentation.instrumentDataFetcher(dataFetcher, fieldFetchParameters,
				this.state);
		DataFetchingEnvironment environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment().build();
		instrumented.get(environment);

		execution.onCompleted(result, null);

		DistributionSummary summary = this.registry.find("graphql.request.datafetch.count").summary();
		assertThat(summary).isNotNull();
		assertThat(summary.count()).isEqualTo(1);
	}

	@Test
	void shouldRecordDataFetchingMetricWhenSuccess() throws Exception {
		DataFetcher<String> dataFetcher = mock(DataFetcher.class);
		given(dataFetcher.get(any())).willReturn("Hello");
		given(this.graphQlTagsProvider.getDataFetchingTags(any(), any(), any())).willReturn(Tags.of("tag1", "val1"));
		given(this.graphQlTagsProvider.getDataFetchingTags(any(), eq("Hello"), any(), any())).willReturn(null);
		InstrumentationFieldFetchParameters fieldFetchParameters = mockFieldFetchParameters(false);

		DataFetcher<?> instrumented = this.instrumentation.instrumentDataFetcher(dataFetcher, fieldFetchParameters,
				this.state);
		DataFetchingEnvironment environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment().build();
		instrumented.get(environment);

		Timer timer = this.registry.find("graphql.datafetcher").tag("tag1", "val1").timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);
	}

	@Test
	void shouldRecordDataFetchingMetricWhenSuccessAndPassResultToTagsProvider() throws Exception {
		DataFetcher<String> dataFetcher = mock(DataFetcher.class);
		given(dataFetcher.get(any())).willReturn("Hello");
		given(this.graphQlTagsProvider.getDataFetchingTags(any(), any(), any())).willReturn(Tags.of("tag1", "val1"));
		given(this.graphQlTagsProvider.getDataFetchingTags(any(), eq("Hello"), any(), any()))
				.willReturn(Tags.of("tag2", "val2"));
		InstrumentationFieldFetchParameters fieldFetchParameters = mockFieldFetchParameters(false);

		DataFetcher<?> instrumented = this.instrumentation.instrumentDataFetcher(dataFetcher, fieldFetchParameters,
				this.state);
		DataFetchingEnvironment environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment().build();
		instrumented.get(environment);

		Timer timer = this.registry.find("graphql.datafetcher").tag("tag1", "val1").tag("tag2", "val2").timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);
	}

	@Test
	void shouldRecordDataFetchingMetricWhenSuccessCompletionStage() throws Exception {
		DataFetcher<CompletionStage<String>> dataFetcher = mock(DataFetcher.class);
		given(dataFetcher.get(any())).willReturn(CompletableFuture.completedFuture("Hello"));
		given(this.graphQlTagsProvider.getDataFetchingTags(any(), any(), any())).willReturn(Tags.of("tag1", "val1"));
		given(this.graphQlTagsProvider.getDataFetchingTags(any(), eq("Hello"), any(), any()))
				.willReturn(Tags.of("tag2", "val2"));
		InstrumentationFieldFetchParameters fieldFetchParameters = mockFieldFetchParameters(false);

		DataFetcher<?> instrumented = this.instrumentation.instrumentDataFetcher(dataFetcher, fieldFetchParameters,
				this.state);
		DataFetchingEnvironment environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment().build();
		instrumented.get(environment);

		Timer timer = this.registry.find("graphql.datafetcher").tag("tag1", "val1").timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);
	}

	@Test
	void shouldRecordDataFetchingMetricWithResultTagsWhenSuccessCompletionStage() throws Exception {
		DataFetcher<CompletionStage<String>> dataFetcher = mock(DataFetcher.class);
		given(dataFetcher.get(any())).willReturn(CompletableFuture.completedFuture("Hello"));
		given(this.graphQlTagsProvider.getDataFetchingTags(any(), any(), any())).willReturn(Tags.of("tag1", "val1"));
		given(this.graphQlTagsProvider.getDataFetchingTags(any(), eq("Hello"), any(), any()))
				.willReturn(Tags.of("tag2", "val2"));
		InstrumentationFieldFetchParameters fieldFetchParameters = mockFieldFetchParameters(false);

		DataFetcher<?> instrumented = this.instrumentation.instrumentDataFetcher(dataFetcher, fieldFetchParameters,
				this.state);
		DataFetchingEnvironment environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment().build();
		instrumented.get(environment);

		Timer timer = this.registry.find("graphql.datafetcher").tag("tag1", "val1").tag("tag2", "val2").timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);
	}

	@Test
	void shouldRecordDataFetchingMetricWithResultTagsAndDedupWhenSuccessCompletionStage() throws Exception {
		DataFetcher<CompletionStage<String>> dataFetcher = mock(DataFetcher.class);
		given(dataFetcher.get(any())).willReturn(CompletableFuture.completedFuture("Hello"));
		given(this.graphQlTagsProvider.getDataFetchingTags(any(), any(), any())).willReturn(Tags.of("tag1", "val1"));
		given(this.graphQlTagsProvider.getDataFetchingTags(any(), any(), any(), any()))
				.willReturn(Tags.of("tag1", "val2"));
		InstrumentationFieldFetchParameters fieldFetchParameters = mockFieldFetchParameters(false);

		DataFetcher<?> instrumented = this.instrumentation.instrumentDataFetcher(dataFetcher, fieldFetchParameters,
				this.state);
		DataFetchingEnvironment environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment().build();
		instrumented.get(environment);

		Timer timer = this.registry.find("graphql.datafetcher").tag("tag1", "val1").timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);
	}

	@Test
	void shouldRecordDataFetchingMetricWhenError() throws Exception {
		DataFetcher<CompletionStage<String>> dataFetcher = mock(DataFetcher.class);
		IllegalStateException exception = new IllegalStateException("test");
		given(dataFetcher.get(any())).willThrow(exception);
		given(this.graphQlTagsProvider.getDataFetchingTags(any(), any(), eq(exception)))
				.willReturn(Tags.of("tag1", "val1"));
		given(this.graphQlTagsProvider.getDataFetchingTags(any(), isNull(), any(), eq(exception)))
				.willReturn(Tags.of("tag2", "val2"));
		InstrumentationFieldFetchParameters fieldFetchParameters = mockFieldFetchParameters(false);

		DataFetcher<?> instrumented = this.instrumentation.instrumentDataFetcher(dataFetcher, fieldFetchParameters,
				this.state);
		DataFetchingEnvironment environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment().build();
		assertThatThrownBy(() -> instrumented.get(environment)).isInstanceOf(IllegalStateException.class);

		Timer timer = this.registry.find("graphql.datafetcher").tag("tag1", "val1").tag("tag2", "val2").timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);
	}

	@Test
	void shouldNotRecordDataFetchingMetricWhenTrivial() throws Exception {
		DataFetcher<String> dataFetcher = mock(DataFetcher.class);
		given(dataFetcher.get(any())).willReturn("Hello");
		InstrumentationFieldFetchParameters fieldFetchParameters = mockFieldFetchParameters(true);

		DataFetcher<?> instrumented = this.instrumentation.instrumentDataFetcher(dataFetcher, fieldFetchParameters,
				this.state);
		DataFetchingEnvironment environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment().build();
		instrumented.get(environment);

		Timer timer = this.registry.find("graphql.datafetcher").timer();
		assertThat(timer).isNull();
	}

	private InstrumentationFieldFetchParameters mockFieldFetchParameters(boolean isTrivial) {
		InstrumentationFieldFetchParameters fieldFetchParameters = mock(InstrumentationFieldFetchParameters.class);
		given(fieldFetchParameters.isTrivialDataFetcher()).willReturn(isTrivial);
		return fieldFetchParameters;
	}

}
