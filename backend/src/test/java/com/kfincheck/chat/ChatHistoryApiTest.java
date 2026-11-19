package com.kfincheck.chat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.kfincheck.auth.AccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="stream.enabled=false")
@AutoConfigureMockMvc
@Transactional
class ChatHistoryApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean AiServerClient ai;
    private UsernamePasswordAuthenticationToken account() {
        String login = "chat_api_" + java.util.UUID.randomUUID().toString().substring(0,12);
        jdbc.update("INSERT INTO app_user(login_id,password_hash,nickname) VALUES(?,?,?)", login, "test-only", "API 테스트");
        long id = jdbc.queryForObject("SELECT user_id FROM app_user WHERE login_id=?", Long.class, login);
        return UsernamePasswordAuthenticationToken.authenticated(new AccountService.User(id,login,"테스트"), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
    private String create(UsernamePasswordAuthenticationToken account) throws Exception {
        return json.readTree(mvc.perform(post("/api/chat/conversations").with(authentication(account)).with(csrf())
            .contentType("application/json").content("{\"symbol\":\"005930\",\"name\":\"삼성전자\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("conversationId").asText();
    }
    @Test void restoresStartsFreshAndRestrictsAllHistoryEndpointsToOwner() throws Exception {
        var owner = account(); var other = account();
        String id = create(owner);
        when(ai.ask(any())).thenAnswer(call -> {
            ChatModels.AiRequest request = call.getArgument(0);
            assertThat(request.history()).isEmpty();
            return new ChatModels.AiResponse("answered","조회 답변",List.of(),json.readTree("{\"type\":\"quote\",\"data\":{\"price\":70000}}"),"삼성전자 현재가");
        });
        mvc.perform(post("/api/chat").with(authentication(owner)).with(csrf()).contentType("application/json")
            .content("{\"conversationId\":\""+id+"\",\"message\":\"삼전 주까\",\"stock\":{\"symbol\":\"005930\",\"name\":\"삼성전자\"}}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.presentation.data.price").value(70000));
        mvc.perform(get("/api/chat/conversations/"+id+"/messages").with(authentication(owner)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[1].response.presentation.data.price").value(70000));
        for (var request : List.of(get("/api/chat/conversations/"+id+"/messages"), delete("/api/chat/conversations/"+id).with(csrf()))) {
            mvc.perform(request.with(authentication(other))).andExpect(status().isNotFound());
        }
        String fresh = create(owner);
        mvc.perform(get("/api/chat/conversations/"+fresh+"/messages").with(authentication(owner)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/chat/conversations").param("symbol","005930"))
            .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/chat/conversations/"+id).with(authentication(owner)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/chat/conversations/"+id+"/messages").with(authentication(owner))).andExpect(status().isNotFound());
        mvc.perform(get("/api/chat/conversations/"+fresh+"/messages").with(authentication(owner))).andExpect(status().isOk());
    }
}
