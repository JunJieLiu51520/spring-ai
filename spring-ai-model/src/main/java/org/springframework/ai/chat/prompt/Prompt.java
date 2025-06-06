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

package org.springframework.ai.chat.prompt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.ModelRequest;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * The Prompt class represents a prompt used in AI model requests. A prompt consists of
 * one or more messages and additional chat options.
 *
 * @author Mark Pollack
 * @author luocongqiu
 * @author Thomas Vitale
 */
public class Prompt implements ModelRequest<List<Message>> {

	private final List<Message> messages;

	private final ChatOptions chatOptions;

	public Prompt(String contents) {
		this(new UserMessage(contents));
	}

	public Prompt(Message message) {
		this(Collections.singletonList(message));
	}

	public Prompt(List<Message> messages) {
		this(messages, ChatOptions.builder().build());
	}

	public Prompt(Message... messages) {
		this(Arrays.asList(messages), ChatOptions.builder().build());
	}

	public Prompt(String contents, ChatOptions chatOptions) {
		this(new UserMessage(contents), chatOptions);
	}

	public Prompt(Message message, ChatOptions chatOptions) {
		this(Collections.singletonList(message), chatOptions);
	}

	public Prompt(List<Message> messages, ChatOptions chatOptions) {
		Assert.notNull(messages, "messages cannot be null");
		Assert.noNullElements(messages, "messages cannot contain null elements");
		this.messages = messages;
		this.chatOptions = (chatOptions != null) ? chatOptions : ChatOptions.builder().build();
	}

	public String getContents() {
		StringBuilder sb = new StringBuilder();
		for (Message message : getInstructions()) {
			sb.append(message.getText());
		}
		return sb.toString();
	}

	@Override
	public ChatOptions getOptions() {
		return this.chatOptions;
	}

	@Override
	public List<Message> getInstructions() {
		return this.messages;
	}

	/**
	 * Get the last user message in the prompt. If no user message is found, an empty
	 * UserMessage is returned.
	 */
	public UserMessage getUserMessage() {
		for (int i = this.messages.size() - 1; i >= 0; i--) {
			Message message = this.messages.get(i);
			if (message instanceof UserMessage userMessage) {
				return userMessage;
			}
		}
		return new UserMessage("");
	}

	@Override
	public String toString() {
		return "Prompt{" + "messages=" + this.messages + ", modelOptions=" + this.chatOptions + '}';
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Prompt prompt)) {
			return false;
		}
		return Objects.equals(this.messages, prompt.messages) && Objects.equals(this.chatOptions, prompt.chatOptions);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.messages, this.chatOptions);
	}

	public Prompt copy() {
		return new Prompt(instructionsCopy(), this.chatOptions.copy());
	}

	private List<Message> instructionsCopy() {
		List<Message> messagesCopy = new ArrayList<>();
		this.messages.forEach(message -> {
			if (message instanceof UserMessage userMessage) {
				messagesCopy.add(userMessage.copy());
			}
			else if (message instanceof SystemMessage systemMessage) {
				messagesCopy.add(systemMessage.copy());
			}
			else if (message instanceof AssistantMessage assistantMessage) {
				messagesCopy.add(new AssistantMessage(assistantMessage.getText(), assistantMessage.getMetadata(),
						assistantMessage.getToolCalls()));
			}
			else if (message instanceof ToolResponseMessage toolResponseMessage) {
				messagesCopy.add(new ToolResponseMessage(new ArrayList<>(toolResponseMessage.getResponses()),
						new HashMap<>(toolResponseMessage.getMetadata())));
			}
			else {
				throw new IllegalArgumentException("Unsupported message type: " + message.getClass().getName());
			}
		});

		return messagesCopy;
	}

	/**
	 * @param userMessageAugmenter the function to augment the last user message.
	 * @return a new prompt instance with the augmented user message.
	 */
	public Prompt augmentUserMessage(Function<UserMessage, UserMessage> userMessageAugmenter) {

		var messagesCopy = new ArrayList<>(this.messages);
		for (int i = messagesCopy.size() - 1; i >= 0; i--) {
			Message message = messagesCopy.get(i);
			if (message instanceof UserMessage userMessage) {
				messagesCopy.set(i, userMessageAugmenter.apply(userMessage));
				break;
			}
			if (i == 0) {
				messagesCopy.add(userMessageAugmenter.apply(new UserMessage("")));
			}
		}

		return new Prompt(messagesCopy, null == this.chatOptions ? null : this.chatOptions.copy());
	}

	/**
	 * Creates a copy of the prompt, replacing the text content of the last UserMessage
	 * with the provided text. If no UserMessage exists, a new one with the given text is
	 * added.
	 * @param newUserText The new text content for the last user message.
	 * @return A new Prompt instance with the augmented user message text.
	 */
	public Prompt augmentUserMessage(String newUserText) {
		return augmentUserMessage(userMessage -> userMessage.mutate().text(newUserText).build());
	}

	public Builder mutate() {
		Builder builder = new Builder().messages(instructionsCopy());
		builder.chatOptions(this.chatOptions.copy());
		return builder;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		@Nullable
		private String content;

		@Nullable
		private List<Message> messages;

		@Nullable
		private ChatOptions chatOptions;

		public Builder content(@Nullable String content) {
			this.content = content;
			return this;
		}

		public Builder messages(Message... messages) {
			if (messages != null) {
				this.messages = Arrays.asList(messages);
			}
			return this;
		}

		public Builder messages(List<Message> messages) {
			this.messages = messages;
			return this;
		}

		public Builder addMessage(Message message) {
			if (this.messages == null) {
				this.messages = new ArrayList<>();
			}
			this.messages.add(message);
			return this;
		}

		public Builder chatOptions(ChatOptions chatOptions) {
			this.chatOptions = chatOptions;
			return this;
		}

		public Prompt build() {
			if (StringUtils.hasText(this.content) && !CollectionUtils.isEmpty(this.messages)) {
				throw new IllegalArgumentException("content and messages cannot be set at the same time");
			}
			else if (StringUtils.hasText(this.content)) {
				this.messages = List.of(new UserMessage(this.content));
			}
			return new Prompt(this.messages, this.chatOptions);
		}

	}

}
