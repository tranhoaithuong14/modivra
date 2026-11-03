package io.modivra;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

public class TestChatModel implements ChatModel {

    private final List<Prompt> recordedStreamPrompts = new CopyOnWriteArrayList<>();
    private final List<Prompt> recordedCallPrompts = new CopyOnWriteArrayList<>();

    private volatile Function<Prompt, Flux<ChatResponse>> streamHandler = defaultStreamHandler();
    private volatile Function<Prompt, ChatResponse> callHandler = defaultCallHandler();

    public void reset() {
        recordedStreamPrompts.clear();
        recordedCallPrompts.clear();
        streamHandler = defaultStreamHandler();
        callHandler = defaultCallHandler();
    }

    public void setStreamHandler(Function<Prompt, Flux<ChatResponse>> handler) {
        this.streamHandler = handler;
    }

    public void setCallHandler(Function<Prompt, ChatResponse> handler) {
        this.callHandler = handler;
    }

    public List<Prompt> getRecordedStreamPrompts() {
        return List.copyOf(recordedStreamPrompts);
    }

    public List<Prompt> getRecordedCallPrompts() {
        return List.copyOf(recordedCallPrompts);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        recordedCallPrompts.add(prompt);
        return callHandler.apply(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        recordedStreamPrompts.add(prompt);
        return streamHandler.apply(prompt);
    }

    public static ChatResponse textChunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    public static ChatResponse textChunkWithUsage(String text, int promptTokens, int completionTokens) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
            .usage(new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens))
            .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }

    private static Function<Prompt, Flux<ChatResponse>> defaultStreamHandler() {
        return prompt -> Flux.just(
            textChunk("Hello"),
            textChunk(" from"),
            textChunk(" Grok"),
            textChunkWithUsage("!", 12, 4)
        );
    }

    private static Function<Prompt, ChatResponse> defaultCallHandler() {
        return prompt -> textChunk("Virtual Threads Overview");
    }
}
