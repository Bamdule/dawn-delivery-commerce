package io.github.bamdule.dawn.messaging.test;

import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.restdocs.test.autoconfigure.RestDocsMockMvcConfigurationCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.restdocs.operation.Operation;
import org.springframework.restdocs.snippet.TemplatedSnippet;
import org.springframework.restdocs.templates.TemplateFormats;

// 서비스마다 API 문서 테스트가 쓴다. 스니펫은 Markdown 으로 만들어 ./gradlew apiDocs 가 docs/api 로 합친다.
// GitHub 화면에서 바로 읽히게 하려고 Asciidoctor 대신 Markdown 을 쓴다
@TestConfiguration(proxyBeanMethods = false)
public class ApiDocs {

    @Bean
    public RestDocsMockMvcConfigurationCustomizer markdownSnippets() {
        return configurer -> configurer
                .snippets().withTemplateFormat(TemplateFormats.markdown())
                .and()
                .operationPreprocessors().withRequestDefaults(prettyPrint()).withResponseDefaults(prettyPrint());
    }

    // 엔드포인트마다 무엇을 하는지 한두 문장. 문서의 제목 아래에 붙는다
    public static TemplatedSnippet description(String text) {
        return new DescriptionSnippet(text);
    }

    private static final class DescriptionSnippet extends TemplatedSnippet {

        private final String text;

        private DescriptionSnippet(String text) {
            super("description", Map.of());
            this.text = text;
        }

        @Override
        // REST Docs 가 이 맵에 속성을 더 넣으므로 바꿀 수 있는 맵을 돌려준다
        protected Map<String, Object> createModel(Operation operation) {
            Map<String, Object> model = new HashMap<>();
            model.put("text", text);
            return model;
        }
    }
}
