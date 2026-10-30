package io.sharellm.server.adapter.inbound.web.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sharellm.server.domain.exception.InvalidRequestException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class OpenAiTranslatorTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final OpenAiTranslator translator = new OpenAiTranslator(mapper);

    private JsonNode json(String text) { return mapper.readTree(text.replace('\'', '"')); }

    @Test
    void chatRequestMapsMessagesOptionsAndTools() {
        ObjectNode ollama = translator.chatRequest(json("""
            {'model':'llama3','temperature':0.2,'top_p':0.9,'top_k':40,'seed':7,
             'frequency_penalty':0.1,'presence_penalty':0.3,'max_tokens':10,'max_completion_tokens':20,
             'messages':[{'role':'developer','content':'be brief'},{'role':'user','content':'hi'}],
             'tools':[{'type':'function'}],'think':true}
            """));
        assertThat(ollama.path("model").asString("")).isEqualTo("llama3");
        assertThat(ollama.path("stream").asBoolean()).isFalse();
        assertThat(ollama.path("messages").get(0).path("role").asString("")).isEqualTo("system");
        assertThat(ollama.path("messages").get(1).path("role").asString("")).isEqualTo("user");
        JsonNode options = ollama.path("options");
        assertThat(options.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(options.path("seed").asInt()).isEqualTo(7);
        assertThat(options.path("presence_penalty").asDouble()).isEqualTo(0.3);
        // max_completion_tokens takes precedence over the legacy max_tokens.
        assertThat(options.path("num_predict").asInt()).isEqualTo(20);
        assertThat(ollama.path("tools").isArray()).isTrue();
        assertThat(ollama.path("think").asBoolean()).isTrue();
        assertThat(ollama.has("format")).isFalse();
    }

    @Test
    void chatRequestRequiresAMessageArrayOfObjects() {
        assertThatThrownBy(() -> translator.chatRequest(json("{'model':'m'}")))
                .isInstanceOf(InvalidRequestException.class).hasMessage("messages array required");
        assertThatThrownBy(() -> translator.chatRequest(json("{'model':'m','messages':['hi']}")))
                .isInstanceOf(InvalidRequestException.class).hasMessage("Invalid message");
    }

    @Test
    void responseFormatsMapToOllamaFormat() {
        assertThat(translator.completionRequest(json("{'prompt':'p','response_format':{'type':'json_object'}}"))
                .path("format").asString("")).isEqualTo("json");
        ObjectNode schema = translator.completionRequest(json(
                "{'prompt':'p','response_format':{'type':'json_schema','json_schema':{'schema':{'type':'object'}}}}"));
        assertThat(schema.path("format").path("type").asString("")).isEqualTo("object");
        assertThat(translator.completionRequest(json(
                "{'prompt':'p','response_format':{'type':'json_schema','json_schema':{}}}")).has("format")).isFalse();
    }

    @Test
    void completionRequestNeedsAStringPrompt() {
        assertThat(translator.completionRequest(json("{'model':'m','prompt':'once'}")).path("prompt").asString(""))
                .isEqualTo("once");
        assertThatThrownBy(() -> translator.completionRequest(json("{'prompt':['a','b']}")))
                .isInstanceOf(InvalidRequestException.class).hasMessage("Only string prompt supported");
    }

    @Test
    void embeddingRequestNeedsInput() {
        assertThat(translator.embeddingRequest(json("{'model':'m','input':['a']}")).path("input").isArray()).isTrue();
        assertThatThrownBy(() -> translator.embeddingRequest(json("{'model':'m'}")))
                .isInstanceOf(InvalidRequestException.class).hasMessage("input required");
    }

    @Test
    void chatResponseCarriesContentUsageAndFinishReason() {
        ObjectNode response = translator.chatResponse(json("""
            {'message':{'role':'assistant','content':'hello','tool_calls':[]},
             'done_reason':'length','prompt_eval_count':3,'eval_count':4}
            """), ID, "llama3", false);
        assertThat(response.path("id").asString("")).isEqualTo("chatcmpl-" + ID);
        assertThat(response.path("object").asString("")).isEqualTo("chat.completion");
        assertThat(response.path("model").asString("")).isEqualTo("llama3");
        JsonNode choice = response.path("choices").get(0);
        assertThat(choice.path("message").path("content").asString("")).isEqualTo("hello");
        assertThat(choice.path("message").has("tool_calls")).isFalse();
        assertThat(choice.path("finish_reason").asString("")).isEqualTo("length");
        assertThat(response.path("usage").path("total_tokens").asInt()).isEqualTo(7);
    }

    @Test
    void chatResponseNormalizesToolCalls() {
        ObjectNode response = translator.chatResponse(json("""
            {'message':{'content':'','tool_calls':[
               {'function':{'name':'lookup','arguments':{'q':'x'}}},
               {'id':'call_given','function':{'name':'raw','arguments':'{\\"a\\":1}'}},
               {'function':{'name':'none'}}]}}
            """), ID, "m", false);
        JsonNode choice = response.path("choices").get(0);
        JsonNode calls = choice.path("message").path("tool_calls");
        assertThat(choice.path("finish_reason").asString("")).isEqualTo("tool_calls");
        assertThat(calls.get(0).path("id").asString("")).startsWith("call_").hasSize(37);
        assertThat(calls.get(0).path("type").asString("")).isEqualTo("function");
        assertThat(calls.get(0).path("function").path("arguments").asString("")).isEqualTo("{\"q\":\"x\"}");
        assertThat(calls.get(1).path("id").asString("")).isEqualTo("call_given");
        assertThat(calls.get(1).path("function").path("arguments").asString("")).isEqualTo("{\"a\":1}");
        assertThat(calls.get(2).path("function").path("arguments").asString("")).isEqualTo("{}");
        assertThat(calls.get(0).has("index")).isFalse();
    }

    @Test
    void streamingChatResponseIsASingleIndexedChunk() {
        ObjectNode response = translator.chatResponse(json("""
            {'message':{'content':'','tool_calls':[{'function':{'name':'a'}},{'function':{'name':'b'}}]}}
            """), ID, "m", true);
        JsonNode choice = response.path("choices").get(0);
        assertThat(response.path("object").asString("")).isEqualTo("chat.completion.chunk");
        assertThat(choice.has("message")).isFalse();
        assertThat(choice.path("delta").path("tool_calls").get(1).path("index").asInt()).isEqualTo(1);
    }

    @Test
    void nonArrayToolCallsAreIgnored() {
        ObjectNode response = translator.chatResponse(json("{'message':{'content':'x','tool_calls':{'a':1}}}"),
                ID, "m", true);
        JsonNode choice = response.path("choices").get(0);
        assertThat(choice.path("delta").has("tool_calls")).isFalse();
        assertThat(choice.path("finish_reason").asString("")).isEqualTo("stop");
    }

    @Test
    void completionResponseCarriesTheGeneratedText() {
        ObjectNode response = translator.completionResponse(json("{'response':'text','eval_count':2}"), ID, "m");
        assertThat(response.path("id").asString("")).isEqualTo("cmpl-" + ID);
        assertThat(response.path("object").asString("")).isEqualTo("text_completion");
        assertThat(response.path("choices").get(0).path("text").asString("")).isEqualTo("text");
        assertThat(response.path("choices").get(0).path("finish_reason").asString("")).isEqualTo("stop");
        assertThat(response.path("usage").path("completion_tokens").asInt()).isEqualTo(2);
    }

    @Test
    void embeddingResponseListsEachVector() {
        ObjectNode response = translator.embeddingResponse(
                json("{'embeddings':[[0.1,0.2],[0.3]],'prompt_eval_count':5}"), "embed-model");
        assertThat(response.path("model").asString("")).isEqualTo("embed-model");
        assertThat(response.path("data")).hasSize(2);
        assertThat(response.path("data").get(1).path("index").asInt()).isEqualTo(1);
        assertThat(response.path("data").get(1).path("embedding").get(0).asDouble()).isEqualTo(0.3);
        assertThat(response.path("usage").path("prompt_tokens").asInt()).isEqualTo(5);
        assertThat(translator.embeddingResponse(json("{'embeddings':{'a':1}}"), "m").path("data")).isEmpty();
    }
}
