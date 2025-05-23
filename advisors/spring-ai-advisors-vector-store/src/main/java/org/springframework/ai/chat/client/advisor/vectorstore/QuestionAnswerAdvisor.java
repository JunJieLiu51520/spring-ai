/*
 * Copyright 2023-2025 the original author or authors.
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

package org.springframework.ai.chat.client.advisor.vectorstore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponseStreamUtils;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Context for the question is retrieved from a Vector Store and added to the prompt's
 * user text.
 *
 * @author Christian Tzolov
 * @author Timo Salm
 * @author Ilayaperumal Gopinathan
 * @author Thomas Vitale
 * @since 1.0.0
 */
public class QuestionAnswerAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

	public static final String RETRIEVED_DOCUMENTS = "qa_retrieved_documents";

	public static final String FILTER_EXPRESSION = "qa_filter_expression";

	private static final PromptTemplate DEFAULT_PROMPT_TEMPLATE = new PromptTemplate("""

			Context information is below, surrounded by ---------------------

			---------------------
			{question_answer_context}
			---------------------

			Given the context and provided history information and not prior knowledge,
			reply to the user comment. If the answer is not in the context, inform
			the user that you can't answer the question.
			""");

	private static final int DEFAULT_ORDER = 0;

	private final VectorStore vectorStore;

	private final PromptTemplate promptTemplate;

	private final SearchRequest searchRequest;

	private final boolean protectFromBlocking;

	private final int order;

	/**
	 * The QuestionAnswerAdvisor retrieves context information from a Vector Store and
	 * combines it with the user's text.
	 * @param vectorStore The vector store to use
	 */
	public QuestionAnswerAdvisor(VectorStore vectorStore) {
		this(vectorStore, SearchRequest.builder().build(), DEFAULT_PROMPT_TEMPLATE, true, DEFAULT_ORDER);
	}

	/**
	 * The QuestionAnswerAdvisor retrieves context information from a Vector Store and
	 * combines it with the user's text.
	 * @param vectorStore The vector store to use
	 * @param searchRequest The search request defined using the portable filter
	 * expression syntax
	 * @deprecated in favor of the builder: {@link #builder(VectorStore)}
	 */
	@Deprecated
	public QuestionAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest) {
		this(vectorStore, searchRequest, DEFAULT_PROMPT_TEMPLATE, true, DEFAULT_ORDER);
	}

	/**
	 * The QuestionAnswerAdvisor retrieves context information from a Vector Store and
	 * combines it with the user's text.
	 * @param vectorStore The vector store to use
	 * @param searchRequest The search request defined using the portable filter
	 * expression syntax
	 * @param userTextAdvise The user text to append to the existing user prompt. The text
	 * should contain a placeholder named "question_answer_context".
	 * @deprecated in favor of the builder: {@link #builder(VectorStore)}
	 */
	@Deprecated
	public QuestionAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest, String userTextAdvise) {
		this(vectorStore, searchRequest, PromptTemplate.builder().template(userTextAdvise).build(), true,
				DEFAULT_ORDER);
	}

	/**
	 * The QuestionAnswerAdvisor retrieves context information from a Vector Store and
	 * combines it with the user's text.
	 * @param vectorStore The vector store to use
	 * @param searchRequest The search request defined using the portable filter
	 * expression syntax
	 * @param userTextAdvise The user text to append to the existing user prompt. The text
	 * should contain a placeholder named "question_answer_context".
	 * @param protectFromBlocking If true the advisor will protect the execution from
	 * blocking threads. If false the advisor will not protect the execution from blocking
	 * threads. This is useful when the advisor is used in a non-blocking environment. It
	 * is true by default.
	 * @deprecated in favor of the builder: {@link #builder(VectorStore)}
	 */
	@Deprecated
	public QuestionAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest, String userTextAdvise,
			boolean protectFromBlocking) {
		this(vectorStore, searchRequest, PromptTemplate.builder().template(userTextAdvise).build(), protectFromBlocking,
				DEFAULT_ORDER);
	}

	/**
	 * The QuestionAnswerAdvisor retrieves context information from a Vector Store and
	 * combines it with the user's text.
	 * @param vectorStore The vector store to use
	 * @param searchRequest The search request defined using the portable filter
	 * expression syntax
	 * @param userTextAdvise The user text to append to the existing user prompt. The text
	 * should contain a placeholder named "question_answer_context".
	 * @param protectFromBlocking If true the advisor will protect the execution from
	 * blocking threads. If false the advisor will not protect the execution from blocking
	 * threads. This is useful when the advisor is used in a non-blocking environment. It
	 * is true by default.
	 * @param order The order of the advisor.
	 * @deprecated in favor of the builder: {@link #builder(VectorStore)}
	 */
	@Deprecated
	public QuestionAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest, String userTextAdvise,
			boolean protectFromBlocking, int order) {
		this(vectorStore, searchRequest, PromptTemplate.builder().template(userTextAdvise).build(), protectFromBlocking,
				order);
	}

	QuestionAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest, @Nullable PromptTemplate promptTemplate,
			boolean protectFromBlocking, int order) {
		Assert.notNull(vectorStore, "vectorStore cannot be null");
		Assert.notNull(searchRequest, "searchRequest cannot be null");

		this.vectorStore = vectorStore;
		this.searchRequest = searchRequest;
		this.promptTemplate = promptTemplate != null ? promptTemplate : DEFAULT_PROMPT_TEMPLATE;
		this.protectFromBlocking = protectFromBlocking;
		this.order = order;
	}

	public static Builder builder(VectorStore vectorStore) {
		return new Builder(vectorStore);
	}

	@Override
	public String getName() {
		return this.getClass().getSimpleName();
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAroundAdvisorChain chain) {

		AdvisedRequest advisedRequest2 = before(advisedRequest);

		AdvisedResponse advisedResponse = chain.nextAroundCall(advisedRequest2);

		return after(advisedResponse);
	}

	@Override
	public Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, StreamAroundAdvisorChain chain) {

		// This can be executed by both blocking and non-blocking Threads
		// E.g. a command line or Tomcat blocking Thread implementation
		// or by a WebFlux dispatch in a non-blocking manner.
		Flux<AdvisedResponse> advisedResponses = (this.protectFromBlocking) ?
		// @formatter:off
			Mono.just(advisedRequest)
				.publishOn(Schedulers.boundedElastic())
				.map(this::before)
				.flatMapMany(request -> chain.nextAroundStream(request))
			: chain.nextAroundStream(before(advisedRequest));
		// @formatter:on

		return advisedResponses.map(ar -> {
			if (AdvisedResponseStreamUtils.onFinishReason().test(ar)) {
				ar = after(ar);
			}
			return ar;
		});
	}

	private AdvisedRequest before(AdvisedRequest request) {

		var context = new HashMap<>(request.adviseContext());

		// 1. Search for similar documents in the vector store.
		var searchRequestToUse = SearchRequest.from(this.searchRequest)
			.query(request.userText())
			.filterExpression(doGetFilterExpression(context))
			.build();

		List<Document> documents = this.vectorStore.similaritySearch(searchRequestToUse);

		// 2. Create the context from the documents.
		context.put(RETRIEVED_DOCUMENTS, documents);

		String documentContext = documents.stream()
			.map(Document::getText)
			.collect(Collectors.joining(System.lineSeparator()));

		// 3. Augment the user prompt with the document context.
		String augmentedUserText = this.promptTemplate.mutate()
			.template(request.userText() + System.lineSeparator() + this.promptTemplate.getTemplate())
			.variables(Map.of("question_answer_context", documentContext))
			.build()
			.render();

		AdvisedRequest advisedRequest = AdvisedRequest.from(request)
			.userText(augmentedUserText)
			.adviseContext(context)
			.build();

		return advisedRequest;
	}

	private AdvisedResponse after(AdvisedResponse advisedResponse) {
		ChatResponse.Builder chatResponseBuilder = ChatResponse.builder().from(advisedResponse.response());
		chatResponseBuilder.metadata(RETRIEVED_DOCUMENTS, advisedResponse.adviseContext().get(RETRIEVED_DOCUMENTS));
		return new AdvisedResponse(chatResponseBuilder.build(), advisedResponse.adviseContext());
	}

	protected Filter.Expression doGetFilterExpression(Map<String, Object> context) {

		if (!context.containsKey(FILTER_EXPRESSION)
				|| !StringUtils.hasText(context.get(FILTER_EXPRESSION).toString())) {
			return this.searchRequest.getFilterExpression();
		}
		return new FilterExpressionTextParser().parse(context.get(FILTER_EXPRESSION).toString());

	}

	public static final class Builder {

		private final VectorStore vectorStore;

		private SearchRequest searchRequest = SearchRequest.builder().build();

		private PromptTemplate promptTemplate;

		private boolean protectFromBlocking = true;

		private int order = DEFAULT_ORDER;

		private Builder(VectorStore vectorStore) {
			Assert.notNull(vectorStore, "The vectorStore must not be null!");
			this.vectorStore = vectorStore;
		}

		public Builder promptTemplate(PromptTemplate promptTemplate) {
			Assert.notNull(promptTemplate, "promptTemplate cannot be null");
			this.promptTemplate = promptTemplate;
			return this;
		}

		public Builder searchRequest(SearchRequest searchRequest) {
			Assert.notNull(searchRequest, "The searchRequest must not be null!");
			this.searchRequest = searchRequest;
			return this;
		}

		/**
		 * @deprecated in favour of {@link #promptTemplate(PromptTemplate)}
		 */
		@Deprecated
		public Builder userTextAdvise(String userTextAdvise) {
			Assert.hasText(userTextAdvise, "The userTextAdvise must not be empty!");
			this.promptTemplate = PromptTemplate.builder().template(userTextAdvise).build();
			return this;
		}

		public Builder protectFromBlocking(boolean protectFromBlocking) {
			this.protectFromBlocking = protectFromBlocking;
			return this;
		}

		public Builder order(int order) {
			this.order = order;
			return this;
		}

		public QuestionAnswerAdvisor build() {
			return new QuestionAnswerAdvisor(this.vectorStore, this.searchRequest, this.promptTemplate,
					this.protectFromBlocking, this.order);
		}

	}

}
